package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
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

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression guard for "every re-share must re-enter review".
 *
 * <p>Pre-fix, {@code updatePublicationInfo} never touched {@code status}: an
 * already-approved PUBLIC publication kept its {@code ACTIVE} status across
 * updates, so edited (un-reviewed) content shipped straight to the marketplace.
 * The fix mirrors {@code publishWorkflow}'s visibility-driven status reset:
 * PUBLIC/UNLISTED → PENDING_REVIEW (reviewer state cleared), PRIVATE → ACTIVE.
 *
 * <p>Each test reproduces the exact report: re-share an approved app and assert
 * the moderation state, rather than asserting the {@code how} (which setter ran).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Re-share re-enters review: updatePublicationInfo resets status by visibility")
class WorkflowPublicationServiceReshareReviewTest {

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

    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORKFLOW_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TENANT_ID = "tenant-001";
    private static final String SHOWCASE_RUN_ID = "run-x";

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
        lenient().when(authClient.getPublisherProfile(any()))
                .thenReturn(new PublisherProfileDto(TENANT_ID, "Test Publisher", "test@publisher.com", "test-avatar-uuid"));
    }

    @Test
    @DisplayName("Re-sharing an already-approved PUBLIC app returns it to PENDING_REVIEW and clears reviewer state")
    void reshareApprovedPublicReturnsToReview() {
        WorkflowPublicationEntity publication = approvedPublic();
        // Prove the pre-condition: it was approved with a reviewer + cleared rejection.
        publication.setReviewerId("admin-7");
        publication.setReviewedAt(Instant.parse("2026-01-01T00:00:00Z"));
        stubPublicUpdate(publication);

        WorkflowPublicationEntity updated = updateWith(PublicationVisibility.PUBLIC);

        assertThat(updated.getStatus()).isEqualTo(PublicationStatus.PENDING_REVIEW);
        assertThat(updated.getReviewerId()).isNull();
        assertThat(updated.getReviewedAt()).isNull();
        assertThat(updated.getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("Re-sharing an UNLISTED app re-enters review")
    void reshareUnlistedReentersReview() {
        WorkflowPublicationEntity publication = approvedPublic();
        publication.setVisibility(PublicationVisibility.UNLISTED);
        stubPublicUpdate(publication);

        WorkflowPublicationEntity updated = updateWith(PublicationVisibility.UNLISTED);

        assertThat(updated.getStatus()).isEqualTo(PublicationStatus.PENDING_REVIEW);
    }

    @Test
    @DisplayName("Re-sharing a previously REJECTED PUBLIC app re-enters review (resubmission)")
    void reshareRejectedPublicReentersReview() {
        WorkflowPublicationEntity publication = approvedPublic();
        publication.setStatus(PublicationStatus.REJECTED);
        publication.setRejectionReason("Contains copyrighted imagery");
        stubPublicUpdate(publication);

        WorkflowPublicationEntity updated = updateWith(PublicationVisibility.PUBLIC);

        assertThat(updated.getStatus()).isEqualTo(PublicationStatus.PENDING_REVIEW);
        assertThat(updated.getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("Re-sharing a PRIVATE app stays ACTIVE (no review path)")
    void resharePrivateStaysActive() {
        WorkflowPublicationEntity publication = approvedPublic();
        publication.setVisibility(PublicationVisibility.PRIVATE);
        publication.setShowcaseRunId(null);
        publication.setShowcaseInterfaceId(null);
        // PRIVATE needs no showcase run, so only findById + getWorkflow + save.
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(Map.of("plan", Map.of()));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowPublicationEntity updated = service.updatePublicationInfo(
                PUBLICATION_ID, TENANT_ID, null,
                "Updated title", "Updated description",
                null, null, null, 0,
                PublicationVisibility.PRIVATE, DisplayMode.WORKFLOW,
                null, false, true, Map.of());

        assertThat(updated.getStatus()).isEqualTo(PublicationStatus.ACTIVE);
    }

    // ── helpers ──

    private WorkflowPublicationEntity updateWith(PublicationVisibility visibility) {
        return service.updatePublicationInfo(
                PUBLICATION_ID, TENANT_ID, null,
                "Updated title", "Updated description",
                null, SHOWCASE_RUN_ID, null, 0,
                visibility, DisplayMode.WORKFLOW,
                null, false, true, Map.of());
    }

    private void stubPublicUpdate(WorkflowPublicationEntity publication) {
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        when(orchestratorClient.getWorkflowForPublication(WORKFLOW_ID, TENANT_ID, null))
                .thenReturn(Map.of("plan", Map.of()));
        // PUBLIC/UNLISTED re-validate the (unchanged) showcase run before save.
        when(orchestratorClient.validateShowcaseRun(SHOWCASE_RUN_ID, TENANT_ID, null))
                .thenReturn(Map.of("isStepByStep", false, "publishable", true, "status", "COMPLETED"));
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    /**
     * An approved, marketplace-live publication: PUBLIC + ACTIVE, WORKFLOW
     * display mode (no interface required), pointing at an existing showcase
     * run so the update path validates it without re-capturing the snapshot.
     */
    private WorkflowPublicationEntity approvedPublic() {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(WORKFLOW_ID);
        publication.setPublisherId(TENANT_ID);
        publication.setOwnerType(OwnerType.USER);
        publication.setOwnerId(TENANT_ID);
        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setVisibility(PublicationVisibility.PUBLIC);
        publication.setDisplayMode(DisplayMode.WORKFLOW);
        publication.setTitle("Original title");
        publication.setDescription("Original description");
        publication.setCreditsPerUse(0);
        publication.setShowcaseRunId(SHOWCASE_RUN_ID);
        publication.setShowcaseChosenEpoch(null);
        return publication;
    }
}
