package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.UserPublicationFavoriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Direct method-call controller tests (the module's convention - no MockMvc).
 * Routing precedence of the literal {@code /favorites} over {@code /{publicationId}}
 * is a Spring framework guarantee already exercised by sibling literals like
 * {@code /my} and {@code /marketplace} on WorkflowPublicationController, so it is
 * not re-tested here; these cover the controller's own contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationFavoriteController")
class PublicationFavoriteControllerTest {

    @Mock
    private UserPublicationFavoriteService favoriteService;

    private PublicationFavoriteController controller;

    private static final String USER = "user-1";

    @BeforeEach
    void setUp() {
        controller = new PublicationFavoriteController(favoriteService);
    }

    @Test
    @DisplayName("POST favorite delegates to the service and returns 204")
    void favoriteDelegates() {
        UUID id = UUID.randomUUID();

        ResponseEntity<?> r = controller.favorite(USER, "org-3", id.toString());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(favoriteService).addFavorite(USER, "org-3", id);
    }

    @Test
    @DisplayName("POST favorite with a malformed id returns 400 with the stable code and never calls the service")
    void favoriteMalformedId() {
        ResponseEntity<?> r = controller.favorite(USER, null, "not-a-uuid");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().toString()).contains("INVALID_OR_INACCESSIBLE_PUBLICATION");
        verify(favoriteService, never()).addFavorite(anyString(), any(), any());
    }

    @Test
    @DisplayName("POST favorite translates a service IllegalArgumentException into 400 with its stable code")
    void favoriteServiceRejection() {
        UUID id = UUID.randomUUID();
        doThrow(new IllegalArgumentException("INVALID_OR_INACCESSIBLE_PUBLICATION"))
                .when(favoriteService).addFavorite(anyString(), any(), any());

        ResponseEntity<?> r = controller.favorite(USER, "org-3", id.toString());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(r.getBody().toString()).contains("INVALID_OR_INACCESSIBLE_PUBLICATION");
    }

    @Test
    @DisplayName("DELETE favorite delegates to the service and returns 204")
    void unfavoriteDelegates() {
        UUID id = UUID.randomUUID();

        ResponseEntity<?> r = controller.unfavorite(USER, "org-3", id.toString());

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(favoriteService).removeFavorite(USER, "org-3", id);
    }

    @Test
    @DisplayName("DELETE favorite with a malformed id returns 400 and never calls the service")
    void unfavoriteMalformedId() {
        ResponseEntity<?> r = controller.unfavorite(USER, null, "bad");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(favoriteService, never()).removeFavorite(anyString(), any(), any());
    }

    @Test
    @DisplayName("GET favorites returns 200 with a 'favorites' payload from the service")
    void listFavorites() {
        when(favoriteService.listFavorites(USER, "org-3")).thenReturn(List.of());

        ResponseEntity<?> r = controller.listFavorites(USER, "org-3");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().toString()).contains("favorites");
        verify(favoriteService).listFavorites(USER, "org-3");
    }

    @Test
    @DisplayName("GET favorites/ids returns 200 with an 'ids' payload from the service")
    void listFavoriteIds() {
        UUID id = UUID.randomUUID();
        when(favoriteService.listFavoriteIds(USER, "org-3")).thenReturn(List.of(id));

        ResponseEntity<?> r = controller.listFavoriteIds(USER, "org-3");

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody().toString()).contains("ids");
        verify(favoriteService).listFavoriteIds(USER, "org-3");
    }
}
