package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Strict-snapshot mode contract for the public marketplace endpoints.
 * After Phase B.8 the controller MUST never call the orchestrator for
 * marketplace reads - it serves only from the publication's frozen
 * {@code showcase_snapshot} JSONB. When the snapshot is missing the
 * controller returns 503 instead of leaking publisher live state.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController - strict snapshot mode")
class WorkflowPublicationControllerShowcaseRenderTest {

    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private PublicationListQueryService listQueryService;
    @Mock private PublicationReviewService reviewService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private ShowcaseSnapshotReader showcaseSnapshotReader;
    @Mock private ShowcaseFileRefRewriter fileRefRewriter;
    @Mock private OrgAccessGuard orgAccessGuard;

    private WorkflowPublicationController controller;

    private static final UUID PUBLICATION_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SHOWCASE_INTERFACE_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SHOWCASE_RUN_ID = "run_abc12345";

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new com.apimarketplace.publication.service.OnboardingCategoryMapper(),
                orgAccessGuard);
    }

    private WorkflowPublicationEntity activePublic() {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUBLICATION_UUID);
        pub.setStatus(PublicationStatus.ACTIVE);
        pub.setVisibility(PublicationVisibility.PUBLIC);
        pub.setShowcaseInterfaceId(SHOWCASE_INTERFACE_UUID);
        pub.setShowcaseRunId(SHOWCASE_RUN_ID);
        return pub;
    }

    @Test
    @DisplayName("render - 503 when snapshot missing; orchestrator never called")
    void renderReturns503WhenSnapshotMissing() {
        WorkflowPublicationEntity pub = activePublic();
        when(publicationService.getPublicationById(PUBLICATION_UUID)).thenReturn(Optional.of(pub));
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(false);

        ResponseEntity<?> response = controller.renderPublicShowcase(
                PUBLICATION_UUID.toString(), SHOWCASE_INTERFACE_UUID.toString(), 0, 1, null);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoOrchestratorReadCalls();
    }

    @Test
    @DisplayName("render - 200 from JSONB when snapshot present")
    void renderReturnsBodyFromSnapshot() {
        WorkflowPublicationEntity pub = activePublic();
        when(publicationService.getPublicationById(PUBLICATION_UUID)).thenReturn(Optional.of(pub));
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(true);
        Map<String, Object> body = Map.of(
                "htmlTemplate", "<h1>hi</h1>",
                "items", List.of(Map.of("epoch", 0, "itemIndex", 0))
        );
        when(showcaseSnapshotReader.readInterfaceRender(any(), anyString(), anyInt(), anyInt(), any()))
                .thenReturn(Optional.of(body));

        ResponseEntity<?> response = controller.renderPublicShowcase(
                PUBLICATION_UUID.toString(), SHOWCASE_INTERFACE_UUID.toString(), 0, 1, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(body);
        verifyNoOrchestratorReadCalls();
    }

    @Test
    @DisplayName("run-state - 503 when snapshot missing; orchestrator never called")
    void runStateReturns503WhenSnapshotMissing() {
        WorkflowPublicationEntity pub = activePublic();
        when(publicationService.getPublicationById(PUBLICATION_UUID)).thenReturn(Optional.of(pub));
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(false);

        ResponseEntity<?> response = controller.getPublicShowcaseRunState(PUBLICATION_UUID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoOrchestratorReadCalls();
    }

    @Test
    @DisplayName("aggregated-steps - 503 when snapshot missing; orchestrator never called")
    void aggregatedStepsReturns503WhenSnapshotMissing() {
        WorkflowPublicationEntity pub = activePublic();
        when(publicationService.getPublicationById(PUBLICATION_UUID)).thenReturn(Optional.of(pub));
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(false);

        ResponseEntity<?> response = controller.getPublicShowcaseAggregatedSteps(PUBLICATION_UUID.toString(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoOrchestratorReadCalls();
    }

    @Test
    @DisplayName("epoch-state - 503 when snapshot missing; orchestrator never called")
    void epochStateReturns503WhenSnapshotMissing() {
        WorkflowPublicationEntity pub = activePublic();
        when(publicationService.getPublicationById(PUBLICATION_UUID)).thenReturn(Optional.of(pub));
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(false);

        ResponseEntity<?> response = controller.getPublicShowcaseEpochState(PUBLICATION_UUID.toString(), 0);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoOrchestratorReadCalls();
    }

    @Test
    @DisplayName("epoch-signals - 503 when snapshot missing; orchestrator never called")
    void epochSignalsReturns503WhenSnapshotMissing() {
        WorkflowPublicationEntity pub = activePublic();
        when(publicationService.getPublicationById(PUBLICATION_UUID)).thenReturn(Optional.of(pub));
        when(showcaseSnapshotReader.hasSnapshot(pub)).thenReturn(false);

        ResponseEntity<?> response = controller.getPublicShowcaseEpochSignals(PUBLICATION_UUID.toString(), 0);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        verifyNoOrchestratorReadCalls();
    }

    @Test
    @DisplayName("Non-public publication - 403, snapshot reader not even consulted")
    void privatePublicationReturns403() {
        WorkflowPublicationEntity pub = activePublic();
        pub.setVisibility(PublicationVisibility.PRIVATE);
        when(publicationService.getPublicationById(PUBLICATION_UUID)).thenReturn(Optional.of(pub));

        ResponseEntity<?> response = controller.getPublicShowcaseRunState(PUBLICATION_UUID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(showcaseSnapshotReader, never()).hasSnapshot(any());
        verifyNoOrchestratorReadCalls();
    }

    /**
     * The orchestrator client must be a black hole on the read path -
     * Phase B.8 contract is "marketplace reads from JSONB only, ever."
     */
    private void verifyNoOrchestratorReadCalls() {
        // Marketplace read path is now snapshot-only; the orchestrator client
        // is reduced to publish/backfill helpers (captureShowcaseSnapshot,
        // deleteClonedRun, cleanupApplicationRuns, …). None of these belong
        // on the read path. We use Mockito's verifyNoInteractions to assert
        // that no method on orchestratorClient is hit during a marketplace
        // GET, regardless of the specific signatures the helper exposes.
        org.mockito.Mockito.verifyNoInteractions(orchestratorClient);
    }
}
