package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Replay test for V342: per-family GRANT sentinel backfill on
 * {@code agent.agents.tools_config}.
 *
 * <p>Proves the four invariants the authoritative grant model relies on:
 * <ol>
 *   <li><b>Behaviour no-op</b> for every existing agent - a non-empty family list
 *       derives {@code 'custom'}, an empty/absent list derives {@code 'none'}
 *       (exactly what the pre-change list rule already resolved to).</li>
 *   <li><b>Null config → deny-by-default</b> - a {@code NULL} tools_config becomes a
 *       self-describing object with all 5 grants {@code 'none'} (NOT silently 'all').</li>
 *   <li><b>App Factory promotion</b> - the one builder id gets all 5 grants {@code 'all'}
 *       + workflowAccessMode {@code 'write'}.</li>
 *   <li><b>Idempotent</b> - re-applying never overwrites an explicit grant.</li>
 * </ol>
 */
@DisplayName("agent.agents tools_config grant backfill (V342)")
class AgentToolsConfigGrantBackfillMigrationTest {

    private static final String APP_FACTORY_ID = "72f2a86f-ccf1-4d81-a159-4b6f683b973c";

    @Test
    @DisplayName("V342 derives grants from lists (no-op), denies null configs, promotes the App Factory builder")
    void v342BackfillsGrantsCorrectly(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeFixture(tempDir, false);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "grant_backfill_replay");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "grant_backfill_replay", tempDir))
                    .doesNotThrowAnyException();

            String db = "grant_backfill_replay";
            // (1) Non-empty workflows list ⇒ 'custom'; empty tables list ⇒ 'none'.
            assertThat(grant(postgres, db, "11111111-1111-1111-1111-111111111111", "workflowsGrant"))
                    .isEqualTo("custom");
            assertThat(grant(postgres, db, "11111111-1111-1111-1111-111111111111", "tablesGrant"))
                    .isEqualTo("none");
            // All-empty agent ⇒ every grant 'none'.
            for (String fam : new String[]{"workflowsGrant", "tablesGrant", "interfacesGrant",
                    "agentsGrant", "applicationsGrant"}) {
                assertThat(grant(postgres, db, "22222222-2222-2222-2222-222222222222", fam))
                        .as("all-empty agent %s", fam)
                        .isEqualTo("none");
            }
            // (2) NULL tools_config ⇒ self-describing, all grants 'none' (deny-by-default), mode 'all'.
            assertThat(grant(postgres, db, "33333333-3333-3333-3333-333333333333", "workflowsGrant"))
                    .isEqualTo("none");
            assertThat(grant(postgres, db, "33333333-3333-3333-3333-333333333333", "applicationsGrant"))
                    .isEqualTo("none");
            assertThat(scalar(postgres, db, "SELECT tools_config->>'mode' FROM agent.agents "
                    + "WHERE id = '33333333-3333-3333-3333-333333333333'")).isEqualTo("all");
            // An explicit pre-existing grant is preserved verbatim (NOT flattened back to 'none').
            assertThat(grant(postgres, db, "44444444-4444-4444-4444-444444444444", "workflowsGrant"))
                    .isEqualTo("all");
            // (3) App Factory: all 5 families 'all' + workflowAccessMode 'write'.
            for (String fam : new String[]{"workflowsGrant", "tablesGrant", "interfacesGrant",
                    "agentsGrant", "applicationsGrant"}) {
                assertThat(grant(postgres, db, APP_FACTORY_ID, fam))
                        .as("App Factory %s", fam)
                        .isEqualTo("all");
            }
            assertThat(scalar(postgres, db, "SELECT tools_config->>'workflowAccessMode' FROM agent.agents "
                    + "WHERE id = '" + APP_FACTORY_ID + "'")).isEqualTo("write");
        }
    }

    @Test
    @DisplayName("V342 is idempotent: re-applying preserves derived and explicit grants")
    void v342IsIdempotent(@TempDir Path tempDir) throws Exception {
        FlywayTestSupport.assumeDockerAvailable();

        writeFixture(tempDir, true);

        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            FlywayTestSupport.createDatabase(postgres, "grant_backfill_idem");

            assertThatCode(() -> FlywayTestSupport.runFlyway(postgres, "grant_backfill_idem", tempDir))
                    .doesNotThrowAnyException();

            String db = "grant_backfill_idem";
            // Re-run never flips a derived grant nor overwrites an explicit one.
            assertThat(grant(postgres, db, "11111111-1111-1111-1111-111111111111", "workflowsGrant"))
                    .isEqualTo("custom");
            assertThat(grant(postgres, db, "44444444-4444-4444-4444-444444444444", "workflowsGrant"))
                    .isEqualTo("all");
            assertThat(grant(postgres, db, APP_FACTORY_ID, "tablesGrant")).isEqualTo("all");
        }
    }

    /**
     * Minimal {@code agent.agents} fixture covering every backfill branch:
     * <ul>
     *   <li>{@code 1111…} - non-empty workflows list, empty tables ⇒ custom / none.</li>
     *   <li>{@code 2222…} - all 5 lists empty ⇒ all none.</li>
     *   <li>{@code 3333…} - tools_config NULL ⇒ deny-by-default object.</li>
     *   <li>{@code 4444…} - explicit {@code workflowsGrant='all'} already present ⇒ preserved.</li>
     *   <li>App Factory id - promoted to all-'all' + write.</li>
     * </ul>
     * The REAL V342 is replayed as V2; when {@code reapply} is set it runs again as V3.
     */
    private static void writeFixture(Path directory, boolean reapply) throws Exception {
        Files.writeString(directory.resolve("V1__seed_agent_schema.sql"), """
                CREATE SCHEMA agent;

                CREATE TABLE agent.agents (
                    id           UUID PRIMARY KEY,
                    tenant_id    TEXT NOT NULL,
                    name         VARCHAR(255) NOT NULL,
                    tools_config JSONB
                );

                -- Non-empty workflows ⇒ custom; empty tables ⇒ none.
                INSERT INTO agent.agents (id, tenant_id, name, tools_config) VALUES
                  ('11111111-1111-1111-1111-111111111111', '1', 'custom-wf',
                   '{"mode":"all","workflows":["wf-1"],"tables":[],"interfaces":[],"agents":[],"applications":[]}'::jsonb);

                -- All 5 lists empty ⇒ all none.
                INSERT INTO agent.agents (id, tenant_id, name, tools_config) VALUES
                  ('22222222-2222-2222-2222-222222222222', '1', 'all-empty',
                   '{"mode":"all","workflows":[],"tables":[],"interfaces":[],"agents":[],"applications":[]}'::jsonb);

                -- tools_config NULL ⇒ deny-by-default object.
                INSERT INTO agent.agents (id, tenant_id, name, tools_config) VALUES
                  ('33333333-3333-3333-3333-333333333333', '1', 'null-config', NULL);

                -- Explicit grant already present ⇒ preserved (empty list must NOT flatten it).
                INSERT INTO agent.agents (id, tenant_id, name, tools_config) VALUES
                  ('44444444-4444-4444-4444-444444444444', '1', 'explicit-all',
                   '{"mode":"all","workflows":[],"workflowsGrant":"all","tables":[],"interfaces":[],"agents":[],"applications":[]}'::jsonb);

                -- App Factory builder - empty lists, to be promoted to all-'all'.
                INSERT INTO agent.agents (id, tenant_id, name, tools_config) VALUES
                  ('72f2a86f-ccf1-4d81-a159-4b6f683b973c', '1', 'App Factory',
                   '{"mode":"all","workflows":[],"tables":[],"interfaces":[],"agents":[],"applications":[],"workflowAccessMode":"write"}'::jsonb);
                """);

        String v342Sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V342__agent_tools_config_grant_backfill.sql"));
        Files.writeString(directory.resolve("V2__grant_backfill.sql"), v342Sql);
        if (reapply) {
            Files.writeString(directory.resolve("V3__reapply_grant_backfill.sql"), v342Sql);
        }
    }

    private static String grant(PostgreSQLContainer<?> postgres, String databaseName, String agentId, String grantKey)
            throws Exception {
        return scalar(postgres, databaseName,
                "SELECT tools_config->>'" + grantKey + "' FROM agent.agents WHERE id = '" + agentId + "'");
    }

    private static String scalar(PostgreSQLContainer<?> postgres, String databaseName, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(
                FlywayTestSupport.jdbcUrl(postgres, databaseName), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }
}
