package com.apimarketplace.orchestrator.integration.completion;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowStatus;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.integration.IntegrationTest;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository.EpochCountRow;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E integration test for the per-epoch SKIPPED bucketing fix.
 *
 * <p><b>Bug:</b> {@code StepCompletionOrchestrator.completeSkippedStepWithoutStateUpdate}
 * (legacy 6-arg form) bucketed every per-item SKIPPED row under
 * {@code (epoch=0, triggerId="trigger:default")} in {@code workflow_epochs}, so the
 * per-epoch UI view rendered {@code statusCounts=null} for split successors when the
 * real run was on a later epoch with a real triggerId.
 *
 * <p><b>What this test proves end-to-end against a real DB:</b> driving the new 8-arg
 * overload with explicit {@code (epoch=4, triggerId="trigger:cron")} writes BOTH
 * {@code workflow_step_data} (epoch column) AND {@code workflow_epochs} (counter row
 * keyed by trigger_id + epoch) under the real DAG coordinates - not the legacy default
 * bucket. Mirror assertions confirm the legacy 6-arg form still bucketed under
 * {@code (epoch=0, triggerId="trigger:default")} for comparison.
 *
 * <p>This is the closest thing to an MCP-driven e2e test: it skips the HTTP/JWT layer
 * but exercises the full Spring-wired chain
 * {@code StepCompletionOrchestrator → SkippedNodePersistenceService → WorkflowStepDataRepository}
 * and {@code StepCompletionOrchestrator → WorkflowEpochService → WorkflowEpochRepository}
 * against an actual H2 database with PostgreSQL compatibility.
 */
@IntegrationTest
@DisplayName("Per-epoch SKIPPED bucketing - E2E DB integration")
class PerEpochSkipBucketingIntegrationTest {

    @Autowired
    private StepCompletionOrchestrator stepCompletionOrchestrator;

    @Autowired
    private WorkflowEpochRepository workflowEpochRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private static final String TENANT_ID = "tenant-1";
    private static final String ORGANIZATION_ID = "org-1";
    private static final String NODE_ID = "mcp:apply_tech";
    private static final String NODE_LABEL = "apply_tech";
    private static final String SKIP_REASON = "Not routed to this branch";
    private static final String SKIP_SOURCE = "agent:classify";

    private WorkflowExecution buildExecution(String runId) {
        // Create a workflow row
        WorkflowEntity workflow = new WorkflowEntity(TENANT_ID, "Test split-classify workflow", "user-1");
        workflow.setId(UUID.randomUUID());
        workflow.setStatus(WorkflowStatus.ACTIVE);
        workflow.setIsActive(true);
        workflow.setOrganizationId(ORGANIZATION_ID);
        entityManager.persist(workflow);

        // Create a workflow run row
        WorkflowRunEntity workflowRun = new WorkflowRunEntity(workflow, TENANT_ID, runId, null, null, "user-1");
        workflowRun.setStatus(RunStatus.RUNNING);
        workflowRun.setOrganizationId(ORGANIZATION_ID);
        entityManager.persist(workflowRun);
        entityManager.flush();

        // Build a minimal WorkflowPlan - recordSkippedNode reads tenantId + id off it.
        WorkflowPlan plan = new WorkflowPlan(
                workflow.getId().toString(),
                TENANT_ID,
                java.util.List.of(), // triggers
                java.util.List.of(), // mcps
                java.util.List.of(), // agents
                java.util.List.of(), // edges
                java.util.List.of(), // cores
                java.util.List.of(), // tables
                java.util.List.of(), // notes
                java.util.List.of(), // interfaces
                java.util.Map.of()); // metadata

        WorkflowExecution execution = new WorkflowExecution(runId, plan, null);
        execution.setWorkflowRunId(workflowRun.getId());
        execution.setDisplayName("Test split-classify workflow");
        return execution;
    }

    @Test
    @DisplayName("8-arg overload writes workflow_epochs + workflow_step_data under real (epoch, triggerId)")
    void newOverloadRoutesToRealDagCoordinates() {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowExecution execution = buildExecution(runId);

        // When - caller drives the new overload with real DAG coordinates
        stepCompletionOrchestrator.completeSkippedStepWithoutStateUpdate(
                execution, NODE_ID, NODE_LABEL, SKIP_REASON, SKIP_SOURCE,
                /* itemIndex */ 3,
                /* epoch     */ 4,
                /* triggerId */ "trigger:cron");

        // Then - workflow_epochs row landed under (epoch=4, triggerId="trigger:cron")
        List<EpochCountRow> realDagRows =
                workflowEpochRepository.findByRunIdTriggerAndEpoch(runId, "trigger:cron", 4);
        assertThat(realDagRows)
                .as("workflow_epochs counter row must land under the REAL DAG (trigger:cron, epoch=4)")
                .extracting(EpochCountRow::entryType, EpochCountRow::entryKey,
                            EpochCountRow::status, EpochCountRow::count)
                .contains(tupleOf("NODE", NODE_ID, "SKIPPED", 1));

        // And NOT under the legacy (epoch=0, triggerId="trigger:default") bucket
        List<EpochCountRow> legacyBucketRows =
                workflowEpochRepository.findByRunIdTriggerAndEpoch(runId, "trigger:default", 0);
        assertThat(legacyBucketRows)
                .as("Pre-fix bucketed every per-item skip into trigger:default/epoch=0 - must be empty post-fix")
                .extracting(EpochCountRow::entryKey)
                .doesNotContain(NODE_ID);

        // And workflow_step_data row carries the same real DAG coordinates
        // (sourced by per-epoch UI's getAggregatedSteps(runId, 4)).
        // We query via JdbcTemplate to bypass H2's lack of native JSONB deserialization on
        // the metadata column; the columns we care about (tool_id, status, item_index, epoch)
        // are simple types.
        Integer countAtEpoch4 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_data WHERE run_id=? AND tool_id=? AND status='SKIPPED' AND item_index=? AND epoch=?",
                Integer.class, runId, NODE_ID, 3, 4);
        assertThat(countAtEpoch4)
                .as("workflow_step_data row must carry epoch=4 so getAggregatedSteps(runId, 4) returns it")
                .isEqualTo(1);

        Integer countAtRealTrigger = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_data WHERE run_id=? AND tool_id=? AND status='SKIPPED' AND item_index=? AND epoch=? AND trigger_id=?",
                Integer.class, runId, NODE_ID, 3, 4, "trigger:cron");
        assertThat(countAtRealTrigger)
                .as("workflow_step_data row must carry trigger_id=trigger:cron, not trigger:default")
                .isEqualTo(1);

        Integer countAtEpoch0 = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_data WHERE run_id=? AND tool_id=? AND status='SKIPPED' AND epoch=0",
                Integer.class, runId, NODE_ID);
        assertThat(countAtEpoch0)
                .as("Pre-fix wrote workflow_step_data.epoch=0 (via global resolver fallback) - post-fix must be 0")
                .isEqualTo(0);

        Integer countAtDefaultTrigger = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM workflow_step_data WHERE run_id=? AND tool_id=? AND status='SKIPPED' AND trigger_id='trigger:default'",
                Integer.class, runId, NODE_ID);
        assertThat(countAtDefaultTrigger)
                .as("Pre-fix wrote SKIPPED workflow_step_data under trigger:default - post-fix must be 0")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("Legacy 6-arg overload preserves prior (epoch=0, triggerId='trigger:default') behavior")
    void legacyOverloadStillBucketsUnderDefault() {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        WorkflowExecution execution = buildExecution(runId);

        // When - caller drives the legacy 6-arg form (no DAG coordinates)
        stepCompletionOrchestrator.completeSkippedStepWithoutStateUpdate(
                execution, NODE_ID, NODE_LABEL, SKIP_REASON, SKIP_SOURCE,
                /* itemIndex */ 1);

        // Then - workflow_epochs row lands under (epoch=0, triggerId="trigger:default")
        // because the legacy shim delegates to the 8-arg with (0, null) and the repo
        // substitutes "trigger:default" for null triggerId. This confirms back-compat
        // is preserved exactly as it was pre-fix.
        List<EpochCountRow> defaultBucketRows =
                workflowEpochRepository.findByRunIdTriggerAndEpoch(runId, "trigger:default", 0);
        assertThat(defaultBucketRows)
                .as("Legacy 6-arg form must keep writing under trigger:default/epoch=0 (back-compat)")
                .extracting(EpochCountRow::entryType, EpochCountRow::entryKey,
                            EpochCountRow::status, EpochCountRow::count)
                .contains(tupleOf("NODE", NODE_ID, "SKIPPED", 1));
    }

    private static org.assertj.core.groups.Tuple tupleOf(Object... values) {
        return org.assertj.core.groups.Tuple.tuple(values);
    }
}
