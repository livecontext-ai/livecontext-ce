package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the agent-acquire failure compensation contract. When the bottom-up
 * clone blows up AFTER standalone interfaces and datasources were already
 * cloned into the acquirer's tenant, the catch block must roll those partial
 * rows back - otherwise a failed acquisition leaves orphan interfaces and
 * datasources behind (and, for workflows, occupies the (org, publication)
 * bucket that blocks re-acquisition).
 *
 * <p>The previous {@code WorkflowPublicationServiceCompensationTest} only drove
 * the WORKFLOW path; the agent path's interface/datasource/agent rollback was
 * never exercised by a test (flagged as the remaining nit in the 2026-06-12
 * publication audit). Here we make the root-agent clone fail and assert the
 * cloned interface and datasource are deleted with the acquirer's org scope.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentPublicationService - acquire-failure compensation rolls back cloned interfaces & datasources")
class AgentPublicationServiceAcquireCompensationTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private WorkflowPublicationService workflowPublicationService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private DataSourceFileCloneService fileCloneService;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private AuthClient authClient;

    private AgentPublicationService service;

    private static final UUID PUBLICATION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final String PUBLISHER = "publisher-1";
    private static final String BUYER = "buyer-7";
    private static final String BUYER_ORG = "org-buyer";
    private static final String OLD_AGENT_ID = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String OLD_IFACE_ID = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final UUID CLONED_IFACE_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000003");
    private static final long CLONED_DS_ID = 4242L;
    private static final String OLD_SUB_AGENT_ID = "dddddddd-0000-0000-0000-000000000004";
    private static final UUID CLONED_SUB_AGENT_ID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000005");
    private static final UUID CLONE_WORKFLOW_ID = UUID.fromString("ffffffff-0000-0000-0000-000000000006");

    @BeforeEach
    void setUp() {
        service = new AgentPublicationService(
                publicationRepository, receiptRepository, agentClient, interfaceClient,
                dataSourceClient, orchestratorClient, breakdownService, snapshotCloneService,
                new ObjectMapper(), workflowPublicationService, entitlementGuard,
                fileCloneService, landingInterfaceSnapshotter, authClient);
    }

    @Test
    @DisplayName("Root-agent clone failure deletes the already-cloned interface and datasource with org scope")
    void rootAgentCloneFailureRollsBackInterfaceAndDatasource() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setPublisherId(PUBLISHER);
        publication.setPublicationType(PublicationType.AGENT);
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setAgentSnapshot(buildSnapshot());

        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);

        // Interface + datasource clones succeed...
        InterfaceDto clonedIface = new InterfaceDto();
        clonedIface.setId(CLONED_IFACE_ID);
        when(interfaceClient.createInterface(any(), eq(BUYER))).thenReturn(clonedIface);
        when(dataSourceClient.createFromSnapshot(any(), eq(BUYER))).thenReturn(dataSourceDto(CLONED_DS_ID));

        // ...then the root agent clone fails (null result → "Failed to clone root agent").
        when(agentClient.cloneFromSnapshot(any())).thenReturn(null);

        // Compensation's workflow sweep finds no cloned workflows.
        lenient().when(orchestratorClient.findAllBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.acquireAgentPublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to clone root agent");

        // The partial interface + datasource rows are rolled back in the buyer's org scope.
        verify(interfaceClient).deleteInterface(CLONED_IFACE_ID, BUYER, BUYER_ORG);
        verify(dataSourceClient).deleteDataSource(CLONED_DS_ID, BUYER, BUYER_ORG);
    }

    @Test
    @DisplayName("A successfully-cloned sub-agent is deleted when the root-agent clone then fails")
    void subAgentRolledBackWhenRootCloneFails() {
        WorkflowPublicationEntity publication = agentPublication();
        publication.setAgentSnapshot(buildSnapshotWithSubAgent());

        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        // Post-order clone: the sub-agent clones first (success), then the root fails.
        when(agentClient.cloneFromSnapshot(any()))
                .thenReturn(Map.of("agentId", CLONED_SUB_AGENT_ID.toString()))
                .thenReturn(null);
        lenient().when(orchestratorClient.findAllBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.acquireAgentPublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to clone root agent");

        verify(agentClient).deleteAgent(CLONED_SUB_AGENT_ID, BUYER, BUYER_ORG);
    }

    @Test
    @DisplayName("Compensation cleans only the workflow rows THIS acquire created (scoped to workflowMapping, not an org-wide sweep)")
    void workflowClonesSweptOnCompensation() {
        WorkflowPublicationEntity publication = agentPublication();
        publication.setAgentSnapshot(buildSnapshotWithWorkflow());

        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        // The referenced workflow clones successfully (populating workflowMapping)...
        when(snapshotCloneService.cloneFromSnapshot(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("workflowId", CLONE_WORKFLOW_ID.toString()));
        // ...then the root agent clone fails.
        when(agentClient.cloneFromSnapshot(any())).thenReturn(null);

        assertThatThrownBy(() -> service.acquireAgentPublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to clone root agent");

        verify(orchestratorClient).cleanupApplicationRuns(CLONE_WORKFLOW_ID, PUBLICATION_ID.toString(), BUYER);
        verify(orchestratorClient).deleteAcquiredWorkflow(CLONE_WORKFLOW_ID, PUBLICATION_ID, BUYER, BUYER_ORG);
    }

    @Test
    @DisplayName("Concurrent agent acquires: the loser's compensation deletes only its OWN workflow rows, never the winner's")
    void concurrentAgentAcquireCompensationLeavesWinnerUntouched() {
        UUID winnerWorkflowId = UUID.fromString("a9999999-9999-9999-9999-999999999999");
        WorkflowPublicationEntity publication = agentPublication();
        publication.setAgentSnapshot(buildSnapshotWithWorkflow());

        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        when(snapshotCloneService.cloneFromSnapshot(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("workflowId", CLONE_WORKFLOW_ID.toString()));
        when(agentClient.cloneFromSnapshot(any())).thenReturn(null);
        // If the code ever reverts to the org-wide sweep, this is what it would (wrongly) enumerate -
        // INCLUDING the concurrent WINNER's row. The scoped fix must never consult it.
        lenient().when(orchestratorClient.findAllBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(List.of(
                        Map.of("id", CLONE_WORKFLOW_ID.toString()),
                        Map.of("id", winnerWorkflowId.toString())));

        assertThatThrownBy(() -> service.acquireAgentPublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .hasMessageContaining("Failed to clone root agent");

        // Only the loser's OWN cloned workflow is cleaned.
        verify(orchestratorClient).deleteAcquiredWorkflow(CLONE_WORKFLOW_ID, PUBLICATION_ID, BUYER, BUYER_ORG);
        // The concurrent winner's row is NEVER touched (the org-wide-sweep data-loss bug).
        verify(orchestratorClient, never()).deleteAcquiredWorkflow(eq(winnerWorkflowId), any(), any(), any());
    }

    @Test
    @DisplayName("Acquiring an agent publication with a missing snapshot fails fast without cloning")
    void missingAgentSnapshotFailsFast() {
        WorkflowPublicationEntity publication = agentPublication();
        publication.setAgentSnapshot(null);

        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.acquireAgentPublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("snapshot is missing");

        verify(agentClient, never()).cloneFromSnapshot(any());
    }

    private static WorkflowPublicationEntity agentPublication() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setPublisherId(PUBLISHER);
        publication.setPublicationType(PublicationType.AGENT);
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setStatus(PublicationStatus.ACTIVE);
        return publication;
    }

    private static Map<String, Object> buildAgentOnlySnapshot() {
        Map<String, Object> agent = new HashMap<>();
        agent.put("id", OLD_AGENT_ID);
        agent.put("name", "Root Agent");
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("agent", agent);
        return snapshot;
    }

    private static Map<String, Object> buildSnapshotWithWorkflow() {
        Map<String, Object> wf = new HashMap<>();
        wf.put("name", "Sub Workflow");
        wf.put("plan", new HashMap<>(Map.of("cores", List.of())));
        Map<String, Object> workflows = new HashMap<>();
        workflows.put("99990000-0000-0000-0000-000000000099", wf);
        Map<String, Object> snapshot = buildAgentOnlySnapshot();
        snapshot.put("workflows", workflows);
        return snapshot;
    }

    private static Map<String, Object> buildSnapshotWithSubAgent() {
        Map<String, Object> subAgentData = new HashMap<>();
        subAgentData.put("id", OLD_SUB_AGENT_ID);
        subAgentData.put("name", "Sub Agent");
        Map<String, Object> subSnapshot = new HashMap<>();
        subSnapshot.put("agent", subAgentData);

        Map<String, Object> subAgents = new HashMap<>();
        subAgents.put(OLD_SUB_AGENT_ID, subSnapshot);

        Map<String, Object> snapshot = buildAgentOnlySnapshot();
        snapshot.put("subAgents", subAgents);
        return snapshot;
    }

    private static Map<String, Object> buildSnapshot() {
        Map<String, Object> iface = new HashMap<>();
        iface.put("name", "Acquired Interface");
        iface.put("htmlTemplate", "<div>hi</div>");
        Map<String, Object> interfaces = new HashMap<>();
        interfaces.put(OLD_IFACE_ID, iface);

        Map<String, Object> ds = new HashMap<>();
        ds.put("name", "Acquired DS");
        ds.put("sourceType", "INLINE");
        ds.put("sourceConfig", new HashMap<>());
        ds.put("columnOrder", List.of());
        Map<String, Object> datasources = new HashMap<>();
        datasources.put("5", ds);

        Map<String, Object> agent = new HashMap<>();
        agent.put("id", OLD_AGENT_ID);
        agent.put("name", "Root Agent");

        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("interfaces", interfaces);
        snapshot.put("datasources", datasources);
        snapshot.put("agent", agent);
        return snapshot;
    }

    private static DataSourceDto dataSourceDto(long id) {
        return new DataSourceDto(
                id, null, "Acquired DS", null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
