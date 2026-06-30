package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Workflow-type stamping on acquire clones - regression for the V268
 * duplicate-key bug.
 *
 * <p>Pre-fix, {@code cloneSubWorkflowsForTenant} created every sub-workflow
 * clone as {@code workflow_type=APPLICATION} stamped with the SAME
 * {@code source_publication_id} as the root clone, violating the V268 partial
 * unique index {@code uq_workflow_org_source_pub_application}
 * ((organization_id, source_publication_id) WHERE workflow_type='APPLICATION')
 * - acquiring ANY publication carrying a distinct sub-workflow 500'd. The same
 * shape broke AGENT publications whose snapshot carries 2+ workflows.
 *
 * <p>Post-fix invariant: only the ROOT clone of a workflow acquisition is
 * APPLICATION; sub-workflow children and agent-publication workflows are
 * standard WORKFLOW rows.
 */
@DisplayName("SnapshotCloneService - workflow-type stamps on acquire clones (V268 invariant)")
class SnapshotCloneServiceWorkflowTypeStampTest {

    private static final String TENANT = "acquirer";
    private static final String ORG = "org-1";
    private static final UUID PUBLICATION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final String CHILD_ID = "77777777-7777-7777-7777-777777777777";
    private static final String GRANDCHILD_ID = "88888888-8888-8888-8888-888888888888";

    private OrchestratorInternalClient orchestratorClient;
    private SnapshotCloneService service;

    @BeforeEach
    void setUp() {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        service = new SnapshotCloneService(
                orchestratorClient,
                mock(AgentClient.class),
                mock(InterfaceClient.class),
                mock(DataSourceClient.class),
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class));
        when(orchestratorClient.createApplicationWorkflow(any(), anyString()))
                .thenReturn(Map.of("id", UUID.randomUUID().toString()));
    }

    private static Map<String, Object> subWorkflowSnapshot(String name, Map<String, Object> plan) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("plan", plan);
        snapshot.put("name", name);
        snapshot.put("description", "");
        return snapshot;
    }

    private static Map<String, Object> trivialPlan() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of());
        plan.put("cores", List.of());
        return plan;
    }

    private List<Map<String, Object>> capturedCreateRequests() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor =
                ArgumentCaptor.forClass((Class<Map<String, Object>>) (Class<?>) Map.class);
        verify(orchestratorClient, atLeastOnce()).createApplicationWorkflow(captor.capture(), anyString());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("Root clone is stamped APPLICATION while the sub-workflow child is stamped WORKFLOW (pre-fix both were APPLICATION → V268 duplicate key)")
    void rootIsApplicationChildIsWorkflow() {
        Map<String, Object> parentPlan = trivialPlan();
        Map<String, Object> subWorkflows = new HashMap<>();
        subWorkflows.put(CHILD_ID, subWorkflowSnapshot("Child", trivialPlan()));
        parentPlan.put("_snapshot_subworkflows", subWorkflows);

        service.cloneFromSnapshot(parentPlan, TENANT, PUBLICATION_ID, "Parent", "desc", null, ORG);

        List<Map<String, Object>> requests = capturedCreateRequests();
        assertThat(requests).hasSize(2);
        Map<String, Object> childRequest = requests.get(0);
        Map<String, Object> rootRequest = requests.get(1);
        assertThat(childRequest.get("workflowType")).isEqualTo("WORKFLOW");
        assertThat(rootRequest.get("workflowType")).isEqualTo("APPLICATION");
        // Both keep the publication stamp for traceability/cleanup - the type,
        // not the stamp, is what keeps the V268 unique index satisfied.
        assertThat(childRequest.get("sourcePublicationId")).isEqualTo(PUBLICATION_ID.toString());
        assertThat(rootRequest.get("sourcePublicationId")).isEqualTo(PUBLICATION_ID.toString());
    }

    @Test
    @DisplayName("Nested sub-workflows (A→B→C) are all stamped WORKFLOW - only the root acquisition is APPLICATION")
    void nestedSubWorkflowsAreAllStampedWorkflow() {
        Map<String, Object> grandchildPlan = trivialPlan();
        Map<String, Object> childPlan = trivialPlan();
        Map<String, Object> nested = new HashMap<>();
        nested.put(GRANDCHILD_ID, subWorkflowSnapshot("Grandchild", grandchildPlan));
        childPlan.put("_snapshot_subworkflows", nested);

        Map<String, Object> parentPlan = trivialPlan();
        Map<String, Object> subWorkflows = new HashMap<>();
        subWorkflows.put(CHILD_ID, subWorkflowSnapshot("Child", childPlan));
        parentPlan.put("_snapshot_subworkflows", subWorkflows);

        service.cloneFromSnapshot(parentPlan, TENANT, PUBLICATION_ID, "Parent", "desc", null, ORG);

        List<Map<String, Object>> requests = capturedCreateRequests();
        assertThat(requests).hasSize(3);
        assertThat(requests.subList(0, 2))
                .allSatisfy(request -> assertThat(request.get("workflowType")).isEqualTo("WORKFLOW"));
        assertThat(requests.get(2).get("workflowType")).isEqualTo("APPLICATION");
    }

    @Test
    @DisplayName("Sub-workflow create returning null undoes its reservation, leaves the mapping incomplete, and the parent root is still created")
    @SuppressWarnings("unchecked")
    void subWorkflowCreateReturningNull_leavesMappingIncompleteAndParentContinues() {
        // The sub-workflow snapshot carries a core:sub_workflow node that back-references the child
        // by its source id. If the child create fails, the reservation is undone so the reference
        // dangles at the SOURCE id (not a phantom clone id) and the parent root is still created.
        String childSourceId = CHILD_ID;

        Map<String, Object> parentPlan = trivialPlan();
        Map<String, Object> subRefCore = new LinkedHashMap<>();
        subRefCore.put("type", "sub_workflow");
        subRefCore.put("subWorkflow", new LinkedHashMap<>(Map.of("workflowId", childSourceId)));
        parentPlan.put("cores", new java.util.ArrayList<>(List.of(subRefCore)));

        Map<String, Object> subWorkflows = new HashMap<>();
        subWorkflows.put(childSourceId, subWorkflowSnapshot("Child", trivialPlan()));
        parentPlan.put("_snapshot_subworkflows", subWorkflows);

        // The CHILD create (workflowType=WORKFLOW) returns null; the ROOT (APPLICATION) succeeds.
        when(orchestratorClient.createApplicationWorkflow(any(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> request = invocation.getArgument(0);
            if ("WORKFLOW".equals(request.get("workflowType"))) {
                return null; // sub-workflow creation fails
            }
            return Map.of("id", "root-wf-id");
        });

        Map<String, Object> result =
                service.cloneFromSnapshot(parentPlan, TENANT, PUBLICATION_ID, "Parent", "desc", null, ORG);

        // The parent acquire still completes with the root id.
        assertThat(result.get("workflowId")).isEqualTo("root-wf-id");

        // The sub_workflow back-reference is NOT remapped (the failed child never entered the mapping):
        // it stays at the source id - fail-soft, no phantom clone id.
        List<Map<String, Object>> requests = capturedCreateRequests();
        Map<String, Object> rootRequest = requests.stream()
                .filter(r -> "APPLICATION".equals(r.get("workflowType")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("root APPLICATION create not captured"));
        Map<String, Object> sentPlan = (Map<String, Object>) rootRequest.get("plan");
        Map<String, Object> sentSubCore = ((List<Map<String, Object>>) sentPlan.get("cores")).get(0);
        Map<String, Object> sentSubWf = (Map<String, Object>) sentSubCore.get("subWorkflow");
        assertThat(sentSubWf.get("workflowId")).isEqualTo(childSourceId);
    }

    @Test
    @DisplayName("Agent-publication workflow clones are stamped WORKFLOW at the root too (an AGENT publication has no application root)")
    void agentPublicationRootIsStampedWorkflow() {
        service.cloneFromSnapshot(trivialPlan(), TENANT, PUBLICATION_ID, "Agent workflow", "desc", null,
                ORG, SnapshotCloneService.CLONE_TYPE_WORKFLOW);

        List<Map<String, Object>> requests = capturedCreateRequests();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).get("workflowType")).isEqualTo("WORKFLOW");
    }
}
