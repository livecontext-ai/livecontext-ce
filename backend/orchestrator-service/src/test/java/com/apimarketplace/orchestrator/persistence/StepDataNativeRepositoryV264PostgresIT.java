package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres regression test for the V264 follow-through fix
 * (commit after 653fc16d6) that dropped {@code ::uuid} / {@code CAST AS uuid}
 * casts from native INSERTs against {@code workflow_step_data.organization_id}
 * after V264 aligned the column type from UUID to VARCHAR(255).
 *
 * <p>The pre-fix SQL contained:
 * <pre>
 *   COALESCE(?::uuid, (SELECT organization_id FROM workflow_runs WHERE id = ?))
 * </pre>
 * Once V264 turned both branches of the COALESCE into VARCHAR, Postgres
 * rejected the statement at parse time with
 * {@code ERROR: COALESCE types uuid and character varying cannot be matched},
 * silently dropping every step_data write platform-wide (prod incident
 * 2026-05-20 11:00 → 16:00 UTC, 12 epochs of one cron workflow lost data).
 *
 * <p>Mock tests (see {@link StepDataNativeRepositoryBatchTest}) cannot catch
 * this: they stub {@code JdbcTemplate.update}, so the SQL string is never
 * parsed by a real planner. H2 (see {@link H2StepDataNativeRepository}) uses
 * a different INSERT without the COALESCE pattern, so the bug was invisible
 * in the e2e suite too. Only a real Postgres execution exercises the
 * COALESCE type-unification rule.
 *
 * <p>Skipped without Docker.
 */
@Testcontainers
@DisplayName("V264 regression - StepDataNativeRepository INSERT survives varchar organization_id")
class StepDataNativeRepositoryV264PostgresIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ORG_ID = "00000000-0000-0000-0000-000000000000";
    private static final UUID RUN_ID = UUID.fromString("656a4aed-47b6-4a9f-bfa7-9bff2f707cca");

    static JdbcTemplate jdbc;
    static NamedParameterJdbcTemplate namedJdbc;
    static StepDataNativeRepository repo;
    static WorkflowStepDataBulkInserter bulkInserter;

    @BeforeAll
    static void setUpClass() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker not available - StepDataNativeRepositoryV264PostgresIT skipped");

        // Mirror prod JDBC URL: orchestrator schema is on the search_path so
        // unqualified table refs (StepDataNativeRepository) AND fully-qualified
        // refs (WorkflowStepDataBulkInserter: orchestrator.workflow_step_data)
        // both resolve to the same physical tables.
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl() + "&currentSchema=orchestrator",
                POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        jdbc = new JdbcTemplate(ds);
        namedJdbc = new NamedParameterJdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS orchestrator");

        // V264'd schema slice - both organization_id columns are VARCHAR(255),
        // matching the post-migration prod state. NOT UUID. The COALESCE in
        // the SUT's INSERT used to assume UUID on the parameter side and
        // VARCHAR on the sub-SELECT side, which Postgres refuses.
        jdbc.execute("CREATE TABLE orchestrator.workflow_runs ("
                + "  id UUID PRIMARY KEY,"
                + "  run_id_public VARCHAR(64) UNIQUE NOT NULL,"
                + "  tenant_id VARCHAR(255) NOT NULL,"
                + "  organization_id VARCHAR(255) NOT NULL"
                + ")");
        jdbc.execute("CREATE TABLE orchestrator.workflow_step_data ("
                + "  id BIGSERIAL PRIMARY KEY,"
                + "  workflow_run_id UUID,"
                + "  run_id VARCHAR(255) NOT NULL,"
                + "  step_alias VARCHAR(2000) NOT NULL,"
                + "  tool_id VARCHAR(2000) NOT NULL,"
                + "  input_data JSONB,"
                + "  output_storage_id UUID,"
                + "  http_status INTEGER,"
                + "  status VARCHAR(32) NOT NULL,"
                + "  start_time TIMESTAMPTZ,"
                + "  end_time TIMESTAMPTZ,"
                + "  error_message TEXT,"
                + "  tenant_id VARCHAR(255) NOT NULL,"
                + "  organization_id VARCHAR(255),"
                + "  epoch INTEGER DEFAULT 0,"
                + "  spawn INTEGER DEFAULT 0,"
                + "  iteration INTEGER DEFAULT 0,"
                + "  item_index INTEGER DEFAULT 0,"
                + "  metadata JSONB,"
                + "  node_type VARCHAR(20),"
                + "  condition_expression TEXT,"
                + "  condition_result BOOLEAN,"
                + "  selected_branch TEXT,"
                + "  loop_id VARCHAR(2000),"
                + "  loop_iteration INTEGER,"
                + "  loop_exit_reason VARCHAR(50),"
                + "  merge_strategy VARCHAR(50),"
                + "  merge_received_branches JSONB,"
                + "  merge_skipped_branches JSONB,"
                + "  item_id VARCHAR(255),"
                + "  trigger_id VARCHAR(255) NOT NULL,"
                + "  skip_reason VARCHAR(255),"
                + "  skip_source_node VARCHAR(255),"
                + "  normalized_key VARCHAR(255),"
                + "  item_number INTEGER,"
                + "  CONSTRAINT idx_workflow_step_data_unique_v6 UNIQUE "
                + "    (workflow_run_id, step_alias, trigger_id, iteration, item_index, epoch, spawn, status)"
                + ")");

        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        repo = new StepDataNativeRepository(jdbc, objectMapper);
        bulkInserter = new WorkflowStepDataBulkInserter(
                namedJdbc, objectMapper,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                true);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("DELETE FROM orchestrator.workflow_step_data");
        jdbc.execute("DELETE FROM orchestrator.workflow_runs");
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, run_id_public, tenant_id, organization_id) "
                + "VALUES (?, ?, ?, ?)",
                RUN_ID, "run_test_v264", "1", ORG_ID);
    }

    @Test
    @DisplayName("insertIgnoringDuplicate with explicit varchar orgId - survives COALESCE post-V264 (regression: COALESCE uuid/varchar parse error)")
    void insertSucceedsWithExplicitVarcharOrgId() {
        WorkflowStepDataEntity row = newRow(0);
        row.setOrganizationId(ORG_ID);

        boolean inserted = repo.insertIgnoringDuplicate(row);

        assertThat(inserted).isTrue();
        String storedOrg = jdbc.queryForObject(
                "SELECT organization_id FROM orchestrator.workflow_step_data WHERE item_index = 0",
                String.class);
        assertThat(storedOrg).isEqualTo(ORG_ID);
    }

    @Test
    @DisplayName("insertIgnoringDuplicate with NULL orgId - COALESCE pulls from parent workflow_runs (both varchar branches)")
    void insertCoalescesFromParentRunWhenOrgIdNull() {
        WorkflowStepDataEntity row = newRow(1);
        row.setOrganizationId(null);

        boolean inserted = repo.insertIgnoringDuplicate(row);

        assertThat(inserted).isTrue();
        String storedOrg = jdbc.queryForObject(
                "SELECT organization_id FROM orchestrator.workflow_step_data WHERE item_index = 1",
                String.class);
        assertThat(storedOrg).isEqualTo(ORG_ID);
    }

    @Test
    @DisplayName("insertBatchIgnoringDuplicates 3-row batch - every row lands with explicit varchar orgId (no implicit-uuid-cast drift)")
    void batchInsertSucceedsWithVarcharOrgId() {
        List<WorkflowStepDataEntity> batch = List.of(newRow(10), newRow(11), newRow(12));
        for (WorkflowStepDataEntity r : batch) r.setOrganizationId(ORG_ID);

        int rows = repo.insertBatchIgnoringDuplicates(batch);

        assertThat(rows).isEqualTo(3);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orchestrator.workflow_step_data WHERE organization_id = ?",
                Long.class, ORG_ID);
        assertThat(count).isEqualTo(3L);
    }

    @Test
    @DisplayName("WorkflowStepDataBulkInserter - INSERT with varchar :organization_id lands without CAST AS uuid")
    void bulkInserterSucceedsWithVarcharOrgId() {
        WorkflowStepDataEntity row = newRow(20);
        row.setOrganizationId(ORG_ID);

        int rows = bulkInserter.saveBatch(List.of(row));

        assertThat(rows).isEqualTo(1);
        String storedOrg = jdbc.queryForObject(
                "SELECT organization_id FROM orchestrator.workflow_step_data WHERE item_index = 20",
                String.class);
        assertThat(storedOrg).isEqualTo(ORG_ID);
    }

    private WorkflowStepDataEntity newRow(int itemIndex) {
        WorkflowStepDataEntity e = new WorkflowStepDataEntity();
        e.setWorkflowRunId(RUN_ID);
        e.setRunId("run_test_v264");
        e.setStepAlias("core:parse_headers");
        e.setToolId("test-tool");
        e.setStatus("COMPLETED");
        e.setTenantId("1");
        e.setEpoch(1);
        e.setSpawn(0);
        e.setIteration(0);
        e.setItemIndex(itemIndex);
        e.setItemId("item-" + itemIndex);
        e.setTriggerId("trigger:cron");
        e.setNormalizedKey("core:parse_headers");
        e.setStartTime(Instant.parse("2026-05-20T16:00:00Z"));
        return e;
    }
}
