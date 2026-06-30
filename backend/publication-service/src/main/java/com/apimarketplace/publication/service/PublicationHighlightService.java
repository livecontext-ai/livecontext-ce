package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.PublicationHighlightEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.dto.PublicHighlightItem;
import com.apimarketplace.publication.repository.PublicationHighlightRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Curation logic for marketplace highlights.
 * <ul>
 *   <li>Public reads are cached (Caffeine, 60s) and filtered to {@code ACTIVE +
 *       PUBLIC} so unpublished/rejected/deleted publications stop showing up
 *       even if they were highlighted before status change.</li>
 *   <li>Admin writes are atomic (DELETE-then-INSERT in a single transaction);
 *       the DEFERRABLE UNIQUE constraint absorbs transient duplicate ranks
 *       within the transaction.</li>
 *   <li>Validation is all-or-nothing: a single unknown / non-PUBLIC / wrong-mode
 *       id rolls back the entire reorder.</li>
 * </ul>
 */
@Service
public class PublicationHighlightService {

    private static final String CACHE_NAME = "highlightsByMode";

    private final PublicationHighlightRepository highlightRepo;
    private final WorkflowPublicationRepository publicationRepo;

    public PublicationHighlightService(PublicationHighlightRepository highlightRepo,
                                        WorkflowPublicationRepository publicationRepo) {
        this.highlightRepo = highlightRepo;
        this.publicationRepo = publicationRepo;
    }

    /**
     * Public read - only ACTIVE + PUBLIC highlighted publications, in admin-set order,
     * projected to a slim {@link PublicHighlightItem} DTO. The full entity (which
     * includes large jsonb columns like {@code planSnapshot} / {@code agentSnapshot})
     * is NEVER serialized to anonymous callers - those are an implementation detail
     * and would otherwise both bloat the cached payload and leak workflow internals.
     * Cached per displayMode for 60s.
     */
    @Cacheable(value = CACHE_NAME, cacheManager = "highlightsCacheManager", key = "#p0.name()")
    @Transactional(readOnly = true)
    public List<PublicHighlight> listPublicHighlights(DisplayMode displayMode) {
        List<PublicationHighlightEntity> highlights = highlightRepo.findByDisplayModeOrderByRankAsc(displayMode);
        if (highlights.isEmpty()) return List.of();

        List<UUID> ids = highlights.stream().map(PublicationHighlightEntity::getPublicationId).toList();
        Map<UUID, WorkflowPublicationEntity> byId = new LinkedHashMap<>();
        for (WorkflowPublicationEntity pub : publicationRepo.findAllById(ids)) {
            if (pub.getStatus() == PublicationStatus.ACTIVE
                    && pub.getVisibility() == PublicationVisibility.PUBLIC) {
                byId.put(pub.getId(), pub);
            }
        }

        return highlights.stream()
                .filter(h -> byId.containsKey(h.getPublicationId()))
                .map(h -> new PublicHighlight(h.getRank(),
                        PublicHighlightItem.from(byId.get(h.getPublicationId()))))
                .toList();
    }

    /**
     * Admin read - same data plus stale rows (rank known, publication may be
     * inactive/rejected/missing - surfaced so the admin can clean up).
     */
    @Transactional(readOnly = true)
    public List<HighlightedPublication> listAdminHighlights(DisplayMode displayMode) {
        List<PublicationHighlightEntity> highlights = highlightRepo.findByDisplayModeOrderByRankAsc(displayMode);
        if (highlights.isEmpty()) return List.of();

        List<UUID> ids = highlights.stream().map(PublicationHighlightEntity::getPublicationId).toList();
        Map<UUID, WorkflowPublicationEntity> byId = new LinkedHashMap<>();
        for (WorkflowPublicationEntity pub : publicationRepo.findAllById(ids)) {
            byId.put(pub.getId(), pub);
        }

        return highlights.stream()
                .map(h -> new HighlightedPublication(h.getRank(), byId.get(h.getPublicationId())))
                .toList();
    }

    /**
     * Replace the full ordered list of highlights for the given display_mode.
     * <p>
     * Validation is all-or-nothing: any duplicate / unknown / non-ACTIVE-PUBLIC /
     * wrong-displayMode id triggers a rollback before any write.
     * <p>
     * <b>Cache eviction caveat:</b> {@link CacheEvict} runs <i>before</i> the
     * surrounding transaction commits. A concurrent reader between evict and
     * commit may re-populate the cache with the pre-write data and serve it
     * for up to the TTL (60s). Acceptable for this use case - admin curation
     * is rare, the staleness window is small, and the next read after TTL
     * settles on the new value. If this becomes a problem we can switch to
     * post-commit eviction via {@code TransactionSynchronizationManager}.
     *
     * @throws IllegalArgumentException with a stable error code on validation failure
     */
    @CacheEvict(value = CACHE_NAME, cacheManager = "highlightsCacheManager", key = "#p0.name()")
    @Transactional
    public void replaceHighlights(DisplayMode displayMode, List<UUID> orderedIds, String adminUserId) {
        if (orderedIds == null) {
            throw new IllegalArgumentException("MISSING_ORDERED_IDS");
        }
        // dedup check - preserves order for a clean error path
        LinkedHashSet<UUID> uniq = new LinkedHashSet<>(orderedIds);
        if (uniq.size() != orderedIds.size()) {
            throw new IllegalArgumentException("DUPLICATE_IDS");
        }

        if (!orderedIds.isEmpty()) {
            // Single SELECT to verify all ids exist, are ACTIVE+PUBLIC, and belong
            // to the requested display_mode. Anything missing -> 400.
            List<WorkflowPublicationEntity> found = publicationRepo.findAllById(orderedIds);
            if (found.size() != orderedIds.size()) {
                throw new IllegalArgumentException("INVALID_OR_INACCESSIBLE_PUBLICATIONS");
            }
            DisplayMode requiredMode = requiredPublicationMode(displayMode);
            for (WorkflowPublicationEntity pub : found) {
                if (pub.getStatus() != PublicationStatus.ACTIVE
                        || pub.getVisibility() != PublicationVisibility.PUBLIC
                        || pub.getDisplayMode() != requiredMode) {
                    throw new IllegalArgumentException("INVALID_OR_INACCESSIBLE_PUBLICATIONS");
                }
            }
        }

        // Atomic re-rank: bulk delete (flush+clear) then sequential inserts at rank=index.
        highlightRepo.deleteAllByDisplayModeBulk(displayMode);
        for (int i = 0; i < orderedIds.size(); i++) {
            highlightRepo.save(new PublicationHighlightEntity(displayMode, orderedIds.get(i), i, adminUserId));
        }
    }

    /**
     * Which publication {@code displayMode} a highlight bucket accepts. Buckets are
     * normally 1:1 with the publication type (the {@code APPLICATION} bucket holds
     * {@code APPLICATION} publications, etc.). The {@code LANDING} bucket is the one
     * exception: it is the curated row driving the public landing page and it holds
     * {@code APPLICATION}-type publications (no publication is ever of type LANDING).
     */
    static DisplayMode requiredPublicationMode(DisplayMode bucket) {
        return bucket == DisplayMode.LANDING ? DisplayMode.APPLICATION : bucket;
    }

    public record HighlightedPublication(int rank, WorkflowPublicationEntity publication) {
    }

    /** Slim public-facing variant - see {@link PublicHighlightItem}. */
    public record PublicHighlight(int rank, PublicHighlightItem publication) {
    }
}
