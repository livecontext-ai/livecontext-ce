package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Phase-2 guard: every GraphSurgery operation rebuilds the {@link WorkflowPlan}
 * through the 12-arg constructor - each rebuild MUST carry
 * {@code plan.getNodePolicies()} forward. A rebuild that dropped the map (e.g. a
 * new call site passing {@code Map.of()}) would silently strip retry /
 * continue-on-failure policies from every node of the workflow on the next
 * builder edit, and the engine would fall back to DEFAULT (no retries) without
 * any error.
 *
 * <p>Covers the pure helper ({@code removeNodeFromPlan}) plus all four
 * service-backed operations (insert_after / remove_node / add_edge /
 * remove_edge), asserting on the plan instance handed to
 * {@code workflowService.saveWorkflow} - the object future executions load.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GraphSurgery - nodePolicies survive every plan rebuild")
class GraphSurgeryNodePolicyPreservationTest {

    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TENANT = "tenant-1";
    private static final NodePolicy FETCH_POLICY = new NodePolicy(2, 100L, true);

    @Mock private WorkflowManagementService workflowService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private SmartDefaultsEngine smartDefaultsEngine;
    @Mock private WorkflowBuilderValidator validator;
    @Mock private WorkflowEntity workflowEntity;

    private GraphSurgery surgery;

    @BeforeEach
    void setUp() {
        surgery = new GraphSurgery(workflowService, workflowRepository, smartDefaultsEngine, validator);
        lenient().when(workflowService.getWorkflow(WORKFLOW_ID)).thenReturn(Optional.of(workflowEntity));
        lenient().when(workflowEntity.getTenantId()).thenReturn(TENANT);
        lenient().when(workflowEntity.getOrganizationId()).thenReturn(null);
        lenient().when(workflowEntity.getPlan()).thenReturn(planMapWithPolicy());
    }

    /**
     * trigger:start → mcp:fetch (policy retry=2/backoff=100/continueOnFailure) →
     * mcp:notify → mcp:cleanup.
     */
    private Map<String, Object> planMapWithPolicy() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(
            new HashMap<>(Map.of("id", "t1", "type", "webhook", "label", "Start"))));
        plan.put("mcps", List.of(
            new HashMap<>(Map.of(
                "id", "github/get-user", "label", "Fetch",
                "nodePolicy", Map.of("retryCount", 2, "retryBackoffMs", 100, "continueOnFailure", true))),
            new HashMap<>(Map.of("id", "slack/send-message", "label", "Notify")),
            new HashMap<>(Map.of("id", "s3/put-object", "label", "Cleanup"))));
        plan.put("edges", List.of(
            new HashMap<>(Map.of("from", "trigger:start", "to", "mcp:fetch")),
            new HashMap<>(Map.of("from", "mcp:fetch", "to", "mcp:notify")),
            new HashMap<>(Map.of("from", "mcp:notify", "to", "mcp:cleanup"))));
        return plan;
    }

    private WorkflowPlan captureSavedPlan() {
        ArgumentCaptor<WorkflowPlan> captor = ArgumentCaptor.forClass(WorkflowPlan.class);
        verify(workflowService).saveWorkflow(captor.capture(), any(), eq(WORKFLOW_ID), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("insert_after: the saved plan still resolves the untouched node's policy")
    void insertAfterPreservesPolicies() {
        surgery.insertAfter(WORKFLOW_ID, "mcp:notify",
            new HashMap<>(Map.of("type", "mcp", "label", "New Step")), TENANT);

        WorkflowPlan saved = captureSavedPlan();
        assertThat(saved.getNodePolicy("mcp:fetch")).isEqualTo(FETCH_POLICY);
    }

    @Test
    @DisplayName("remove_node (of an UNRELATED node): the saved plan still resolves the surviving node's policy")
    void removeNodePreservesPolicies() {
        surgery.removeNode(WORKFLOW_ID, "mcp:notify", TENANT);

        WorkflowPlan saved = captureSavedPlan();
        assertThat(saved.getNodePolicy("mcp:fetch")).isEqualTo(FETCH_POLICY);
        // The removal itself worked (not a no-op save of the original plan)
        assertThat(saved.getMcps()).extracting(s -> s.getNormalizedKey())
            .doesNotContain("mcp:notify");
    }

    @Test
    @DisplayName("add_edge: the saved plan still resolves the node's policy")
    void addEdgePreservesPolicies() {
        surgery.addEdge(WORKFLOW_ID, "mcp:fetch", "mcp:cleanup", null, TENANT);

        WorkflowPlan saved = capturedPlanWithEdgeCount(4);
        assertThat(saved.getNodePolicy("mcp:fetch")).isEqualTo(FETCH_POLICY);
    }

    @Test
    @DisplayName("remove_edge: the saved plan still resolves the node's policy")
    void removeEdgePreservesPolicies() {
        surgery.removeEdge(WORKFLOW_ID, "mcp:notify", "mcp:cleanup", TENANT);

        WorkflowPlan saved = capturedPlanWithEdgeCount(2);
        assertThat(saved.getNodePolicy("mcp:fetch")).isEqualTo(FETCH_POLICY);
    }

    private WorkflowPlan capturedPlanWithEdgeCount(int expectedEdges) {
        WorkflowPlan saved = captureSavedPlan();
        assertThat(saved.getEdges()).hasSize(expectedEdges);
        return saved;
    }

    @Test
    @DisplayName("removeNodeFromPlan (pure helper): policies map is carried verbatim into the rebuilt plan")
    void removeNodeFromPlanHelperPreservesPolicies() {
        WorkflowPlan plan = WorkflowPlan.fromMap(planMapWithPolicy(), TENANT);
        assertThat(plan.getNodePolicy("mcp:fetch")).isEqualTo(FETCH_POLICY);

        WorkflowPlan after = surgery.removeNodeFromPlan(plan, "mcp:cleanup");

        assertThat(after.getNodePolicies()).isEqualTo(plan.getNodePolicies());
        assertThat(after.getNodePolicy("mcp:fetch")).isEqualTo(FETCH_POLICY);
    }
}
