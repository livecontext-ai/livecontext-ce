package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.dto.PublicationListItem;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Wiring contract: the THREE marketplace list endpoints that feed an Acquire button
 * ({@code /marketplace}, {@code /search}, {@code /marketplace/by-type/{type}}) must read the
 * caller's {@code X-User-ID}/{@code X-Organization-ID} and stamp the active-workspace
 * {@code ownedByMe} flag onto every item. The static rule is covered by
 * {@link WorkflowPublicationControllerOwnershipTest}; this pins that each endpoint actually
 * CALLS the enrichment (a dropped header or a missing enrich call would silently show
 * "Acquire" on owned apps and break nothing else) - and that by-type uses the {@code content}
 * envelope while the others use {@code publications}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController - marketplace ownedByMe wiring")
class WorkflowPublicationControllerMarketplaceWiringTest {

    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private PublicationListQueryService listQueryService;
    @Mock private PublicationReviewService reviewService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private ShowcaseSnapshotReader showcaseSnapshotReader;
    @Mock private ShowcaseFileRefRewriter fileRefRewriter;
    @Mock private OnboardingCategoryMapper onboardingCategoryMapper;
    @Mock private OrgAccessGuard orgAccessGuard;

    private WorkflowPublicationController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(publicationService, agentPublicationService,
                listQueryService, reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                onboardingCategoryMapper, orgAccessGuard);
    }

    /** Minimal ORG-owned ACTIVE+PUBLIC list item. */
    private PublicationListItem orgOwned(String ownerOrgId) {
        return new PublicationListItem(
                UUID.randomUUID(), "WORKFLOW", UUID.randomUUID(), null,
                "T", "D", null, null, "WORKFLOW",
                0, "publisher-x", "Pub", null, null,
                "ACTIVE", "PUBLIC", "ORG", ownerOrgId, 0, 0, null,
                null, 0, 0, 0, 0, 0, null, 0,
                Instant.now(), Instant.now(),
                null, null, null, null, null,
                null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(ResponseEntity<?> res, String key) {
        return (List<Map<String, Object>>) ((Map<String, Object>) res.getBody()).get(key);
    }

    @Test
    @DisplayName("/marketplace stamps ownedByMe from the active org header")
    void marketplaceEnriches() {
        when(listQueryService.findMarketplacePublications(anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(orgOwned("org-A"))));

        // Active workspace == owner org → owned.
        assertThat(items(controller.getMarketplacePublications(0, 20, null, "5", "org-A"), "publications")
                .get(0).get("ownedByMe")).isEqualTo(true);
        // Member-elsewhere / different active workspace → not owned.
        assertThat(items(controller.getMarketplacePublications(0, 20, null, "5", "org-B"), "publications")
                .get(0).get("ownedByMe")).isEqualTo(false);
        // Anonymous (no user id) → not owned.
        assertThat(items(controller.getMarketplacePublications(0, 20, null, null, "org-A"), "publications")
                .get(0).get("ownedByMe")).isEqualTo(false);
    }

    @Test
    @DisplayName("/search stamps ownedByMe (the live search-bar path)")
    void searchEnriches() {
        when(listQueryService.searchByTitle(eq("q"), eq(null)))
                .thenReturn(List.of(orgOwned("org-A")));

        assertThat(items(controller.searchPublications("q", null, "5", "org-A"), "publications")
                .get(0).get("ownedByMe")).isEqualTo(true);
        assertThat(items(controller.searchPublications("q", null, "5", "org-B"), "publications")
                .get(0).get("ownedByMe")).isEqualTo(false);
    }

    @Test
    @DisplayName("/marketplace/by-type stamps ownedByMe onto the 'content' envelope (resource marketplaces)")
    void byTypeEnriches() {
        when(listQueryService.findMarketplaceByType(eq("WORKFLOW"), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(orgOwned("org-A"))));

        assertThat(items(controller.getMarketplaceByType("WORKFLOW", 0, 20, "5", "org-A"), "content")
                .get(0).get("ownedByMe")).isEqualTo(true);
        assertThat(items(controller.getMarketplaceByType("WORKFLOW", 0, 20, "5", "org-B"), "content")
                .get(0).get("ownedByMe")).isEqualTo(false);
    }
}
