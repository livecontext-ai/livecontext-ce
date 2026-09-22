package com.apimarketplace.agent.catalog.sync;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression for
 * {@code V489__rate_limit_fallback_scales_with_context_window.sql}.
 *
 * <p>V489 raises rows still carrying the generic 60000/500/20000/200 fallback,
 * which on a large-context model is a ceiling below the cost of one of its own
 * requests. Two things have to hold, and neither is visible in production if it
 * breaks: the arithmetic must match
 * {@code CatalogMergeService.applyRateLimitDefaults} exactly, or a row repaired
 * here and the same model re-inserted by a later sync disagree; and the WHERE
 * clause must stay narrow, or it overwrites a deliberate admin decision that is
 * indistinguishable from the blanket stamp except by
 * {@code user_modified_fields}.
 *
 * <p>The expected values are spelled out as literals rather than recomputed
 * from the same formula, so a change to the rule fails here instead of being
 * mirrored silently.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("V489 context-scaled rate-limit fallback - real Postgres, real migration search_path")
class RateLimitContextScalingV489MigrationPostgresTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String MIGRATION = "V489__rate_limit_fallback_scales_with_context_window.sql";

    static JdbcTemplate jdbc;
    static String v489;

    @BeforeAll
    static void setUpClass() {
        v489 = loadMigration(MIGRATION);
        String beforeEach = loadMigration("beforeEachMigrate.sql");
        Assumptions.assumeTrue(v489 != null && beforeEach != null,
                "migration files not found from module cwd - skipped");

        DataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA orchestrator");
        jdbc.execute("CREATE SCHEMA agent");
        jdbc.execute(ddl("agent"));
        jdbc.execute(ddl("orchestrator"));

        seed("agent");
        seed("orchestrator");

        jdbc.execute(beforeEach + "\n" + v489);
    }

    private static String ddl(String schema) {
        return "CREATE TABLE " + schema + ".model_config_overrides " + """
                (
                    id                        BIGSERIAL PRIMARY KEY,
                    provider                  VARCHAR(50)  NOT NULL,
                    model_id                  VARCHAR(150) NOT NULL,
                    context_window            INTEGER,
                    rate_limit_tpm            INTEGER,
                    rate_limit_rpm            INTEGER,
                    rate_limit_tpm_per_tenant INTEGER,
                    rate_limit_rpm_per_tenant INTEGER,
                    user_modified_fields      TEXT[] NOT NULL DEFAULT '{}',
                    updated_at                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE (provider, model_id)
                )""";
    }

    private static void seed(String schema) {
        String fallback = "60000,500,20000,200";
        jdbc.update("INSERT INTO " + schema + ".model_config_overrides "
                + "(provider, model_id, context_window, rate_limit_tpm, rate_limit_rpm, "
                + " rate_limit_tpm_per_tenant, rate_limit_rpm_per_tenant, user_modified_fields) VALUES "
                // In scope: the fallback fingerprint, across the context range.
                + "('deepseek','deepseek-v4-flash',1000000," + fallback + ",'{}'),"
                + "('xai','grok-4-1-fast',2000000," + fallback + ",'{}'),"
                + "('moonshot','moonshot-v1-8k',8192," + fallback + ",'{}'),"
                + "('perplexity','sonar-pro',NULL," + fallback + ",'{}'),"
                + "('acme','bogus-feed-row',2000000000," + fallback + ",'{}'),"
                // Out of scope: an admin owns these four numbers.
                + "('zai','admin-capped',1000000," + fallback + ",'{rateLimitTpm,rateLimitRpm}'),"
                + "('zai','admin-capped-tenant',1000000," + fallback + ",'{rateLimitTpmPerTenant}'),"
                // Out of scope: not the fingerprint.
                + "('google','gemini-3.6-flash',1048576,800000,2000,200000,500,'{}'),"
                + "('openai','gpt-5.4-mini',1000000,NULL,NULL,NULL,NULL,'{}'),"
                + "('deepseek','half-stamped',1000000,60000,500,NULL,200,'{}')");
    }

    @Test
    @DisplayName("A large-context row is raised to four times its own context window")
    void raisesLargeContextRows() {
        // 1_000_000 x 4. The old 60000 was below the ~50000 a single tool-heavy
        // agent turn reserves, which is what made the limiter the bottleneck.
        assertThat(limits("deepseek", "deepseek-v4-flash"))
                .containsExactly(4_000_000, 500, 1_000_000, 200);
    }

    @Test
    @DisplayName("A small-context row lands on the flat floor instead")
    void smallContextRowsGetTheFlatFloor() {
        // 8192 x 4 is far under 2_000_000, so the floor wins and the tenant
        // share is the configured 500_000 rather than a quarter of the window.
        assertThat(limits("moonshot", "moonshot-v1-8k"))
                .containsExactly(2_000_000, 500, 500_000, 200);
    }

    @Test
    @DisplayName("A row with no context window gets the flat floor")
    void nullContextWindowGetsTheFlatFloor() {
        assertThat(limits("perplexity", "sonar-pro"))
                .containsExactly(2_000_000, 500, 500_000, 200);
    }

    @Test
    @DisplayName("The tenant share is a quarter once the window is large enough to beat the floor")
    void tenantShareIsAQuarterOfALargeWindow() {
        // 2_000_000 context x 4 = 8_000_000 global, a quarter of it per tenant.
        assertThat(limits("xai", "grok-4-1-fast"))
                .containsExactly(8_000_000, 500, 2_000_000, 200);
    }

    @Test
    @DisplayName("An absurd context window is clamped to the same ceiling the Java uses")
    void absurdContextWindowIsClampedToMaxDerivedTpm() {
        // 2e9 x 4 exceeds int. Postgres raises "integer out of range" rather
        // than wrapping, so getting this wrong fails the whole deploy on one
        // bad row - which is how the first draft of this migration was caught.
        // The literal is CatalogMergeService.MAX_DERIVED_TPM: asserting only
        // "positive" would let the two drift apart silently, which is the
        // mirroring this class exists to prevent.
        assertThat(limits("acme", "bogus-feed-row"))
                .containsExactly(100_000_000, 500, 25_000_000, 200);
    }

    @Test
    @DisplayName("A row whose rate limits are user-modified is left alone")
    void skipsUserModifiedRows() {
        // An admin who set a deliberate ceiling keeps it. Either rate-limit
        // field being marked is enough to put the row out of scope.
        assertThat(limits("zai", "admin-capped"))
                .as("rateLimitTpm marked")
                .containsExactly(60000, 500, 20000, 200);
        assertThat(limits("zai", "admin-capped-tenant"))
                .as("rateLimitTpmPerTenant marked")
                .containsExactly(60000, 500, 20000, 200);
    }

    @Test
    @DisplayName("A row that does not carry the exact fingerprint is out of scope")
    void skipsRowsWithoutTheExactFingerprint() {
        // Real published limits, deliberate NULLs (curated models resolve
        // through ai.agent.rate-limits) and half-stamped rows all read as
        // something other than the blanket stamp.
        assertThat(limits("google", "gemini-3.6-flash"))
                .as("feed-published limits must survive")
                .containsExactly(800000, 2000, 200000, 500);
        assertThat(limits("openai", "gpt-5.4-mini"))
                .as("a curated model relies on its NULL columns")
                .containsExactly(null, null, null, null);
        assertThat(limits("deepseek", "half-stamped"))
                .as("all four must match before the row counts as stamped")
                .containsExactly(60000, 500, null, 200);
    }

    @Test
    @DisplayName("Runs against the agent schema despite beforeEachMigrate pointing at orchestrator")
    void targetsTheAgentSchema() {
        // The identical row exists in both schemas. Compare them by value: the
        // agent copy moved, the orchestrator copy did not, so the qualified
        // table name is doing the work rather than a search_path that happens
        // to be right.
        assertThat(limits("deepseek", "deepseek-v4-flash"))
                .as("agent copy repaired")
                .containsExactly(4_000_000, 500, 1_000_000, 200);
        assertThat(decoyLimits("deepseek", "deepseek-v4-flash"))
                .as("the orchestrator decoy must be untouched")
                .containsExactly(60000, 500, 20000, 200);
    }

    @Test
    @DisplayName("Re-running changes nothing")
    void rerunIsIdempotent() {
        jdbc.execute(v489);
        assertThat(limits("deepseek", "deepseek-v4-flash"))
                .containsExactly(4_000_000, 500, 1_000_000, 200);
        assertThat(limits("zai", "admin-capped"))
                .containsExactly(60000, 500, 20000, 200);
        assertThat(limits("google", "gemini-3.6-flash"))
                .containsExactly(800000, 2000, 200000, 500);
    }

    @Test
    @DisplayName("No unmodified row is left carrying the old fingerprint")
    void noUnmodifiedRowKeepsTheOldFingerprint() {
        // Set-level completeness: the migration's whole job. Stated against the
        // fingerprint itself rather than a threshold, so it discriminates on
        // the global dimension too (60000 clears any "one agent turn" bar, yet
        // is exactly the value that has to go).
        Integer stillStamped = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.model_config_overrides "
                + "WHERE rate_limit_tpm = 60000 AND rate_limit_rpm = 500 "
                + "  AND rate_limit_tpm_per_tenant = 20000 AND rate_limit_rpm_per_tenant = 200 "
                + "  AND user_modified_fields = '{}'", Integer.class);
        assertThat(stillStamped).isZero();
    }

    @Test
    @DisplayName("Every repaired row clears one agent turn on both dimensions")
    void everyRepairedRowClearsOneAgentTurn() {
        // The property the whole change exists for, asserted over the result
        // set rather than per model. Scoped to the rows this migration wrote
        // (tpm >= the 2 000 000 floor) so a deliberately lower feed-published
        // ceiling elsewhere does not mask a real regression here.
        Integer belowOneTurn = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.model_config_overrides "
                + "WHERE rate_limit_tpm >= 2000000 "
                + "  AND (rate_limit_tpm <= 50000 OR rate_limit_tpm_per_tenant IS NULL "
                + "       OR rate_limit_tpm_per_tenant <= 50000 "
                + "       OR rate_limit_tpm_per_tenant > rate_limit_tpm)", Integer.class);
        assertThat(belowOneTurn).isZero();
    }

    private static List<Integer> limits(String provider, String modelId) {
        return limitsIn("agent", provider, modelId);
    }

    private static List<Integer> decoyLimits(String provider, String modelId) {
        return limitsIn("orchestrator", provider, modelId);
    }

    private static List<Integer> limitsIn(String schema, String provider, String modelId) {
        return jdbc.queryForObject(
                "SELECT rate_limit_tpm, rate_limit_rpm, rate_limit_tpm_per_tenant, rate_limit_rpm_per_tenant "
                + "FROM " + schema + ".model_config_overrides WHERE provider = ? AND model_id = ?",
                (rs, n) -> java.util.Arrays.asList(
                        (Integer) rs.getObject(1), (Integer) rs.getObject(2),
                        (Integer) rs.getObject(3), (Integer) rs.getObject(4)),
                provider, modelId);
    }

    private static String loadMigration(String name) {
        for (String prefix : new String[]{"", "../", "../../"}) {
            Path p = Path.of(prefix + "backend/migration-service/src/main/resources/db/migration/" + name);
            Path alt = Path.of(prefix + "migration-service/src/main/resources/db/migration/" + name);
            for (Path candidate : new Path[]{p, alt}) {
                if (Files.exists(candidate)) {
                    try {
                        return Files.readString(candidate);
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
