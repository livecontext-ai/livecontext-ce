package com.apimarketplace.orchestrator.service;

import com.apimarketplace.orchestrator.domain.ResourceFavoriteEntity;
import com.apimarketplace.orchestrator.domain.ResourceFavoriteEntity.PK;
import com.apimarketplace.orchestrator.domain.ResourceFavoriteType;
import com.apimarketplace.orchestrator.repository.ResourceFavoriteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Per-user, per-workspace favorites for the caller's own native resources
 * (workflow / table / interface / agent). The native counterpart to
 * publication-service's {@code UserPublicationFavoriteService} (which favorites
 * marketplace publications).
 *
 * <p>Scoping mirrors the marketplace convention: {@code userId} is the caller
 * ({@code X-User-ID}) and {@code organizationId} is the active workspace
 * ({@code X-Organization-ID}). A null/blank workspace normalizes to {@code ""}
 * (personal scope) so personal and per-org favorites stay distinct sets.
 *
 * <p>There is intentionally NO existence validation and NO cross-service call:
 * a favorite is a personal UI preference, so storing a (type, id) the user can
 * already see in their list is enough. An id that no longer resolves simply
 * never matches a listed resource and stays invisible - cheaper and simpler than
 * round-tripping four services to validate every star.
 */
@Service
public class ResourceFavoriteService {

    private final ResourceFavoriteRepository favoriteRepo;

    public ResourceFavoriteService(ResourceFavoriteRepository favoriteRepo) {
        this.favoriteRepo = favoriteRepo;
    }

    /** Personal scope (no active workspace) keys on "" - never null - so the composite PK is well-defined. */
    private static String normOrg(String organizationId) {
        return (organizationId == null || organizationId.isBlank()) ? "" : organizationId;
    }

    /**
     * Mark a resource as favorited for (user, workspace). Idempotent: a second
     * call is a no-op.
     */
    @Transactional
    public void addFavorite(String userId, String organizationId, ResourceFavoriteType type, String resourceId) {
        String org = normOrg(organizationId);
        PK pk = new PK(userId, org, type.name(), resourceId);
        if (!favoriteRepo.existsById(pk)) {
            favoriteRepo.save(new ResourceFavoriteEntity(userId, org, type.name(), resourceId));
        }
    }

    /** Remove a favorite. Idempotent: removing a non-favorite is a no-op (deletes 0 rows). */
    @Transactional
    public void removeFavorite(String userId, String organizationId, ResourceFavoriteType type, String resourceId) {
        favoriteRepo.deleteFavorite(userId, normOrg(organizationId), type.name(), resourceId);
    }

    /** The favorited resource ids of one type for (user, workspace), newest first - for painting card stars. */
    @Transactional(readOnly = true)
    public List<String> listFavoriteIds(String userId, String organizationId, ResourceFavoriteType type) {
        return favoriteRepo.findResourceIds(userId, normOrg(organizationId), type.name());
    }
}
