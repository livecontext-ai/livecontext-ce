package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.dto.PublicHighlightItem;
import com.apimarketplace.publication.service.PublicationHighlightService;
import com.apimarketplace.publication.service.PublicationHighlightService.PublicHighlight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Direct method-call controller tests (the module's convention - no MockMvc, see
 * {@code PublicationFavoriteControllerTest}). The endpoint
 * {@code GET /api/publications/highlights/{displayMode}} takes NO pagination
 * params: it returns the full curated row for the requested bucket. The list's
 * ACTIVE+PUBLIC filtering and rank-ordering are a service concern already covered
 * by {@code PublicationHighlightServiceTest}, so these tests pin the CONTROLLER's
 * own contract instead: the {@code displayMode} path var binds and routes through
 * to the service, the response preserves the service's list order verbatim (the
 * controller must never re-sort), and each entry is mapped to the slim
 * {@code {rank, publication}} shape.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationHighlightPublicController")
class PublicationHighlightPublicControllerTest {

    @Mock
    private PublicationHighlightService highlightService;

    private PublicationHighlightPublicController controller;

    @BeforeEach
    void setUp() {
        controller = new PublicationHighlightPublicController(highlightService);
    }

    private PublicHighlightItem item(UUID id, String title) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setId(id);
        p.setTitle(title);
        p.setDisplayMode(DisplayMode.APPLICATION);
        return PublicHighlightItem.from(p);
    }

    @Test
    @DisplayName("returns 200 and echoes the requested displayMode path var, delegating to the service for that exact mode")
    void routesDisplayModeToService() {
        when(highlightService.listPublicHighlights(DisplayMode.LANDING))
                .thenReturn(List.of());

        ResponseEntity<?> r = controller.listPublicHighlights(DisplayMode.LANDING);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(highlightService).listPublicHighlights(DisplayMode.LANDING);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertThat(body).isNotNull();
        // The path var is echoed back as-is - proving the binding is not hardcoded.
        assertThat(body.get("displayMode")).isEqualTo("LANDING");
        assertThat((List<?>) body.get("highlights")).isEmpty();
    }

    @Test
    @DisplayName("preserves the service's list order verbatim and maps each entry to {rank, publication} - never re-sorting by rank")
    void preservesServiceOrderingAndMapsShape() {
        UUID idA = UUID.randomUUID();
        UUID idB = UUID.randomUUID();
        UUID idC = UUID.randomUUID();

        // Ranks deliberately NON-monotonic in list order: a controller that re-sorted
        // by rank would reorder to (B,C,A). The contract is to echo the service order.
        when(highlightService.listPublicHighlights(DisplayMode.APPLICATION))
                .thenReturn(List.of(
                        new PublicHighlight(10, item(idA, "Alpha")),
                        new PublicHighlight(0, item(idB, "Bravo")),
                        new PublicHighlight(5, item(idC, "Charlie"))
                ));

        ResponseEntity<?> r = controller.listPublicHighlights(DisplayMode.APPLICATION);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertThat(body.get("displayMode")).isEqualTo("APPLICATION");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> highlights = (List<Map<String, Object>>) body.get("highlights");
        assertThat(highlights).hasSize(3);

        // Order preserved exactly as the service returned it (A, B, C).
        assertThat(highlights.get(0).get("rank")).isEqualTo(10);
        assertThat(((PublicHighlightItem) highlights.get(0).get("publication")).id()).isEqualTo(idA);
        assertThat(((PublicHighlightItem) highlights.get(0).get("publication")).title()).isEqualTo("Alpha");

        assertThat(highlights.get(1).get("rank")).isEqualTo(0);
        assertThat(((PublicHighlightItem) highlights.get(1).get("publication")).id()).isEqualTo(idB);

        assertThat(highlights.get(2).get("rank")).isEqualTo(5);
        assertThat(((PublicHighlightItem) highlights.get(2).get("publication")).id()).isEqualTo(idC);

        // Each mapped entry exposes ONLY {rank, publication} - no other keys leak through.
        assertThat(highlights.get(0).keySet()).containsExactlyInAnyOrder("rank", "publication");
    }

    @Test
    @DisplayName("empty curated row returns 200 with an empty highlights list (no null body)")
    void emptyHighlightsReturnsEmptyList() {
        when(highlightService.listPublicHighlights(DisplayMode.AGENT))
                .thenReturn(List.of());

        ResponseEntity<?> r = controller.listPublicHighlights(DisplayMode.AGENT);

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) r.getBody();
        assertThat(body.get("displayMode")).isEqualTo("AGENT");
        assertThat((List<?>) body.get("highlights")).isEmpty();
    }
}
