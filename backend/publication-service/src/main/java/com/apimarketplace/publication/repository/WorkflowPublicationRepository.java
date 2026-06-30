package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WorkflowPublicationRepository extends JpaRepository<WorkflowPublicationEntity, UUID> {

    Optional<WorkflowPublicationEntity> findByWorkflowId(UUID workflowId);

    boolean existsByWorkflowId(UUID workflowId);

    Optional<WorkflowPublicationEntity> findByAgentConfigId(UUID agentConfigId);

    boolean existsByAgentConfigId(UUID agentConfigId);

    void deleteByAgentConfigId(UUID agentConfigId);

    Optional<WorkflowPublicationEntity> findByPublicationTypeAndResourceId(PublicationType publicationType, String resourceId);

    boolean existsByPublicationTypeAndResourceId(PublicationType publicationType, String resourceId);

    List<WorkflowPublicationEntity> findByPublisherId(String publisherId);

    List<WorkflowPublicationEntity> findByPublisherIdOrderByPublishedAtDesc(String publisherId);

    List<WorkflowPublicationEntity> findByPublisherIdAndStatus(String publisherId, PublicationStatus status);

    // ========================================================================
    // V223 / #151 - owner-scope finders (owner_type + owner_id dual-write)
    //
    // These replace the legacy `publisher_id`-only finders for "my
    // publications" listings. A USER-owned publication is visible only to its
    // publisher; an ORG-owned publication is visible to every teammate of the
    // owning organization. Mirrors PR27/PR30 strict-scope finder naming.
    // ========================================================================

    /**
     * Strictly-scoped list - all rows owned by exactly this scope
     * (USER/userId or ORG/organizationId), most recent first.
     */
    List<WorkflowPublicationEntity> findByOwnerTypeAndOwnerIdOrderByPublishedAtDesc(
            OwnerType ownerType, String ownerId);

    /**
     * Paged variant of {@link #findByOwnerTypeAndOwnerIdOrderByPublishedAtDesc}.
     */
    Page<WorkflowPublicationEntity> findByOwnerTypeAndOwnerIdOrderByPublishedAtDesc(
            OwnerType ownerType, String ownerId, Pageable pageable);

    /** Count for quota / dashboard. */
    long countByOwnerTypeAndOwnerId(OwnerType ownerType, String ownerId);

    @Query("SELECT p FROM WorkflowPublicationEntity p WHERE p.status = :status AND p.visibility = :visibility ORDER BY p.publishedAt DESC")
    List<WorkflowPublicationEntity> findPublicActivePublications(
            @Param("status") PublicationStatus status,
            @Param("visibility") PublicationVisibility visibility);

    @Query("SELECT p FROM WorkflowPublicationEntity p WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC' ORDER BY p.publishedAt DESC")
    Page<WorkflowPublicationEntity> findMarketplacePublications(Pageable pageable);

    @Query("SELECT p FROM WorkflowPublicationEntity p WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC' ORDER BY p.useCount DESC")
    List<WorkflowPublicationEntity> findPopularPublications(Pageable pageable);

    @Query("SELECT p FROM WorkflowPublicationEntity p WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC' AND LOWER(p.title) LIKE LOWER(CONCAT('%', :search, '%')) ORDER BY p.publishedAt DESC")
    List<WorkflowPublicationEntity> searchByTitle(@Param("search") String search);

    @Query("SELECT p FROM WorkflowPublicationEntity p WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC' AND p.categoryId = :categoryId ORDER BY p.publishedAt DESC")
    Page<WorkflowPublicationEntity> findMarketplacePublicationsByCategory(
            @Param("categoryId") UUID categoryId, Pageable pageable);

    @Query(value = "SELECT p.* FROM workflow_publications p WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC' AND p.category_slug = :categorySlug ORDER BY p.published_at DESC",
           countQuery = "SELECT COUNT(*) FROM workflow_publications p WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC' AND p.category_slug = :categorySlug",
           nativeQuery = true)
    Page<WorkflowPublicationEntity> findMarketplacePublicationsByCategorySlug(
            @Param("categorySlug") String categorySlug, Pageable pageable);

    /**
     * Marketplace search - PR5 (V227) full-text + trigram.
     *
     * <p>Three OR-branches give the agent typo tolerance, stemming, and fallback:
     * <ul>
     *   <li>{@code plainto_tsquery('english', :q)} - AND-joins tokens after English
     *       stemming ({@code "emails"→"email"}, {@code "sending"→"send"}). Best
     *       precision when the query phrase is well-formed.</li>
     *   <li>{@code to_tsquery('simple', :compactQuery)} - OR-joins verbatim tokens
     *       (e.g. {@code "flights|to|thailand"}). Recall fallback when stemming
     *       misses or the query mixes languages. Caller sanitizes tokens
     *       ({@code [^\p{L}\p{N}]+} stripped) and passes {@code null} when no
     *       usable tokens remain - the SQL guard skips the branch on null.</li>
     *   <li>{@code catalog.word_similarity(:q, search_text) > 0.3} - pg_trgm fuzzy
     *       fallback for typos ({@code "gmial"→Gmail}). Uses {@code word_similarity}
     *       (not {@code similarity}) because {@code search_text} is a long
     *       concatenation (~250+ chars) while {@code :q} is short (1-3 words):
     *       plain {@code similarity()} compares whole strings and saturates near 0
     *       for that ratio, so it never crosses any usable threshold. The catalog
     *       {@code LexicalSearchIndexRepository} uses plain {@code similarity()}
     *       only because it scores against SHORT fields (provider, tool_name,
     *       action - single tokens), not the concatenated long text.
     *       Schema-qualified because {@code pg_trgm} lives in the {@code catalog}
     *       schema and is not in publication-service's default search_path.</li>
     * </ul>
     *
     * <p>Ranking: {@code GREATEST(rank_en, rank_simple, word_similarity)} as the
     * primary score so the strongest signal among the three branches wins, then
     * {@code use_count} (popularity) and {@code published_at} (recency) as
     * tiebreakers - preserving the pre-PR5 ordering on ts_rank ties.
     */
    @Query(value = """
        SELECT p.* FROM publication.workflow_publications p
        WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'
          AND (
                p.tsv_search @@ plainto_tsquery('english', :q)
             OR (:compactQuery IS NOT NULL AND p.tsv_search @@ to_tsquery('simple', cast(:compactQuery AS text)))
             OR catalog.word_similarity(cast(:q AS text), p.search_text) > 0.3
          )
        ORDER BY
          GREATEST(
            ts_rank(p.tsv_search, plainto_tsquery('english', :q)),
            CASE WHEN :compactQuery IS NULL THEN 0
                 ELSE ts_rank(p.tsv_search, to_tsquery('simple', cast(:compactQuery AS text)))
            END,
            catalog.word_similarity(cast(:q AS text), p.search_text)
          ) DESC,
          p.use_count DESC,
          p.published_at DESC
        """,
        countQuery = """
        SELECT COUNT(*) FROM publication.workflow_publications p
        WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'
          AND (
                p.tsv_search @@ plainto_tsquery('english', :q)
             OR (:compactQuery IS NOT NULL AND p.tsv_search @@ to_tsquery('simple', cast(:compactQuery AS text)))
             OR catalog.word_similarity(cast(:q AS text), p.search_text) > 0.3
          )
        """,
        nativeQuery = true)
    Page<WorkflowPublicationEntity> searchMarketplace(
        @Param("q") String q,
        @Param("compactQuery") String compactQuery,
        Pageable pageable);

    long countByPublisherId(String publisherId);

    long countByPublisherIdAndStatus(String publisherId, PublicationStatus status);

    @Query("SELECT p.workflowId FROM WorkflowPublicationEntity p WHERE p.workflowId IN :workflowIds AND p.status = 'ACTIVE'")
    List<UUID> findPublishedWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);

    /**
     * Batch lookup of (workflowId → publicationId) for ACTIVE publications.
     *
     * <p>Companion to {@link #findPublishedWorkflowIds}: agent-facing tools
     * (e.g. {@code workflow(action='list')}) need the publication ID directly
     * to render {@code "open_app"} hints, not just a boolean "is published"
     * flag. Returned shape is {@code [[workflowId, publicationId], ...]} -
     * the controller transforms into a {@code Map<UUID, UUID>} for the
     * HTTP client.
     */
    @Query("SELECT p.workflowId, p.id FROM WorkflowPublicationEntity p WHERE p.workflowId IN :workflowIds AND p.status = 'ACTIVE'")
    List<Object[]> findActivePublicationIdsByWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);

    /**
     * Batch lookup of (workflowId → publication status) for every still-shared
     * publication (ACTIVE, PENDING_REVIEW, REJECTED). Unlike
     * {@link #findPublishedWorkflowIds} (ACTIVE-only boolean), this exposes the
     * full moderation state so the workflow list can show a distinct
     * "shared · in review" / "rejected" badge instead of only the approved
     * "shared" marker. INACTIVE (unpublished / soft-deleted) rows are excluded -
     * they read as "not shared" everywhere. Returned shape is
     * {@code [[workflowId, status], ...]}; the controller folds it into a
     * {@code Map<UUID, String>}.
     */
    @Query("SELECT p.workflowId, p.status FROM WorkflowPublicationEntity p WHERE p.workflowId IN :workflowIds AND p.status <> 'INACTIVE'")
    List<Object[]> findPublicationStatusesByWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);

    /**
     * Batch lookup of (publicationId → status) keyed by the publication's OWN id, for ALL
     * statuses INCLUDING INACTIVE. Companion to {@link #findPublicationStatusesByWorkflowIds}
     * (which keys by the source workflowId and hides INACTIVE): the applications board lists
     * raw APPLICATION-type workflow rows by their {@code sourcePublicationId}, and must detect
     * INACTIVE publications to EXCLUDE their leftover acquired/published instances - an
     * unpublished app must not linger on the board (whereas {@code /app/applications} already
     * filters them out). Returned shape is {@code [[publicationId, status], ...]}.
     */
    @Query("SELECT p.id, p.status FROM WorkflowPublicationEntity p WHERE p.id IN :publicationIds")
    List<Object[]> findStatusesByPublicationIds(@Param("publicationIds") Collection<UUID> publicationIds);

    /**
     * Batch lookup of (workflowId → [publicationId, status]) for the caller's own
     * <em>application</em> publications - i.e. WORKFLOW publications backed by a showcase
     * interface, the exact predicate {@code /app/applications} (applicationOnly) uses
     * (see {@link com.apimarketplace.publication.service.PublicationListQueryService#findByScope}).
     * INACTIVE (unpublished) rows are excluded so a withdrawn app never resurfaces.
     *
     * <p>Lets the orchestrator applications board surface a publisher's OWN published
     * applications next to the acquired ones - without it the board only ever showed
     * APPLICATION-type (acquired) rows, so a self-published app was missing from the
     * Applications board and lingered only as a plain workflow card. Source workflows are
     * WORKFLOW-type (their {@code WorkflowEntity.sourcePublicationId} is null), so the board
     * has no other way to learn the publication id. Returned shape is
     * {@code [[workflowId, publicationId, status, showcaseInterfaceId, showcaseRunId], ...]} - one
     * row per workflow (a workflow has at most one publication, see {@link #findByWorkflowId}). The
     * showcase ids let the applications board render the publisher's OWN app via the authenticated
     * per-run path (valid at any publication visibility), exactly like {@code /app/applications}.
     */
    @Query("SELECT p.workflowId, p.id, p.status, p.showcaseInterfaceId, p.showcaseRunId FROM WorkflowPublicationEntity p "
            + "WHERE p.workflowId IN :workflowIds "
            + "AND p.publicationType = 'WORKFLOW' "
            + "AND p.showcaseInterfaceId IS NOT NULL "
            + "AND p.status <> 'INACTIVE'")
    List<Object[]> findApplicationPublicationsByWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);

    /**
     * Batch lookup of (workflowId → marketplace visibility: PUBLIC / PRIVATE / UNLISTED) for the
     * caller's still-shared publications (INACTIVE excluded - an unpublished workflow has no
     * visibility to surface). Keyed by the source workflowId so BOTH the workflows board and the
     * applications board can mark each own card with a public / private indicator and filter on it,
     * exactly like {@code /app/applications} does with {@code WorkflowPublication.visibility}.
     * Returned shape is {@code [[workflowId, visibility], ...]}; the controller folds it into a
     * {@code Map<UUID, String>}. A workflow has at most one publication (see {@link #findByWorkflowId}).
     */
    @Query("SELECT p.workflowId, p.visibility FROM WorkflowPublicationEntity p WHERE p.workflowId IN :workflowIds AND p.status <> 'INACTIVE'")
    List<Object[]> findPublicationVisibilitiesByWorkflowIds(@Param("workflowIds") Collection<UUID> workflowIds);

    /**
     * Batch lookup of (resourceId → [status, rejectionReason]) for standalone resource
     * publications (TABLE / INTERFACE / SKILL) of one {@code publicationType}, for every still-shared
     * publication (ACTIVE / PENDING_REVIEW / REJECTED; INACTIVE excluded - reads as "not shared").
     *
     * <p>The per-id companion is {@link #findByPublicationTypeAndResourceId}: this collapses the
     * resource-list "is each row published?" badge sweep (one HTTP per row to
     * {@code is-resource-published/{type}/{id}}) into a single batched query, exactly mirroring
     * {@link #findPublicationStatusesByWorkflowIds} for workflows. A given (type, resourceId) has at
     * most one publication ({@link #existsByPublicationTypeAndResourceId} is a uniqueness invariant),
     * so there is one row per resourceId. {@code rejectionReason} is non-null only for REJECTED rows.
     * Returned shape is {@code [[resourceId, status, rejectionReason], ...]}.
     */
    @Query("SELECT p.resourceId, p.status, p.rejectionReason FROM WorkflowPublicationEntity p "
            + "WHERE p.publicationType = :type AND p.resourceId IN :resourceIds AND p.status <> 'INACTIVE'")
    List<Object[]> findResourcePublicationStatusesByTypeAndResourceIds(
            @Param("type") PublicationType type,
            @Param("resourceIds") Collection<String> resourceIds);

    /**
     * Batch ({@code agentConfigId -> [agentConfigId, status, rejectionReason]}) for AGENT publications,
     * non-INACTIVE. The agent-library equivalent of
     * {@link #findResourcePublicationStatusesByTypeAndResourceIds}: AGENT publications key on the
     * {@code agentConfigId} UUID column, not the string {@code resource_id} (which is null for them),
     * so they need their own query.
     */
    @Query("SELECT p.agentConfigId, p.status, p.rejectionReason FROM WorkflowPublicationEntity p "
            + "WHERE p.publicationType = com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType.AGENT "
            + "AND p.agentConfigId IN :agentConfigIds AND p.status <> 'INACTIVE'")
    List<Object[]> findAgentPublicationStatusesByConfigIds(
            @Param("agentConfigIds") Collection<UUID> agentConfigIds);

    List<WorkflowPublicationEntity> findByProjectId(UUID projectId);

    List<WorkflowPublicationEntity> findByProjectIdAndOwnerTypeAndOwnerId(
            UUID projectId,
            WorkflowPublicationEntity.OwnerType ownerType,
            String ownerId);

    long countByProjectId(UUID projectId);

    long countByProjectIdAndOwnerTypeAndOwnerId(
            UUID projectId,
            WorkflowPublicationEntity.OwnerType ownerType,
            String ownerId);

    void deleteByWorkflowId(UUID workflowId);

    /**
     * 2026-05-18 - status='ACTIVE' guard added per repo-hygiene audit. Without
     * it a caller of acquirePublication(pubId) could increment counters on a
     * DRAFT/PENDING_REVIEW/REJECTED publication if upstream pubId validation
     * ever regresses. Returns the row count so callers can detect a no-op.
     */
    @Modifying
    @Query("UPDATE WorkflowPublicationEntity p SET p.useCount = p.useCount + 1, p.totalCreditsEarned = p.totalCreditsEarned + p.creditsPerUse, p.version = p.version + 1 WHERE p.id = :id AND p.status = 'ACTIVE'")
    int incrementUsage(@Param("id") UUID id);

    /**
     * 2026-05-18 - status='ACTIVE' guard added per repo-hygiene audit. Review
     * stats must not be backfilled on a DRAFT/REJECTED publication.
     */
    @Modifying
    @Query("UPDATE WorkflowPublicationEntity p SET p.averageRating = :avgRating, p.reviewCount = :reviewCount, p.version = p.version + 1 WHERE p.id = :id AND p.status = 'ACTIVE'")
    int updateReviewStats(@Param("id") UUID id, @Param("avgRating") double avgRating, @Param("reviewCount") int reviewCount);

    @Query("SELECT p FROM WorkflowPublicationEntity p WHERE p.status = 'PENDING_REVIEW' ORDER BY p.publishedAt DESC")
    Page<WorkflowPublicationEntity> findPendingReviewPublications(Pageable pageable);

    long countByStatus(PublicationStatus status);

    @Query("SELECT p FROM WorkflowPublicationEntity p WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC' AND p.publicationType = :type ORDER BY p.publishedAt DESC")
    Page<WorkflowPublicationEntity> findMarketplacePublicationsByType(
            @Param("type") PublicationType type, Pageable pageable);

    // searchMarketplaceByType - dropped in PR5. No callers in production code (verified
    // by audit C v0.2 grep, only the method definition itself). When re-needed, mirror
    // the searchMarketplace FTS pattern with an additional `p.publication_type = :type`
    // predicate.
}
