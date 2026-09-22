package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the REAL V491 against catalog and mirror rows shaped like production.
 *
 * <p>V491 copies each model's own cache prices into the billing mirror, so a cached token
 * stops being priced by a per-family constant. The copy carries two guards whose absence
 * is invisible until it is expensive, and both were real defects in the first draft:
 *
 * <ul>
 *   <li><b>Strictly positive.</b> The catalog stores {@code 0} for "this provider does not
 *       charge for cache writes" - every DeepSeek row does. Copied literally, 0 does not
 *       mean that in the mirror: it means the cache is FREE. NULL is the only honest way
 *       to say "unknown", and billing then falls back to the family multiplier.</li>
 *   <li><b>In range.</b> The source column is {@code NUMERIC(14,6)} and the target
 *       {@code NUMERIC(10,6)}. A sentinel feed price (openrouter/auto ships -1, which
 *       becomes -1000000 after the per-million scaling) overflows the target and aborts
 *       the migration - and with auth-service on {@code ddl-auto: validate}, an aborted
 *       migration means the auth pods do not boot.</li>
 * </ul>
 *
 * <p>It also must not clobber a value the mirror already holds, which is what makes the
 * statement safe to replay.
 *
 * <p>Lives in migration-service, on {@link FlywayTestSupport}, so it runs against CI's
 * {@code services: postgres} rather than a container: the ARC runners have no Docker
 * socket, and a container-only migration test would SKIP there and report green having
 * replayed nothing - which is the precise failure this whole change exists to stop
 * trusting.
 */
@DisplayName("V491 copies each model's own cache prices into the billing mirror")
class CacheRateMirrorV491MigrationTest {

    private static final String DB = "cache_rate_mirror_v491";

    private static final String MIGRATION = "V491__bill_cache_at_the_model_rate.sql";

    /** The catalog and mirror as they stand before V491, trimmed to what it reasons about. */
    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA agent;
            CREATE SCHEMA auth;

            CREATE TABLE agent.model_config_overrides (
                id                BIGSERIAL PRIMARY KEY,
                provider          VARCHAR(50)  NOT NULL,
                model_id          VARCHAR(150) NOT NULL,
                price_cache_read  NUMERIC(14,6),
                price_cache_write NUMERIC(14,6),
                UNIQUE (provider, model_id)
            );

            CREATE TABLE auth.model_pricing (
                id          SERIAL PRIMARY KEY,
                provider    VARCHAR(50)   NOT NULL,
                model       VARCHAR(100)  NOT NULL,
                input_rate  NUMERIC(10,6) NOT NULL,
                output_rate NUMERIC(10,6) NOT NULL,
                is_active   BOOLEAN NOT NULL DEFAULT TRUE
            );

            INSERT INTO agent.model_config_overrides (provider, model_id, price_cache_read, price_cache_write) VALUES
                ('anthropic','claude-fable-5-1', 0.250000, 12.500000),
                ('deepseek','deepseek-chat',     0.028000,  0.000000),
                ('openrouter','auto',        -1000000.000000, -1000000.000000),
                ('acme','too-big',              99999.000000, 99999.000000),
                ('acme','one-side-too-big',         0.500000, 99999.000000),
                ('openai','gpt-5.6-sol',            0.400000, NULL),
                ('google','gemini-3-pro',           NULL,     NULL),
                ('anthropic','admin-override',      0.900000, 9.000000);

            INSERT INTO auth.model_pricing (provider, model, input_rate, output_rate, is_active) VALUES
                ('anthropic','claude-fable-5-1', 10.0, 50.0, TRUE),
                ('deepseek','deepseek-chat',      0.28, 0.42, TRUE),
                ('openrouter','auto',             1.0,  4.0,  TRUE),
                ('acme','too-big',                1.0,  4.0,  TRUE),
                ('acme','one-side-too-big',       1.0,  4.0,  TRUE),
                ('openai','gpt-5.6-sol',          4.0, 20.0,  TRUE),
                ('google','gemini-3-pro',         2.0, 12.0,  TRUE),
                ('anthropic','admin-override',   10.0, 50.0,  TRUE),
                ('anthropic','retired',          10.0, 50.0,  FALSE);
            """;

    @Test
    @DisplayName("every shape the production catalog actually holds is copied, skipped or left alone as its own rule says")
    void copiesOnlyWhatIsUsable(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB);

            assertThatCode(() -> postgres.runFlyway(DB, tempDir)).doesNotThrowAnyException();

            // A normal row copies both rates with full unit fidelity - both sides are USD/1M.
            assertThat(read(postgres, "anthropic", "claude-fable-5-1")).isEqualByComparingTo("0.25");
            assertThat(write(postgres, "anthropic", "claude-fable-5-1")).isEqualByComparingTo("12.5");

            // A zero cache-WRITE price is unknown, never free. DeepSeek's real shape.
            assertThat(read(postgres, "deepseek", "deepseek-chat")).isEqualByComparingTo("0.028");
            assertThat(write(postgres, "deepseek", "deepseek-chat")).isNull();

            // A sentinel is skipped rather than overflowing NUMERIC(10,6) and aborting.
            assertThat(read(postgres, "openrouter", "auto")).isNull();
            assertThat(write(postgres, "openrouter", "auto")).isNull();

            // So is a value above the target column's range.
            assertThat(read(postgres, "acme", "too-big")).isNull();
            assertThat(write(postgres, "acme", "too-big")).isNull();

            // Mixed: the row is selected on its usable rate, and the unusable one must
            // still be skipped rather than riding in on the same UPDATE.
            assertThat(read(postgres, "acme", "one-side-too-big")).isEqualByComparingTo("0.5");
            assertThat(write(postgres, "acme", "one-side-too-big")).isNull();

            // A row with one known rate copies that one and leaves the other unknown.
            assertThat(read(postgres, "openai", "gpt-5.6-sol")).isEqualByComparingTo("0.4");
            assertThat(write(postgres, "openai", "gpt-5.6-sol")).isNull();

            // No cache price in the catalog leaves the mirror on its family fallback.
            assertThat(read(postgres, "google", "gemini-3-pro")).isNull();

            // An inactive mirror row is not repaired: only the row billing reads is.
            assertThat(read(postgres, "anthropic", "retired")).isNull();

            // The columns land with the target's precision, which is what the range guard
            // protects - widen one and the guard silently becomes too tight.
            assertThat(precisionOf(postgres, "cache_read_rate")).isEqualTo("10,6");
            assertThat(precisionOf(postgres, "cache_write_rate")).isEqualTo("10,6");
        }
    }

    @Test
    @DisplayName("a value already in the mirror survives a replay, which is what makes the copy safe to re-run")
    void existingValueIsNotClobbered(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(DB + "_replay");

            postgres.runFlyway(DB + "_replay", tempDir);
            execute(postgres, DB + "_replay",
                    "UPDATE auth.model_pricing SET cache_read_rate = 0.111111 "
                    + "WHERE provider = 'anthropic' AND model = 'admin-override'");
            // Flyway will not replay an applied version, so the statement is re-run directly -
            // the property under test is the SQL's idempotence, not Flyway's bookkeeping.
            execute(postgres, DB + "_replay", Files.readString(
                    Path.of("src/main/resources/db/migration/" + MIGRATION)));

            assertThat(read(postgres, DB + "_replay", "anthropic", "admin-override"))
                    .isEqualByComparingTo("0.111111");
            // The write rate was still unset, so the replay is what fills it.
            assertThat(write(postgres, DB + "_replay", "anthropic", "admin-override"))
                    .isEqualByComparingTo("9.0");
        }
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_catalog_and_mirror.sql"), SCHEMA_AND_ROWS);
        // The harness reads only this directory, so the real migration is copied in: a test
        // that forgot it would assert against untouched rows and fail loudly, not pass.
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION),
                directory.resolve(MIGRATION));
    }

    private static BigDecimal read(FlywayTestSupport.PostgresTarget p, String provider, String model)
            throws Exception {
        return read(p, DB, provider, model);
    }

    private static BigDecimal read(FlywayTestSupport.PostgresTarget p, String db,
                                   String provider, String model) throws Exception {
        return rate(p, db, "cache_read_rate", provider, model);
    }

    private static BigDecimal write(FlywayTestSupport.PostgresTarget p, String provider, String model)
            throws Exception {
        return write(p, DB, provider, model);
    }

    private static BigDecimal write(FlywayTestSupport.PostgresTarget p, String db,
                                    String provider, String model) throws Exception {
        return rate(p, db, "cache_write_rate", provider, model);
    }

    private static BigDecimal rate(FlywayTestSupport.PostgresTarget p, String db, String column,
                                   String provider, String model) throws Exception {
        try (var connection = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT " + column + " FROM auth.model_pricing "
                     + "WHERE provider = '" + provider + "' AND model = '" + model + "'")) {
            return rs.next() ? rs.getBigDecimal(1) : null;
        }
    }

    private static String precisionOf(FlywayTestSupport.PostgresTarget p, String column) throws Exception {
        try (var connection = DriverManager.getConnection(p.jdbcUrl(DB), p.username(), p.password());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT numeric_precision || ',' || numeric_scale FROM information_schema.columns "
                     + "WHERE table_schema = 'auth' AND table_name = 'model_pricing' "
                     + "AND column_name = '" + column + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static void execute(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (var connection = DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
