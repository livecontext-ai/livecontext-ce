package com.apimarketplace.agent.migration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for {@code V109__model_catalog_infra.sql} - the migration that
 * introduces the audit trigger, credit-derivation trigger, HMAC hash-chain, and
 * bulk markup recompute. Runs V109 directly against a real Postgres 16 testcontainer
 * on top of a minimal, hand-rolled baseline that mirrors V32 + V65 + V108 as they
 * affect {@code agent.model_config_overrides}.
 *
 * <p>Why not run the full migration chain? V74 enables pgvector in the
 * {@code datasource} schema and V75 relies on the driver-level search_path
 * containing {@code datasource} - a pain to wire up from the Flyway Java API and
 * unrelated to the V109 contract being tested here. The minimal-baseline
 * approach tests V109 in isolation and is ~30× faster.
 *
 * <p>What this pins:
 * <ul>
 *   <li><b>derive_model_credits</b> - price × markup × 10 on INSERT and UPDATE,
 *       NULL price → NULL credits, no bleed across rows.</li>
 *   <li><b>log_model_field_changes</b> - no-op when {@code app.model_audit_hmac_key}
 *       is unset (Flyway-safe path); writes a chained HMAC row when set.</li>
 *   <li><b>compute_model_field_edit_hmac</b> - {@code prev_row_hmac} links to the
 *       latest committed row, so two consecutive edits form an unbroken chain.</li>
 *   <li><b>recompute_all_model_credits</b> - a single UPDATE on billing_settings
 *       rewrites credits on every row without touching price.</li>
 * </ul>
 *
 * <p>The HMAC key is fed through {@code SET LOCAL app.model_audit_hmac_key = '...'}
 * per-transaction in this test - production does it once per physical connection
 * via Hikari {@code connection-init-sql} (see {@code ModelAuditHmacConfig}).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("V109 model_catalog_infra - triggers and HMAC chain (Postgres IT)")
class V109TriggerIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("agent_test")
            .withUsername("test")
            .withPassword("test");

    private static final String V109_PATH =
            "../migration-service/src/main/resources/db/migration/V109__model_catalog_infra.sql";

    private static final String HMAC_KEY = "test-hmac-key-not-real-just-for-chain-verification";

    private JdbcTemplate jdbc;

    @BeforeAll
    void applyV109OnMinimalBaseline() throws IOException {
        // `currentSchema=agent` mirrors the agent-service datasource URL in prod
        // (application.yml: `jdbc:...?currentSchema=agent`). Needed because the
        // V109 trigger function `derive_model_credits` references
        // `billing_settings` unqualified - Postgres resolves that via search_path
        // on the executing session, not the session that created the function.
        String jdbcUrl = POSTGRES.getJdbcUrl() + "&currentSchema=agent";
        DriverManagerDataSource ds = new DriverManagerDataSource(
                jdbcUrl, POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        this.jdbc = new JdbcTemplate(ds);

        // --------------------------------------------------------------------
        // Minimal baseline: agent schema + model_config_overrides as it stood
        // after V32 (create) + V65 (rate-limit columns) + V108 (backfill DN).
        // This is the pre-V109 shape that the trigger DDL expects to ALTER.
        // --------------------------------------------------------------------
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS agent");
        jdbc.execute("""
                CREATE TABLE agent.model_config_overrides (
                    id           BIGSERIAL    PRIMARY KEY,
                    provider     VARCHAR(50)  NOT NULL,
                    model_id     VARCHAR(150) NOT NULL,
                    enabled      BOOLEAN,
                    display_name VARCHAR(255),
                    tier         VARCHAR(20),
                    ranking      INTEGER,
                    recommended  BOOLEAN,
                    price_input  NUMERIC(10,4),
                    price_output NUMERIC(10,4),
                    is_custom    BOOLEAN      NOT NULL DEFAULT FALSE,
                    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
                    rate_limit_tpm            INTEGER,
                    rate_limit_rpm            INTEGER,
                    rate_limit_tpm_per_tenant INTEGER,
                    rate_limit_rpm_per_tenant INTEGER,
                    UNIQUE(provider, model_id)
                )
                """);

        // --------------------------------------------------------------------
        // Apply V109 verbatim from the migration-service sources. Reading the
        // file (rather than duplicating SQL) guarantees test/prod parity -
        // if V109 changes, this test picks up the change on the next run.
        // --------------------------------------------------------------------
        String v109 = Files.readString(Paths.get(V109_PATH), StandardCharsets.UTF_8);
        jdbc.execute(v109);
    }

    @BeforeEach
    void resetCatalog() {
        // model_field_edits has FK → model_config_overrides(id) ON DELETE CASCADE,
        // so clearing the parent clears the audit chain. We do NOT reset the
        // sequence - tests don't assert absolute chain_seq, only ordering.
        jdbc.update("DELETE FROM agent.model_config_overrides WHERE id > 0");
        jdbc.update("UPDATE agent.billing_settings SET markup = 1.20 WHERE id = 1");
    }

    @AfterAll
    void tearDown() {
        if (jdbc != null) {
            jdbc.update("DELETE FROM agent.model_config_overrides WHERE id > 0");
        }
    }

    // ========================================================================
    // derive_model_credits - BEFORE INSERT OR UPDATE OF price_input, price_output
    // Contract: credits = ROUND(price × markup × 10, 4). NULL price → NULL credits.
    // ========================================================================

    @Test
    @DisplayName("INSERT with priced row derives credits = price × markup × 10 (ROUND(4))")
    void insertDerivesCredits() {
        insertModel("openai", "gpt-5", "GPT-5", new BigDecimal("1.25"), new BigDecimal("10.00"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT credits_input, credits_output FROM agent.model_config_overrides "
                        + "WHERE provider = 'openai' AND model_id = 'gpt-5'");

        // 1.25 × 1.20 × 10 = 15.0000 ; 10.00 × 1.20 × 10 = 120.0000
        assertThat(((BigDecimal) row.get("credits_input"))).isEqualByComparingTo("15.0000");
        assertThat(((BigDecimal) row.get("credits_output"))).isEqualByComparingTo("120.0000");
    }

    @Test
    @DisplayName("INSERT with NULL price leaves credits NULL - billing code must fall back")
    void insertNullPriceLeavesCreditsNull() {
        insertModel("anthropic", "claude-opus-4-7", "Claude Opus 4.7", null, null);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT credits_input, credits_output FROM agent.model_config_overrides "
                        + "WHERE provider = 'anthropic' AND model_id = 'claude-opus-4-7'");

        assertThat(row.get("credits_input")).isNull();
        assertThat(row.get("credits_output")).isNull();
    }

    @Test
    @DisplayName("UPDATE of price_input re-derives credits_input only")
    void updatePriceRederivesCredits() {
        insertModel("openai", "gpt-5-mini", "GPT-5 mini", new BigDecimal("0.50"), new BigDecimal("2.00"));

        jdbc.update("UPDATE agent.model_config_overrides SET price_input = ? "
                + "WHERE provider = 'openai' AND model_id = 'gpt-5-mini'", new BigDecimal("0.75"));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT credits_input, credits_output FROM agent.model_config_overrides "
                        + "WHERE provider = 'openai' AND model_id = 'gpt-5-mini'");

        // 0.75 × 1.20 × 10 = 9.0000
        assertThat(((BigDecimal) row.get("credits_input"))).isEqualByComparingTo("9.0000");
        // Unchanged: 2.00 × 1.20 × 10 = 24.0000
        assertThat(((BigDecimal) row.get("credits_output"))).isEqualByComparingTo("24.0000");
    }

    // ========================================================================
    // log_model_field_changes - AFTER UPDATE with HMAC-gated no-op
    // ========================================================================

    @Test
    @DisplayName("Audit trigger is a no-op when app.model_audit_hmac_key is NOT set (Flyway path)")
    void auditNoOpsWithoutHmacKey() {
        insertModel("openai", "gpt-5-nano", "GPT-5 nano", new BigDecimal("0.10"), new BigDecimal("0.40"));
        int startingCount = countEdits();

        // No SET LOCAL - simulates a migration-service Flyway connection which
        // has no model_audit_hmac_key in session scope.
        jdbc.update("UPDATE agent.model_config_overrides SET display_name = ? "
                + "WHERE provider = 'openai' AND model_id = 'gpt-5-nano'", "GPT-5 Nano v2");

        assertThat(countEdits())
                .as("audit trigger must no-op so migrations that touch model_config_overrides don't explode")
                .isEqualTo(startingCount);
    }

    @Test
    @DisplayName("Audit trigger writes one model_field_edits row per changed field when HMAC key is set")
    void auditWritesRowWhenHmacKeySet() {
        long modelId = insertModel("openai", "gpt-5-audit", "GPT-5 audit",
                new BigDecimal("1.00"), new BigDecimal("5.00"));

        auditedUpdate("UPDATE agent.model_config_overrides "
                + "SET display_name = 'GPT-5 Audit v2', price_input = 1.50 "
                + "WHERE id = " + modelId);

        List<Map<String, Object>> edits = jdbc.queryForList(
                "SELECT field_name, edit_source, edited_by_user_id, row_hmac "
                        + "FROM agent.model_field_edits WHERE model_config_id = ? "
                        + "ORDER BY chain_seq", modelId);

        assertThat(edits)
                .as("two fields changed → two audit rows")
                .extracting(r -> r.get("field_name"))
                .contains("display_name", "price_input");
        assertThat(edits.get(0).get("edit_source")).isEqualTo("admin");
        assertThat(edits.get(0).get("edited_by_user_id")).isEqualTo("admin-42");
        assertThat(edits.get(0).get("row_hmac")).isNotNull();
    }

    @Test
    @DisplayName("HMAC chain: second edit's prev_row_hmac equals first edit's row_hmac")
    void hmacChainLinksRows() {
        long modelId = insertModel("openai", "gpt-5-chain", "Chain row",
                new BigDecimal("1.00"), new BigDecimal("2.00"));

        auditedUpdate("UPDATE agent.model_config_overrides SET display_name = 'Edit #1' WHERE id = " + modelId);
        auditedUpdate("UPDATE agent.model_config_overrides SET display_name = 'Edit #2' WHERE id = " + modelId);

        List<Map<String, Object>> edits = jdbc.queryForList(
                "SELECT chain_seq, row_hmac, prev_row_hmac "
                        + "FROM agent.model_field_edits WHERE model_config_id = ? "
                        + "ORDER BY chain_seq", modelId);

        assertThat(edits).hasSize(2);
        assertThat(edits.get(0).get("prev_row_hmac"))
                .as("first audit row in the chain has no predecessor")
                .isNull();
        assertThat((byte[]) edits.get(1).get("prev_row_hmac"))
                .as("second row's prev_row_hmac must equal the first row's row_hmac")
                .isEqualTo(edits.get(0).get("row_hmac"));
    }

    @Test
    @DisplayName("Direct INSERT into model_field_edits without HMAC key set is rejected by compute_model_field_edit_hmac")
    void directInsertWithoutKeyFails() {
        long modelId = insertModel("openai", "gpt-5-reject", "Reject row",
                new BigDecimal("1.00"), new BigDecimal("2.00"));

        // This bypasses log_model_field_changes (which would no-op) and tries to
        // write directly. The BEFORE INSERT HMAC trigger must raise because there
        // is no secret to sign with.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO agent.model_field_edits "
                        + "(model_config_id, field_name, old_value, new_value, edit_source) "
                        + "VALUES (?, 'display_name', 'a', 'b', 'admin')", modelId))
                .isInstanceOfAny(DataIntegrityViolationException.class,
                        org.springframework.jdbc.UncategorizedSQLException.class)
                .hasMessageContaining("model_audit_hmac_key");
    }

    // ========================================================================
    // recompute_all_model_credits - AFTER UPDATE OF markup ON billing_settings
    // ========================================================================

    @Test
    @DisplayName("UPDATE billing_settings.markup triggers bulk credit recompute across all rows without touching price")
    void markupChangeRecomputesAllCredits() {
        insertModel("openai", "gpt-5-bulk-a", "A", new BigDecimal("1.00"), new BigDecimal("4.00"));
        insertModel("anthropic", "claude-bulk-b", "B", new BigDecimal("2.00"), new BigDecimal("8.00"));
        insertModel("openai", "gpt-5-bulk-c", "C", null, null);  // NULL-price row must stay NULL

        jdbc.update("UPDATE agent.billing_settings SET markup = 2.00 WHERE id = 1");

        // price × 2.00 × 10
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT price_input, credits_input, credits_output FROM agent.model_config_overrides "
                        + "WHERE provider = 'openai' AND model_id = 'gpt-5-bulk-a'");
        Map<String, Object> b = jdbc.queryForMap(
                "SELECT credits_input, credits_output FROM agent.model_config_overrides "
                        + "WHERE provider = 'anthropic' AND model_id = 'claude-bulk-b'");
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT credits_input, credits_output FROM agent.model_config_overrides "
                        + "WHERE provider = 'openai' AND model_id = 'gpt-5-bulk-c'");

        assertThat(((BigDecimal) a.get("price_input")))
                .as("price must not be touched by the recompute - only credits are re-derived")
                .isEqualByComparingTo("1.00");
        assertThat(((BigDecimal) a.get("credits_input"))).isEqualByComparingTo("20.0000");
        assertThat(((BigDecimal) a.get("credits_output"))).isEqualByComparingTo("80.0000");
        assertThat(((BigDecimal) b.get("credits_input"))).isEqualByComparingTo("40.0000");
        assertThat(((BigDecimal) b.get("credits_output"))).isEqualByComparingTo("160.0000");
        assertThat(c.get("credits_input")).as("NULL-price row stays NULL after bulk recompute").isNull();
        assertThat(c.get("credits_output")).isNull();
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Insert a catalog row and return its id. Credits are auto-derived. */
    private long insertModel(String provider, String modelId, String displayName,
                             BigDecimal priceIn, BigDecimal priceOut) {
        return jdbc.queryForObject(
                "INSERT INTO agent.model_config_overrides "
                        + "(provider, model_id, display_name, tier, price_input, price_output, "
                        + " enabled, ranking, rate_limit_tpm, rate_limit_rpm, source) "
                        + "VALUES (?, ?, ?, 'standard', ?, ?, TRUE, 0, 0, 0, 'manual') "
                        + "RETURNING id",
                Long.class, provider, modelId, displayName, priceIn, priceOut);
    }

    private int countEdits() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM agent.model_field_edits", Integer.class);
    }

    /** Run an UPDATE inside a TX with the HMAC session var set, so the audit trigger writes. */
    private void auditedUpdate(String sql) {
        jdbc.execute((java.sql.Connection c) -> {
            c.setAutoCommit(false);
            try (var s = c.createStatement()) {
                s.execute("SET LOCAL app.model_audit_hmac_key = '" + HMAC_KEY + "'");
                s.execute("SET LOCAL app.current_user_id = 'admin-42'");
                s.execute("SET LOCAL app.model_edit_source = 'admin'");
                s.execute(sql);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
            return null;
        });
    }
}
