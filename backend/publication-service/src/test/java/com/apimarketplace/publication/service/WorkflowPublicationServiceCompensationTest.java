package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the acquire-failure compensation contract: when the clone blows up
 * mid-pipeline, every partially-created workflow row is cleaned (runs) AND
 * deleted, and the delete carries the acquirer's ORGANIZATION scope.
 *
 * <p>The org argument is the regression here: the orchestrator's canonical
 * delete strict-scope-guards against the row's organization_id (NOT NULL
 * post-V263) using the caller org from the X-Organization-ID header. A
 * compensation that calls the delete without the org scope is silently
 * refused for every row - the pre-fix behavior that left orphan clones
 * behind after every failed acquisition.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService - acquire-failure compensation deletes clones with org scope")
class WorkflowPublicationServiceCompensationTest {

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

    private static final UUID PUBLICATION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID WORKFLOW_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ROOT_CLONE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID CHILD_CLONE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final String BUYER = "buyer-7";
    private static final String BUYER_ORG = "org-buyer";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository,
                snapshotVersionRepository,
                receiptRepository,
                reviewRepository,
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                breakdownService,
                new ObjectMapper(),
                snapshotCloneService,
                entitlementGuard,
                authClient);
    }

    @Test
    @DisplayName("Failed clone: every partial row gets runs-cleanup + an org-scoped delete, then the original failure bubbles up")
    void failedCloneDeletesPartialRowsWithOrgScope() {
        WorkflowPublicationEntity publication = activePublication();
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        // Not yet acquired, no receipt → first-time acquisition path.
        when(orchestratorClient.existsBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG)).thenReturn(false);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);
        // The clone pipeline dies mid-flight AFTER creating a root + a child row, surfacing the
        // EXACT ids it created via AcquireCloneFailedException (the scoped-compensation contract).
        AcquireCloneFailedException cloneFailure = new AcquireCloneFailedException(
                Set.of(ROOT_CLONE_ID.toString(), CHILD_CLONE_ID.toString()),
                new RuntimeException("clone exploded"));
        lenient().when(snapshotCloneService.cloneFromSnapshot(any(), anyString(), any(), any(), any(), any(), anyString()))
                .thenThrow(cloneFailure);
        lenient().when(snapshotCloneService.cloneFromSnapshot(any(), anyString(), any(), any(), any(), any()))
                .thenThrow(cloneFailure);

        assertThatThrownBy(() -> service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("clone exploded");

        verify(orchestratorClient).cleanupApplicationRuns(ROOT_CLONE_ID, PUBLICATION_ID.toString(), BUYER);
        verify(orchestratorClient).cleanupApplicationRuns(CHILD_CLONE_ID, PUBLICATION_ID.toString(), BUYER);
        // The org scope on the delete is load-bearing: without it the
        // orchestrator's strict-scope guard refuses every row.
        verify(orchestratorClient).deleteAcquiredWorkflow(ROOT_CLONE_ID, PUBLICATION_ID, BUYER, BUYER_ORG);
        verify(orchestratorClient).deleteAcquiredWorkflow(CHILD_CLONE_ID, PUBLICATION_ID, BUYER, BUYER_ORG);
    }

    @Test
    @DisplayName("Concurrent first-time acquires: the loser's compensation deletes only its OWN rows, never the winner's")
    void concurrentAcquireCompensationLeavesWinnerUntouched() {
        UUID loserRoot = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        UUID loserChild = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UUID winnerRoot = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

        WorkflowPublicationEntity publication = activePublication();
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.existsBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG)).thenReturn(false);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(BUYER_ORG, PUBLICATION_ID)).thenReturn(false);

        // The loser's clone fails (its own root create lost the V268 unique-index race), surfacing
        // ONLY the rows IT created.
        AcquireCloneFailedException loserFailure = new AcquireCloneFailedException(
                Set.of(loserRoot.toString(), loserChild.toString()),
                new RuntimeException("duplicate key"));
        lenient().when(snapshotCloneService.cloneFromSnapshot(any(), anyString(), any(), any(), any(), any(), anyString()))
                .thenThrow(loserFailure);
        lenient().when(snapshotCloneService.cloneFromSnapshot(any(), anyString(), any(), any(), any(), any()))
                .thenThrow(loserFailure);
        // If the code ever reverts to the org-wide sweep, this is what it would (wrongly) enumerate
        // - including the concurrent WINNER's row. The scoped fix must never consult it.
        lenient().when(orchestratorClient.findAllBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .thenReturn(List.of(Map.of("id", winnerRoot.toString())));

        assertThatThrownBy(() -> service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .hasMessage("duplicate key");

        // Only the loser's OWN rows are cleaned.
        verify(orchestratorClient).deleteAcquiredWorkflow(loserRoot, PUBLICATION_ID, BUYER, BUYER_ORG);
        verify(orchestratorClient).deleteAcquiredWorkflow(loserChild, PUBLICATION_ID, BUYER, BUYER_ORG);
        // The concurrent winner's row is NEVER touched (the org-wide-sweep data-loss bug).
        verify(orchestratorClient, never()).deleteAcquiredWorkflow(eq(winnerRoot), any(), any(), any());
    }

    private WorkflowPublicationEntity activePublication() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(WORKFLOW_ID);
        publication.setPublisherId("publisher-1");
        publication.setStatus(WorkflowPublicationEntity.PublicationStatus.ACTIVE);
        publication.setVisibility(WorkflowPublicationEntity.PublicationVisibility.PUBLIC);
        publication.setCreditsPerUse(0);
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of());
        publication.setPlanSnapshot(plan);
        return publication;
    }
}
