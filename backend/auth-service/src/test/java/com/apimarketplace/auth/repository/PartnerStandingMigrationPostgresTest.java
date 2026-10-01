package com.apimarketplace.auth.repository;

import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V556 on a real Postgres, with the REAL raise statement. {@code ddl-auto} (what the Spring tests
 * build their schema with) knows neither the CHECK constraints nor the column defaults, so this
 * runs the migration itself, twice to prove it re-runs cleanly, then executes the exact SQL of
 * {@link PartnerStandingRepository#raise} (read from its annotation) against it: the one statement
 * that guarantees a tier never goes down.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V556 partner standing migration and the raise statement - real Postgres")
class PartnerStandingMigrationPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "CREDENTIAL_TEST_PG",
            "it is the only proof that a partner tier can only go up, which one native upsert enforces");

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private String raiseSql;
    private String endFounderSql;

    private static String migration(String name) throws Exception {
        String relative = "migration-service/src/main/resources/db/migration/" + name;
        Path here = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path file = candidate.resolve(relative);
            if (Files.isRegularFile(file)) return Files.readString(file);
        }
        throw new IllegalStateException("could not locate " + relative + " from " + here);
    }

    @BeforeAll
    void setUpSchema() throws Exception {
        DB.require();
        DriverManagerDataSource ds = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS auth");
        jdbc.execute("DROP TABLE IF EXISTS auth.partner_standing CASCADE");
        String v556 = migration("V556__partner_standing.sql");
        jdbc.execute(v556);
        jdbc.execute(v556); // idempotent: a re-run must not fail
        raiseSql = PartnerStandingRepository.class
                .getMethod("raise", Long.class, String.class, boolean.class, Long.class, Instant.class)
                .getAnnotation(Query.class).value();
        endFounderSql = PartnerStandingRepository.class
                .getMethod("endFounder", Long.class, String.class, Long.class, Instant.class)
                .getAnnotation(Query.class).value();
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE auth.partner_standing");
    }

    private int raise(long userId, String tier, boolean founder, Long admin, Instant now) {
        return named.update(raiseSql, new MapSqlParameterSource()
                .addValue("userId", userId).addValue("tier", tier).addValue("founder", founder)
                .addValue("adminUserId", admin).addValue("now", Timestamp.from(now)));
    }

    private int endFounder(long userId, String tier, Long admin, Instant now) {
        return named.update(endFounderSql, new MapSqlParameterSource()
                .addValue("userId", userId).addValue("tier", tier)
                .addValue("adminUserId", admin).addValue("now", Timestamp.from(now)));
    }

    @Test
    @DisplayName("V557 endFounder: a founder goes back to the earned tier, founder off, naming the admin; a non-founder is untouched")
    void endFounderOnRealTable() {
        raise(7, "PLATINUM", true, 1L, Instant.parse("2026-10-02T00:00:00Z"));
        raise(8, "GOLD", false, null, Instant.parse("2026-10-02T00:00:00Z"));

        assertThat(endFounder(7, "GOLD", 2L, Instant.parse("2026-11-01T00:00:00Z"))).isEqualTo(1);
        assertThat(row(7)).containsEntry("tier", "GOLD").containsEntry("founder", false).containsEntry("updated_by_user_id", 2L);

        // Only founder rows: a partner who earned Gold keeps it, and a second end writes nothing.
        assertThat(endFounder(8, "SILVER", 2L, Instant.parse("2026-11-01T00:00:00Z"))).isZero();
        assertThat(row(8)).containsEntry("tier", "GOLD");
        assertThat(endFounder(7, "SILVER", 2L, Instant.parse("2026-11-02T00:00:00Z"))).isZero();
        assertThat(row(7)).containsEntry("tier", "GOLD");
    }

    private Map<String, Object> row(long userId) {
        return jdbc.queryForMap("SELECT tier, founder, reached_at, updated_by_user_id FROM auth.partner_standing WHERE user_id = ?", userId);
    }

    @Test
    @DisplayName("a tier outside SILVER / GOLD / PLATINUM is refused by the database")
    void unknownTierRefused() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth.partner_standing (user_id, tier) VALUES (7, 'BRONZE')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a founder is always Platinum: founder on a lower tier is refused by the database")
    void founderMustBePlatinum() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth.partner_standing (user_id, tier, founder) VALUES (7, 'GOLD', true)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("defaults: a bare row is Silver, not a founder, dated now")
    void defaults() {
        jdbc.update("INSERT INTO auth.partner_standing (user_id) VALUES (7)");

        assertThat(row(7)).containsEntry("tier", "SILVER").containsEntry("founder", false);
        assertThat(row(7).get("reached_at")).isNotNull();
    }

    @Test
    @DisplayName("raise: inserts, moves up, and never moves down; reached_at follows the tier only")
    void raiseOnlyGoesUp() {
        Instant t1 = Instant.parse("2026-10-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-11-01T00:00:00Z");
        Instant t3 = Instant.parse("2026-12-01T00:00:00Z");

        assertThat(raise(7, "GOLD", false, null, t1)).isEqualTo(1);
        assertThat(row(7)).containsEntry("tier", "GOLD");

        // Lower or equal: nothing written, not even the timestamps.
        assertThat(raise(7, "SILVER", false, null, t2)).isZero();
        assertThat(raise(7, "GOLD", false, null, t2)).isZero();
        assertThat(row(7)).containsEntry("tier", "GOLD");
        assertThat(((Timestamp) row(7).get("reached_at")).toInstant()).isEqualTo(t1);

        assertThat(raise(7, "PLATINUM", false, null, t3)).isEqualTo(1);
        assertThat(row(7)).containsEntry("tier", "PLATINUM").containsEntry("founder", false);
        assertThat(((Timestamp) row(7).get("reached_at")).toInstant()).isEqualTo(t3);
    }

    @Test
    @DisplayName("founder grant on a partner already Platinum: turns founder on, keeps the date the tier was reached")
    void founderOnAnExistingPlatinum() {
        Instant reached = Instant.parse("2026-10-01T00:00:00Z");
        raise(7, "PLATINUM", false, null, reached);

        assertThat(raise(7, "PLATINUM", true, 1L, Instant.parse("2026-10-05T00:00:00Z"))).isEqualTo(1);

        assertThat(row(7)).containsEntry("tier", "PLATINUM").containsEntry("founder", true)
                .containsEntry("updated_by_user_id", 1L);
        assertThat(((Timestamp) row(7).get("reached_at")).toInstant()).isEqualTo(reached);
        // A second grant changes nothing.
        assertThat(raise(7, "PLATINUM", true, 2L, Instant.parse("2026-10-06T00:00:00Z"))).isZero();
        assertThat(row(7)).containsEntry("updated_by_user_id", 1L);
    }

    @Test
    @DisplayName("a founder stays a founder: a later revenue raise can neither lower the tier nor clear the flag")
    void founderIsForLife() {
        raise(7, "PLATINUM", true, 1L, Instant.parse("2026-10-01T00:00:00Z"));

        assertThat(raise(7, "GOLD", false, null, Instant.parse("2027-02-01T00:00:00Z"))).isZero();

        assertThat(row(7)).containsEntry("tier", "PLATINUM").containsEntry("founder", true);
    }
}
