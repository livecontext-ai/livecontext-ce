package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.dto.StorageExplorerProjection;
import com.apimarketplace.common.storage.dto.StoragePreviewFile;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Native SQL repository for Storage Explorer queries.
 * Optimized for performance: projections only (no blob columns), partial indexes.
 *
 * <p>Post-V261 sweep: every storage row has a non-null {@code organization_id}
 * (gateway always injects {@code X-Organization-ID}; personal-workspace users
 * resolve to their personal-org UUID from {@code auth.organization_member.is_default=true}).
 * The legacy "personal scope = organization_id IS NULL" path has been removed;
 * {@code organizationId} is now a required parameter on every search/stats call.</p>
 */
@Repository
public class StorageExplorerRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * Projection column list shared by every explorer data query so the SELECT order stays in
     * lock-step with {@link #mapRows}. The trailing {@code is_folder, parent_folder_id} carry the
     * V313 manual-folder columns.
     */
    private static final String PROJECTION_COLUMNS =
            "s.id, s.storage_type, s.source_type, s.file_name, s.mime_type, "
            + "s.size_bytes, s.created_at, s.workflow_id, s.project_id, s.run_id, s.step_key, "
            + "s.epoch, s.s3_key, s.content_type, s.is_folder, s.parent_folder_id";

    /** Max preview files returned per folder for the iOS-style 9-up tile. */
    static final int PREVIEW_FILES_PER_FOLDER = 9;

    /**
     * Org-scoped search. Returns only rows tagged with {@code organizationId};
     * tenant is ignored (org membership is asserted upstream by the controller
     * via the gateway's {@code X-Organization-Role} claim).
     */
    public Page<StorageExplorerProjection> search(
            String organizationId,
            String search,
            String sourceType,
            String storageType,
            String workflowId,
            String runId,
            Instant dateFrom,
            Instant dateTo,
            boolean filesOnly,
            Pageable pageable) {
        return search(organizationId, search, sourceType, storageType, workflowId, runId,
                dateFrom, dateTo, filesOnly, false, null, List.of(), pageable);
    }

    /**
     * @param s3Only when true, restrict to real user-facing files:
     *               {@code (s3_key IS NOT NULL OR source_type = 'CHAT_ATTACHMENT')} - object-storage
     *               files PLUS chat attachments (saved as DB BINARY with no s3_key). The full-page
     *               Files browser sets it so observability TEXT pseudo-files
     *               ({@code tool_call_result.txt}/{@code agent_message.txt}) and avatars
     *               ({@code USER_AVATAR}/{@code ORG_AVATAR}) don't appear there, while a user's
     *               chat-uploaded images DO. Stronger than {@code filesOnly}; the two compose (AND).
     */
    public Page<StorageExplorerProjection> search(
            String organizationId,
            String search,
            String sourceType,
            String storageType,
            String workflowId,
            String runId,
            Instant dateFrom,
            Instant dateTo,
            boolean filesOnly,
            boolean s3Only,
            String fileCategory,
            Collection<UUID> excludedIds,
            Pageable pageable) {

        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }

        StringBuilder where = new StringBuilder();
        appendWhere(where, filesOnly, s3Only, search, sourceType, storageType, workflowId, runId, dateFrom, dateTo, excludedIds);
        // File-type category - appended here (not in the shared appendWhere) so the
        // agent files tool's searchSlice is untouched. The fragment is a CONSTANT
        // (no user input in SQL) keyed by a validated category, so it is injection-safe.
        where.append(fileCategoryClause(fileCategory));

        // Count query
        String countSql = "SELECT COUNT(*) FROM storage.storage s " + where;
        Query countQuery = em.createNativeQuery(countSql);
        setParams(countQuery, organizationId, search, sourceType, storageType,
                workflowId, runId, dateFrom, dateTo, excludedIds);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        if (total == 0) {
            return new PageImpl<>(List.of(), pageable, 0);
        }

        // Data query
        String dataSql = "SELECT " + PROJECTION_COLUMNS +
                " FROM storage.storage s " + where +
                " ORDER BY s.created_at DESC" +
                " LIMIT :limit OFFSET :offset";

        Query dataQuery = em.createNativeQuery(dataSql);
        setParams(dataQuery, organizationId, search, sourceType, storageType,
                workflowId, runId, dateFrom, dateTo, excludedIds);
        dataQuery.setParameter("limit", pageable.getPageSize());
        dataQuery.setParameter("offset", (int) pageable.getOffset());

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        return new PageImpl<>(mapRows(rows), pageable, total);
    }

    /**
     * Offset-based slice for the agent-facing {@code files} tool. Mirrors
     * {@link #search} but (a) honours an arbitrary {@code offset}/{@code limit}
     * directly - the agent list envelope is offset-based, not page-based - and
     * (b) supports {@code filesOnly} to restrict to real files
     * ({@code file_name IS NOT NULL}). Step-output JSON blobs (which carry no
     * file name) are excluded so the agent's file browser is not flooded with
     * machine JSON - those are read through workflow {@code get_run} instead.
     */
    public SliceResult searchSlice(
            String organizationId, String search, String sourceType, String storageType,
            String workflowId, String runId, Instant dateFrom, Instant dateTo,
            boolean filesOnly, int limit, int offset) {
        return searchSlice(organizationId, search, sourceType, storageType, workflowId, runId,
                dateFrom, dateTo, filesOnly, List.of(), limit, offset);
    }

    public SliceResult searchSlice(
            String organizationId, String search, String sourceType, String storageType,
            String workflowId, String runId, Instant dateFrom, Instant dateTo,
            boolean filesOnly, Collection<UUID> excludedIds, int limit, int offset) {

        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }

        StringBuilder where = new StringBuilder();
        // Agent files tool: never S3-only (s3Only=false) - it browses real files
        // (file_name not null), including DB-resident chat attachments.
        appendWhere(where, filesOnly, false, search, sourceType, storageType, workflowId, runId, dateFrom, dateTo, excludedIds);

        String countSql = "SELECT COUNT(*) FROM storage.storage s " + where;
        Query countQuery = em.createNativeQuery(countSql);
        setParams(countQuery, organizationId, search, sourceType, storageType,
                workflowId, runId, dateFrom, dateTo, excludedIds);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        if (total == 0) {
            return new SliceResult(List.of(), 0);
        }

        String dataSql = "SELECT " + PROJECTION_COLUMNS +
                " FROM storage.storage s " + where +
                " ORDER BY s.created_at DESC" +
                " LIMIT :limit OFFSET :offset";

        Query dataQuery = em.createNativeQuery(dataSql);
        setParams(dataQuery, organizationId, search, sourceType, storageType,
                workflowId, runId, dateFrom, dateTo, excludedIds);
        dataQuery.setParameter("limit", limit);
        dataQuery.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        return new SliceResult(mapRows(rows), total);
    }

    /** Slice of storage projections + the total matching count (offset pagination). */
    public record SliceResult(List<StorageExplorerProjection> rows, long total) {}

    /**
     * Folder-aware listing for the Files browser (V313 manual folders).
     *
     * <p><b>Root</b> ({@code parentFolderId == null}): returns the manual folder rows at top
     * level ({@code is_folder = true AND parent_folder_id IS NULL}) followed by the files at top
     * level ({@code is_folder = false AND parent_folder_id IS NULL}). A folder is not a mime type,
     * so the file filters ({@code fileCategory}, {@code s3Only}, {@code filesOnly}) constrain ONLY
     * the file rows - folders are always shown - but the {@code search} text DOES filter folder
     * names (so searching narrows both).
     *
     * <p><b>Inside a folder</b> ({@code parentFolderId} non-null): returns the direct children of
     * that folder ({@code parent_folder_id = :parentFolderId}); sub-folders first, then files, with
     * the same file-only filters.
     *
     * <p>Both cases order folders before files ({@code is_folder DESC}) then {@code created_at DESC}
     * within each group. Returns a {@link SliceResult} (rows + total) so the service can page it.
     */
    public SliceResult listFolderScope(
            String organizationId,
            UUID parentFolderId,
            String search,
            String sourceType,
            String storageType,
            String workflowId,
            String runId,
            Instant dateFrom,
            Instant dateTo,
            boolean filesOnly,
            boolean s3Only,
            String fileCategory,
            Collection<UUID> excludedIds,
            int limit,
            int offset) {

        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }

        // Folder branch of the OR: folders bypass file-type/s3/filesOnly filters (a folder is not
        // a mime type) but still honour the name search.
        StringBuilder folderPredicate = new StringBuilder("s.is_folder = true");
        if (search != null && !search.isBlank()) {
            folderPredicate.append(" AND s.file_name ILIKE :search");
        }

        // File branch of the OR: real rows with every active file filter applied.
        StringBuilder filePredicate = new StringBuilder("s.is_folder = false");
        if (filesOnly) {
            filePredicate.append(" AND s.file_name IS NOT NULL");
        }
        if (s3Only) {
            filePredicate.append(" AND s.s3_key IS NOT NULL");
        }
        if (search != null && !search.isBlank()) {
            filePredicate.append(" AND (s.file_name ILIKE :search OR s.content_type ILIKE :search OR s.step_key ILIKE :search)");
        }
        if (sourceType != null && !sourceType.isBlank()) {
            filePredicate.append(" AND s.source_type = :sourceType");
        }
        if (storageType != null && !storageType.isBlank()) {
            filePredicate.append(" AND s.storage_type = :storageType");
        }
        if (workflowId != null && !workflowId.isBlank()) {
            filePredicate.append(" AND s.workflow_id = :workflowId");
        }
        if (runId != null && !runId.isBlank()) {
            filePredicate.append(" AND s.run_id = :runId");
        }
        if (dateFrom != null) {
            filePredicate.append(" AND s.created_at >= :dateFrom");
        }
        if (dateTo != null) {
            filePredicate.append(" AND s.created_at <= :dateTo");
        }
        filePredicate.append(fileCategoryClause(fileCategory));

        StringBuilder where = new StringBuilder("WHERE s.organization_id = :orgId AND s.status = 'ACTIVE'");
        if (parentFolderId != null) {
            where.append(" AND s.parent_folder_id = :parentFolderId");
        } else {
            where.append(" AND s.parent_folder_id IS NULL");
        }
        if (excludedIds != null && !excludedIds.isEmpty()) {
            where.append(" AND s.id NOT IN (:excludedIds)");
        }
        where.append(" AND ((").append(folderPredicate).append(") OR (").append(filePredicate).append("))");

        String countSql = "SELECT COUNT(*) FROM storage.storage s " + where;
        Query countQuery = em.createNativeQuery(countSql);
        setFolderScopeParams(countQuery, organizationId, parentFolderId, search, sourceType,
                storageType, workflowId, runId, dateFrom, dateTo, excludedIds);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        if (total == 0) {
            return new SliceResult(List.of(), 0);
        }

        // Folders sort by their LAST ACTIVITY - the most recent ACTIVE child added (the date the last
        // element landed in the folder) - files by their own created_at, folders first. A folder with
        // no children falls back to its own created_at. This matches the MAX(created_at) a virtual
        // workflow folder already uses, so manual and virtual folders order consistently. The member
        // restricted-id deny-list is honoured in the child-date subquery so a restricted child neither
        // advances a folder's position nor leaks its recency (mirrors the childCount/preview exclusion).
        String dataSql = "SELECT " + PROJECTION_COLUMNS +
                " FROM storage.storage s " + where +
                " ORDER BY s.is_folder DESC, " + folderActivityOrderExpr(excludedIds) + " DESC" +
                " LIMIT :limit OFFSET :offset";
        Query dataQuery = em.createNativeQuery(dataSql);
        setFolderScopeParams(dataQuery, organizationId, parentFolderId, search, sourceType,
                storageType, workflowId, runId, dateFrom, dateTo, excludedIds);
        dataQuery.setParameter("limit", limit);
        dataQuery.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        return new SliceResult(mapRows(rows), total);
    }

    /**
     * Direct-child counts for a set of folders, keyed by {@code parent_folder_id} - ONE GROUP BY
     * query (no N+1 over the page's folders). Folders with zero children are absent from the map;
     * the caller defaults them to 0. {@code excludedIds} is the member restricted-id deny-list
     * (same set the controller passes to {@link #listFolderScope}); restricted children are not
     * counted, so a restricted MEMBER/VIEWER sees an accurate (non-inflated) {@code childCount}.
     */
    public Map<UUID, Integer> countChildrenByParent(String organizationId, Collection<UUID> folderIds,
                                                    Collection<UUID> excludedIds) {
        if (folderIds == null || folderIds.isEmpty()) {
            return Map.of();
        }
        // Honour the member restricted-id deny-list (same idiom as listFolderScope) so a
        // restricted MEMBER/VIEWER doesn't see an inflated childCount that includes rows
        // they're not allowed to see.
        boolean hasExclusions = excludedIds != null && !excludedIds.isEmpty();
        String sql = "SELECT s.parent_folder_id, COUNT(*) FROM storage.storage s "
                + "WHERE s.organization_id = :orgId AND s.status = 'ACTIVE' "
                + "AND s.parent_folder_id IN (:folderIds) "
                + (hasExclusions ? "AND s.id NOT IN (:excludedIds) " : "")
                + "GROUP BY s.parent_folder_id";
        Query query = em.createNativeQuery(sql);
        query.setParameter("orgId", organizationId);
        query.setParameter("folderIds", folderIds);
        if (hasExclusions) {
            query.setParameter("excludedIds", excludedIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            counts.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return counts;
    }

    /**
     * SQL ORDER-BY expression for a folder's "last activity" - {@code MAX(child.created_at)}, falling
     * back to the folder's own {@code created_at} when it is empty - used to sort folders newest-first
     * (the date the last element was added). For a non-folder row the expression is just
     * {@code s.created_at}, so the SAME expression orders a mixed folder+file listing. The
     * {@code excludedIds} deny-list is folded into the correlated sub-query when present (the
     * {@code :excludedIds} parameter is already bound by the caller), so a restricted child does not
     * advance a folder's position. Constant SQL (no user input) - injection-safe.
     */
    private static String folderActivityOrderExpr(Collection<UUID> excludedIds) {
        String childExclusion = (excludedIds != null && !excludedIds.isEmpty())
                ? " AND c.id NOT IN (:excludedIds)" : "";
        return "CASE WHEN s.is_folder THEN COALESCE("
                + "(SELECT MAX(c.created_at) FROM storage.storage c "
                + "WHERE c.parent_folder_id = s.id AND c.status = 'ACTIVE'" + childExclusion + "), "
                + "s.created_at) ELSE s.created_at END";
    }

    /**
     * Latest ACTIVE child {@code created_at} per folder ({@code MAX(child.created_at)}), keyed by
     * {@code parent_folder_id} - ONE GROUP BY query (no N+1 over the page's folders). A folder with no
     * children is absent from the map; the caller falls back to the folder's own {@code created_at}.
     * This is the folder's "last activity" (the date the last element was added) used to display + sort
     * folders newest-first - the same {@code MAX(created_at)} a virtual workflow folder already carries.
     * {@code excludedIds} honours the member restricted-id deny-list so a restricted child neither
     * advances a folder's position nor reveals its recency (same idiom as {@link #countChildrenByParent}).
     */
    public Map<UUID, Instant> latestChildCreatedAtByParent(String organizationId, Collection<UUID> folderIds,
                                                          Collection<UUID> excludedIds) {
        if (folderIds == null || folderIds.isEmpty()) {
            return Map.of();
        }
        boolean hasExclusions = excludedIds != null && !excludedIds.isEmpty();
        String sql = "SELECT s.parent_folder_id, MAX(s.created_at) FROM storage.storage s "
                + "WHERE s.organization_id = :orgId AND s.status = 'ACTIVE' "
                + "AND s.parent_folder_id IN (:folderIds) "
                + (hasExclusions ? "AND s.id NOT IN (:excludedIds) " : "")
                + "GROUP BY s.parent_folder_id";
        Query query = em.createNativeQuery(sql);
        query.setParameter("orgId", organizationId);
        query.setParameter("folderIds", folderIds);
        if (hasExclusions) {
            query.setParameter("excludedIds", excludedIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<UUID, Instant> dates = new LinkedHashMap<>();
        for (Object[] row : rows) {
            if (row[1] != null) {
                dates.put((UUID) row[0], toInstant(row[1]));
            }
        }
        return dates;
    }

    /**
     * Candidate preview file children for a set of folders, newest first - ONE query for the whole
     * page (no N+1). Returns {@code (parent_folder_id, id, mime_type, file_name)} rows ordered by
     * {@code parent_folder_id, created_at DESC}; the caller slices ≤ {@link #PREVIEW_FILES_PER_FOLDER}
     * per folder in Java. Only non-folder rows are considered (a folder has no preview tile of its
     * own). EVERY file type is a candidate (not just image/video/pdf): the frontend renders an image
     * thumbnail for images and a file-type icon for everything else, so the {@code mime_type} /
     * {@code file_name} travel with each id. {@code excludedIds} is the member restricted-id deny-list
     * (same set the controller passes to {@link #listFolderScope}); restricted child UUIDs are never
     * disclosed in the preview tile.
     */
    public Map<UUID, List<StoragePreviewFile>> findPreviewFilesByParent(String organizationId, Collection<UUID> folderIds,
                                                                        Collection<UUID> excludedIds) {
        if (folderIds == null || folderIds.isEmpty()) {
            return Map.of();
        }
        // Honour the member restricted-id deny-list (same idiom as listFolderScope) so a
        // restricted child UUID is never disclosed in a folder's preview thumbnail set.
        boolean hasExclusions = excludedIds != null && !excludedIds.isEmpty();
        String sql = "SELECT s.parent_folder_id, s.id, s.mime_type, s.file_name FROM storage.storage s "
                + "WHERE s.organization_id = :orgId AND s.status = 'ACTIVE' "
                + "AND s.is_folder = false AND s.parent_folder_id IN (:folderIds) "
                + (hasExclusions ? "AND s.id NOT IN (:excludedIds) " : "")
                + "ORDER BY s.parent_folder_id, s.created_at DESC, s.id DESC";
        Query query = em.createNativeQuery(sql);
        query.setParameter("orgId", organizationId);
        query.setParameter("folderIds", folderIds);
        if (hasExclusions) {
            query.setParameter("excludedIds", excludedIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<UUID, List<StoragePreviewFile>> previews = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID parent = (UUID) row[0];
            List<StoragePreviewFile> list = previews.computeIfAbsent(parent, k -> new ArrayList<>());
            if (list.size() < PREVIEW_FILES_PER_FOLDER) {
                list.add(new StoragePreviewFile(uuidString(row[1]), (String) row[2], (String) row[3]));
            }
        }
        return previews;
    }

    // ====================================================================================
    // Phase 2b - VIRTUAL workflow folder tree (workflow → epoch → spawn → iteration)
    //
    // Virtual folders are NOT persisted: they are GROUP-BY aggregations over the run-context
    // columns (workflow_id, epoch, spawn, item_index) of the org's workflow file rows. Only rows
    // with parent_folder_id IS NULL participate (a file moved into a manual folder leaves the
    // virtual tree). The GROUP-BY column and the level filters are CONSTANTS selected by the
    // {@link VirtualFolderAddress.Level} enum - never user input - so there is no SQL injection
    // surface; every scalar (org id, workflow id, epoch, spawn) is bound as a parameter.
    // ====================================================================================

    /**
     * One row of a virtual GROUP-BY: the group {@code key} (the grouped column value as a string -
     * a workflow_id, or the int epoch/spawn/item rendered via {@code String.valueOf}), the
     * {@code count} of files in the group, and the {@code latestCreatedAt} ({@code MAX(created_at)})
     * used both to order groups (newest first) and as the virtual folder's "modified" time.
     */
    public record VirtualGroup(String key, long count, Instant latestCreatedAt) {}

    /**
     * The GROUP-BY column for a given child level. CONSTANT per level (no user input) - injection-safe.
     */
    private static String virtualGroupColumn(VirtualFolderAddress.Level childLevel) {
        return switch (childLevel) {
            case WORKFLOW -> "s.workflow_id";
            case RUN -> "s.run_id";
            case EPOCH -> "s.epoch";
            case SPAWN -> "s.spawn";
            case ITERATION -> "s.item_index";
        };
    }

    /**
     * The level-scope predicate fragment for a given child level. Each {@code = :param} fragment is a
     * CONSTANT; the values are bound by {@link #setVirtualGroupParams}. WORKFLOW (the root level) only
     * requires {@code workflow_id IS NOT NULL}; deeper levels pin the parent coordinates. RUN groups the
     * runs of a workflow. EPOCH/SPAWN/ITERATION optionally pin a specific run when {@code hasRun}
     * (a workflow with ≥2 runs); when collapsed (single run) no run clause is added.
     */
    private static String virtualLevelPredicate(VirtualFolderAddress.Level childLevel, boolean hasRun) {
        String runClause = hasRun ? " AND s.run_id = :run" : "";
        return switch (childLevel) {
            case WORKFLOW -> "s.workflow_id IS NOT NULL";
            case RUN -> "s.workflow_id = :wf";
            case EPOCH -> "s.workflow_id = :wf" + runClause;
            case SPAWN -> "s.workflow_id = :wf AND s.epoch = :epoch" + runClause;
            case ITERATION -> "s.workflow_id = :wf AND s.epoch = :epoch AND s.spawn = :spawn" + runClause;
        };
    }

    /** Shared base WHERE for every virtual query: org-scoped active leaf files at the virtual root. */
    private static String virtualBaseWhere(boolean hasExclusions) {
        return "WHERE s.organization_id = :orgId AND s.status = 'ACTIVE' "
                + "AND s.is_folder = false AND s.parent_folder_id IS NULL "
                + (hasExclusions ? "AND s.id NOT IN (:excludedIds) " : "");
    }

    /**
     * The file-row predicate that decides which rows COUNT toward a virtual folder - identical to the
     * leaf-file listing. A virtual {@code workflow → epoch → spawn → iteration} folder must be computed
     * over the SAME rows the user will see inside it, otherwise an epoch whose only rows are machine
     * step-output JSON (no {@code file_name}/{@code s3_key}) shows a folder that opens EMPTY. The Files
     * browser passes {@code filesOnly + s3Only}, so a folder then appears only for an epoch/spawn/item
     * that actually produced a downloadable/uploaded file. Defaults are all "off" ({@link #NONE}).
     */
    public record VirtualFileFilter(boolean filesOnly, boolean s3Only, String search,
                                    String sourceType, String storageType, String fileCategory,
                                    Instant dateFrom, Instant dateTo) {
        /** No file constraint - count every workflow row (the pre-fix behaviour). */
        public static final VirtualFileFilter NONE =
                new VirtualFileFilter(false, false, null, null, null, null, null, null);
    }

    /** Append the {@link VirtualFileFilter} predicate (filesOnly/s3Only/search/sourceType/storageType/fileCategory). */
    private static void appendVirtualFileFilters(StringBuilder where, VirtualFileFilter f) {
        if (f == null) {
            return;
        }
        if (f.filesOnly()) {
            where.append("AND s.file_name IS NOT NULL ");
        }
        if (f.s3Only()) {
            where.append("AND s.s3_key IS NOT NULL ");
        }
        if (f.search() != null && !f.search().isBlank()) {
            where.append("AND (s.file_name ILIKE :search OR s.content_type ILIKE :search OR s.step_key ILIKE :search) ");
        }
        if (f.sourceType() != null && !f.sourceType().isBlank()) {
            where.append("AND s.source_type = :sourceType ");
        }
        if (f.storageType() != null && !f.storageType().isBlank()) {
            where.append("AND s.storage_type = :storageType ");
        }
        if (f.dateFrom() != null) {
            where.append("AND s.created_at >= :dateFrom ");
        }
        if (f.dateTo() != null) {
            where.append("AND s.created_at <= :dateTo ");
        }
        where.append(fileCategoryClause(f.fileCategory()));
    }

    /** Bind the {@link VirtualFileFilter} string params (filesOnly/s3Only/fileCategory are SQL constants). */
    private static void bindVirtualFileFilter(Query query, VirtualFileFilter f) {
        if (f == null) {
            return;
        }
        if (f.search() != null && !f.search().isBlank()) {
            query.setParameter("search", "%" + f.search() + "%");
        }
        if (f.sourceType() != null && !f.sourceType().isBlank()) {
            query.setParameter("sourceType", f.sourceType());
        }
        if (f.storageType() != null && !f.storageType().isBlank()) {
            query.setParameter("storageType", f.storageType());
        }
        if (f.dateFrom() != null) {
            query.setParameter("dateFrom", f.dateFrom());
        }
        if (f.dateTo() != null) {
            query.setParameter("dateTo", f.dateTo());
        }
    }

    private static void setVirtualGroupParams(Query query, String organizationId,
                                              VirtualFolderAddress.Level childLevel, String workflowId,
                                              String runId, Integer epoch, Integer spawn, Collection<UUID> excludedIds) {
        query.setParameter("orgId", organizationId);
        if (excludedIds != null && !excludedIds.isEmpty()) {
            query.setParameter("excludedIds", excludedIds);
        }
        // Bind only the parent coordinates the level's predicate references.
        if (childLevel == VirtualFolderAddress.Level.RUN
                || childLevel == VirtualFolderAddress.Level.EPOCH
                || childLevel == VirtualFolderAddress.Level.SPAWN
                || childLevel == VirtualFolderAddress.Level.ITERATION) {
            query.setParameter("wf", workflowId);
        }
        // run pinned only at/below EPOCH and only when the workflow has ≥2 runs (runId != null).
        if (runId != null
                && (childLevel == VirtualFolderAddress.Level.EPOCH
                || childLevel == VirtualFolderAddress.Level.SPAWN
                || childLevel == VirtualFolderAddress.Level.ITERATION)) {
            query.setParameter("run", runId);
        }
        if (childLevel == VirtualFolderAddress.Level.SPAWN
                || childLevel == VirtualFolderAddress.Level.ITERATION) {
            query.setParameter("epoch", epoch);
        }
        if (childLevel == VirtualFolderAddress.Level.ITERATION) {
            query.setParameter("spawn", spawn);
        }
    }

    /**
     * Group the org's virtual-root workflow files at {@code childLevel}, returning one
     * {@link VirtualGroup} per distinct value of the level's GROUP-BY column, ordered newest first
     * ({@code MAX(created_at) DESC}).
     *
     * <ul>
     *   <li>WORKFLOW → {@code GROUP BY s.workflow_id} (root: every workflow with a virtual-root file)</li>
     *   <li>EPOCH → {@code GROUP BY s.epoch} within {@code workflowId}</li>
     *   <li>SPAWN → {@code GROUP BY s.spawn} within {@code (workflowId, epoch)}</li>
     *   <li>ITERATION → {@code GROUP BY s.item_index} within {@code (workflowId, epoch, spawn)}</li>
     * </ul>
     *
     * <p>The GROUP-BY column + level filters are enum-selected constants; {@code workflowId} /
     * {@code epoch} / {@code spawn} are bound params. A NULL group key (only possible for
     * {@code item_index}, which is nullable) is skipped - a file with no iteration index is not its
     * own iteration folder; it is collapsed at the spawn level by the service.</p>
     */
    public List<VirtualGroup> listVirtualGroups(String organizationId, VirtualFolderAddress.Level childLevel,
                                                String workflowId, String runId, Integer epoch, Integer spawn,
                                                Collection<UUID> excludedIds, VirtualFileFilter fileFilter) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }
        boolean hasExclusions = excludedIds != null && !excludedIds.isEmpty();
        String col = virtualGroupColumn(childLevel);
        StringBuilder where = new StringBuilder(virtualBaseWhere(hasExclusions))
                .append("AND ").append(virtualLevelPredicate(childLevel, runId != null)).append(" ");
        // Only rows that match what the user will actually SEE inside the folder count toward it -
        // otherwise a group of machine step-output rows yields an empty folder.
        appendVirtualFileFilters(where, fileFilter);
        // RUN groups by run_id; a NULL run_id is not a real run folder (legacy rows pre-dating run
        // threading), so it is excluded from the run grouping rather than rendered as a blank run.
        if (childLevel == VirtualFolderAddress.Level.RUN) {
            where.append("AND s.run_id IS NOT NULL ");
        }
        // Runs are numbered chronologically ("Run 1" = the earliest run), so order them oldest-first
        // by their first file; every other level lists newest-first (matching the folder "modified" time).
        String orderBy = childLevel == VirtualFolderAddress.Level.RUN
                ? "ORDER BY MIN(s.created_at) ASC"
                : "ORDER BY MAX(s.created_at) DESC";
        String sql = "SELECT " + col + ", COUNT(*), MAX(s.created_at) FROM storage.storage s "
                + where
                + "GROUP BY " + col + " "
                + orderBy;

        Query query = em.createNativeQuery(sql);
        setVirtualGroupParams(query, organizationId, childLevel, workflowId, runId, epoch, spawn, excludedIds);
        bindVirtualFileFilter(query, fileFilter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<VirtualGroup> groups = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            String key = virtualKey(row[0]);
            if (key == null) {
                // NULL item_index group - not a real iteration folder; the spawn level collapses it.
                continue;
            }
            long count = ((Number) row[1]).longValue();
            Instant latest = row[2] != null ? toInstant(row[2]) : null;
            groups.add(new VirtualGroup(key, count, latest));
        }
        return groups;
    }

    /** Render a group key column value to a String: workflow_id is already a String; numbers via intValue. */
    private static String virtualKey(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return String.valueOf(n.intValue());
        }
        return value.toString();
    }

    /**
     * Preview files (≤9 per group, newest first, EVERY file type) for the virtual groups at
     * {@code childLevel}, keyed by the same string group key as {@link #listVirtualGroups}. ONE query
     * for the whole level (no N+1). Mirrors {@link #findPreviewFilesByParent}: rows are ordered by
     * the group column then {@code created_at DESC, id DESC} and capped at
     * {@link #PREVIEW_FILES_PER_FOLDER} per key in Java; each row carries the file's
     * {@code mime_type} / {@code file_name} so the frontend can pick a thumbnail or a file-type icon.
     */
    public Map<String, List<StoragePreviewFile>> previewFilesForVirtualGroups(String organizationId,
                                                              VirtualFolderAddress.Level childLevel,
                                                              String workflowId, String runId, Integer epoch, Integer spawn,
                                                              Collection<UUID> excludedIds, VirtualFileFilter fileFilter) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }
        boolean hasExclusions = excludedIds != null && !excludedIds.isEmpty();
        String col = virtualGroupColumn(childLevel);
        StringBuilder where = new StringBuilder(virtualBaseWhere(hasExclusions))
                .append("AND ").append(virtualLevelPredicate(childLevel, runId != null)).append(" ");
        if (childLevel == VirtualFolderAddress.Level.RUN) {
            where.append("AND s.run_id IS NOT NULL ");
        }
        // Same file constraint as the group + leaf queries, so a folder's preview tiles only ever
        // come from rows that actually surface inside it.
        appendVirtualFileFilters(where, fileFilter);
        String sql = "SELECT " + col + ", s.id, s.mime_type, s.file_name FROM storage.storage s "
                + where
                + "ORDER BY " + col + ", s.created_at DESC, s.id DESC";

        Query query = em.createNativeQuery(sql);
        setVirtualGroupParams(query, organizationId, childLevel, workflowId, runId, epoch, spawn, excludedIds);
        bindVirtualFileFilter(query, fileFilter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        Map<String, List<StoragePreviewFile>> previews = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String key = virtualKey(row[0]);
            if (key == null) {
                continue;
            }
            List<StoragePreviewFile> list = previews.computeIfAbsent(key, k -> new ArrayList<>());
            if (list.size() < PREVIEW_FILES_PER_FOLDER) {
                list.add(new StoragePreviewFile(uuidString(row[1]), (String) row[2], (String) row[3]));
            }
        }
        return previews;
    }

    /**
     * Leaf files at a virtual location - the persisted file rows shown once the tree stops branching
     * (an iteration, a single-spawn/single-iteration epoch that collapses, or the root's loose files).
     *
     * <p>Workflow scope:
     * <ul>
     *   <li>{@code workflowId == null} → {@code s.workflow_id IS NULL} (root LOOSE files: files with
     *       no workflow at all).</li>
     *   <li>otherwise → {@code s.workflow_id = :wf}, plus {@code epoch}/{@code spawn}/{@code itemIndex}
     *       equality for each non-null coordinate.</li>
     * </ul>
     * Always org+active, {@code is_folder=false}, {@code parent_folder_id IS NULL}, then the usual file
     * filters ({@code filesOnly}, {@code s3Only}, {@code search} triple-ILIKE, {@code sourceType},
     * {@code storageType}, {@code dateFrom}/{@code dateTo}, {@code fileCategory}). Returns a
     * {@link SliceResult} (count + the {@code LIMIT/OFFSET} page ordered {@code created_at DESC}).</p>
     *
     * <p>{@code nullItemOnly} (only meaningful when {@code workflowId != null}): restrict to rows with
     * {@code item_index IS NULL} instead of an equality on {@code itemIndex}. A workflow file produced
     * OUTSIDE a split carries {@code item_index = NULL}; such a file is not its own iteration folder, so
     * when a spawn ALSO has split-produced (non-null item_index) files - which become iteration folders -
     * the service shows these null-item files at the spawn level alongside the folders (header/body) so
     * they are never orphaned from the virtual tree. {@code itemIndex} is ignored when this is true.</p>
     */
    public SliceResult listVirtualLeafFiles(
            String organizationId, String workflowId, String runId, Integer epoch, Integer spawn, Integer itemIndex,
            String search, String sourceType, String storageType, boolean filesOnly, boolean s3Only,
            String fileCategory, Instant dateFrom, Instant dateTo, Collection<UUID> excludedIds,
            boolean nullItemOnly, int limit, int offset) {

        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }
        boolean hasExclusions = excludedIds != null && !excludedIds.isEmpty();

        StringBuilder where = new StringBuilder(virtualBaseWhere(hasExclusions));
        if (workflowId == null) {
            where.append("AND s.workflow_id IS NULL ");
        } else {
            where.append("AND s.workflow_id = :wf ");
            // Pin a specific run only when navigating under a RUN folder (workflow with ≥2 runs).
            if (runId != null) {
                where.append("AND s.run_id = :run ");
            }
            if (epoch != null) {
                where.append("AND s.epoch = :epoch ");
            }
            if (spawn != null) {
                where.append("AND s.spawn = :spawn ");
            }
            if (nullItemOnly) {
                where.append("AND s.item_index IS NULL ");
            } else if (itemIndex != null) {
                where.append("AND s.item_index = :itemIndex ");
            }
        }
        // The shared file-row filter (filesOnly/s3Only/search/sourceType/storageType/fileCategory) -
        // the SAME predicate the virtual GROUP/preview queries apply, so a folder's content matches
        // what made the folder appear. Dates are virtual-scope-only here and stay inline.
        // Dates are applied inline below (and bound via setVirtualLeafParams), so the shared
        // file filter here carries no date - passing them would duplicate the predicate.
        appendVirtualFileFilters(where, new VirtualFileFilter(filesOnly, s3Only, search, sourceType, storageType, fileCategory, null, null));
        if (dateFrom != null) {
            where.append("AND s.created_at >= :dateFrom ");
        }
        if (dateTo != null) {
            where.append("AND s.created_at <= :dateTo ");
        }

        String countSql = "SELECT COUNT(*) FROM storage.storage s " + where;
        Query countQuery = em.createNativeQuery(countSql);
        setVirtualLeafParams(countQuery, organizationId, workflowId, runId, epoch, spawn, itemIndex, nullItemOnly,
                search, sourceType, storageType, dateFrom, dateTo, excludedIds);
        long total = ((Number) countQuery.getSingleResult()).longValue();
        if (total == 0) {
            return new SliceResult(List.of(), 0);
        }

        String dataSql = "SELECT " + PROJECTION_COLUMNS +
                " FROM storage.storage s " + where +
                " ORDER BY s.created_at DESC" +
                " LIMIT :limit OFFSET :offset";
        Query dataQuery = em.createNativeQuery(dataSql);
        setVirtualLeafParams(dataQuery, organizationId, workflowId, runId, epoch, spawn, itemIndex, nullItemOnly,
                search, sourceType, storageType, dateFrom, dateTo, excludedIds);
        dataQuery.setParameter("limit", limit);
        dataQuery.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = dataQuery.getResultList();
        return new SliceResult(mapRows(rows), total);
    }

    private void setVirtualLeafParams(Query query, String organizationId, String workflowId, String runId,
                                      Integer epoch, Integer spawn, Integer itemIndex, boolean nullItemOnly,
                                      String search, String sourceType, String storageType,
                                      Instant dateFrom, Instant dateTo, Collection<UUID> excludedIds) {
        query.setParameter("orgId", organizationId);
        if (excludedIds != null && !excludedIds.isEmpty()) {
            query.setParameter("excludedIds", excludedIds);
        }
        if (workflowId != null) {
            query.setParameter("wf", workflowId);
            if (runId != null) {
                query.setParameter("run", runId);
            }
            if (epoch != null) {
                query.setParameter("epoch", epoch);
            }
            if (spawn != null) {
                query.setParameter("spawn", spawn);
            }
            // NULL-item mode binds no :itemIndex (the SQL uses IS NULL); otherwise bind the equality.
            if (!nullItemOnly && itemIndex != null) {
                query.setParameter("itemIndex", itemIndex);
            }
        }
        if (search != null && !search.isBlank()) {
            query.setParameter("search", "%" + search + "%");
        }
        if (sourceType != null && !sourceType.isBlank()) {
            query.setParameter("sourceType", sourceType);
        }
        if (storageType != null && !storageType.isBlank()) {
            query.setParameter("storageType", storageType);
        }
        if (dateFrom != null) {
            query.setParameter("dateFrom", Timestamp.from(dateFrom));
        }
        if (dateTo != null) {
            query.setParameter("dateTo", Timestamp.from(dateTo));
        }
    }

    /**
     * The manual folders at the Files root ({@code is_folder = true AND parent_folder_id IS NULL}),
     * org-scoped, newest first. Used by the virtual-root composition so manual root folders sit
     * alongside the virtual workflow folders. A non-blank {@code search} filters folder names
     * ({@code file_name ILIKE}); {@code excludedIds} honours the member restricted-id deny-list.
     */
    public List<StorageExplorerProjection> listRootManualFolders(String organizationId, String search,
                                                                 Collection<UUID> excludedIds) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }
        boolean hasExclusions = excludedIds != null && !excludedIds.isEmpty();
        boolean hasSearch = search != null && !search.isBlank();
        String sql = "SELECT " + PROJECTION_COLUMNS + " FROM storage.storage s "
                + "WHERE s.organization_id = :orgId AND s.status = 'ACTIVE' "
                + "AND s.is_folder = true AND s.parent_folder_id IS NULL "
                + (hasExclusions ? "AND s.id NOT IN (:excludedIds) " : "")
                + (hasSearch ? "AND s.file_name ILIKE :search " : "")
                + "ORDER BY s.created_at DESC";

        Query query = em.createNativeQuery(sql);
        query.setParameter("orgId", organizationId);
        if (hasExclusions) {
            query.setParameter("excludedIds", excludedIds);
        }
        if (hasSearch) {
            query.setParameter("search", "%" + search + "%");
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return mapRows(rows);
    }

    /**
     * EVERY manual folder in the org ({@code is_folder = true}), regardless of nesting depth,
     * org-scoped, ordered by {@code file_name}. Mirrors {@link #listRootManualFolders} but WITHOUT
     * the {@code parent_folder_id IS NULL} restriction so the caller can build the full folder tree
     * (the Files "Move to…" picker). {@code excludedIds} drops the folders being moved (and their
     * descendants) so the picker never offers an invalid destination; the member restricted-id
     * deny-list can be folded into the same set. Manual folders are user-created and bounded, so an
     * unpaginated read is fine here.
     */
    public List<StorageExplorerProjection> listAllManualFolders(String organizationId,
                                                               Collection<UUID> excludedIds) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }
        boolean hasExclusions = excludedIds != null && !excludedIds.isEmpty();
        String sql = "SELECT " + PROJECTION_COLUMNS + " FROM storage.storage s "
                + "WHERE s.organization_id = :orgId AND s.status = 'ACTIVE' "
                + "AND s.is_folder = true "
                + (hasExclusions ? "AND s.id NOT IN (:excludedIds) " : "")
                + "ORDER BY s.file_name";

        Query query = em.createNativeQuery(sql);
        query.setParameter("orgId", organizationId);
        if (hasExclusions) {
            query.setParameter("excludedIds", excludedIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return mapRows(rows);
    }

    private void setFolderScopeParams(Query query, String organizationId, UUID parentFolderId,
                                      String search, String sourceType, String storageType,
                                      String workflowId, String runId, Instant dateFrom, Instant dateTo,
                                      Collection<UUID> excludedIds) {
        query.setParameter("orgId", organizationId);
        if (parentFolderId != null) {
            query.setParameter("parentFolderId", parentFolderId);
        }
        if (excludedIds != null && !excludedIds.isEmpty()) {
            query.setParameter("excludedIds", excludedIds);
        }
        if (search != null && !search.isBlank()) {
            query.setParameter("search", "%" + search + "%");
        }
        if (sourceType != null && !sourceType.isBlank()) {
            query.setParameter("sourceType", sourceType);
        }
        if (storageType != null && !storageType.isBlank()) {
            query.setParameter("storageType", storageType);
        }
        if (workflowId != null && !workflowId.isBlank()) {
            query.setParameter("workflowId", workflowId);
        }
        if (runId != null && !runId.isBlank()) {
            query.setParameter("runId", runId);
        }
        if (dateFrom != null) {
            query.setParameter("dateFrom", Timestamp.from(dateFrom));
        }
        if (dateTo != null) {
            query.setParameter("dateTo", Timestamp.from(dateTo));
        }
    }

    /**
     * SQL predicate for a file-type category - a server-side mirror of the frontend
     * {@code matchesFileType} (mime-type OR filename-extension). Returns a leading
     * {@code " AND (...)"} fragment, or {@code ""} for null/blank/{@code "_all"} or an
     * unknown category. The fragments are CONSTANTS (the category only selects which
     * fixed clause to use), so there is no SQL injection surface.
     */
    static String fileCategoryClause(String category) {
        if (category == null || category.isBlank() || "_all".equals(category)) return "";
        return switch (category) {
            case "images" -> " AND s.mime_type ILIKE 'image/%'";
            case "pdf" -> " AND s.mime_type ILIKE '%pdf%'";
            case "documents" -> " AND (s.mime_type ILIKE '%msword%' OR s.mime_type ILIKE '%wordprocessingml%'"
                    + " OR s.mime_type ILIKE '%opendocument.text%' OR s.mime_type ILIKE '%rtf%'"
                    + " OR s.file_name ILIKE '%.doc' OR s.file_name ILIKE '%.docx'"
                    + " OR s.file_name ILIKE '%.odt' OR s.file_name ILIKE '%.rtf')";
            case "spreadsheets" -> " AND (s.mime_type ILIKE '%spreadsheet%' OR s.mime_type ILIKE '%ms-excel%'"
                    + " OR s.mime_type ILIKE '%csv%' OR s.file_name ILIKE '%.csv' OR s.file_name ILIKE '%.xls'"
                    + " OR s.file_name ILIKE '%.xlsx' OR s.file_name ILIKE '%.ods')";
            case "presentations" -> " AND (s.mime_type ILIKE '%presentation%' OR s.mime_type ILIKE '%powerpoint%'"
                    + " OR s.file_name ILIKE '%.ppt' OR s.file_name ILIKE '%.pptx' OR s.file_name ILIKE '%.odp')";
            case "video" -> " AND s.mime_type ILIKE 'video/%'";
            case "audio" -> " AND s.mime_type ILIKE 'audio/%'";
            case "archives" -> " AND (s.mime_type ILIKE '%zip%' OR s.mime_type ILIKE '%tar%'"
                    + " OR s.mime_type ILIKE '%gzip%' OR s.mime_type ILIKE '%rar%' OR s.mime_type ILIKE '%7z%'"
                    + " OR s.mime_type ILIKE '%compressed%' OR s.file_name ILIKE '%.zip' OR s.file_name ILIKE '%.tar'"
                    + " OR s.file_name ILIKE '%.gz' OR s.file_name ILIKE '%.rar' OR s.file_name ILIKE '%.7z')";
            case "text" -> " AND s.mime_type ILIKE '%plain%'";
            case "code" -> " AND (s.mime_type ILIKE '%json%' OR s.mime_type ILIKE '%xml%'"
                    + " OR s.mime_type ILIKE '%javascript%' OR s.mime_type ILIKE '%typescript%'"
                    + " OR s.mime_type ILIKE '%html%' OR s.mime_type ILIKE '%css%' OR s.mime_type ILIKE '%yaml%'"
                    + " OR s.mime_type ILIKE '%yml%' OR s.file_name ILIKE '%.json' OR s.file_name ILIKE '%.xml'"
                    + " OR s.file_name ILIKE '%.js' OR s.file_name ILIKE '%.ts' OR s.file_name ILIKE '%.html'"
                    + " OR s.file_name ILIKE '%.css' OR s.file_name ILIKE '%.yaml' OR s.file_name ILIKE '%.yml'"
                    + " OR s.file_name ILIKE '%.py' OR s.file_name ILIKE '%.java' OR s.file_name ILIKE '%.sql')";
            default -> "";
        };
    }

    /**
     * Shared WHERE-clause builder for {@link #search} and {@link #searchSlice}.
     * {@code filesOnly=false}/{@code s3Only=false} reproduces the original explorer
     * predicate verbatim. {@code s3Only=true} restricts to real user-facing files -
     * {@code (s.s3_key IS NOT NULL OR s.source_type = 'CHAT_ATTACHMENT')} - i.e. object-storage
     * files PLUS DB-resident chat attachments, while still hiding observability TEXT pseudo-files
     * and avatars. Package-private for direct SQL-fragment unit testing (mirrors {@link #fileCategoryClause}).
     */
    static void appendWhere(StringBuilder where, boolean filesOnly, boolean s3Only, String search,
                            String sourceType, String storageType, String workflowId,
                            String runId, Instant dateFrom, Instant dateTo,
                            Collection<UUID> excludedIds) {
        // V313: manual folders are a Files-browser-only concept. The legacy flat path
        // (agent files list + side-panel explorer + stats + counts) is files-only, so
        // folder rows (which carry a non-null file_name) must never leak here. The
        // folder-AWARE path (listFolderScope) is the only place folders surface.
        where.append("WHERE s.organization_id = :orgId AND s.status = 'ACTIVE' AND s.is_folder = false");
        if (excludedIds != null && !excludedIds.isEmpty()) {
            where.append(" AND s.id NOT IN (:excludedIds)");
        }
        if (filesOnly) {
            where.append(" AND s.file_name IS NOT NULL");
        }
        if (s3Only) {
            // "Real user-facing files" for the full-page Files browser: object-storage files
            // (s3_key set) PLUS chat attachments - those are saved as DB BINARY with NO s3_key
            // (source_type='CHAT_ATTACHMENT'), so a pure `s3_key IS NOT NULL` test hid the
            // images a user uploaded in chat. Still excludes observability TEXT blobs
            // (tool_call_result.txt / agent_message.txt) and avatars (USER_AVATAR / ORG_AVATAR),
            // which carry neither an s3_key nor that source_type.
            where.append(" AND (s.s3_key IS NOT NULL OR s.source_type = 'CHAT_ATTACHMENT')");
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (s.file_name ILIKE :search OR s.content_type ILIKE :search OR s.step_key ILIKE :search)");
        }
        if (sourceType != null && !sourceType.isBlank()) {
            where.append(" AND s.source_type = :sourceType");
        }
        if (storageType != null && !storageType.isBlank()) {
            where.append(" AND s.storage_type = :storageType");
        }
        if (workflowId != null && !workflowId.isBlank()) {
            where.append(" AND s.workflow_id = :workflowId");
        }
        if (runId != null && !runId.isBlank()) {
            where.append(" AND s.run_id = :runId");
        }
        if (dateFrom != null) {
            where.append(" AND s.created_at >= :dateFrom");
        }
        if (dateTo != null) {
            where.append(" AND s.created_at <= :dateTo");
        }
    }

    /** Map native projection rows → DTO projections (shared by search/searchSlice/listFolderScope). */
    private static List<StorageExplorerProjection> mapRows(List<Object[]> rows) {
        List<StorageExplorerProjection> results = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            results.add(new StorageExplorerProjection(
                (UUID) row[0],
                (String) row[1],
                (String) row[2],
                (String) row[3],
                (String) row[4],
                row[5] != null ? ((Number) row[5]).intValue() : null,
                row[6] != null ? toInstant(row[6]) : null,
                (String) row[7],
                (UUID) row[8],
                (String) row[9],
                (String) row[10],
                row[11] != null ? ((Number) row[11]).intValue() : null,
                (String) row[12],
                (String) row[13],
                row[14] != null && (Boolean) row[14],
                (UUID) row[15]
            ));
        }
        return results;
    }

    /**
     * Org-scoped aggregate stats. Same scope rule as {@link #search}.
     */
    public List<Map<String, Object>> getStats(String organizationId) {
        return getStats(organizationId, List.of());
    }

    public List<Map<String, Object>> getStats(String organizationId, Collection<UUID> excludedIds) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId is required (post-V261 sweep)");
        }
        // V313: folders are a Files-browser-only concept - keep them out of the flat stats
        // aggregation (mirror the legacy flat appendWhere path).
        String sql = "SELECT source_type, storage_type, COUNT(*) as count, COALESCE(SUM(size_bytes), 0) as total_bytes " +
                "FROM storage.storage " +
                "WHERE organization_id = :orgId AND status = 'ACTIVE' AND is_folder = false " +
                ((excludedIds != null && !excludedIds.isEmpty()) ? "AND id NOT IN (:excludedIds) " : "") +
                "GROUP BY source_type, storage_type " +
                "ORDER BY count DESC";

        Query query = em.createNativeQuery(sql);
        query.setParameter("orgId", organizationId);
        if (excludedIds != null && !excludedIds.isEmpty()) {
            query.setParameter("excludedIds", excludedIds);
        }

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Map<String, Object>> stats = new ArrayList<>(rows.size());

        for (Object[] row : rows) {
            stats.add(Map.of(
                "sourceType", row[0] != null ? row[0] : "UNKNOWN",
                "storageType", row[1] != null ? row[1] : "UNKNOWN",
                "count", ((Number) row[2]).longValue(),
                "totalBytes", ((Number) row[3]).longValue()
            ));
        }

        return stats;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Instant inst) return inst;
        if (value instanceof Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        throw new IllegalArgumentException("Cannot convert " + value.getClass() + " to Instant");
    }

    /**
     * Render a native-query {@code id} column to its canonical UUID string. The element type of a
     * native-query {@code Object[]} varies by driver/dialect: the PostgreSQL driver returns a
     * {@link UUID}, while H2 (PostgreSQL mode) returns the raw 16-byte value. Mirrors the defensive
     * {@link #toInstant} / {@link #virtualKey} converters so the native preview queries map identically
     * on either backend (a bare {@code (UUID) cast} threw a {@code ClassCastException} on H2).
     */
    private static String uuidString(Object value) {
        if (value == null) return null;
        if (value instanceof UUID u) return u.toString();
        if (value instanceof String s) return s;
        if (value instanceof byte[] b && b.length == 16) {
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b);
            return new UUID(bb.getLong(), bb.getLong()).toString();
        }
        return value.toString();
    }

    private void setParams(Query query, String organizationId, String search,
                           String sourceType, String storageType, String workflowId, String runId,
                           Instant dateFrom, Instant dateTo, Collection<UUID> excludedIds) {
        query.setParameter("orgId", organizationId);
        if (excludedIds != null && !excludedIds.isEmpty()) {
            query.setParameter("excludedIds", excludedIds);
        }
        if (search != null && !search.isBlank()) {
            query.setParameter("search", "%" + search + "%");
        }
        if (sourceType != null && !sourceType.isBlank()) {
            query.setParameter("sourceType", sourceType);
        }
        if (storageType != null && !storageType.isBlank()) {
            query.setParameter("storageType", storageType);
        }
        if (workflowId != null && !workflowId.isBlank()) {
            query.setParameter("workflowId", workflowId);
        }
        if (runId != null && !runId.isBlank()) {
            query.setParameter("runId", runId);
        }
        if (dateFrom != null) {
            query.setParameter("dateFrom", Timestamp.from(dateFrom));
        }
        if (dateTo != null) {
            query.setParameter("dateTo", Timestamp.from(dateTo));
        }
    }
}
