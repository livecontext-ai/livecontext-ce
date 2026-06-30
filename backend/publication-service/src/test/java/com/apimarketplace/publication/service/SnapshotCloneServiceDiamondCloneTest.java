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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reproduction for the publication-snapshot audit CRITICAL "C2": the acquire-side clone drops the
 * second parent's reference to a sub-workflow that is SHARED by two parents (diamond A → B, A → C,
 * B → D, C → D).
 *
 * <p>{@code cloneSubWorkflowsForTenant} threads ONE {@code clonedIds} set through the whole
 * recursion. D is cloned (and added to {@code clonedIds}) while cloning B's subtree. When C's
 * subtree is cloned, D is already in {@code clonedIds}, so the loop logs "Circular sub-workflow
 * reference detected ... skipping" and {@code continue}s WITHOUT recording {@code D → newD} in C's
 * local {@code workflowMapping}. {@code remapSubWorkflowReferences(planC, {})} is then a no-op, so
 * C's {@code sub_workflow} node is left pointing at the SOURCE-tenant D UUID - a dangling
 * cross-tenant reference the acquirer cannot resolve.</p>
 *
 * <p>This test builds a snapshot where both B and C carry D (i.e. assuming a correct publish) and
 * fails on current code (one parent keeps the source D id); it passes once a shared child's clone
 * id is reused across sibling frames so every referencing parent remaps to the clone.</p>
 */
@DisplayName("SnapshotCloneService - diamond shared sub-workflow clone remap (audit C2)")
class SnapshotCloneServiceDiamondCloneTest {

    private static final String TENANT = "acquirer";
    private static final String ORG = "org-acme";
    private static final UUID PUBLICATION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String B_ID = "44444444-4444-4444-4444-444444444444";
    private static final String C_ID = "55555555-5555-5555-5555-555555555555";
    private static final String D_ID = "66666666-6666-6666-6666-666666666666"; // shared child (publisher tenant)

    private OrchestratorInternalClient orchestratorClient;
    private SnapshotCloneService service;
    private Method cloneSubWorkflowsForTenant;

    @BeforeEach
    void setUp() throws Exception {
        orchestratorClient = mock(OrchestratorInternalClient.class);
        service = new SnapshotCloneService(
                orchestratorClient,
                mock(AgentClient.class),
                mock(InterfaceClient.class),
                mock(DataSourceClient.class),
                mock(StorageBreakdownService.class),
                new ObjectMapper(),
                mock(DataSourceFileCloneService.class));
        // sourcePublicationId + fileNamespaceId are threaded separately; the real acquire
        // path passes the publication id for both (the decoupled-duplicate path nulls the source).
        cloneSubWorkflowsForTenant = SnapshotCloneService.class.getDeclaredMethod(
                "cloneSubWorkflowsForTenant", Map.class, String.class, UUID.class, UUID.class, String.class);
        cloneSubWorkflowsForTenant.setAccessible(true);
    }

    @Test
    @DisplayName("both parents of a shared sub-workflow remap their node to the child's CLONE, not the source id")
    void sharedChildRemappedForEveryParent() throws Exception {
        // Each created workflow gets a deterministic clone id "<title>::clone".
        when(orchestratorClient.createApplicationWorkflow(any(), anyString())).thenAnswer(inv -> {
            Map<String, Object> req = inv.getArgument(0);
            return Map.of("id", req.get("title") + "::clone");
        });

        Map<String, Object> planA = new HashMap<>();
        planA.put("cores", subWorkflowCores(B_ID, C_ID));
        Map<String, Object> aSubs = new LinkedHashMap<>();
        aSubs.put(B_ID, snapshot("B", childPlanReferencing(D_ID)));
        aSubs.put(C_ID, snapshot("C", childPlanReferencing(D_ID)));
        planA.put("_snapshot_subworkflows", aSubs);

        cloneSubWorkflowsForTenant.invoke(service, planA, TENANT, PUBLICATION_ID, PUBLICATION_ID, ORG);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> reqCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(orchestratorClient, atLeast(1)).createApplicationWorkflow(reqCaptor.capture(), anyString());
        List<Map<String, Object>> reqs = reqCaptor.getAllValues();

        String bRef = subWorkflowIdInCreatedPlan(reqs, "B");
        String cRef = subWorkflowIdInCreatedPlan(reqs, "C");

        assertThat(bRef)
                .as("B's cloned sub_workflow node must point at D's CLONE, not the publisher's D id")
                .isEqualTo("D::clone");
        assertThat(cRef)
                .as("C's cloned sub_workflow node must ALSO point at D's CLONE - pre-fix the shared "
                  + "clonedIds set skips D's second occurrence so C dangles at the source-tenant id " + D_ID)
                .isEqualTo("D::clone");
    }

    private static List<Object> subWorkflowCores(String... workflowIds) {
        List<Object> cores = new ArrayList<>();
        for (String id : workflowIds) {
            Map<String, Object> sub = new HashMap<>();
            sub.put("workflowId", id);
            Map<String, Object> core = new HashMap<>();
            core.put("type", "sub_workflow");
            core.put("subWorkflow", sub);
            cores.add(core);
        }
        return cores;
    }

    /** A parent plan that references child {@code childId} AND carries that child's snapshot. */
    private static Map<String, Object> childPlanReferencing(String childId) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", subWorkflowCores(childId));
        Map<String, Object> subs = new HashMap<>();
        subs.put(childId, snapshot("D", new HashMap<>())); // D is a leaf
        plan.put("_snapshot_subworkflows", subs);
        return plan;
    }

    private static Map<String, Object> snapshot(String name, Map<String, Object> plan) {
        Map<String, Object> snap = new HashMap<>();
        snap.put("name", name);
        snap.put("description", "");
        snap.put("plan", plan);
        return snap;
    }

    @SuppressWarnings("unchecked")
    private static String subWorkflowIdInCreatedPlan(List<Map<String, Object>> reqs, String title) {
        Map<String, Object> req = reqs.stream()
                .filter(r -> title.equals(r.get("title")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no createApplicationWorkflow call for title " + title));
        Map<String, Object> plan = (Map<String, Object>) req.get("plan");
        List<Object> cores = (List<Object>) plan.get("cores");
        for (Object c : cores) {
            Map<String, Object> core = (Map<String, Object>) c;
            if ("sub_workflow".equals(core.get("type"))) {
                Map<String, Object> sub = (Map<String, Object>) core.get("subWorkflow");
                return String.valueOf(sub.get("workflowId"));
            }
        }
        return null;
    }
}
