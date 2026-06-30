package com.apimarketplace.publication.service;

import com.apimarketplace.publication.dto.PublicationListItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Lightweight query service for publication list endpoints.
 * Uses native SQL to select all columns EXCEPT plan_snapshot (200KB-2MB JSONB),
 * which is never needed in list/search/marketplace responses.
 *
 * <p>This avoids loading the heavy planSnapshot blob into JVM heap for every
 * publication in a list query. For 20 publications at 200KB each, this saves
 * ~4MB per request.</p>
 *
 * <p>Detail endpoints that need planSnapshot continue to use the full entity
 * via WorkflowPublicationService.</p>
 */
@Service
@Transactional(readOnly = true)
public class PublicationListQueryService {

    @PersistenceContext
    private EntityManager em;

    /**
     * Column list for SELECT - everything except plan_snapshot.
     * The node_icons column is cast to TEXT to avoid JSONB deserialization overhead;
     * it will be parsed to a List in PublicationListItem.toResponseMap().
     */
    private static final String SELECT_COLUMNS = """
            p.id, p.publication_type, p.workflow_id, p.agent_config_id,
            p.title, p.description,
            p.showcase_interface_id, p.showcase_run_id, p.display_mode,
            p.credits_per_use, p.publisher_id, p.publisher_name,
            p.publisher_email, p.publisher_avatar_url,
            p.status, p.visibility, p.owner_type, p.owner_id, p.use_count, p.total_credits_earned,
            p.plan_version, CAST(p.node_icons AS TEXT),
            p.agent_count, p.skill_count,
            p.interface_count, p.datasource_count, p.workflow_count,
            p.average_rating, p.review_count,
            p.published_at, p.updated_at,
            p.category_id, p.category_slug, p.category_name,
            p.category_icon_slug, p.category_color,
            p.project_id,
            p.rejection_reason,
            p.agent_snapshot->'agent'->>'avatarUrl',
            p.agent_snapshot->'agent'->>'modelProvider',
            p.agent_snapshot->'agent'->>'modelName',
            p.resource_id
            """;

    private static final String FROM_CLAUSE = """
            FROM workflow_publications p
            """;

    // ========================================================================
    // Marketplace (paginated)
    // ========================================================================

    /**
     * Get marketplace publications (ACTIVE + PUBLIC), excluding planSnapshot.
     */
    public Page<PublicationListItem> findMarketplacePublications(int page, int size) {
        String dataSql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " ORDER BY p.published_at DESC";

        String countSql = "SELECT COUNT(*) FROM workflow_publications p"
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'";

        return executePagedQuery(dataSql, countSql, PageRequest.of(page, size));
    }

    /**
     * Get marketplace publications filtered by category slug, excluding planSnapshot.
     */
    public Page<PublicationListItem> findMarketplacePublicationsByCategory(String categorySlug, int page, int size) {
        String dataSql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.category_slug = :categorySlug"
                + " ORDER BY p.published_at DESC";

        String countSql = "SELECT COUNT(*) FROM workflow_publications p"
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.category_slug = :categorySlug";

        Pageable pageable = PageRequest.of(page, size);

        Query dataQuery = em.createNativeQuery(dataSql);
        dataQuery.setParameter("categorySlug", categorySlug);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        Query countQuery = em.createNativeQuery(countSql);
        countQuery.setParameter("categorySlug", categorySlug);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        long total = ((Number) countQuery.getSingleResult()).longValue();

        List<PublicationListItem> items = rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());

        return new PageImpl<>(items, pageable, total);
    }

    // ========================================================================
    // Public profile - "apps by this user"
    // ========================================================================

    /**
     * Public listing of a given user's published apps, shown on the public
     * profile page ({@code /u/{username}}). Mirrors the marketplace filter
     * (ACTIVE + PUBLIC) so a private/inactive/unlisted publication never leaks,
     * additionally constrained to {@code publisher_id} - the author who clicked
     * Publish (org-owned rows still surface under their human publisher here).
     *
     * @param publisherId the user id (String form of {@code auth.users.id})
     */
    public Page<PublicationListItem> findPublicByPublisherPaged(String publisherId, int page, int size) {
        String dataSql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.publisher_id = :publisherId"
                + " ORDER BY p.published_at DESC";

        String countSql = "SELECT COUNT(*) FROM workflow_publications p"
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.publisher_id = :publisherId";

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Query dataQuery = em.createNativeQuery(dataSql);
        dataQuery.setParameter("publisherId", publisherId);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        Query countQuery = em.createNativeQuery(countSql);
        countQuery.setParameter("publisherId", publisherId);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        long total = ((Number) countQuery.getSingleResult()).longValue();

        List<PublicationListItem> items = rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());

        return new PageImpl<>(items, pageable, total);
    }

    // ========================================================================
    // Onboarding suggestions
    // ========================================================================

    /**
     * Onboarding "suggested applications": public marketplace applications whose
     * category matches the caller's onboarding-derived category slugs.
     *
     * <p>"Application" uses the exact predicate of {@link #findByScope} /
     * {@code /app/applications} (applicationOnly): a {@code WORKFLOW} publication
     * backed by a showcase interface. Results are ordered by popularity
     * ({@code use_count}) then recency. When the category-filtered set is empty
     * (e.g. the user's categories have no published apps yet), it falls back to
     * the top applications across all categories so the onboarding modal is never
     * blank when applications exist at all.
     *
     * @param categorySlugs onboarding-derived slugs; {@code null}/empty → fallback only
     * @param limit         max rows (clamped to [1, 50])
     */
    public List<PublicationListItem> suggestApplications(List<String> categorySlugs, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        final String appFilter =
                " AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL";

        if (categorySlugs != null && !categorySlugs.isEmpty()) {
            String sql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                    + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                    + appFilter
                    + " AND p.category_slug IN (:slugs)"
                    + " ORDER BY p.use_count DESC, p.published_at DESC";
            Query query = em.createNativeQuery(sql);
            query.setParameter("slugs", categorySlugs);
            query.setMaxResults(safeLimit);

            @SuppressWarnings("unchecked")
            List<Object[]> rows = query.getResultList();
            if (!rows.isEmpty()) {
                return rows.stream()
                        .map(PublicationListQueryService::mapRow)
                        .collect(Collectors.toList());
            }
        }

        String fallbackSql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + appFilter
                + " ORDER BY p.use_count DESC, p.published_at DESC";
        Query fallbackQuery = em.createNativeQuery(fallbackSql);
        fallbackQuery.setMaxResults(safeLimit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = fallbackQuery.getResultList();
        return rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Agent Marketplace (paginated)
    // ========================================================================

    public Page<PublicationListItem> findMarketplaceAgentPublications(int page, int size) {
        String dataSql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.publication_type = 'AGENT'"
                + " ORDER BY p.published_at DESC";

        String countSql = "SELECT COUNT(*) FROM workflow_publications p"
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.publication_type = 'AGENT'";

        return executePagedQuery(dataSql, countSql, PageRequest.of(page, size));
    }

    /**
     * Generic per-type marketplace query. Passes {@code type} literally so it
     * matches the PublicationType enum values (TABLE, INTERFACE, SKILL, AGENT,
     * WORKFLOW).
     */
    public Page<PublicationListItem> findMarketplaceByType(String type, int page, int size) {
        String dataSql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.publication_type = :type"
                + " ORDER BY p.published_at DESC";

        String countSql = "SELECT COUNT(*) FROM workflow_publications p"
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.publication_type = :type";

        Pageable pageable = PageRequest.of(page, size);

        Query dataQuery = em.createNativeQuery(dataSql);
        dataQuery.setParameter("type", type);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        Query countQuery = em.createNativeQuery(countSql);
        countQuery.setParameter("type", type);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        long total = ((Number) countQuery.getSingleResult()).longValue();

        List<PublicationListItem> items = rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());
        return new PageImpl<>(items, pageable, total);
    }

    public List<PublicationListItem> searchAgentPublications(String search) {
        String sql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND p.publication_type = 'AGENT'"
                + " AND (LOWER(p.title) LIKE LOWER(:search) OR LOWER(p.description) LIKE LOWER(:search))"
                + " ORDER BY p.use_count DESC, p.published_at DESC";

        // Escape LIKE special characters to prevent wildcard injection
        String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        Query query = em.createNativeQuery(sql);
        query.setParameter("search", "%" + escaped + "%");
        query.setMaxResults(100); // safety limit for unbounded search

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Search
    // ========================================================================

    /**
     * Search publications by title (ACTIVE + PUBLIC), excluding planSnapshot.
     */
    public List<PublicationListItem> searchByTitle(String search) {
        return searchByTitle(search, null);
    }

    /**
     * Search publications by title with optional category filter.
     *
     * <p>Pre-fix the controller-level {@code /search} endpoint accepted no
     * category param at all, so typing into the marketplace search bar
     * silently dropped any active category filter - only the title-only
     * result set came back, surprising users who had pre-filtered.
     *
     * @param search       title fragment (LIKE-escaped, wrapped in %…%)
     * @param categorySlug optional category slug to AND with the title filter
     */
    public List<PublicationListItem> searchByTitle(String search, String categorySlug) {
        StringBuilder sql = new StringBuilder("SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " AND LOWER(p.title) LIKE LOWER(:search)");
        if (categorySlug != null && !categorySlug.isBlank()) {
            sql.append(" AND p.category_slug = :categorySlug");
        }
        sql.append(" ORDER BY p.published_at DESC");

        String escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        Query query = em.createNativeQuery(sql.toString());
        query.setParameter("search", "%" + escaped + "%");
        if (categorySlug != null && !categorySlug.isBlank()) {
            query.setParameter("categorySlug", categorySlug);
        }
        query.setMaxResults(100);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Popular
    // ========================================================================

    /**
     * Get popular publications ordered by use count, excluding planSnapshot.
     */
    public List<PublicationListItem> findPopularPublications(int limit) {
        String sql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status = 'ACTIVE' AND p.visibility = 'PUBLIC'"
                + " ORDER BY p.use_count DESC"
                + " LIMIT :limit";

        Query query = em.createNativeQuery(sql);
        query.setParameter("limit", limit);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // My publications
    // ========================================================================

    /**
     * Personal-scope overload - see {@link #findByScope(String, String, boolean)}.
     * Kept for legacy callers that don't yet thread organizationId.
     */
    public List<PublicationListItem> findByPublisher(String publisherId) {
        return findByScope(publisherId, null, false);
    }

    /**
     * Personal-scope overload - see {@link #findByScope(String, String, boolean)}.
     */
    public List<PublicationListItem> findByPublisher(String publisherId, boolean applicationOnly) {
        return findByScope(publisherId, null, applicationOnly);
    }

    /**
     * Get publications visible to the caller in their current workspace scope.
     *
     * <p>When {@code organizationId} is non-null/blank the caller is in an
     * org workspace: returns every ORG-owned row whose {@code owner_id}
     * equals {@code organizationId} (i.e. teammate-published rows show up).
     * Otherwise the caller is in personal mode: returns every USER-owned row
     * whose {@code owner_id} equals {@code tenantId}.
     *
     * <p>{@code applicationOnly}: when true, restrict to WORKFLOW publications
     * that have a showcase interface - actual "applications" - hiding
     * standalone TABLE/INTERFACE/SKILL/AGENT rows that surface in dedicated
     * pages and must not appear under /app/applications.
     */
    public List<PublicationListItem> findByScope(String tenantId,
                                                   String organizationId,
                                                   boolean applicationOnly) {
        return findByScope(tenantId, organizationId, applicationOnly, Set.of());
    }

    /**
     * Scope-aware publication list with caller-specific resource exclusions already
     * resolved by auth-service. Publication-service only applies the supplied IDs.
     */
    public List<PublicationListItem> findByScope(String tenantId,
                                                   String organizationId,
                                                   boolean applicationOnly,
                                                   Set<UUID> excludedPublicationIds) {
        boolean orgMode = organizationId != null && !organizationId.isBlank();
        String scopeFilter = orgMode
                ? " AND p.owner_type = 'ORG' AND p.owner_id = :ownerId"
                : " AND p.owner_type = 'USER' AND p.owner_id = :ownerId";
        String typeFilter = applicationOnly
                ? " AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL"
                : "";
        String exclusionFilter = excludedPublicationIds != null && !excludedPublicationIds.isEmpty()
                ? " AND p.id NOT IN (:excludedPublicationIds)"
                : "";
        String sql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status != 'INACTIVE'"
                + scopeFilter
                + typeFilter
                + exclusionFilter
                + " ORDER BY p.published_at DESC";

        Query query = em.createNativeQuery(sql);
        query.setParameter("ownerId", orgMode ? organizationId : tenantId);
        if (excludedPublicationIds != null && !excludedPublicationIds.isEmpty()) {
            query.setParameter("excludedPublicationIds", excludedPublicationIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());
    }

    /**
     * Personal-scope overload - see {@link #findByScopePaged(String, String, boolean, String, int, int)}.
     */
    public Page<PublicationListItem> findByPublisherPaged(String publisherId,
                                                            boolean applicationOnly,
                                                            String q,
                                                            int page,
                                                            int size) {
        return findByScopePaged(publisherId, null, applicationOnly, q, page, size);
    }

    /**
     * Paged + searchable variant of {@link #findByScope(String, String, boolean)}.
     * The search term {@code q} is matched server-side against title + description (ILIKE).
     */
    public Page<PublicationListItem> findByScopePaged(String tenantId,
                                                       String organizationId,
                                                       boolean applicationOnly,
                                                       String q,
                                                       int page,
                                                       int size) {
        return findByScopePaged(tenantId, organizationId, applicationOnly, q, page, size, Set.of());
    }

    /**
     * Paged scope-aware publication list with caller-specific resource exclusions
     * already resolved by auth-service.
     */
    public Page<PublicationListItem> findByScopePaged(String tenantId,
                                                       String organizationId,
                                                       boolean applicationOnly,
                                                       String q,
                                                       int page,
                                                       int size,
                                                       Set<UUID> excludedPublicationIds) {
        boolean orgMode = organizationId != null && !organizationId.isBlank();
        String scopeFilter = orgMode
                ? " AND p.owner_type = 'ORG' AND p.owner_id = :ownerId"
                : " AND p.owner_type = 'USER' AND p.owner_id = :ownerId";
        String typeFilter = applicationOnly
                ? " AND p.publication_type = 'WORKFLOW' AND p.showcase_interface_id IS NOT NULL"
                : "";
        String exclusionFilter = excludedPublicationIds != null && !excludedPublicationIds.isEmpty()
                ? " AND p.id NOT IN (:excludedPublicationIds)"
                : "";
        boolean hasSearch = q != null && !q.isBlank();
        String searchFilter = hasSearch
                ? " AND (p.title ILIKE :q OR COALESCE(p.description, '') ILIKE :q)"
                : "";

        String dataSql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.status != 'INACTIVE'"
                + scopeFilter + typeFilter + exclusionFilter + searchFilter
                + " ORDER BY p.published_at DESC";

        String countSql = "SELECT COUNT(*) FROM workflow_publications p"
                + " WHERE p.status != 'INACTIVE'"
                + scopeFilter + typeFilter + exclusionFilter + searchFilter;

        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Query dataQuery = em.createNativeQuery(dataSql);
        dataQuery.setParameter("ownerId", orgMode ? organizationId : tenantId);
        if (hasSearch) dataQuery.setParameter("q", "%" + q.trim() + "%");
        if (excludedPublicationIds != null && !excludedPublicationIds.isEmpty()) {
            dataQuery.setParameter("excludedPublicationIds", excludedPublicationIds);
        }
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        Query countQuery = em.createNativeQuery(countSql);
        countQuery.setParameter("ownerId", orgMode ? organizationId : tenantId);
        if (hasSearch) countQuery.setParameter("q", "%" + q.trim() + "%");
        if (excludedPublicationIds != null && !excludedPublicationIds.isEmpty()) {
            countQuery.setParameter("excludedPublicationIds", excludedPublicationIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        long total = ((Number) countQuery.getSingleResult()).longValue();

        List<PublicationListItem> items = rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());

        return new PageImpl<>(items, pageable, total);
    }

    // ========================================================================
    // Batch by IDs (for purchases/acquired endpoints)
    // ========================================================================

    /**
     * Batch load active publications by IDs, excluding planSnapshot.
     */
    public List<PublicationListItem> findByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();

        String sql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.id IN (:ids) AND p.status = 'ACTIVE'";

        Query query = em.createNativeQuery(sql);
        query.setParameter("ids", ids);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());
    }

    /**
     * Batch load publications by IDs whose status is ACTIVE or INACTIVE, excluding planSnapshot.
     *
     * <p>Used ONLY for receipt-scoped lists - acquired applications and purchases -
     * where the caller already holds a receipt for each publication. Once the
     * publisher unpublishes (status INACTIVE) or deletes (soft delete = status
     * INACTIVE) the source, {@link #findByIds} (which filters {@code status='ACTIVE'})
     * stops returning it, so the enrichment falls back to a minimal {@code remote=true}
     * stand-in with no showcase fields - dropping the installed-app card onto the
     * node-icon cover tile and bypassing the receipt-gated authenticated
     * showcase-render. Resolving the real INACTIVE row keeps the card (and the
     * publisher identity) intact for the acquirer after removal.
     *
     * <p>Scoped to {@code status IN ('ACTIVE','INACTIVE')} on purpose - NOT every status: a
     * source that is back in {@code PENDING_REVIEW} or {@code REJECTED} must not surface its
     * internal moderation {@code rejectionReason} on an acquirer's card; those transient states
     * fall back to the synth tile, same as before. No visibility filter is applied: PRIVATE is
     * fine here (the id set is already restricted upstream to what the caller acquired/purchased).
     */
    public List<PublicationListItem> findByIdsIncludingInactive(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return List.of();

        String sql = "SELECT " + SELECT_COLUMNS + FROM_CLAUSE
                + " WHERE p.id IN (:ids) AND p.status IN ('ACTIVE', 'INACTIVE')";

        Query query = em.createNativeQuery(sql);
        query.setParameter("ids", ids);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    /**
     * Execute a paginated native query and return a Spring Page.
     */
    private Page<PublicationListItem> executePagedQuery(String dataSql, String countSql, Pageable pageable) {
        Query dataQuery = em.createNativeQuery(dataSql);
        dataQuery.setFirstResult((int) pageable.getOffset());
        dataQuery.setMaxResults(pageable.getPageSize());

        Query countQuery = em.createNativeQuery(countSql);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        List<PublicationListItem> items = rows.stream()
                .map(PublicationListQueryService::mapRow)
                .collect(Collectors.toList());

        return new PageImpl<>(items, pageable, total);
    }

    /**
     * Map a native query result row to a PublicationListItem record.
     * Column order must match SELECT_COLUMNS exactly.
     */
    private static PublicationListItem mapRow(Object[] row) {
        int i = 0;
        return new PublicationListItem(
                toUuid(row[i++]),          // id
                toStr(row[i++]),           // publicationType
                toUuid(row[i++]),          // workflowId
                toUuid(row[i++]),          // agentConfigId
                toStr(row[i++]),           // title
                toStr(row[i++]),           // description
                toUuid(row[i++]),          // showcaseInterfaceId
                toStr(row[i++]),           // showcaseRunId
                toStr(row[i++]),           // displayMode
                toInt(row[i++]),           // creditsPerUse
                toStr(row[i++]),           // publisherId
                toStr(row[i++]),           // publisherName
                toStr(row[i++]),           // publisherEmail
                toStr(row[i++]),           // publisherAvatarUrl
                toStr(row[i++]),           // status
                toStr(row[i++]),           // visibility
                toStr(row[i++]),           // ownerType
                toStr(row[i++]),           // ownerId
                toInt(row[i++]),           // useCount
                toInt(row[i++]),           // totalCreditsEarned
                toInt(row[i++]),           // planVersion
                toStr(row[i++]),           // nodeIcons (CAST to TEXT)
                toInt(row[i++]),           // agentCount
                toInt(row[i++]),           // skillCount
                toInt(row[i++]),           // interfaceCount
                toInt(row[i++]),           // datasourceCount
                toInt(row[i++]),           // workflowCount
                toDouble(row[i++]),        // averageRating
                toInt(row[i++]),           // reviewCount
                toInstant(row[i++]),       // publishedAt
                toInstant(row[i++]),       // updatedAt
                toUuid(row[i++]),          // categoryId
                toStr(row[i++]),           // categorySlug
                toStr(row[i++]),           // categoryName
                toStr(row[i++]),           // categoryIconSlug
                toStr(row[i++]),           // categoryColor
                toUuid(row[i++]),          // projectId
                toStr(row[i++]),           // rejectionReason
                toStr(row[i++]),           // agentAvatarUrl
                toStr(row[i++]),           // agentModelProvider
                toStr(row[i++]),           // agentModelName
                toStr(row[i++])            // resourceId
        );
    }

    private static UUID toUuid(Object val) {
        if (val == null) return null;
        if (val instanceof UUID uuid) return uuid;
        if (val instanceof byte[] bytes) {
            // H2 returns UUIDs as byte[16] in native queries
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(bb.getLong(), bb.getLong());
        }
        return UUID.fromString(val.toString());
    }

    private static String toStr(Object val) {
        return val != null ? val.toString() : null;
    }

    private static Integer toInt(Object val) {
        if (val == null) return null;
        if (val instanceof Number num) return num.intValue();
        return Integer.parseInt(val.toString());
    }

    private static Double toDouble(Object val) {
        if (val == null) return null;
        if (val instanceof Number num) return num.doubleValue();
        return Double.parseDouble(val.toString());
    }

    private static Instant toInstant(Object val) {
        if (val == null) return null;
        if (val instanceof Instant inst) return inst;
        if (val instanceof OffsetDateTime odt) return odt.toInstant();
        if (val instanceof java.sql.Timestamp ts) return ts.toInstant();
        return Instant.parse(val.toString());
    }
}
