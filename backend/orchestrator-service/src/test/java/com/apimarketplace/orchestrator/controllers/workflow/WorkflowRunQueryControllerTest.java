package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.common.storage.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for status counts accumulation in {@link WorkflowRunQueryController}.
 *
 * Key design decision: ALL nodes accumulate counts across ALL epochs.
 * Whether single-DAG or multi-DAG, the unfiltered query is always used.
 * If a webhook fires 3 times (epoch 0, 1, 2), counts show 3.
 *
 * Single source of truth: WorkflowEpochService (workflow_epochs table).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunQueryController - Accumulated Status Counts")
class WorkflowRunQueryControllerTest {

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowStepDataRepository workflowStepDataRepository;
    @Mock private WorkflowRunStatusService workflowRunStatusService;
    @Mock private WorkflowEpochService workflowEpochService;
    @Mock private com.apimarketplace.orchestrator.trigger.ProductionRunResolver productionRunResolver;
    @Mock private StorageService storageService;
    @Mock private com.apimarketplace.orchestrator.repository.WorkflowEpochRepository workflowEpochRepository;
    @Mock private com.apimarketplace.orchestrator.services.ApplicationRunVersionBatchService applicationRunVersionBatchService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WorkflowRunQueryController controller;

    private static final String RUN_ID = "run-abc-123";
    private static final String TENANT_ID = "tenant-test";

    @BeforeEach
    void setUp() {
        controller = new WorkflowRunQueryController(
            workflowRunRepository,
            workflowStepDataRepository,
            workflowRunStatusService,
            workflowEpochService,
            productionRunResolver,
            storageService,
            objectMapper,
            workflowEpochRepository,
            applicationRunVersionBatchService
        );
    }

    // ===== Helpers =====

    private WorkflowRunEntity buildRunEntity(Map<String, Object> metadata, Map<String, Object> plan) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        run.setStatus(RunStatus.RUNNING);
        run.setUpdatedAt(Instant.now());
        run.setMetadata(metadata);
        run.setPlan(plan);
        return run;
    }

    private Map<String, Object> buildPlanMap(List<Map<String, Object>> triggers, List<Map<String, Object>> edges) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", triggers);
        plan.put("edges", edges != null ? edges : List.of());
        plan.put("mcps", List.of());
        plan.put("agents", List.of());
        plan.put("cores", List.of());
        plan.put("tables", List.of());
        plan.put("notes", List.of());
        plan.put("interfaces", List.of());
        return plan;
    }

    private Map<String, Object> buildFullPlanMap(
            List<Map<String, Object>> triggers,
            List<Map<String, Object>> mcps,
            List<Map<String, Object>> agents,
            List<Map<String, Object>> cores,
            List<Map<String, Object>> interfaces,
            List<Map<String, Object>> edges) {
        Map<String, Object> plan = buildPlanMap(triggers, edges);
        if (mcps != null) plan.put("mcps", mcps);
        if (agents != null) plan.put("agents", agents);
        if (cores != null) plan.put("cores", cores);
        if (interfaces != null) plan.put("interfaces", interfaces);
        return plan;
    }

    private Map<String, Object> triggerMap(String id, String label, String type) {
        Map<String, Object> t = new HashMap<>();
        t.put("id", id);
        t.put("label", label);
        t.put("type", type);
        return t;
    }

    private Map<String, Object> mcpMap(String id, String label) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        m.put("label", label);
        return m;
    }

    private Map<String, Object> agentMap(String label, String type) {
        Map<String, Object> a = new HashMap<>();
        a.put("label", label);
        a.put("type", type);
        return a;
    }

    private Map<String, Object> coreMap(String id, String type, String label) {
        Map<String, Object> c = new HashMap<>();
        c.put("id", id);
        c.put("type", type);
        c.put("label", label);
        return c;
    }

    private Map<String, Object> interfaceMap(String id, String label) {
        Map<String, Object> i = new HashMap<>();
        i.put("id", id);
        i.put("label", label);
        return i;
    }

    private Map<String, Object> edgeMap(String from, String to) {
        Map<String, Object> e = new HashMap<>();
        e.put("from", from);
        e.put("to", to);
        return e;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Long>> getNodes(Map<String, Object> body) {
        return (Map<String, Map<String, Long>>) body.get("nodes");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Long>> getEdges(Map<String, Object> body) {
        return (Map<String, Map<String, Long>>) body.get("edges");
    }

    /** Tracks per-test edge stub so {@link #stubEpochEdgeCounts} can update the bundled response. */
    private Map<String, Map<String, Long>> currentEdgeStub = Map.of();
    private Map<String, Map<String, Long>> currentNodeStub = Map.of();

    private void rewireAccumulatedCounts() {
        WorkflowEpochService.AccumulatedCounts bundled =
            new WorkflowEpochService.AccumulatedCounts(currentNodeStub, currentEdgeStub);
        lenient().when(workflowEpochService.getAccumulatedCounts(RUN_ID)).thenReturn(bundled);
    }

    /** Stub epoch counts to return empty (both nodes and edges). */
    private void stubEpochCountsEmpty() {
        currentNodeStub = Map.of();
        currentEdgeStub = Map.of();
        rewireAccumulatedCounts();
    }

    /** Stub node epoch counts with data; edges default to empty. */
    private void stubEpochCounts(Map<String, Map<String, Long>> counts) {
        currentNodeStub = counts;
        currentEdgeStub = Map.of();
        rewireAccumulatedCounts();
    }

    /** Stub edge epoch counts with data (must be called after {@link #stubEpochCounts}). */
    private void stubEpochEdgeCounts(Map<String, Map<String, Long>> edgeCounts) {
        currentEdgeStub = edgeCounts;
        rewireAccumulatedCounts();
    }

    // ===== Tests =====

    @Test
    @DisplayName("Returns 404 when run not found")
    void returnsNotFoundWhenRunMissing() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getStatusCounts(RUN_ID, TENANT_ID, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Nested
    @DisplayName("Response structure")
    class ResponseStructure {

        @Test
        @DisplayName("Response contains all required fields")
        void responseContainsAllFields() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")), List.of()));
            run.setStatus(RunStatus.WAITING_TRIGGER);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            assertThat(body).containsKeys("runId", "status", "epoch", "nodes", "edges", "updatedAt");
            assertThat(body.get("runId")).isEqualTo(RUN_ID);
            assertThat(body.get("status")).isEqualTo("WAITING_TRIGGER");
            assertThat(body.get("epoch")).isEqualTo(1);
            assertThat(body.get("updatedAt")).isNotNull();
        }

        @Test
        @DisplayName("dagEpochs included when present in metadata")
        void dagEpochsIncludedWhenPresent() {
            Map<String, Object> dagEpochs = new HashMap<>();
            dagEpochs.put("trigger:webhook1", 2);
            dagEpochs.put("trigger:webhook2", 2);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 2);
            metadata.put("dagEpochs", dagEpochs);

            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook"),
                        triggerMap("wh2", "Webhook2", "webhook")),
                List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            assertThat(body).containsKey("dagEpochs");
            @SuppressWarnings("unchecked")
            Map<String, Object> returnedDagEpochs = (Map<String, Object>) body.get("dagEpochs");
            assertThat(returnedDagEpochs).containsEntry("trigger:webhook1", 2);
        }

        @Test
        @DisplayName("dagEpochs absent when not in metadata")
        void dagEpochsAbsentWhenNotInMetadata() {
            Map<String, Object> metadata = Map.of("currentEpoch", 0);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Wh1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            assertThat(body).doesNotContainKey("dagEpochs");
        }
    }

    @Nested
    @DisplayName("Accumulation across epochs")
    class Accumulation {

        @Test
        @DisplayName("Accumulates counts across all epochs (the key behavior)")
        void accumulatesAcrossAllEpochs() {
            Map<String, Object> metadata = Map.of("currentEpoch", 2);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")),
                List.of(edgeMap("trigger:webhook1", "mcp:step1"))));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 3L),
                "step1", Map.of("completed", 3L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(3L);
            assertThat(nodes.get("step1").get("completed")).isEqualTo(3L);

            verify(workflowEpochService).getAccumulatedCounts(RUN_ID);
        }

        @Test
        @DisplayName("Epoch 0 - first execution returns counts")
        void epoch0ReturnsCurrentCounts() {
            Map<String, Object> metadata = Map.of("currentEpoch", 0);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 1L),
                "api_call", Map.of("completed", 1L)
            ));

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            assertThat(body.get("epoch")).isEqualTo(0);

            Map<String, Map<String, Long>> nodes = getNodes(body);
            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(1L);
            assertThat(nodes).containsKey("api_call");
        }

        @Test
        @DisplayName("Multiple statuses per node are all returned")
        void multipleStatusesPerNode() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of(
                "api_call", Map.of("completed", 5L, "failed", 2L, "running", 1L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));
            assertThat(nodes.get("api_call")).containsEntry("completed", 5L);
            assertThat(nodes.get("api_call")).containsEntry("failed", 2L);
            assertThat(nodes.get("api_call")).containsEntry("running", 1L);
        }

        @Test
        @DisplayName("Empty DB result returns empty node counts")
        void emptyDbResultReturnsEmptyNodes() {
            Map<String, Object> metadata = Map.of("currentEpoch", 0);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Wh1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));
            assertThat(nodes).isEmpty();
        }

        @Test
        @DisplayName("Multi-DAG with different epochs still accumulates all counts")
        void multiDagWithDifferentEpochsStillAccumulates() {
            Map<String, Object> dagEpochs = new HashMap<>();
            dagEpochs.put("trigger:webhook1", 2);
            dagEpochs.put("trigger:webhook2", 1);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 2);
            metadata.put("dagEpochs", dagEpochs);

            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook"),
                        triggerMap("wh2", "Webhook2", "webhook")),
                List.of(mcpMap("step_a", "Step A"), mcpMap("step_b", "Step B")),
                null, null, null,
                List.of(edgeMap("trigger:webhook1", "mcp:step_a"),
                        edgeMap("trigger:webhook2", "mcp:step_b")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 2L),
                "step_a", Map.of("completed", 2L),
                "webhook2", Map.of("completed", 1L),
                "step_b", Map.of("completed", 1L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("step_a").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("webhook2").get("completed")).isEqualTo(1L);
            assertThat(nodes.get("step_b").get("completed")).isEqualTo(1L);
        }

        @Test
        @DisplayName("Multi-DAG with same epochs accumulates all counts")
        void multiDagWithSameEpochsAccumulates() {
            Map<String, Object> dagEpochs = new HashMap<>();
            dagEpochs.put("trigger:webhook1", 1);
            dagEpochs.put("trigger:webhook2", 1);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 1);
            metadata.put("dagEpochs", dagEpochs);

            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook"),
                        triggerMap("wh2", "Webhook2", "webhook")),
                List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 1L),
                "webhook2", Map.of("completed", 1L)
            ));

            controller.getStatusCounts(RUN_ID, TENANT_ID, null);

            verify(workflowEpochService).getAccumulatedCounts(RUN_ID);
        }
    }

    @Nested
    @DisplayName("Edge counts")
    class EdgeCounts {

        @Test
        @DisplayName("Edge counts come from epoch_counts edge entries, not target node counts")
        void edgeCountsComeFromEpochEdgeEntries() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")),
                List.of(mcpMap("step1", "Step1")),
                null, null, null,
                List.of(edgeMap("trigger:webhook1", "mcp:step1")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 2L),
                "step1", Map.of("completed", 2L)
            ));
            stubEpochEdgeCounts(Map.of(
                "trigger:webhook1->mcp:step1", Map.of("completed", 2L)
            ));

            Map<String, Map<String, Long>> edges = getEdges(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));
            assertThat(edges).containsKey("trigger:webhook1->mcp:step1");
            assertThat(edges.get("trigger:webhook1->mcp:step1").get("completed")).isEqualTo(2L);
        }

        @Test
        @DisplayName("Edge counts work for interface node targets")
        void edgeCountsWorkForInterfaceNodes() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")),
                List.of(mcpMap("step1", "Step1")),
                null, null,
                List.of(interfaceMap("form1", "User Form")),
                List.of(edgeMap("trigger:webhook1", "mcp:step1"),
                        edgeMap("mcp:step1", "interface:user_form")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 2L),
                "step1", Map.of("completed", 2L),
                "user_form", Map.of("completed", 2L)
            ));
            stubEpochEdgeCounts(Map.of(
                "trigger:webhook1->mcp:step1", Map.of("completed", 2L),
                "mcp:step1->interface:user_form", Map.of("completed", 2L)
            ));

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            Map<String, Map<String, Long>> nodes = getNodes(body);
            Map<String, Map<String, Long>> edges = getEdges(body);

            assertThat(nodes.get("user_form").get("completed")).isEqualTo(2L);

            assertThat(edges).containsKey("mcp:step1->interface:user_form");
            assertThat(edges.get("mcp:step1->interface:user_form").get("completed")).isEqualTo(2L);
        }

        @Test
        @DisplayName("Multi-trigger fan-in: unfired trigger edge stays at zero when sibling fires through shared merge")
        void multiTriggerFanInUnfiredEdgeStaysZero() {
            // Plan mirrors the user-reported bug: ManualA + Scheduler both fan into core:sharedmerge.
            // Only Scheduler has fired (5 times). The manuala edge MUST NOT inherit the merge's count.
            Map<String, Object> metadata = Map.of("currentEpoch", 5);
            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("manuala", "ManualA", "manual"),
                        triggerMap("scheduler", "Scheduler", "schedule")),
                null,
                null,
                List.of(coreMap("merge1", "merge", "SharedMerge"),
                        coreMap("echo1", "set", "Echo")),
                null,
                List.of(edgeMap("trigger:manuala", "core:sharedmerge"),
                        edgeMap("trigger:scheduler", "core:sharedmerge"),
                        edgeMap("core:sharedmerge", "core:echo")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            // Node counts: scheduler fired 5 times, merge ran 5 times, manuala never fired.
            stubEpochCounts(Map.of(
                "scheduler", Map.of("completed", 5L),
                "sharedmerge", Map.of("completed", 5L),
                "echo", Map.of("completed", 5L)
            ));

            // Edge counts: only the scheduler's edge has actual traversal records.
            // The manuala->merge edge has NO entry - it was never traversed.
            stubEpochEdgeCounts(Map.of(
                "trigger:scheduler->core:sharedmerge", Map.of("completed", 5L),
                "core:sharedmerge->core:echo", Map.of("completed", 5L)
            ));

            Map<String, Map<String, Long>> edges = getEdges(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            // Regression: manuala edge MUST NOT appear with target merge node's count.
            assertThat(edges)
                .as("Unfired trigger's edge must not be attributed any count from sibling trigger fires")
                .doesNotContainKey("trigger:manuala->core:sharedmerge");

            assertThat(edges.get("trigger:scheduler->core:sharedmerge").get("completed")).isEqualTo(5L);
            assertThat(edges.get("core:sharedmerge->core:echo").get("completed")).isEqualTo(5L);
        }

        @Test
        @DisplayName("Plan edge with no epoch_counts entry is omitted from response")
        void planEdgeWithoutTraversalIsOmitted() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")),
                List.of(mcpMap("step1", "Step1"), mcpMap("step2", "Step2")),
                null, null, null,
                List.of(edgeMap("trigger:webhook1", "mcp:step1"),
                        edgeMap("trigger:webhook1", "mcp:step2")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 1L),
                "step1", Map.of("completed", 1L)
            ));
            // Only one edge traversed.
            stubEpochEdgeCounts(Map.of(
                "trigger:webhook1->mcp:step1", Map.of("completed", 1L)
            ));

            Map<String, Map<String, Long>> edges = getEdges(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(edges).containsKey("trigger:webhook1->mcp:step1");
            assertThat(edges).doesNotContainKey("trigger:webhook1->mcp:step2");
        }

        @Test
        @DisplayName("Plan-edge labels are normalized before lookup (raw 'My Webhook' matches normalized 'my_webhook')")
        void rawPlanLabelsAreNormalizedForLookup() {
            // Plan ships with raw, non-normalized labels (mixed case, spaces).
            // Write side (EdgeStatusService.normalizePreservingPort) stores normalized keys
            // in epoch_counts. The read side MUST normalize symmetrically - otherwise the
            // lookup misses and the edge appears at zero in the response.
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "My Webhook", "webhook")),
                List.of(mcpMap("step1", "Step One")),
                null, null, null,
                List.of(edgeMap("trigger:My Webhook", "mcp:Step One")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of("my_webhook", Map.of("completed", 2L)));
            // epoch_counts holds the normalized form, exactly as written.
            stubEpochEdgeCounts(Map.of(
                "trigger:my_webhook->mcp:step_one", Map.of("completed", 2L)
            ));

            Map<String, Map<String, Long>> edges = getEdges(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));
            assertThat(edges).containsKey("trigger:my_webhook->mcp:step_one");
            assertThat(edges.get("trigger:my_webhook->mcp:step_one").get("completed")).isEqualTo(2L);
        }

        @Test
        @DisplayName("Ported edge keys (e.g. classify category_0) collapse to stripped key in response")
        void portedEdgeKeysCollapseToStrippedKey() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")),
                List.of(mcpMap("dispatch", "Dispatch")),
                List.of(agentMap("Classifier", "classify")),
                null, null,
                List.of(edgeMap("agent:classifier:category_0", "mcp:dispatch"),
                        edgeMap("agent:classifier:category_1", "mcp:dispatch")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of("dispatch", Map.of("completed", 3L)));
            // Two ports each fired with their own counts; aggregated under the stripped key.
            stubEpochEdgeCounts(Map.of(
                "agent:classifier:category_0->mcp:dispatch", Map.of("completed", 2L),
                "agent:classifier:category_1->mcp:dispatch", Map.of("completed", 1L)
            ));

            Map<String, Map<String, Long>> edges = getEdges(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(edges.get("agent:classifier->mcp:dispatch").get("completed")).isEqualTo(3L);
        }

        @Test
        @DisplayName("No plan returns empty edge counts")
        void noPlanReturnsEmptyEdgeCounts() {
            Map<String, Object> metadata = Map.of("currentEpoch", 0);
            WorkflowRunEntity run = buildRunEntity(metadata, null);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            Map<String, Map<String, Long>> edges = getEdges(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));
            assertThat(edges).isEmpty();
        }
    }

    @Nested
    @DisplayName("All node types accumulation")
    class AllNodeTypesAccumulation {

        @Test
        @DisplayName("Interface nodes accumulate counts across epochs like other nodes")
        void interfaceNodesAccumulateAcrossEpochs() {
            Map<String, Object> metadata = Map.of("currentEpoch", 2);
            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")),
                List.of(mcpMap("step1", "Step1")),
                null, null,
                List.of(interfaceMap("form1", "User Form")),
                List.of(edgeMap("trigger:webhook1", "mcp:step1"),
                        edgeMap("mcp:step1", "interface:user_form")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 3L),
                "step1", Map.of("completed", 3L),
                "user_form", Map.of("completed", 3L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes.get("user_form").get("completed")).isEqualTo(3L);
            assertThat(nodes.get("step1").get("completed")).isEqualTo(3L);
            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(3L);
        }

        @Test
        @DisplayName("All 7 prefix types accumulate")
        void allPrefixTypesAccumulate() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 2L),
                "api_call", Map.of("completed", 2L),
                "classifier", Map.of("completed", 2L),
                "check", Map.of("completed", 2L),
                "users", Map.of("completed", 2L),
                "user_form", Map.of("completed", 2L),
                "my_note", Map.of("completed", 2L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("api_call").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("classifier").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("check").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("users").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("user_form").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("my_note").get("completed")).isEqualTo(2L);
        }

        @Test
        @DisplayName("Interface nodes in multi-DAG also accumulate across all epochs")
        void interfaceNodesInMultiDagAccumulate() {
            Map<String, Object> dagEpochs = new HashMap<>();
            dagEpochs.put("trigger:webhook1", 2);
            dagEpochs.put("trigger:webhook2", 1);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 2);
            metadata.put("dagEpochs", dagEpochs);

            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook"),
                        triggerMap("wh2", "Webhook2", "webhook")),
                null, null, null,
                List.of(interfaceMap("form_a", "Form A"), interfaceMap("form_b", "Form B")),
                List.of(edgeMap("trigger:webhook1", "interface:form_a"),
                        edgeMap("trigger:webhook2", "interface:form_b")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 2L),
                "form_a", Map.of("completed", 2L),
                "webhook2", Map.of("completed", 1L),
                "form_b", Map.of("completed", 1L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes.get("form_a").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("form_b").get("completed")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("Backward compatibility")
    class BackwardCompat {

        @Test
        @DisplayName("No dagEpochs - uses accumulation query")
        void noDagEpochsUsesAccumulationQuery() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of("webhook1", Map.of("completed", 1L)));

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            assertThat(body).doesNotContainKey("dagEpochs");
            verify(workflowEpochService).getAccumulatedCounts(RUN_ID);
        }

        @Test
        @DisplayName("Null metadata - defaults to epoch 0")
        void nullMetadataDefaultsToEpochZero() {
            WorkflowRunEntity run = buildRunEntity(null, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            assertThat(body.get("epoch")).isEqualTo(0);
        }

        @Test
        @DisplayName("Single dagEpochs entry - still uses accumulation")
        void singleDagEpochsEntryUsesAccumulation() {
            Map<String, Object> dagEpochs = new HashMap<>();
            dagEpochs.put("trigger:webhook1", 3);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 3);
            metadata.put("dagEpochs", dagEpochs);

            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCounts(Map.of("webhook1", Map.of("completed", 3L)));

            controller.getStatusCounts(RUN_ID, TENANT_ID, null);

            verify(workflowEpochService).getAccumulatedCounts(RUN_ID);
        }

        @Test
        @DisplayName("Empty metadata map - defaults to epoch 0")
        void emptyMetadataDefaultsToEpochZero() {
            WorkflowRunEntity run = buildRunEntity(Map.of(), buildPlanMap(
                List.of(triggerMap("wh1", "Wh1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            assertThat(body.get("epoch")).isEqualTo(0);
        }

        @Test
        @DisplayName("currentEpoch as String is ignored - defaults to 0")
        void currentEpochAsStringIsIgnored() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", "not-a-number");
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Wh1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            Map<String, Object> body = getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null));
            assertThat(body.get("epoch")).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Resilience")
    class Resilience {

        @Test
        @DisplayName("Null plan - still returns node counts via accumulation")
        void nullPlanStillReturnsNodeCounts() {
            Map<String, Object> dagEpochs = new HashMap<>();
            dagEpochs.put("trigger:webhook1", 2);
            dagEpochs.put("trigger:webhook2", 1);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 2);
            metadata.put("dagEpochs", dagEpochs);

            WorkflowRunEntity run = buildRunEntity(metadata, null);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 2L),
                "step_a", Map.of("completed", 1L)
            ));

            ResponseEntity<?> response = controller.getStatusCounts(RUN_ID, TENANT_ID, null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

            Map<String, Map<String, Long>> nodes = getNodes(getBody(response));
            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("step_a").get("completed")).isEqualTo(1L);
        }

        @Test
        @DisplayName("Empty dagEpochs map - uses accumulation")
        void emptyDagEpochsUsesAccumulation() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 1);
            metadata.put("dagEpochs", new HashMap<>());

            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Wh1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
            stubEpochCountsEmpty();

            controller.getStatusCounts(RUN_ID, TENANT_ID, null);

            verify(workflowEpochService).getAccumulatedCounts(RUN_ID);
        }

        @Test
        @DisplayName("Multi-DAG with agent and core nodes - all accumulate")
        void multiDagWithAgentAndCoreNodesAccumulate() {
            Map<String, Object> dagEpochs = new HashMap<>();
            dagEpochs.put("trigger:webhook1", 3);
            dagEpochs.put("trigger:webhook2", 1);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("currentEpoch", 3);
            metadata.put("dagEpochs", dagEpochs);

            Map<String, Object> plan = buildFullPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook"),
                        triggerMap("wh2", "Webhook2", "webhook")),
                List.of(mcpMap("api1", "Api1")),
                List.of(agentMap("Classifier", "classify")),
                List.of(coreMap("dec1", "decision", "Check")),
                null,
                List.of(edgeMap("trigger:webhook1", "mcp:api1"),
                        edgeMap("mcp:api1", "core:check:if"),
                        edgeMap("trigger:webhook2", "agent:classifier")));

            WorkflowRunEntity run = buildRunEntity(metadata, plan);
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 3L),
                "api1", Map.of("completed", 2L, "failed", 1L),
                "check", Map.of("completed", 2L),
                "webhook2", Map.of("completed", 1L),
                "classifier", Map.of("completed", 1L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(3L);
            assertThat(nodes.get("api1").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("api1").get("failed")).isEqualTo(1L);
            assertThat(nodes.get("check").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("classifier").get("completed")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("WorkflowEpochService integration")
    class WorkflowEpochServiceIntegration {

        @Test
        @DisplayName("Uses epoch counts as single source of truth")
        void usesEpochCountsAsSingleSource() {
            Map<String, Object> metadata = Map.of("currentEpoch", 2);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")),
                List.of(edgeMap("trigger:webhook1", "mcp:step1"))));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 3L),
                "step1", Map.of("completed", 3L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(3L);
            assertThat(nodes.get("step1").get("completed")).isEqualTo(3L);

            verify(workflowEpochService).getAccumulatedCounts(RUN_ID);
        }

        @Test
        @DisplayName("Empty epoch counts returns empty nodes (no fallback)")
        void emptyEpochCountsReturnsEmpty() {
            Map<String, Object> metadata = Map.of("currentEpoch", 1);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")), List.of()));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            stubEpochCountsEmpty();

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes).isEmpty();
            verify(workflowEpochService).getAccumulatedCounts(RUN_ID);
        }

        @Test
        @DisplayName("Multi-epoch accumulation via epoch counts")
        void multiEpochAccumulation() {
            Map<String, Object> metadata = Map.of("currentEpoch", 2);
            WorkflowRunEntity run = buildRunEntity(metadata, buildPlanMap(
                List.of(triggerMap("wh1", "Webhook1", "webhook")),
                List.of(edgeMap("trigger:webhook1", "mcp:step1"))));
            when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));

            // 3 epochs accumulated
            stubEpochCounts(Map.of(
                "webhook1", Map.of("completed", 3L),
                "step1", Map.of("completed", 2L, "failed", 1L)
            ));

            Map<String, Map<String, Long>> nodes = getNodes(getBody(controller.getStatusCounts(RUN_ID, TENANT_ID, null)));

            assertThat(nodes.get("webhook1").get("completed")).isEqualTo(3L);
            assertThat(nodes.get("step1").get("completed")).isEqualTo(2L);
            assertThat(nodes.get("step1").get("failed")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("getApplicationRunVersionBatch - POST /applications/run-version-batch")
    class ApplicationRunVersionBatch {

        @Test
        @DisplayName("empty workflowIds returns {} without touching the service")
        void emptyReturnsEmpty() {
            ResponseEntity<Map<String, com.apimarketplace.orchestrator.controllers.dto.ApplicationRunVersionSummary>> resp =
                controller.getApplicationRunVersionBatch(Map.of("workflowIds", List.of()), "user-1", "org-1");
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody()).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(applicationRunVersionBatchService);
        }

        @Test
        @DisplayName("missing or non-List workflowIds returns {}")
        void nonListReturnsEmpty() {
            assertThat(controller.getApplicationRunVersionBatch(Map.of(), "user-1", "org-1").getBody()).isEmpty();
            assertThat(controller.getApplicationRunVersionBatch(Map.of("workflowIds", "not-a-list"), "user-1", "org-1").getBody()).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(applicationRunVersionBatchService);
        }

        @Test
        @DisplayName("all-malformed UUIDs short-circuit to {} (service not called)")
        void allMalformedReturnsEmpty() {
            ResponseEntity<Map<String, com.apimarketplace.orchestrator.controllers.dto.ApplicationRunVersionSummary>> resp =
                controller.getApplicationRunVersionBatch(Map.of("workflowIds", List.of("not-a-uuid", "also-bad")), "user-1", "org-1");
            assertThat(resp.getBody()).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(applicationRunVersionBatchService);
        }

        @Test
        @DisplayName("parses the valid ids (skipping malformed) and remaps the UUID keys to strings")
        void parsesValidAndRemapsKeys() {
            UUID wf = UUID.randomUUID();
            com.apimarketplace.orchestrator.controllers.dto.ApplicationRunVersionSummary summary =
                new com.apimarketplace.orchestrator.controllers.dto.ApplicationRunVersionSummary(
                    "run-9", Instant.parse("2026-06-01T00:00:00Z"), 4);
            org.mockito.Mockito.when(applicationRunVersionBatchService.resolve(Set.of(wf), "org-1", "user-1"))
                .thenReturn(Map.of(wf, summary));

            ResponseEntity<Map<String, com.apimarketplace.orchestrator.controllers.dto.ApplicationRunVersionSummary>> resp =
                controller.getApplicationRunVersionBatch(Map.of("workflowIds", Arrays.asList(wf.toString(), "garbage")), "user-1", "org-1");

            assertThat(resp.getBody()).containsOnlyKeys(wf.toString());
            assertThat(resp.getBody().get(wf.toString()).applicationRunId()).isEqualTo("run-9");
            assertThat(resp.getBody().get(wf.toString()).pinnedVersion()).isEqualTo(4);
        }
    }
}
