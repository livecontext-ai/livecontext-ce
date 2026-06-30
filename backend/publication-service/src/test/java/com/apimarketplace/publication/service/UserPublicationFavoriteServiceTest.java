package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.UserPublicationFavoriteEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.dto.PublicHighlightItem;
import com.apimarketplace.publication.repository.UserPublicationFavoriteRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserPublicationFavoriteService")
class UserPublicationFavoriteServiceTest {

    @Mock
    private UserPublicationFavoriteRepository favoriteRepo;

    @Mock
    private WorkflowPublicationRepository publicationRepo;

    private UserPublicationFavoriteService service;

    private static final String USER = "user-1";

    @BeforeEach
    void setUp() {
        service = new UserPublicationFavoriteService(favoriteRepo, publicationRepo);
    }

    private WorkflowPublicationEntity pub(UUID id, PublicationStatus status, PublicationVisibility visibility) {
        WorkflowPublicationEntity p = new WorkflowPublicationEntity();
        p.setId(id);
        p.setStatus(status);
        p.setVisibility(visibility);
        p.setDisplayMode(DisplayMode.APPLICATION);
        return p;
    }

    // ---------- addFavorite ----------

    @Test
    @DisplayName("addFavorite persists a new favorite for an existing publication")
    void addFavoritePersistsNew() {
        UUID id = UUID.randomUUID();
        when(publicationRepo.existsById(id)).thenReturn(true);
        when(favoriteRepo.existsById(any())).thenReturn(false);

        service.addFavorite(USER, "org-7", id);

        verify(favoriteRepo).save(argThat(f ->
                f.getUserId().equals(USER)
                        && f.getOrganizationId().equals("org-7")
                        && f.getPublicationId().equals(id)));
    }

    @Test
    @DisplayName("addFavorite is idempotent - an already-favorited publication is not saved again")
    void addFavoriteIdempotent() {
        UUID id = UUID.randomUUID();
        when(publicationRepo.existsById(id)).thenReturn(true);
        when(favoriteRepo.existsById(any())).thenReturn(true);

        service.addFavorite(USER, "org-7", id);

        verify(favoriteRepo, never()).save(any());
    }

    @Test
    @DisplayName("addFavorite rejects an unknown publication with a stable error and never writes")
    void addFavoriteRejectsUnknown() {
        UUID id = UUID.randomUUID();
        when(publicationRepo.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> service.addFavorite(USER, "org-7", id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("INVALID_OR_INACCESSIBLE_PUBLICATION");

        verify(favoriteRepo, never()).existsById(any());
        verify(favoriteRepo, never()).save(any());
    }

    @Test
    @DisplayName("addFavorite normalizes a null workspace to \"\" (personal scope) in the persisted key")
    void addFavoriteNormalizesNullOrg() {
        UUID id = UUID.randomUUID();
        when(publicationRepo.existsById(id)).thenReturn(true);
        when(favoriteRepo.existsById(any())).thenReturn(false);

        service.addFavorite(USER, null, id);

        verify(favoriteRepo).save(argThat(f -> f.getOrganizationId().equals("")));
    }

    @Test
    @DisplayName("addFavorite normalizes a blank workspace to \"\" (personal scope)")
    void addFavoriteNormalizesBlankOrg() {
        UUID id = UUID.randomUUID();
        when(publicationRepo.existsById(id)).thenReturn(true);
        when(favoriteRepo.existsById(any())).thenReturn(false);

        service.addFavorite(USER, "   ", id);

        verify(favoriteRepo).save(argThat(f -> f.getOrganizationId().equals("")));
    }

    // ---------- removeFavorite ----------

    @Test
    @DisplayName("removeFavorite delegates a scoped delete (workspace preserved)")
    void removeFavoriteDelegates() {
        UUID id = UUID.randomUUID();
        when(favoriteRepo.deleteFavorite(USER, "org-7", id)).thenReturn(1);

        service.removeFavorite(USER, "org-7", id);

        verify(favoriteRepo).deleteFavorite(USER, "org-7", id);
    }

    @Test
    @DisplayName("removeFavorite is idempotent - deleting a non-favorite (0 rows) does not throw")
    void removeFavoriteIdempotent() {
        UUID id = UUID.randomUUID();
        when(favoriteRepo.deleteFavorite(eq(USER), eq(""), eq(id))).thenReturn(0);

        service.removeFavorite(USER, null, id); // null org normalized to ""

        verify(favoriteRepo).deleteFavorite(USER, "", id);
    }

    // ---------- listFavoriteIds ----------

    @Test
    @DisplayName("listFavoriteIds delegates with the normalized workspace and returns ids in order")
    void listFavoriteIdsDelegates() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(favoriteRepo.findPublicationIds(USER, "")).thenReturn(List.of(a, b));

        List<UUID> result = service.listFavoriteIds(USER, "  ");

        assertThat(result).containsExactly(a, b);
        verify(favoriteRepo).findPublicationIds(USER, "");
    }

    // ---------- listFavorites ----------

    @Test
    @DisplayName("listFavorites returns empty without hitting the publication repo when there are no favorites")
    void listFavoritesEmpty() {
        when(favoriteRepo.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(USER, ""))
                .thenReturn(List.of());

        List<PublicHighlightItem> result = service.listFavorites(USER, null);

        assertThat(result).isEmpty();
        verify(publicationRepo, never()).findAllById(any());
    }

    @Test
    @DisplayName("listFavorites drops non-ACTIVE publications (deactivated/rejected) so the row never renders a broken card")
    void listFavoritesDropsInactive() {
        UUID active = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        when(favoriteRepo.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(USER, ""))
                .thenReturn(List.of(
                        new UserPublicationFavoriteEntity(USER, "", active),
                        new UserPublicationFavoriteEntity(USER, "", inactive)));
        when(publicationRepo.findAllById(List.of(active, inactive))).thenReturn(List.of(
                pub(active, PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC),
                pub(inactive, PublicationStatus.INACTIVE, PublicationVisibility.PUBLIC)));

        List<PublicHighlightItem> result = service.listFavorites(USER, "");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(active);
    }

    @Test
    @DisplayName("listFavorites KEEPS private/unlisted apps - a personal list is not visibility-gated (unlike highlights)")
    void listFavoritesKeepsPrivate() {
        UUID privateApp = UUID.randomUUID();
        when(favoriteRepo.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(USER, ""))
                .thenReturn(List.of(new UserPublicationFavoriteEntity(USER, "", privateApp)));
        when(publicationRepo.findAllById(List.of(privateApp))).thenReturn(List.of(
                pub(privateApp, PublicationStatus.ACTIVE, PublicationVisibility.PRIVATE)));

        List<PublicHighlightItem> result = service.listFavorites(USER, "");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(privateApp);
    }

    @Test
    @DisplayName("listFavorites preserves newest-favorited-first order regardless of the publication repo's order")
    void listFavoritesPreservesOrder() {
        UUID first = UUID.randomUUID();  // favorited most recently
        UUID second = UUID.randomUUID();
        when(favoriteRepo.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(USER, ""))
                .thenReturn(List.of(
                        new UserPublicationFavoriteEntity(USER, "", first),
                        new UserPublicationFavoriteEntity(USER, "", second)));
        // repo returns them in the opposite (arbitrary) order
        when(publicationRepo.findAllById(List.of(first, second))).thenReturn(List.of(
                pub(second, PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC),
                pub(first, PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC)));

        List<PublicHighlightItem> result = service.listFavorites(USER, "");

        assertThat(result).extracting(PublicHighlightItem::id).containsExactly(first, second);
    }

    @Test
    @DisplayName("listFavorites drops a stale favorite whose publication no longer resolves")
    void listFavoritesDropsMissing() {
        UUID present = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(favoriteRepo.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(USER, ""))
                .thenReturn(List.of(
                        new UserPublicationFavoriteEntity(USER, "", present),
                        new UserPublicationFavoriteEntity(USER, "", missing)));
        when(publicationRepo.findAllById(List.of(present, missing))).thenReturn(List.of(
                pub(present, PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC)));

        List<PublicHighlightItem> result = service.listFavorites(USER, "");

        assertThat(result).extracting(PublicHighlightItem::id).containsExactly(present);
    }

    @Test
    @DisplayName("listFavorites returns the slim PublicHighlightItem DTO (no heavy jsonb)")
    void listFavoritesReturnsSlimDto() {
        UUID id = UUID.randomUUID();
        when(favoriteRepo.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(USER, ""))
                .thenReturn(List.of(new UserPublicationFavoriteEntity(USER, "", id)));
        WorkflowPublicationEntity p = pub(id, PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC);
        p.setTitle("My favorite app");
        when(publicationRepo.findAllById(List.of(id))).thenReturn(List.of(p));

        List<PublicHighlightItem> result = service.listFavorites(USER, "");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(PublicHighlightItem.class);
        assertThat(result.get(0).title()).isEqualTo("My favorite app");
    }
}
