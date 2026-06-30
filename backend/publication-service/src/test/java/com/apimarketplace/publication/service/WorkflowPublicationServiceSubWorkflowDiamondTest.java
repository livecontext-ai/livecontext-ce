package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reproduction for the publication-snapshot audit CRITICAL "C1": a DIAMOND sub-workflow topology
 * (A → B, A → C, B → D, C → D) loses the shared child D on the publish side.
 *
 * <p>{@code enrichPlanResources} shares a SINGLE {@code visitedWorkflowIds} set across the whole
 * recursion (created once in {@code enrichPlanWithSubWorkflowData}). D is added to that set while
 * descending into B, so when the recursion later descends into C, {@code workflowIds.removeAll(
 * visitedWorkflowIds)} strips D - C's plan therefore gets NO {@code _snapshot_subworkflows} entry
 * for D, even though C still has a {@code sub_workflow} core referencing D's publisher-tenant UUID.
 * At acquire time there is no snapshot to clone D from for C, so C's node dangles cross-tenant.</p>
 *
 * <p>The no-loss invariant: every parent that references a sub-workflow must carry that child's
 * snapshot. This test fails on current code (exactly one of B/C carries D) and passes once the
 * shared-visited dedup no longer drops a child shared across sibling parents.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService - diamond sub-workflow snapshot completeness (audit C1)")
class WorkflowPublicationServiceSubWorkflowDiamondTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationSnapshotVersionRepository snapshotVersionRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private PublicationReviewRepository reviewRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private AuthClient authClient;

    private WorkflowPublicationService service;

    private static final String TENANT = "tenant-001";
    private static final String ORG = "org-acme";
    private static final UUID A = UUID.fromString("22222222-2222-2222-2222-222222222222"); // root
    private static final UUID B = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID C = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID D = UUID.fromString("66666666-6666-6666-6666-666666666666"); // shared child

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository, snapshotVersionRepository, receiptRepository, reviewRepository,
                orchestratorClient, agentClient, interfaceClient, dataSourceClient, breakdownService,
                new ObjectMapper(), snapshotCloneService, entitlementGuard, authClient);
    }

    @Test
    @DisplayName("a sub-workflow shared by two parents is snapshotted under BOTH parents, not just one")
    void diamondSharedChildSnapshottedUnderBothParents() {
        // A references B and C; both B and C reference the same child D.
        Map<String, Object> planA = planWithSubWorkflows(B, C);
        Map<String, Object> planB = planWithSubWorkflows(D);
        Map<String, Object> planC = planWithSubWorkflows(D);
        Map<String, Object> planD = new HashMap<>(); // leaf

        when(orchestratorClient.getWorkflowForPublication(B, TENANT, ORG)).thenReturn(workflowData("B", planB));
        when(orchestratorClient.getWorkflowForPublication(C, TENANT, ORG)).thenReturn(workflowData("C", planC));
        when(orchestratorClient.getWorkflowForPublication(D, TENANT, ORG)).thenReturn(workflowData("D", planD));

        service.enrichPlanWithSubWorkflowData(planA, TENANT, ORG, A);

        @SuppressWarnings("unchecked")
        Map<String, Object> aSubs = (Map<String, Object>) planA.get("_snapshot_subworkflows");
        assertThat(aSubs).as("A must snapshot both B and C").containsKeys(B.toString(), C.toString());

        Map<String, Object> bSubs = nestedSubworkflows(aSubs, B);
        Map<String, Object> cSubs = nestedSubworkflows(aSubs, C);

        assertThat(bSubs)
                .as("B's snapshot must carry the shared child D")
                .isNotNull()
                .containsKey(D.toString());
        assertThat(cSubs)
                .as("C's snapshot must ALSO carry the shared child D - a diamond child must not be "
                  + "dropped from the second parent (pre-fix the global visitedWorkflowIds set strips it)")
                .isNotNull()
                .containsKey(D.toString());
    }

    @Test
    @DisplayName("a back-reference to the root (A->B->A) terminates and is left as __self__ (no infinite recursion)")
    void cycleBackToRootTerminates() {
        Map<String, Object> planA = planWithSubWorkflows(B);
        Map<String, Object> planB = planWithSubWorkflows(A); // B references the root A - a cycle

        when(orchestratorClient.getWorkflowForPublication(B, TENANT, ORG)).thenReturn(workflowData("B", planB));

        // Must return (not StackOverflow / hang).
        service.enrichPlanWithSubWorkflowData(planA, TENANT, ORG, A);

        // The root is never fetched as a sub-workflow (the ancestor path cuts the cycle).
        verify(orchestratorClient, never()).getWorkflowForPublication(eq(A), any(), any());

        @SuppressWarnings("unchecked")
        Map<String, Object> aSubs = (Map<String, Object>) planA.get("_snapshot_subworkflows");
        assertThat(aSubs).containsKey(B.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> bSnap = (Map<String, Object>) aSubs.get(B.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> bPlan = (Map<String, Object>) bSnap.get("plan");
        assertThat(subWorkflowIdOf(bPlan))
                .as("the cycle edge B->A must be marked __self__, not left pointing at the root id")
                .isEqualTo("__self__");
        assertThat(bPlan)
                .as("the ancestor root must not be re-snapshotted under B")
                .doesNotContainKey("_snapshot_subworkflows");
    }

    @Test
    @DisplayName("total-snapshot budget bounds a pathological graph (stops after the cap, no blow-up)")
    void snapshotBudgetCapsTotalSnapshots() {
        int original = WorkflowPublicationService.maxSnapshottedSubWorkflows;
        WorkflowPublicationService.maxSnapshottedSubWorkflows = 2;
        try {
            Map<String, Object> planA = planWithSubWorkflows(B, C, D); // 3 leaf children, cap is 2
            lenient().when(orchestratorClient.getWorkflowForPublication(B, TENANT, ORG)).thenReturn(workflowData("B", new HashMap<>()));
            lenient().when(orchestratorClient.getWorkflowForPublication(C, TENANT, ORG)).thenReturn(workflowData("C", new HashMap<>()));
            lenient().when(orchestratorClient.getWorkflowForPublication(D, TENANT, ORG)).thenReturn(workflowData("D", new HashMap<>()));

            service.enrichPlanWithSubWorkflowData(planA, TENANT, ORG, A);

            @SuppressWarnings("unchecked")
            Map<String, Object> aSubs = (Map<String, Object>) planA.get("_snapshot_subworkflows");
            assertThat(aSubs)
                    .as("the budget of 2 must cap the snapshot count, never the full 3, on a pathological graph")
                    .hasSize(2);
        } finally {
            WorkflowPublicationService.maxSnapshottedSubWorkflows = original;
        }
    }

    @SuppressWarnings("unchecked")
    private static String subWorkflowIdOf(Map<String, Object> plan) {
        List<Object> cores = (List<Object>) plan.get("cores");
        for (Object c : cores) {
            Map<String, Object> core = (Map<String, Object>) c;
            if ("sub_workflow".equals(core.get("type"))) {
                return String.valueOf(((Map<String, Object>) core.get("subWorkflow")).get("workflowId"));
            }
        }
        return null;
    }

    @Test
    @DisplayName("recursion re-namespaces a FileRef embedded in a sub-workflow's interface (_snapshot_data) - H3")
    void recursionCopiesSubWorkflowEmbeddedInterfaceFileRef() {
        UUID pubId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        String publisherPath = "tenant-001/general/sub-iface-image.png";

        // B's plan carries an interface node whose _snapshot_data embeds a FileRef at the publisher
        // path (no id, so enrichPlanWithInterfaceData leaves the pre-set snapshot data untouched).
        Map<String, Object> fileRef = new HashMap<>();
        fileRef.put("_type", "file");
        fileRef.put("path", publisherPath);
        fileRef.put("name", "sub-iface-image.png");
        fileRef.put("mimeType", "image/png");
        Map<String, Object> ifaceData = new HashMap<>();
        ifaceData.put("img", fileRef);
        Map<String, Object> ifaceNode = new HashMap<>();
        ifaceNode.put("_snapshot_data", ifaceData);
        Map<String, Object> planB = new HashMap<>();
        planB.put("interfaces", new ArrayList<>(List.of(ifaceNode)));

        Map<String, Object> planA = planWithSubWorkflows(B);
        when(orchestratorClient.getWorkflowForPublication(B, TENANT, ORG)).thenReturn(workflowData("B", planB));
        when(orchestratorClient.copyFile(any(), any()))
                .thenReturn(Map.of("newPath", "_publications/" + pubId + "/sub/sub-iface-image.png"));

        // 5-arg overload with a non-null publicationId so the embedded-FileRef snapshotting runs.
        service.enrichPlanWithSubWorkflowData(planA, TENANT, ORG, A, pubId);

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
        verify(orchestratorClient, atLeastOnce()).copyFile(captor.capture(), any());
        assertThat(captor.getAllValues())
                .as("the sub-workflow's embedded interface FileRef must be copied into the publication "
                  + "namespace by the recursion (pre-fix only the top-level plan's embedded refs were)")
                .anySatisfy((req) -> assertThat(req.get("sourcePath")).isEqualTo(publisherPath));
    }

    /** Build a plan whose cores are sub_workflow nodes referencing the given child workflow ids. */
    private static Map<String, Object> planWithSubWorkflows(UUID... childIds) {
        List<Object> cores = new ArrayList<>();
        for (UUID childId : childIds) {
            Map<String, Object> sub = new HashMap<>();
            sub.put("workflowId", childId.toString());
            Map<String, Object> core = new HashMap<>();
            core.put("type", "sub_workflow");
            core.put("subWorkflow", sub);
            cores.add(core);
        }
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", cores);
        return plan;
    }

    /** Orchestrator getWorkflowForPublication response: in-scope (same org), with a plan. */
    private static Map<String, Object> workflowData(String name, Map<String, Object> plan) {
        Map<String, Object> wf = new HashMap<>();
        wf.put("tenantId", TENANT);
        wf.put("organizationId", ORG);
        wf.put("name", name);
        wf.put("plan", plan);
        return wf;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> nestedSubworkflows(Map<String, Object> parentSubs, UUID parentId) {
        Map<String, Object> snapshot = (Map<String, Object>) parentSubs.get(parentId.toString());
        if (snapshot == null) return null;
        Map<String, Object> plan = (Map<String, Object>) snapshot.get("plan");
        if (plan == null) return null;
        return (Map<String, Object>) plan.get("_snapshot_subworkflows");
    }
}
