package com.apimarketplace.common.storage.repository;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository pour les operations de stockage
 * Respecte les principes SOLID et les bonnes pratiques
 */
@Repository
public interface StorageRepository extends JpaRepository<StorageEntity, UUID> {
    
    /**
     * Trouve un storage par ID et tenant (securite).
     *
     * @deprecated Batch-C: prefer {@link #findByIdAndOrganizationIdStrict(UUID, String)}
     *   for new code. Kept because cross-service callers in orchestrator-service
     *   ({@code StepOutputService}, {@code StorageNestedService},
     *   {@code StorageSkeletonService}) are owned by Batch B's reroute sweep.
     *   Will be removed once those callers migrate.
     */
    @Deprecated(since = "Batch-C", forRemoval = false)
    @Query("SELECT s FROM StorageEntity s WHERE s.id = :id AND s.tenantId = :tenantId AND s.status = 'ACTIVE'")
    Optional<StorageEntity> findByIdAndTenantId(@Param("id") UUID id, @Param("tenantId") String tenantId);

    /**
     * Strict org-scope single fetch - returns row ONLY if it belongs to the given org.
     * Tenant of the calling user is NOT checked here; org membership is asserted
     * upstream by the controller (X-Organization-Role gateway claim).
     *
     * <p>Post-V261: every user-scoped row has a non-null {@code organization_id}
     * (gateway always injects {@code X-Organization-ID}; personal-workspace users
     * resolve to their personal-org UUID). This is the single strict-scope finder
     * for {@code (id, orgId)}; the legacy {@code findByIdAndTenantIdAndOrganizationIdIsNull}
     * was removed in the post-V261 sweep.</p>
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.id = :id AND s.organizationId = :orgId AND s.status = 'ACTIVE'")
    Optional<StorageEntity> findByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    /**
     * Strict org-scope fetch of a MANUAL FOLDER row only (V313). Returns the row only when it is a
     * folder ({@code is_folder = true}) owned by the given org - used to validate a move/create
     * target and to walk the parent chain for cycle detection.
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.id = :id AND s.organizationId = :orgId "
         + "AND s.isFolder = true AND s.status = 'ACTIVE'")
    Optional<StorageEntity> findFolderByIdAndOrganizationIdStrict(@Param("id") UUID id, @Param("orgId") String orgId);

    /**
     * Direct children (folders + files) of a manual folder, org-scoped + ACTIVE. Used when deleting
     * a folder to re-parent its children up one level (never cascade-delete files).
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.parentFolderId = :parentFolderId "
         + "AND s.organizationId = :orgId AND s.status = 'ACTIVE'")
    List<StorageEntity> findChildrenByParentFolderIdAndOrganizationId(@Param("parentFolderId") UUID parentFolderId,
                                                                      @Param("orgId") String orgId);

    /**
     * Trouve tous les storages d'un tenant.
     *
     * @deprecated Batch-C: prefer {@link #findByOrganizationIdStrict(String)} for
     *   org-aware listings. Kept because cross-service callers + legacy
     *   personal-workspace path in {@code StorageService.listByTenant} still
     *   route through it.
     */
    @Deprecated(since = "Batch-C", forRemoval = false)
    @Query("SELECT s FROM StorageEntity s WHERE s.tenantId = :tenantId AND s.status = 'ACTIVE' ORDER BY s.createdAt DESC")
    List<StorageEntity> findByTenantId(@Param("tenantId") String tenantId);

    /**
     * Strict org-scope listing - every row owned by this org regardless of which
     * member created it. Used when X-Organization-ID is set on the request.
     *
     * <p>Post-V261: the legacy personal-scope listing
     * {@code findByTenantIdAndOrganizationIdIsNull} was removed; org_id is always
     * non-null, so this is the only strict-isolation listing path.</p>
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.organizationId = :orgId AND s.status = 'ACTIVE' "
         + "ORDER BY s.createdAt DESC")
    List<StorageEntity> findByOrganizationIdStrict(@Param("orgId") String orgId);

    @Query("SELECT s FROM StorageEntity s WHERE s.projectId = :projectId AND s.organizationId = :orgId "
         + "AND s.status = 'ACTIVE' AND s.fileName IS NOT NULL ORDER BY s.createdAt DESC")
    List<StorageEntity> findFilesByProjectIdAndOrganizationId(@Param("projectId") UUID projectId,
                                                               @Param("orgId") String organizationId);

    @Query("SELECT COUNT(s) FROM StorageEntity s WHERE s.projectId = :projectId AND s.organizationId = :orgId "
         + "AND s.status = 'ACTIVE' AND s.fileName IS NOT NULL")
    long countFilesByProjectIdAndOrganizationId(@Param("projectId") UUID projectId,
                                                @Param("orgId") String organizationId);

    @Modifying
    @Query("UPDATE StorageEntity s SET s.projectId = :projectId WHERE s.id = :id AND s.organizationId = :orgId "
         + "AND s.status = 'ACTIVE' AND s.fileName IS NOT NULL")
    int updateFileProjectIdForScope(@Param("id") UUID id,
                                    @Param("orgId") String organizationId,
                                    @Param("projectId") UUID projectId);

    @Modifying
    @Query("UPDATE StorageEntity s SET s.projectId = NULL WHERE s.projectId = :projectId AND s.organizationId = :orgId")
    int clearProjectIdForFilesInScope(@Param("projectId") UUID projectId,
                                      @Param("orgId") String organizationId);

    /**
     * Calcule l'usage total d'un tenant.
     *
     * @deprecated Batch-C: prefer {@link #calculateOrganizationUsage(String)}
     *   for org-aware quota reconciliation. Still used by
     *   {@code QuotaService.calculateActualUsage} on the legacy tenant code
     *   path until Phase 11.
     */
    @Deprecated(since = "Batch-C", forRemoval = false)
    @Query("SELECT COALESCE(SUM(s.sizeBytes), 0) FROM StorageEntity s WHERE s.tenantId = :tenantId AND s.status = 'ACTIVE'")
    Long calculateTenantUsage(@Param("tenantId") String tenantId);

    /**
     * Calcule l'usage total d'une organisation (toutes lignes tagguees org).
     * Source of truth for organization_storage_quota.used_bytes reconciliation.
     */
    @Query("SELECT COALESCE(SUM(s.sizeBytes), 0) FROM StorageEntity s "
         + "WHERE s.organizationId = :orgId AND s.status = 'ACTIVE'")
    Long calculateOrganizationUsage(@Param("orgId") String orgId);
    
    /**
     * Trouve les storages expires
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.expiresAt < :now AND s.status = 'ACTIVE'")
    List<StorageEntity> findExpiredStorages(@Param("now") Instant now);
    
    /**
     * Trouve les storages non accedes depuis une date
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.accessedAt < :before AND s.status = 'ACTIVE'")
    List<StorageEntity> findStoragesNotAccessedSince(@Param("before") Instant before);
    
    /**
     * Met a jour le statut d'un storage
     */
    @Modifying
    @Query("UPDATE StorageEntity s SET s.status = :status WHERE s.id = :id")
    int updateStatus(@Param("id") UUID id, @Param("status") StorageStatus status);
    
    /**
     * Met a jour la date d'acces
     */
    @Modifying
    @Query("UPDATE StorageEntity s SET s.accessedAt = :accessedAt WHERE s.id = :id")
    int updateAccessedAt(@Param("id") UUID id, @Param("accessedAt") Instant accessedAt);
    
    /**
     * Supprime les storages d'un tenant (soft delete).
     *
     * @deprecated Batch-C: no in-tree caller writes through this. Kept for
     *   admin tooling / future tenant retirement scripts that operate
     *   pre-V261 (organization_id NULL) rows.
     */
    @Deprecated(since = "Batch-C", forRemoval = false)
    @Modifying
    @Query("UPDATE StorageEntity s SET s.status = 'DELETED' WHERE s.tenantId = :tenantId AND s.status = 'ACTIVE'")
    int softDeleteByTenantId(@Param("tenantId") String tenantId);

    /**
     * Soft-delete S3 files within a date range for a tenant.
     *
     * @deprecated Batch-C: prefer {@link #softDeleteByOrgIdAndDateRange(String, Instant, Instant)}.
     *   Still used by {@code StorageService.deleteByDateRange} on the legacy
     *   tenant code path.
     */
    @Deprecated(since = "Batch-C", forRemoval = false)
    @Modifying
    @Query("UPDATE StorageEntity s SET s.status = 'DELETED' WHERE s.tenantId = :tenantId AND s.status = 'ACTIVE' AND s.sourceType = 'S3_FILE' AND s.createdAt >= :dateFrom AND s.createdAt < :dateTo")
    int softDeleteByDateRange(@Param("tenantId") String tenantId, @Param("dateFrom") Instant dateFrom, @Param("dateTo") Instant dateTo);

    /**
     * Strict org-scope bulk soft-delete of S3 files within a date range. Mirror
     * of {@link #softDeleteByDateRange}; filters by {@code organization_id} only
     * so admins/owners can purge org files independently of who created them.
     */
    @Modifying
    @Query("UPDATE StorageEntity s SET s.status = 'DELETED' WHERE s.organizationId = :orgId "
         + "AND s.status = 'ACTIVE' AND s.sourceType = 'S3_FILE' "
         + "AND s.createdAt >= :dateFrom AND s.createdAt < :dateTo")
    int softDeleteByOrgIdAndDateRange(@Param("orgId") String organizationId,
                                       @Param("dateFrom") Instant dateFrom,
                                       @Param("dateTo") Instant dateTo);

    @Modifying
    @Query("UPDATE StorageEntity s SET s.status = 'DELETED' WHERE s.organizationId = :orgId "
         + "AND s.status = 'ACTIVE' AND s.sourceType = 'S3_FILE' AND s.id NOT IN (:excludedIds) "
         + "AND s.createdAt >= :dateFrom AND s.createdAt < :dateTo")
    int softDeleteByOrgIdAndDateRangeExcludingIds(@Param("orgId") String organizationId,
                                                  @Param("dateFrom") Instant dateFrom,
                                                  @Param("dateTo") Instant dateTo,
                                                  @Param("excludedIds") java.util.Collection<UUID> excludedIds);
    
    /**
     * Trouve les storages par type de contenu
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.tenantId = :tenantId AND s.contentType = :contentType AND s.status = 'ACTIVE'")
    List<StorageEntity> findByTenantIdAndContentType(@Param("tenantId") String tenantId, @Param("contentType") String contentType);
    
    /**
     * Trouve les storages par type de stockage
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.tenantId = :tenantId AND s.storageType = :storageType AND s.status = 'ACTIVE'")
    List<StorageEntity> findByTenantIdAndStorageType(@Param("tenantId") String tenantId, @Param("storageType") String storageType);

    /**
     * Finds storages by tenant and source type (e.g. USER_AVATAR).
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.tenantId = :tenantId AND s.sourceType = :sourceType AND s.status = 'ACTIVE' ORDER BY s.createdAt DESC")
    List<StorageEntity> findByTenantIdAndSourceType(@Param("tenantId") String tenantId, @Param("sourceType") String sourceType);
    
    /**
     * Trouve les storages par extension de fichier
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.tenantId = :tenantId AND s.fileExtension = :fileExtension AND s.status = 'ACTIVE'")
    List<StorageEntity> findByTenantIdAndFileExtension(@Param("tenantId") String tenantId, @Param("fileExtension") String fileExtension);
    
    /**
     * Trouve les storages par MIME type
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.tenantId = :tenantId AND s.mimeType = :mimeType AND s.status = 'ACTIVE'")
    List<StorageEntity> findByTenantIdAndMimeType(@Param("tenantId") String tenantId, @Param("mimeType") String mimeType);
    
    /**
     * Trouve les storages par nom de fichier
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.tenantId = :tenantId AND s.fileName LIKE :fileNamePattern AND s.status = 'ACTIVE'")
    List<StorageEntity> findByTenantIdAndFileNameLike(@Param("tenantId") String tenantId, @Param("fileNamePattern") String fileNamePattern);

    /**
     * Extrait une valeur JSON à un chemin donné directement en SQL.
     * Le chemin est un tableau de clés, ex: {"output", "output", "response", "data", "user", "username"}
     * Retourne null si le chemin n'existe pas.
     */
    @Query(value = "SELECT jsonb_extract_path_text(s.data, VARIADIC :path) " +
                   "FROM storage.storage s " +
                   "WHERE s.id = :id AND s.tenant_id = :tenantId AND s.status = 'ACTIVE'",
           nativeQuery = true)
    String extractJsonPath(@Param("id") UUID id, @Param("tenantId") String tenantId, @Param("path") String[] path);

    /**
     * Extrait un sous-objet JSON à un chemin donné directement en SQL.
     * Retourne le JSON sous forme de String pour parsing ultérieur.
     */
    @Query(value = "SELECT jsonb_extract_path(s.data, VARIADIC :path)::text " +
                   "FROM storage.storage s " +
                   "WHERE s.id = :id AND s.tenant_id = :tenantId AND s.status = 'ACTIVE'",
           nativeQuery = true)
    String extractJsonObject(@Param("id") UUID id, @Param("tenantId") String tenantId, @Param("path") String[] path);

    /**
     * Retrieves only the structure skeleton without loading the full data payload.
     * Optimized for frontend lazy loading - returns lightweight schema for tree display.
     */
    @Query(value = "SELECT s.structure_skeleton::text " +
                   "FROM storage.storage s " +
                   "WHERE s.id = :id AND s.tenant_id = :tenantId AND s.status = 'ACTIVE'",
           nativeQuery = true)
    String getSkeletonOnly(@Param("id") UUID id, @Param("tenantId") String tenantId);

    /**
     * Retrieves skeleton and metadata without the full data payload.
     * Returns: id, content_type, size_bytes, created_at, structure_skeleton
     */
    @Query(value = "SELECT s.id, s.content_type, s.size_bytes, s.created_at, s.structure_skeleton::text as skeleton " +
                   "FROM storage.storage s " +
                   "WHERE s.id = :id AND s.tenant_id = :tenantId AND s.status = 'ACTIVE'",
           nativeQuery = true)
    Object[] getMetadataWithSkeleton(@Param("id") UUID id, @Param("tenantId") String tenantId);

    /**
     * Soft-delete all storage entries for a workflow (across all runs).
     */
    @Modifying
    @Query("UPDATE StorageEntity s SET s.status = 'DELETED' WHERE s.workflowId = :workflowId AND s.status = 'ACTIVE'")
    int softDeleteByWorkflowId(@Param("workflowId") String workflowId);

    /**
     * Strict org-scope soft-delete of a VIRTUAL workflow folder's contents (Files browser).
     *
     * <p>Deletes every active file row that the virtual {@code workflow → [run →] epoch → spawn →
     * iteration} folder groups, so a user can remove a whole workflow folder (or a single
     * epoch/spawn/iteration sub-folder) the same way they delete a manual folder. The address
     * coordinates are "match-or-any": a {@code null} param leaves that level unconstrained, so a
     * WORKFLOW-level delete ({@code runId/epoch/spawn/itemIndex} all null) wipes the workflow's whole
     * subtree, while a deeper address narrows to one run/epoch/spawn/iteration.</p>
     *
     * <p>{@code parent_folder_id IS NULL} mirrors {@code searchVirtualScope}: a file moved into a
     * MANUAL folder has left the virtual tree, so it is NOT part of this folder and is preserved.
     * {@code file_name IS NOT NULL AND s3_key IS NOT NULL} matches the Files browser's
     * {@code filesOnly + s3Only} view, so the delete removes exactly the files the folder shows (its
     * displayed child count) and never touches the workflow's machine step-output blobs.</p>
     */
    @Modifying
    @Query("UPDATE StorageEntity s SET s.status = 'DELETED' "
         + "WHERE s.organizationId = :orgId AND s.status = 'ACTIVE' "
         + "AND s.parentFolderId IS NULL AND s.workflowId = :workflowId "
         + "AND s.fileName IS NOT NULL AND s.s3Key IS NOT NULL "
         + "AND (:runId IS NULL OR s.runId = :runId) "
         + "AND (:epoch IS NULL OR s.epoch = :epoch) "
         + "AND (:spawn IS NULL OR s.spawn = :spawn) "
         + "AND (:itemIndex IS NULL OR s.itemIndex = :itemIndex)")
    int softDeleteByVirtualScope(@Param("orgId") String organizationId,
                                 @Param("workflowId") String workflowId,
                                 @Param("runId") String runId,
                                 @Param("epoch") Integer epoch,
                                 @Param("spawn") Integer spawn,
                                 @Param("itemIndex") Integer itemIndex);

    /** {@link #softDeleteByVirtualScope} variant that preserves restricted-member deny-listed ids. */
    @Modifying
    @Query("UPDATE StorageEntity s SET s.status = 'DELETED' "
         + "WHERE s.organizationId = :orgId AND s.status = 'ACTIVE' "
         + "AND s.parentFolderId IS NULL AND s.workflowId = :workflowId "
         + "AND s.fileName IS NOT NULL AND s.s3Key IS NOT NULL "
         + "AND s.id NOT IN (:excludedIds) "
         + "AND (:runId IS NULL OR s.runId = :runId) "
         + "AND (:epoch IS NULL OR s.epoch = :epoch) "
         + "AND (:spawn IS NULL OR s.spawn = :spawn) "
         + "AND (:itemIndex IS NULL OR s.itemIndex = :itemIndex)")
    int softDeleteByVirtualScopeExcludingIds(@Param("orgId") String organizationId,
                                             @Param("workflowId") String workflowId,
                                             @Param("runId") String runId,
                                             @Param("epoch") Integer epoch,
                                             @Param("spawn") Integer spawn,
                                             @Param("itemIndex") Integer itemIndex,
                                             @Param("excludedIds") java.util.Collection<UUID> excludedIds);

    // ========== Run Context Queries (Epoch-Aware) ==========

    /**
     * Find all storage entries for a workflow run filtered by epoch.
     * Returns step_key and data for building SpEL context.
     *
     * <p>Order: {@code created_at, id} (id DESC tiebreaker for deterministic last-wins
     * when multiple rows share the same millisecond timestamp - required by
     * {@code RunContextService.buildPerItemContext} for stable per-item resolution).
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.runId = :runId AND s.epoch = :epoch AND s.tenantId = :tenantId AND s.status = 'ACTIVE' ORDER BY s.createdAt, s.id DESC")
    List<StorageEntity> findByRunIdAndEpoch(@Param("runId") String runId, @Param("epoch") int epoch, @Param("tenantId") String tenantId);

    /**
     * Find the most recent spawn for each (step_key, item_index) within an epoch.
     *
     * <p>For spawn-based context loading: a node executed at (epoch=E, spawn=S)
     * should see predecessor outputs from the most recent spawn <= S.
     * This allows rerun nodes to see outputs from predecessors that weren't
     * re-executed (spawn=0) while using fresh outputs from re-executed nodes (spawn=1+).
     *
     * <p>DISTINCT ON {@code (step_key, item_index)} preserves per-item rows in split scope:
     * a split with N items writes N storages (one per item_index) for each per-item node.
     * Collapsing only on {@code step_key} would silently drop N-1 items on rerun (this was
     * a latent bug fixed in 2026-05-08 alongside the Daily Email Digest split context fix).
     *
     * <p>Order: {@code item_index NULLS LAST} ranks per-item rows ahead of shared rows,
     * {@code spawn DESC, created_at DESC, id DESC} picks latest version with deterministic tiebreaker.
     *
     * @param runId The workflow run ID
     * @param epoch The epoch to filter by
     * @param maxSpawn Maximum spawn to consider (inclusive)
     * @param tenantId The tenant ID
     * @return List of storage entities, one per (step_key, item_index) with latest spawn <= maxSpawn
     */
    @Query(value = """
        SELECT DISTINCT ON (s.step_key, s.item_index) s.*
        FROM storage.storage s
        WHERE s.run_id = :runId AND s.epoch = :epoch AND s.spawn <= :maxSpawn
          AND s.tenant_id = :tenantId AND s.status = 'ACTIVE'
        ORDER BY s.step_key, s.item_index NULLS LAST, s.spawn DESC, s.created_at DESC, s.id DESC
        """, nativeQuery = true)
    List<StorageEntity> findByRunIdAndEpochWithLatestSpawn(
        @Param("runId") String runId,
        @Param("epoch") int epoch,
        @Param("maxSpawn") int maxSpawn,
        @Param("tenantId") String tenantId);

    /**
     * Find storage entry for a specific step in a run, filtered by epoch.
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.runId = :runId AND s.stepKey = :stepKey AND s.epoch = :epoch AND s.tenantId = :tenantId AND s.status = 'ACTIVE'")
    Optional<StorageEntity> findByRunIdAndStepKeyAndEpoch(@Param("runId") String runId, @Param("stepKey") String stepKey, @Param("epoch") int epoch, @Param("tenantId") String tenantId);

    /**
     * Find storage entry for a specific step, item index, and epoch (for loops/splits).
     */
    @Query("SELECT s FROM StorageEntity s WHERE s.runId = :runId AND s.stepKey = :stepKey AND s.itemIndex = :itemIndex AND s.epoch = :epoch AND s.tenantId = :tenantId AND s.status = 'ACTIVE'")
    Optional<StorageEntity> findByRunIdAndStepKeyAndItemIndexAndEpoch(
        @Param("runId") String runId,
        @Param("stepKey") String stepKey,
        @Param("itemIndex") Integer itemIndex,
        @Param("epoch") int epoch,
        @Param("tenantId") String tenantId);

    /**
     * Extract a specific value from a step's output using JSON path, filtered by epoch.
     */
    @Query(value = "SELECT jsonb_extract_path_text(s.data, VARIADIC :path) " +
                   "FROM storage.storage s " +
                   "WHERE s.run_id = :runId AND s.step_key = :stepKey AND s.epoch = :epoch AND s.tenant_id = :tenantId AND s.status = 'ACTIVE'",
           nativeQuery = true)
    String extractValueFromStepWithEpoch(
        @Param("runId") String runId,
        @Param("stepKey") String stepKey,
        @Param("epoch") int epoch,
        @Param("tenantId") String tenantId,
        @Param("path") String[] path);

    // ========== Narrow Run-Context Loading (post-2026-05-22 OOM hardening) ==========

    /**
     * Batch hard-delete by id set, no entity hydration. Used by
     * {@code RunCloneService.deleteClonedRun} on the showcase deletion path where the
     * caller only needs the rows gone, not the entities loaded. Replaces the
     * {@code findAllById(ids).then(deleteAll)} dance that materialised the full JSONB
     * column just to call delete.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM StorageEntity s WHERE s.id IN :ids")
    int deleteAllByIds(@Param("ids") java.util.Collection<UUID> ids);

    /**
     * Distinct {@code step_key} values for an epoch. Cheap projection (no JSONB), used by
     * {@code RunContextService} to build the alias → full-key map before narrowing the
     * heavy {@code findByRunIdAndEpochAndStepKeyInBounded} fetch.
     */
    @Query("SELECT DISTINCT s.stepKey FROM StorageEntity s " +
           "WHERE s.runId = :runId AND s.epoch = :epoch " +
           "  AND s.tenantId = :tenantId AND s.status = 'ACTIVE' " +
           "  AND s.stepKey IS NOT NULL")
    List<String> findDistinctStepKeysByRunIdAndEpoch(
        @Param("runId") String runId,
        @Param("epoch") int epoch,
        @Param("tenantId") String tenantId);


    /**
     * Bounded variant of {@link #findByRunIdAndEpoch} - fetches only rows whose {@code stepKey}
     * is in the supplied set AND whose persisted {@code size_bytes} is strictly less than the
     * configured cap. Used by {@code RunContextService.loadRunContextForItem} after extracting
     * the step_keys referenced by SpEL mappings.
     *
     * <p>Two narrowing predicates together prevent the 2026-05-22 OOM shape:
     * <ul>
     *   <li>{@code stepKey IN :stepKeys} - drops every step output not actually consumed by the
     *       template (saves ~30 rows × ~5 KB each on the Gmail Auto-Labeler shape).
     *   <li>{@code sizeBytes < :maxRowBytes} - skips humongous rows ({@code table:load_processed}
     *       at 235 KB was the load-bearing leak). Callers detect missing keys against the
     *       requested set and substitute an oversized marker via
     *       {@link #findOversizedStepKeyMetaForEpoch}.
     * </ul>
     *
     * <p>{@code size_bytes} is populated by {@code StorageService} at write time
     * ({@code StorageEntity.sizeBytes} is NOT NULL), so the predicate is cheap - no
     * {@code octet_length(data::text)} cast needed.
     */
    @Query("SELECT s FROM StorageEntity s " +
           "WHERE s.runId = :runId AND s.epoch = :epoch AND s.stepKey IN :stepKeys " +
           "  AND s.tenantId = :tenantId AND s.status = 'ACTIVE' " +
           "  AND s.sizeBytes < :maxRowBytes " +
           "ORDER BY s.createdAt, s.id DESC")
    List<StorageEntity> findByRunIdAndEpochAndStepKeyInBounded(
        @Param("runId") String runId,
        @Param("epoch") int epoch,
        @Param("stepKeys") java.util.Collection<String> stepKeys,
        @Param("maxRowBytes") int maxRowBytes,
        @Param("tenantId") String tenantId);

    /**
     * All per-item ACTIVE output rows for ONE step within one epoch, oldest first.
     *
     * <p>Used by {@code StepOutputService.loadPerItemNodeOutputs} as the durable
     * per-item fallback for split→aggregate resolution: when the in-memory
     * {@code SplitContext.resultsByNode} lost a routed item's predecessor output
     * (cross-pod async-agent resume, or post-restart), the aggregate reads the
     * persisted per-item outputs straight from here - one query per referenced node
     * (no N+1 over items, no {@code workflow_step_data} round-trip). Item index +
     * payload come back in a single row.
     *
     * <p>Covered by {@code idx_storage_run_step_epoch (run_id, step_key, epoch)} →
     * index range scan, no seq scan. {@code ORDER BY createdAt ASC} so a caller keying
     * by {@code itemIndex} sees the latest spawn/rerun win (last-write-wins), matching
     * the in-memory {@code withResultAtIndex} semantics. Unlike
     * {@link #findByRunIdAndEpochAndStepKeyInBounded} this has NO size cap - the
     * fallback must return a node's output even when it is large, or it would
     * re-introduce the very emptiness it repairs.
     */
    @Query("SELECT s FROM StorageEntity s " +
           "WHERE s.runId = :runId AND s.epoch = :epoch AND s.stepKey = :stepKey " +
           "  AND s.tenantId = :tenantId AND s.status = 'ACTIVE' " +
           "ORDER BY s.createdAt ASC, s.spawn ASC, s.id ASC")
    List<StorageEntity> findByRunIdAndEpochAndStepKey(
        @Param("runId") String runId,
        @Param("epoch") int epoch,
        @Param("stepKey") String stepKey,
        @Param("tenantId") String tenantId);

    /**
     * Returns metadata for rows that exceeded the size cap of
     * {@link #findByRunIdAndEpochAndStepKeyInBounded}. Callers stamp an
     * {@code {"__oversized": true, "size_bytes": N}} marker into the SpEL context for these
     * step_keys so templates can detect oversized data without ever loading the JSONB.
     *
     * <p>Returns {@code Object[]{stepKey, sizeBytes}} per row - keep the projection small.
     */
    @Query("SELECT s.stepKey, s.sizeBytes FROM StorageEntity s " +
           "WHERE s.runId = :runId AND s.epoch = :epoch AND s.stepKey IN :stepKeys " +
           "  AND s.tenantId = :tenantId AND s.status = 'ACTIVE' " +
           "  AND s.sizeBytes >= :maxRowBytes")
    List<Object[]> findOversizedStepKeyMetaForEpoch(
        @Param("runId") String runId,
        @Param("epoch") int epoch,
        @Param("stepKeys") java.util.Collection<String> stepKeys,
        @Param("maxRowBytes") int maxRowBytes,
        @Param("tenantId") String tenantId);

    // ========== JSONB Array Pagination Queries ==========

    /**
     * Count the number of elements in a JSONB array at a given dot-separated path
     * within a storage row identified by (runId, stepKey, epoch, tenantId).
     *
     * <p>Example: for expression {@code {{mcp:fetch.output.items}}}, the caller passes
     * {@code stepKey="mcp:fetch"} and {@code jsonPath="output.items"}. The query navigates
     * the JSONB tree to the array and returns its length - without deserializing the array
     * contents into Java. Returns null if the path doesn't exist or the value isn't an array.
     */
    @Query(value = """
        SELECT CASE
          WHEN jsonb_typeof(s.data #> string_to_array(:jsonPath, '.')) = 'array'
          THEN jsonb_array_length(s.data #> string_to_array(:jsonPath, '.'))
          ELSE NULL
        END
        FROM storage.storage s
        WHERE s.run_id = :runId AND s.step_key = :stepKey AND s.epoch = :epoch
          AND s.tenant_id = :tenantId AND s.status = 'ACTIVE'
        ORDER BY s.created_at DESC, s.id DESC
        LIMIT 1
        """, nativeQuery = true)
    Integer countArrayAtPath(
        @Param("runId") String runId,
        @Param("stepKey") String stepKey,
        @Param("epoch") int epoch,
        @Param("tenantId") String tenantId,
        @Param("jsonPath") String jsonPath);

    /**
     * Extract a page of elements from a JSONB array at a given dot-separated path.
     * Returns each array element as a JSON text string, paginated via LIMIT/OFFSET.
     *
     * <p>This avoids deserializing the entire array (e.g. 485 emails) into Java -
     * only the requested page (e.g. 50 items) leaves PostgreSQL.
     *
     * <p>The CTE isolates the single latest storage row first (same selection as
     * {@link #countArrayAtPath}), then LATERAL-joins against that single row. Without
     * the CTE, a LATERAL join against multiple matching rows would produce a
     * cross-product of elements from all rows, interleaving results incorrectly.
     */
    @Query(value = """
        WITH latest AS (
          SELECT s.data
          FROM storage.storage s
          WHERE s.run_id = :runId AND s.step_key = :stepKey AND s.epoch = :epoch
            AND s.tenant_id = :tenantId AND s.status = 'ACTIVE'
          ORDER BY s.created_at DESC, s.id DESC
          LIMIT 1
        )
        SELECT elem.value::text
        FROM latest,
        LATERAL jsonb_array_elements(latest.data #> string_to_array(:jsonPath, '.')) WITH ORDINALITY AS elem(value, ord)
        ORDER BY elem.ord
        LIMIT :pageSize OFFSET :offset
        """, nativeQuery = true)
    List<String> getArraySliceAtPath(
        @Param("runId") String runId,
        @Param("stepKey") String stepKey,
        @Param("epoch") int epoch,
        @Param("tenantId") String tenantId,
        @Param("jsonPath") String jsonPath,
        @Param("pageSize") int pageSize,
        @Param("offset") int offset);
}
