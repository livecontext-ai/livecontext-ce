package com.apimarketplace.orchestrator.controllers;

import com.apimarketplace.orchestrator.domain.ResourceFavoriteType;
import com.apimarketplace.orchestrator.service.ResourceFavoriteService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Direct method-call controller tests (the module convention - no MockMvc).
 * These cover the controller's own contract: resource-type parsing (including
 * the case-insensitive and unknown-type paths), delegation, and response shapes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResourceFavoriteController")
class ResourceFavoriteControllerTest {

    @Mock
    private ResourceFavoriteService favoriteService;

    private ResourceFavoriteController controller;

    private static final String USER = "user-1";

    @BeforeEach
    void setUp() {
        controller = new ResourceFavoriteController(favoriteService);
    }

    @Test
    @DisplayName("POST favorite parses the type and delegates, returning 204")
    void favoriteDelegates() {
        ResponseEntity<?> r = controller.favorite(USER, "org-3", "workflow", "wf-1");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(favoriteService).addFavorite(USER, "org-3", ResourceFavoriteType.WORKFLOW, "wf-1");
    }

    @Test
    @DisplayName("POST favorite accepts a mixed-case type segment")
    void favoriteCaseInsensitiveType() {
        ResponseEntity<?> r = controller.favorite(USER, null, "InTeRfAcE", "if-9");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(favoriteService).addFavorite(USER, null, ResourceFavoriteType.INTERFACE, "if-9");
    }

    @Test
    @DisplayName("POST favorite with an unknown type returns 400 and never touches the service")
    void favoriteUnknownType() {
        ResponseEntity<?> r = controller.favorite(USER, "org-3", "banana", "x");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody()).isEqualTo(Map.of("error", "INVALID_RESOURCE_TYPE"));
        verifyNoInteractions(favoriteService);
    }

    @Test
    @DisplayName("DELETE unfavorite parses the type and delegates, returning 204")
    void unfavoriteDelegates() {
        ResponseEntity<?> r = controller.unfavorite(USER, "org-3", "agent", "ag-2");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(favoriteService).removeFavorite(USER, "org-3", ResourceFavoriteType.AGENT, "ag-2");
    }

    @Test
    @DisplayName("DELETE unfavorite with an unknown type returns 400 and never touches the service")
    void unfavoriteUnknownType() {
        ResponseEntity<?> r = controller.unfavorite(USER, "org-3", "nope", "x");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(favoriteService, never()).removeFavorite(anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("GET ids returns the service ids wrapped under the 'ids' key")
    void listIdsReturnsWrappedIds() {
        when(favoriteService.listFavoriteIds(USER, "org-1", ResourceFavoriteType.TABLE))
                .thenReturn(List.of("10", "20"));

        ResponseEntity<?> r = controller.listFavoriteIds(USER, "org-1", "table");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).isEqualTo(Map.of("ids", List.of("10", "20")));
    }

    @Test
    @DisplayName("GET ids with an unknown type returns 400 and never touches the service")
    void listIdsUnknownType() {
        ResponseEntity<?> r = controller.listFavoriteIds(USER, "org-1", "files");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(favoriteService);
    }
}
