package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the soft-delete contract of
 * {@link WorkflowPublicationService#deletePublication(UUID, String, String)}
 * (DELETE /publications/workflow/{id}): an ACTIVE publication transitions to
 * INACTIVE (delisting it from the ACTIVE-only marketplace + anonymous-read
 * gate) and its cloned showcase run is cleaned up. The receipt-backed soft
 * delete keeps the row so existing acquirers still resolve their installed
 * clone; only the status flips.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService - delete publication (soft delete -> INACTIVE)")
class WorkflowPublicationServiceDeletePublicationTest {

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
    private static final String OWNER = "owner-1";

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
    @DisplayName("Deleting an ACTIVE publication flips it to INACTIVE (delisted), persists, and drops the cloned showcase run")
    void deleteActivePublicationTransitionsToInactiveAndCleansShowcaseRun() {
        WorkflowPublicationEntity publication = activePublicationWithShowcaseRun("showcase_run_abc");
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(publication));

        service.deletePublication(WORKFLOW_ID, OWNER, null);

        // Marketplace + anonymous-read gate keys on status==ACTIVE; flipping to INACTIVE delists it.
        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.INACTIVE);
        verify(publicationRepository).save(publication);
        // The cloned "showcase_"-prefixed run is removed so it stops backing a now-delisted listing.
        verify(orchestratorClient).deleteClonedRun("showcase_run_abc");
    }

    @Test
    @DisplayName("Delete leaves a non-cloned showcase run untouched (only \"showcase_\"-prefixed runs are cleaned)")
    void deleteDoesNotTouchNonClonedShowcaseRun() {
        WorkflowPublicationEntity publication = activePublicationWithShowcaseRun("run-original-42");
        when(publicationRepository.findByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(publication));

        service.deletePublication(WORKFLOW_ID, OWNER, null);

        assertThat(publication.getStatus()).isEqualTo(PublicationStatus.INACTIVE);
        verify(publicationRepository).save(publication);
        // A user-owned source run id (no "showcase_" prefix) must not be deleted by the soft delete.
        verify(orchestratorClient, never()).deleteClonedRun(anyString());
    }

    private WorkflowPublicationEntity activePublicationWithShowcaseRun(String showcaseRunId) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(WORKFLOW_ID);
        // owner_id unset -> legacy fallback resolves owner scope via publisher_id equality.
        publication.setPublisherId(OWNER);
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setShowcaseRunId(showcaseRunId);
        return publication;
    }
}
