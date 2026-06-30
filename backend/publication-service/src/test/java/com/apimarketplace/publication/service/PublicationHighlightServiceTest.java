package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.PublicationHighlightEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationHighlightRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.PublicationHighlightService.HighlightedPublication;
import com.apimarketplace.publication.service.PublicationHighlightService.PublicHighlight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationHighlightService")
class PublicationHighlightServiceTest {

    @Mock
    private PublicationHighlightRepository highlightRepo;

    @Mock
    private WorkflowPublicationRepository publicationRepo;

    private PublicationHighlightService service;

    private static final String ADMIN_ID = "42";

    @BeforeEach
    void setUp() {
        service = new PublicationHighlightService(highlightRepo, publicationRepo);
    }

    private WorkflowPublicationEntity activePublic(UUID id, DisplayMode mode) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setId(id);
        p.setStatus(PublicationStatus.ACTIVE);
        p.setVisibility(PublicationVisibility.PUBLIC);
        p.setDisplayMode(mode);
        return p;
    }

    @Test
    @DisplayName("listPublicHighlights filters out non-ACTIVE rows so deactivated curated entries don't leak")
    void listPublicHighlightsFiltersInactive() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        when(highlightRepo.findByDisplayModeOrderByRankAsc(DisplayMode.APPLICATION))
                .thenReturn(List.of(
                        new PublicationHighlightEntity(DisplayMode.APPLICATION, active, 0, ADMIN_ID),
                        new PublicationHighlightEntity(DisplayMode.APPLICATION, inactive, 1, ADMIN_ID)
                ));

        WorkflowPublicationEntity activePub = activePublic(active, DisplayMode.APPLICATION);
        WorkflowPublicationEntity inactivePub = activePublic(inactive, DisplayMode.APPLICATION);
        inactivePub.setStatus(PublicationStatus.INACTIVE);
        when(publicationRepo.findAllById(List.of(active, inactive)))
                .thenReturn(List.of(activePub, inactivePub));

        List<PublicHighlight> result = service.listPublicHighlights(DisplayMode.APPLICATION);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).publication().id()).isEqualTo(active);
    }

    @Test
    @DisplayName("listPublicHighlights filters out PRIVATE/UNLISTED rows")
    void listPublicHighlightsFiltersNonPublic() {
        UUID pub = UUID.randomUUID();
        UUID priv = UUID.randomUUID();
        when(highlightRepo.findByDisplayModeOrderByRankAsc(DisplayMode.APPLICATION))
                .thenReturn(List.of(
                        new PublicationHighlightEntity(DisplayMode.APPLICATION, pub, 0, ADMIN_ID),
                        new PublicationHighlightEntity(DisplayMode.APPLICATION, priv, 1, ADMIN_ID)
                ));

        WorkflowPublicationEntity publicPub = activePublic(pub, DisplayMode.APPLICATION);
        WorkflowPublicationEntity privatePub = activePublic(priv, DisplayMode.APPLICATION);
        privatePub.setVisibility(PublicationVisibility.PRIVATE);
        when(publicationRepo.findAllById(List.of(pub, priv)))
                .thenReturn(List.of(publicPub, privatePub));

        List<PublicHighlight> result = service.listPublicHighlights(DisplayMode.APPLICATION);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).publication().id()).isEqualTo(pub);
    }

    @Test
    @DisplayName("listPublicHighlights returns empty list when no highlights exist (no DB roundtrip for publications)")
    void listPublicHighlightsEmpty() {
        when(highlightRepo.findByDisplayModeOrderByRankAsc(DisplayMode.APPLICATION))
                .thenReturn(List.of());

        List<PublicHighlight> result = service.listPublicHighlights(DisplayMode.APPLICATION);

        assertThat(result).isEmpty();
        verify(publicationRepo, never()).findAllById(any());
    }

    @Test
    @DisplayName("listPublicHighlights returns slim DTO - heavy jsonb columns never reach anonymous callers")
    void listPublicHighlightsReturnsSlimDto() {
        UUID id = UUID.randomUUID();
        when(highlightRepo.findByDisplayModeOrderByRankAsc(DisplayMode.APPLICATION))
                .thenReturn(List.of(new PublicationHighlightEntity(DisplayMode.APPLICATION, id, 0, ADMIN_ID)));

        WorkflowPublicationEntity pub = activePublic(id, DisplayMode.APPLICATION);
        pub.setTitle("Featured app");
        pub.setPlanSnapshot(java.util.Map.of("workflow", "internal-secret-data"));
        pub.setAgentSnapshot(java.util.Map.of("system_prompt", "internal-secret"));
        pub.setShowcaseSnapshot(java.util.Map.of("epoch", "internal-secret"));
        when(publicationRepo.findAllById(List.of(id))).thenReturn(List.of(pub));

        List<PublicHighlight> result = service.listPublicHighlights(DisplayMode.APPLICATION);

        assertThat(result).hasSize(1);
        // The runtime type IS the slim DTO; no @JsonIgnore reliance - jsonb fields
        // are simply not part of PublicHighlightItem at all.
        assertThat(result.get(0).publication()).isInstanceOf(
                com.apimarketplace.publication.dto.PublicHighlightItem.class);
        assertThat(result.get(0).publication().id()).isEqualTo(id);
        assertThat(result.get(0).publication().title()).isEqualTo("Featured app");
    }

    @Test
    @DisplayName("listAdminHighlights surfaces stale rows (inactive/missing publications) so admins can clean up")
    void listAdminHighlightsKeepsStale() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        when(highlightRepo.findByDisplayModeOrderByRankAsc(DisplayMode.APPLICATION))
                .thenReturn(List.of(
                        new PublicationHighlightEntity(DisplayMode.APPLICATION, active, 0, ADMIN_ID),
                        new PublicationHighlightEntity(DisplayMode.APPLICATION, inactive, 1, ADMIN_ID)
                ));

        WorkflowPublicationEntity activePub = activePublic(active, DisplayMode.APPLICATION);
        WorkflowPublicationEntity inactivePub = activePublic(inactive, DisplayMode.APPLICATION);
        inactivePub.setStatus(PublicationStatus.INACTIVE);
        when(publicationRepo.findAllById(List.of(active, inactive)))
                .thenReturn(List.of(activePub, inactivePub));

        List<HighlightedPublication> result = service.listAdminHighlights(DisplayMode.APPLICATION);

        assertThat(result).hasSize(2); // BOTH rows surfaced - admin needs visibility
    }

    @Test
    @DisplayName("replaceHighlights rejects DUPLICATE_IDS without touching DB")
    void replaceRejectsDuplicates() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.replaceHighlights(
                DisplayMode.APPLICATION, List.of(id, id), ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("DUPLICATE_IDS");

        verifyNoInteractions(publicationRepo);
        verify(highlightRepo, never()).deleteAllByDisplayModeBulk(any());
    }

    @Test
    @DisplayName("replaceHighlights rolls back when an id does not exist (count mismatch)")
    void replaceRejectsUnknownId() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(publicationRepo.findAllById(List.of(known, unknown)))
                .thenReturn(List.of(activePublic(known, DisplayMode.APPLICATION)));

        assertThatThrownBy(() -> service.replaceHighlights(
                DisplayMode.APPLICATION, List.of(known, unknown), ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_OR_INACCESSIBLE_PUBLICATIONS");

        verify(highlightRepo, never()).deleteAllByDisplayModeBulk(any());
        verify(highlightRepo, never()).save(any());
    }

    @Test
    @DisplayName("replaceHighlights rejects publications whose displayMode does not match the path")
    void replaceRejectsCrossMode() {
        UUID id = UUID.randomUUID();
        when(publicationRepo.findAllById(List.of(id)))
                .thenReturn(List.of(activePublic(id, DisplayMode.AGENT))); // wrong mode

        assertThatThrownBy(() -> service.replaceHighlights(
                DisplayMode.APPLICATION, List.of(id), ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_OR_INACCESSIBLE_PUBLICATIONS");

        verify(highlightRepo, never()).deleteAllByDisplayModeBulk(any());
    }

    @Test
    @DisplayName("replaceHighlights rejects non-ACTIVE publications even if id matches")
    void replaceRejectsInactive() {
        UUID id = UUID.randomUUID();
        WorkflowPublicationEntity inactive = activePublic(id, DisplayMode.APPLICATION);
        inactive.setStatus(PublicationStatus.PENDING_REVIEW);
        when(publicationRepo.findAllById(List.of(id))).thenReturn(List.of(inactive));

        assertThatThrownBy(() -> service.replaceHighlights(
                DisplayMode.APPLICATION, List.of(id), ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_OR_INACCESSIBLE_PUBLICATIONS");
    }

    @Test
    @DisplayName("replaceHighlights writes ranks 0..N-1 in declared order, deleting first")
    void replaceWritesRanksInOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        when(publicationRepo.findAllById(List.of(a, b, c))).thenReturn(List.of(
                activePublic(a, DisplayMode.APPLICATION),
                activePublic(b, DisplayMode.APPLICATION),
                activePublic(c, DisplayMode.APPLICATION)
        ));

        service.replaceHighlights(DisplayMode.APPLICATION, List.of(a, b, c), ADMIN_ID);

        InOrder order = inOrder(highlightRepo);
        order.verify(highlightRepo).deleteAllByDisplayModeBulk(DisplayMode.APPLICATION);
        order.verify(highlightRepo).save(argThatHighlight(a, 0));
        order.verify(highlightRepo).save(argThatHighlight(b, 1));
        order.verify(highlightRepo).save(argThatHighlight(c, 2));
    }

    @Test
    @DisplayName("replaceHighlights with empty list deletes everything (admin clears the row)")
    void replaceEmptyClears() {
        service.replaceHighlights(DisplayMode.APPLICATION, List.of(), ADMIN_ID);

        verify(highlightRepo).deleteAllByDisplayModeBulk(DisplayMode.APPLICATION);
        verify(highlightRepo, never()).save(any());
        verifyNoInteractions(publicationRepo);
    }

    @Test
    @DisplayName("replaceHighlights null orderedIds rejects with MISSING_ORDERED_IDS, no DB writes")
    void replaceRejectsNullList() {
        assertThatThrownBy(() -> service.replaceHighlights(
                DisplayMode.APPLICATION, null, ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MISSING_ORDERED_IDS");

        verifyNoInteractions(highlightRepo);
        verifyNoInteractions(publicationRepo);
    }

    @Test
    @DisplayName("replaceHighlights persists adminUserId in created_by - audit trail")
    void replacePersistsAdminId() {
        UUID a = UUID.randomUUID();
        when(publicationRepo.findAllById(List.of(a)))
                .thenReturn(List.of(activePublic(a, DisplayMode.APPLICATION)));

        service.replaceHighlights(DisplayMode.APPLICATION, List.of(a), "admin-7");

        verify(highlightRepo).save(argThatHighlightWithAdmin(a, 0, "admin-7"));
    }

    // ---- LANDING bucket: curated row driving the public landing page. ----
    // It is a SEPARATE bucket from APPLICATION (which drives the chat highlights
    // row) but holds APPLICATION-type publications, so its validation must map
    // LANDING -> APPLICATION instead of requiring displayMode == LANDING.

    @Test
    @DisplayName("requiredPublicationMode maps LANDING -> APPLICATION and is identity for every other bucket")
    void requiredPublicationModeMapping() {
        assertThat(PublicationHighlightService.requiredPublicationMode(DisplayMode.LANDING))
                .isEqualTo(DisplayMode.APPLICATION);
        for (DisplayMode m : DisplayMode.values()) {
            if (m == DisplayMode.LANDING) continue;
            assertThat(PublicationHighlightService.requiredPublicationMode(m)).isEqualTo(m);
        }
    }

    @Test
    @DisplayName("replaceHighlights(LANDING) accepts an APPLICATION publication and writes it at rank 0")
    void replaceLandingAcceptsApplication() {
        UUID app = UUID.randomUUID();
        when(publicationRepo.findAllById(List.of(app)))
                .thenReturn(List.of(activePublic(app, DisplayMode.APPLICATION)));

        service.replaceHighlights(DisplayMode.LANDING, List.of(app), ADMIN_ID);

        InOrder order = inOrder(highlightRepo);
        order.verify(highlightRepo).deleteAllByDisplayModeBulk(DisplayMode.LANDING);
        order.verify(highlightRepo).save(argThatHighlight(app, 0));
    }

    @Test
    @DisplayName("replaceHighlights(LANDING) rejects a non-APPLICATION publication (e.g. INTERFACE)")
    void replaceLandingRejectsNonApplication() {
        UUID iface = UUID.randomUUID();
        when(publicationRepo.findAllById(List.of(iface)))
                .thenReturn(List.of(activePublic(iface, DisplayMode.INTERFACE)));

        assertThatThrownBy(() -> service.replaceHighlights(
                DisplayMode.LANDING, List.of(iface), ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_OR_INACCESSIBLE_PUBLICATIONS");

        verify(highlightRepo, never()).deleteAllByDisplayModeBulk(any());
        verify(highlightRepo, never()).save(any());
    }

    @Test
    @DisplayName("replaceHighlights(LANDING) rejects an APPLICATION publication that is not ACTIVE+PUBLIC")
    void replaceLandingRejectsNonActivePublic() {
        UUID priv = UUID.randomUUID();
        WorkflowPublicationEntity privateApp = activePublic(priv, DisplayMode.APPLICATION);
        privateApp.setVisibility(PublicationVisibility.PRIVATE);
        when(publicationRepo.findAllById(List.of(priv))).thenReturn(List.of(privateApp));

        assertThatThrownBy(() -> service.replaceHighlights(
                DisplayMode.LANDING, List.of(priv), ADMIN_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_OR_INACCESSIBLE_PUBLICATIONS");

        verify(highlightRepo, never()).deleteAllByDisplayModeBulk(any());
    }

    @Test
    @DisplayName("listPublicHighlights(LANDING) returns the curated APPLICATION publications in rank order")
    void listPublicLandingReturnsCurated() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(highlightRepo.findByDisplayModeOrderByRankAsc(DisplayMode.LANDING))
                .thenReturn(List.of(
                        new PublicationHighlightEntity(DisplayMode.LANDING, first, 0, ADMIN_ID),
                        new PublicationHighlightEntity(DisplayMode.LANDING, second, 1, ADMIN_ID)
                ));
        when(publicationRepo.findAllById(List.of(first, second)))
                .thenReturn(List.of(
                        activePublic(first, DisplayMode.APPLICATION),
                        activePublic(second, DisplayMode.APPLICATION)
                ));

        List<PublicHighlight> result = service.listPublicHighlights(DisplayMode.LANDING);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).publication().id()).isEqualTo(first);
        assertThat(result.get(1).publication().id()).isEqualTo(second);
    }

    private static PublicationHighlightEntity argThatHighlight(UUID id, int rank) {
        return org.mockito.ArgumentMatchers.argThat(h ->
                h.getPublicationId().equals(id) && h.getRank() == rank);
    }

    private static PublicationHighlightEntity argThatHighlightWithAdmin(UUID id, int rank, String admin) {
        return org.mockito.ArgumentMatchers.argThat(h ->
                h.getPublicationId().equals(id)
                        && h.getRank() == rank
                        && admin.equals(h.getCreatedBy()));
    }
}
