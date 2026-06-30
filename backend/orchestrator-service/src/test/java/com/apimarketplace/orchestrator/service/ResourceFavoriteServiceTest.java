package com.apimarketplace.orchestrator.service;

import com.apimarketplace.orchestrator.domain.ResourceFavoriteEntity;
import com.apimarketplace.orchestrator.domain.ResourceFavoriteEntity.PK;
import com.apimarketplace.orchestrator.domain.ResourceFavoriteType;
import com.apimarketplace.orchestrator.repository.ResourceFavoriteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResourceFavoriteService")
class ResourceFavoriteServiceTest {

    @Mock
    private ResourceFavoriteRepository favoriteRepo;

    private ResourceFavoriteService service;

    private static final String USER = "user-1";

    @BeforeEach
    void setUp() {
        service = new ResourceFavoriteService(favoriteRepo);
    }

    @Test
    @DisplayName("addFavorite saves a row with the normalized org and the enum name when not already favorited")
    void addFavoriteSavesWhenNew() {
        when(favoriteRepo.existsById(any())).thenReturn(false);

        service.addFavorite(USER, "org-9", ResourceFavoriteType.WORKFLOW, "wf-123");

        ArgumentCaptor<ResourceFavoriteEntity> captor = ArgumentCaptor.forClass(ResourceFavoriteEntity.class);
        verify(favoriteRepo).save(captor.capture());
        ResourceFavoriteEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(USER);
        assertThat(saved.getOrganizationId()).isEqualTo("org-9");
        assertThat(saved.getResourceType()).isEqualTo("WORKFLOW");
        assertThat(saved.getResourceId()).isEqualTo("wf-123");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("addFavorite is idempotent - an existing row is not re-saved")
    void addFavoriteIdempotent() {
        when(favoriteRepo.existsById(any())).thenReturn(true);

        service.addFavorite(USER, "org-9", ResourceFavoriteType.TABLE, "42");

        verify(favoriteRepo, never()).save(any());
    }

    @Test
    @DisplayName("addFavorite with a null workspace keys on the empty-string personal scope")
    void addFavoriteNullOrgNormalizesToEmpty() {
        when(favoriteRepo.existsById(any())).thenReturn(false);

        service.addFavorite(USER, null, ResourceFavoriteType.AGENT, "ag-7");

        // The existence check and the saved row must both use "" for the org component.
        ArgumentCaptor<PK> pkCaptor = ArgumentCaptor.forClass(PK.class);
        verify(favoriteRepo).existsById(pkCaptor.capture());
        assertThat(pkCaptor.getValue()).isEqualTo(new PK(USER, "", "AGENT", "ag-7"));

        ArgumentCaptor<ResourceFavoriteEntity> captor = ArgumentCaptor.forClass(ResourceFavoriteEntity.class);
        verify(favoriteRepo).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEmpty();
    }

    @Test
    @DisplayName("addFavorite with a blank workspace also normalizes to the personal scope")
    void addFavoriteBlankOrgNormalizesToEmpty() {
        when(favoriteRepo.existsById(any())).thenReturn(false);

        service.addFavorite(USER, "   ", ResourceFavoriteType.INTERFACE, "if-1");

        ArgumentCaptor<ResourceFavoriteEntity> captor = ArgumentCaptor.forClass(ResourceFavoriteEntity.class);
        verify(favoriteRepo).save(captor.capture());
        assertThat(captor.getValue().getOrganizationId()).isEmpty();
    }

    @Test
    @DisplayName("removeFavorite delegates the delete with the normalized org and enum name")
    void removeFavoriteDelegates() {
        when(favoriteRepo.deleteFavorite(USER, "org-2", "INTERFACE", "if-5")).thenReturn(1);

        service.removeFavorite(USER, "org-2", ResourceFavoriteType.INTERFACE, "if-5");

        verify(favoriteRepo).deleteFavorite(USER, "org-2", "INTERFACE", "if-5");
    }

    @Test
    @DisplayName("removeFavorite with a null workspace deletes in the personal scope (no-op friendly)")
    void removeFavoriteNullOrg() {
        service.removeFavorite(USER, null, ResourceFavoriteType.WORKFLOW, "wf-x");

        verify(favoriteRepo).deleteFavorite(USER, "", "WORKFLOW", "wf-x");
    }

    @Test
    @DisplayName("listFavoriteIds returns the repository ids for the (user, workspace, type)")
    void listFavoriteIdsDelegates() {
        when(favoriteRepo.findResourceIds(USER, "org-1", "AGENT")).thenReturn(List.of("a", "b"));

        List<String> ids = service.listFavoriteIds(USER, "org-1", ResourceFavoriteType.AGENT);

        assertThat(ids).containsExactly("a", "b");
        verify(favoriteRepo).findResourceIds(USER, "org-1", "AGENT");
    }

    @Test
    @DisplayName("listFavoriteIds maps a null workspace to the personal scope")
    void listFavoriteIdsNullOrg() {
        when(favoriteRepo.findResourceIds(eq(USER), eq(""), eq("TABLE"))).thenReturn(List.of());

        service.listFavoriteIds(USER, null, ResourceFavoriteType.TABLE);

        verify(favoriteRepo).findResourceIds(USER, "", "TABLE");
    }
}
