package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.testsupport.ScratchPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code workflow_step_data.is_mocked} (V526), end to end on a real Postgres: the shipped migration,
 * the three native INSERT paths and the three hand-listed "lightweight" SELECTs.
 *
 * <p>Every one of those binds or lists its columns by hand, so each can drop the column on its own,
 * and none of them fails loudly when it does: an INSERT that omits it lets DEFAULT false through,
 * which puts every mocked {@code error} step back in the Grafana tool-health queue as a real
 * failure. Mock-based tests stub the JdbcTemplate and never parse this SQL.
 *
 * <p>Runs on the CI job's Postgres through {@link ScratchPostgres}, not Testcontainers: the CI
 * runners have no Docker socket, so a Testcontainers class there skips and the step goes green
 * having proved nothing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("workflow_step_data.is_mocked (V526) - migration, insert paths and reads on real Postgres")
class WorkflowStepDataMockedFlagPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "ORCHESTRATOR_TEST_PG",
            "it is the only executable proof that a mocked step keeps its mock flag through every "
                    + "hand-written INSERT and SELECT of workflow_step_data");

    private static final Path V526 = Paths.get("..", "migration-service", "src", "main", "resources",
            "db", "migration", "V526__workflow_step_data_is_mocked.sql");
    private static final UUID RUN_ID = UUID.fromString("6c1f0f5e-9b0e-4a7c-8d3e-2f1a4b5c6d7e");
    private static final String RUN = "run_mocked_flag";

    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private StepDataNativeRepository repo;
    private WorkflowStepDataBulkInserter bulkInserter;

    @BeforeAll
    void setUp() {
        DB.require();
        DriverManagerDataSource ds = new DriverManagerDataSource(DB.url(), DB.user(), DB.password());
        // The native repository writes the table UNQUALIFIED and the bulk inserter writes it
        // schema-qualified, exactly like production, where orchestrator is on the search_path.
        Properties props = new Properties();
        props.setProperty("currentSchema", "orchestrator");
        ds.setConnectionProperties(props);
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);

        // The scratch database is shared with other classes: own only the tables this one builds.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS orchestrator");
        dropTables();
        jdbc.execute("CREATE TABLE orchestrator.workflow_runs ("
                + " id UUID PRIMARY KEY, organization_id VARCHAR(255) NOT NULL)");
        // The PRE-V526 shape: the migration under test adds the column.
        jdbc.execute("""
                CREATE TABLE orchestrator.workflow_step_data (
                    id BIGSERIAL PRIMARY KEY,
                    workflow_run_id UUID,
                    run_id VARCHAR(255) NOT NULL,
                    step_alias VARCHAR(2000) NOT NULL,
                    tool_id VARCHAR(2000) NOT NULL,
                    input_data JSONB,
                    output_storage_id UUID,
                    http_status INTEGER,
                    status VARCHAR(32) NOT NULL,
                    start_time TIMESTAMPTZ,
                    end_time TIMESTAMPTZ,
                    error_message TEXT,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255),
                    epoch INTEGER DEFAULT 0,
                    spawn INTEGER DEFAULT 0,
                    iteration INTEGER DEFAULT 0,
                    item_index INTEGER DEFAULT 0,
                    metadata JSONB,
                    node_type VARCHAR(20),
                    condition_expression TEXT,
                    condition_result BOOLEAN,
                    selected_branch TEXT,
                    loop_id VARCHAR(2000),
                    loop_iteration INTEGER,
                    loop_exit_reason VARCHAR(50),
                    merge_strategy VARCHAR(50),
                    merge_received_branches JSONB,
                    merge_skipped_branches JSONB,
                    item_id VARCHAR(255),
                    trigger_id VARCHAR(255) NOT NULL,
                    skip_reason VARCHAR(255),
                    skip_source_node VARCHAR(255),
                    normalized_key VARCHAR(255),
                    item_number INTEGER,
                    CONSTRAINT idx_workflow_step_data_unique_v6 UNIQUE
                        (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status)
                )
                """);
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, organization_id) VALUES (?, 'org-1')", RUN_ID);

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repo = new StepDataNativeRepository(jdbc, mapper);
        bulkInserter = new WorkflowStepDataBulkInserter(named, mapper, new SimpleMeterRegistry(), true);
    }

    @AfterAll
    void tearDown() {
        if (jdbc != null) {
            dropTables();
        }
    }

    @Test
    @Order(1)
    @DisplayName("the shipped V526 applies to the old table shape, and a row written before it reads false")
    void migrationAddsTheColumnWithFalseForExistingRows() throws Exception {
        jdbc.update("INSERT INTO orchestrator.workflow_step_data "
                + "(workflow_run_id, run_id, step_alias, tool_id, status, tenant_id, trigger_id, item_index) "
                + "VALUES (?, ?, 'mcp:legacy', 'gmail/send-message', 'FAILED', 't1', 'trigger:t', 99)",
                RUN_ID, RUN);

        // As Flyway runs it on PostgreSQL: inside a transaction, which is what SET LOCAL needs.
        String sql = Files.readString(V526, StandardCharsets.UTF_8);
        jdbc.execute("BEGIN; " + sql + " COMMIT;");

        assertThat(jdbc.queryForObject(
                "SELECT is_mocked FROM orchestrator.workflow_step_data WHERE item_index = 99", Boolean.class))
                .isFalse();
        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'orchestrator' AND table_name = 'workflow_step_data' "
                + "AND column_name = 'is_mocked'", String.class))
                .isEqualTo("NO");
    }

    @Test
    @Order(2)
    @DisplayName("is_mocked survives all three native insert paths, true AND false")
    void everyInsertPathKeepsTheFlag() {
        // Both values on every path, so a path that hard-codes a constant cannot pass.
        repo.insertIgnoringDuplicate(row(0, true));
        repo.insertIgnoringDuplicate(row(1, false));
        repo.insertBatchIgnoringDuplicates(List.of(row(2, true), row(3, false)));
        bulkInserter.saveBatch(List.of(row(4, true), row(5, false)));

        assertThat(jdbc.queryForList("SELECT item_index FROM orchestrator.workflow_step_data "
                + "WHERE is_mocked ORDER BY item_index", Integer.class))
                .containsExactly(0, 2, 4);
        assertThat(jdbc.queryForList("SELECT item_index FROM orchestrator.workflow_step_data "
                + "WHERE NOT is_mocked AND item_index < 99 ORDER BY item_index", Integer.class))
                .containsExactly(1, 3, 5);
    }

    @Test
    @Order(3)
    @DisplayName("the shipped lightweight reads return is_mocked, for Hibernate to map onto the entity")
    void lightweightReadsReturnTheFlag() throws Exception {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workflowRunId", RUN_ID)
                .addValue("epoch", 1);
        for (String method : List.of("findByWorkflowRunIdLightweightAll", "findLatestPerAliasLightweight",
                "findByWorkflowRunIdAndEpochLatestPerAliasLightweight")) {
            List<Map<String, Object>> rows = named.queryForList(shippedSql(method), params);
            assertThat(rows).describedAs(method).isNotEmpty();
            assertThat(rows.get(0)).describedAs(method).containsKey("is_mocked");
        }
        // The full read also proves the value, not only the column: row 0 is mocked, row 1 is not.
        List<Map<String, Object>> all = named.queryForList(
                shippedSql("findByWorkflowRunIdLightweightAll"), params);
        assertThat(all).filteredOn(r -> Integer.valueOf(0).equals(r.get("item_index")))
                .extracting(r -> r.get("is_mocked")).containsExactly(true);
        assertThat(all).filteredOn(r -> Integer.valueOf(1).equals(r.get("item_index")))
                .extracting(r -> r.get("is_mocked")).containsExactly(false);
    }

    private static String shippedSql(String methodName) {
        Method method = Arrays.stream(WorkflowStepDataRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no repository method " + methodName));
        return method.getAnnotation(Query.class).value();
    }

    private static WorkflowStepDataEntity row(int itemIndex, boolean mocked) {
        WorkflowStepDataEntity e = new WorkflowStepDataEntity();
        e.setWorkflowRunId(RUN_ID);
        e.setRunId(RUN);
        // A distinct alias per row, so the latest-per-alias reads return every one of them.
        e.setStepAlias("mcp:send_" + itemIndex);
        e.setToolId("gmail/send-message");
        e.setStatus(mocked ? "FAILED" : "COMPLETED");
        e.setTenantId("t1");
        e.setOrganizationId("org-1");
        e.setEpoch(1);
        e.setSpawn(0);
        e.setIteration(0);
        e.setItemIndex(itemIndex);
        e.setTriggerId("trigger:t");
        e.setNormalizedKey("mcp:send_" + itemIndex);
        e.setStartTime(Instant.parse("2026-09-24T10:00:00Z"));
        e.setMocked(mocked);
        return e;
    }

    private void dropTables() {
        jdbc.execute("DROP TABLE IF EXISTS orchestrator.workflow_step_data CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS orchestrator.workflow_runs CASCADE");
    }
}
