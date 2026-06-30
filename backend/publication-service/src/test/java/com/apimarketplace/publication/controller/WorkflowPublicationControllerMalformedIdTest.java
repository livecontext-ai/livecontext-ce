package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Pins that the public read-by-id publication endpoints reject a MALFORMED (non-UUID) id as a CLIENT
 * error (HTTP 400), matching this controller's house convention - ~25 other handlers already map
 * {@code IllegalArgumentException} (the canonical malformed-id error) to {@code 400}.
 *
 * <p>Found in the 2026-06-24 CE live QA run: {@code getPublicationById} passed the raw path param
 * straight into {@code UUID.fromString} and had ONLY a broad {@code catch (Exception) -> 500}, so a
 * non-UUID id returned a server 500 ("Failed to get publication"). It now has the house-style
 * {@code catch (IllegalArgumentException) -> 400}. {@code getShowcaseData} already returned 400 for a
 * malformed id (it always had that catch); this test also pins it so the two read-by-id endpoints stay
 * consistent.
 *
 * <p>{@code getPublicationByIdMalformedIdReturns400} fails on the pre-fix code (500) and passes
 * post-fix (400).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController - malformed (non-UUID) id is a 400 client error, not a 500")
class WorkflowPublicationControllerMalformedIdTest {

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

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard);
    }

    @Test
    @DisplayName("GET by id with a non-UUID id returns 400 (was 500) and never reaches any service")
    void getPublicationByIdMalformedIdReturns400() {
        ResponseEntity<?> response = controller.getPublicationById(
                "pub-mktpub-g3-agent", "user-1", "org-1", null, null, null);

        // Pre-fix: UUID.fromString threw -> only catch(Exception) -> 500. Post-fix: house-style
        // catch(IllegalArgumentException) -> 400.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        // The malformed id is rejected before ANY id-consuming collaborator is touched.
        verifyNoInteractions(publicationService, agentPublicationService,
                resourcePublicationService, listQueryService);
    }

    @Test
    @DisplayName("GET showcase with a non-UUID id returns 400 (already the case) and never reaches any service")
    void getShowcaseDataMalformedIdReturns400() {
        ResponseEntity<?> response = controller.getShowcaseData("not-a-uuid", 0, 1);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(publicationService, agentPublicationService,
                resourcePublicationService, listQueryService);
    }
}
