package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.UserPublicationFavoriteEntity;
import com.apimarketplace.publication.domain.UserPublicationFavoriteEntity.PK;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.dto.PublicHighlightItem;
import com.apimarketplace.publication.repository.UserPublicationFavoriteRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-user, per-workspace favorite applications - the personal counterpart to the
 * admin-curated {@link PublicationHighlightService}. A user stars an application and
 * it surfaces in the "Favorites" view of the Home row.
 *
 * <p>Scoping mirrors the marketplace ownership convention: {@code userId} is the
 * caller ({@code X-User-ID}) and {@code organizationId} is the active workspace
 * ({@code X-Organization-ID}). A null/blank workspace normalizes to {@code ""}
 * (personal scope) so personal and per-org favorites stay distinct sets, exactly
 * like {@code conversation.user_chat_defaults}.
 *
 * <p>Unlike highlights there is intentionally NO Caffeine cache: favorites are
 * per-user and written interactively, so a shared cache would neither help nor be
 * correct.
 */
@Service
public class UserPublicationFavoriteService {

    private final UserPublicationFavoriteRepository favoriteRepo;
    private final WorkflowPublicationRepository publicationRepo;

    public UserPublicationFavoriteService(UserPublicationFavoriteRepository favoriteRepo,
                                          WorkflowPublicationRepository publicationRepo) {
        this.favoriteRepo = favoriteRepo;
        this.publicationRepo = publicationRepo;
    }

    /** Personal scope (no active workspace) keys on "" - never null - so the composite PK is well-defined. */
    private static String normOrg(String organizationId) {
        return (organizationId == null || organizationId.isBlank()) ? "" : organizationId;
    }

    /**
     * Mark a publication as favorited for (user, workspace). Idempotent: a second
     * call is a no-op. The publication must exist locally; favorites are a personal
     * list so visibility is NOT gated (a user may favorite their own private app),
     * but an unknown id is rejected with a clean error instead of leaving the
     * foreign key to raise a constraint violation.
     *
     * @throws IllegalArgumentException {@code INVALID_OR_INACCESSIBLE_PUBLICATION} when the id is unknown.
     */
    @Transactional
    public void addFavorite(String userId, String organizationId, UUID publicationId) {
        String org = normOrg(organizationId);
        if (!publicationRepo.existsById(publicationId)) {
            throw new IllegalArgumentException("INVALID_OR_INACCESSIBLE_PUBLICATION");
        }
        if (!favoriteRepo.existsById(new PK(userId, org, publicationId))) {
            favoriteRepo.save(new UserPublicationFavoriteEntity(userId, org, publicationId));
        }
    }

    /** Remove a favorite. Idempotent: removing a non-favorite is a no-op (deletes 0 rows). */
    @Transactional
    public void removeFavorite(String userId, String organizationId, UUID publicationId) {
        favoriteRepo.deleteFavorite(userId, normOrg(organizationId), publicationId);
    }

    /** Just the favorited publication ids for (user, workspace), newest first - for painting card stars. */
    @Transactional(readOnly = true)
    public List<UUID> listFavoriteIds(String userId, String organizationId) {
        return favoriteRepo.findPublicationIds(userId, normOrg(organizationId));
    }

    /**
     * The caller's favorited applications, hydrated to the slim {@link PublicHighlightItem}
     * card DTO and ordered newest-favorited-first. Favorites whose publication no longer
     * exists or is no longer {@code ACTIVE} (deleted/rejected/deactivated) are dropped so
     * the row never renders a broken card. Visibility is NOT filtered: this is the user's
     * own list, so their private/unlisted apps still show.
     */
    @Transactional(readOnly = true)
    public List<PublicHighlightItem> listFavorites(String userId, String organizationId) {
        List<UserPublicationFavoriteEntity> favorites =
                favoriteRepo.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(userId, normOrg(organizationId));
        if (favorites.isEmpty()) return List.of();

        List<UUID> ids = favorites.stream().map(UserPublicationFavoriteEntity::getPublicationId).toList();
        Map<UUID, WorkflowPublicationEntity> byId = new LinkedHashMap<>();
        for (WorkflowPublicationEntity pub : publicationRepo.findAllById(ids)) {
            if (pub.getStatus() == PublicationStatus.ACTIVE) {
                byId.put(pub.getId(), pub);
            }
        }

        return favorites.stream()
                .filter(f -> byId.containsKey(f.getPublicationId()))
                .map(f -> PublicHighlightItem.from(byId.get(f.getPublicationId())))
                .toList();
    }
}
