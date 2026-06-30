package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.dto.MappingResolutionResult;
import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.service.api.StorageOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service principal pour la gestion du stockage.
 * Respecte les principes SOLID:
 * - SRP: Delegue les responsabilites aux services specialises
 * - OCP: Extensible via les interfaces
 * - LSP: Implemente l'interface StorageOperations
 * - ISP: Interfaces segregees (StorageOperations, QuotaOperations, MappingOperations)
 * - DIP: Depend des abstractions (interfaces)
 */
@Service
@Transactional
public class StorageService implements StorageOperations {

    private static final Logger logger = LoggerFactory.getLogger(StorageService.class);

    // NOTE: intentionally NOT including STEP_OUTPUT / INTERFACE_SCREENSHOT here. This set drives the
    // FILES-vs-STEP_OUTPUTS breakdown category for saveJson/update/delete; a JSON step output also
    // carries sourceType=STEP_OUTPUT and must stay in the STEP_OUTPUTS rollup. The S3 file-index path
    // always books "FILES" directly in trackUsageBestEffort, so file-typed STEP_OUTPUT/screenshot rows
    // are already counted correctly on save.
    private static final java.util.Set<String> FILE_SOURCE_TYPES =
            java.util.Set.of(StorageSourceTypes.S3_FILE, StorageSourceTypes.CHAT_ATTACHMENT);

    /** Sentinel content/storage/source type for a V313 manual folder row (no real payload). */
    private static final String FOLDER_CONTENT_TYPE = "application/x-directory";
    private static final String FOLDER_STORAGE_TYPE = "FOLDER";
    private static final String FOLDER_SOURCE_TYPE = "FOLDER";
    /** Max length of a manual folder name (trimmed). */
    private static final int MAX_FOLDER_NAME_LENGTH = 100;

    private final StorageRepository storageRepository;
    private final QuotaOperations quotaService;
    private final MappingOperations mappingService;
    private final StorageUtils storageUtils;
    private final JsonSkeletonGenerator skeletonGenerator;
    private final ObjectMapper objectMapper;
    private final StorageBreakdownService breakdownService;

    public StorageService(StorageRepository storageRepository,
                         QuotaOperations quotaService,
                         MappingOperations mappingService,
                         StorageUtils storageUtils,
                         JsonSkeletonGenerator skeletonGenerator,
                         ObjectMapper objectMapper,
                         StorageBreakdownService breakdownService) {
        this.storageRepository = storageRepository;
        this.quotaService = quotaService;
        this.mappingService = mappingService;
        this.storageUtils = storageUtils;
        this.skeletonGenerator = skeletonGenerator;
        this.objectMapper = objectMapper;
        this.breakdownService = breakdownService;
    }

    @Override
    public UUID saveJson(String tenantId, Object data, String contentType, Instant expiresAt) {
        return saveJson(tenantId, data, contentType, expiresAt, null);
    }

    @Override
    public UUID saveJson(String tenantId, Object data, String contentType, Instant expiresAt, UUID toolId) {
        return saveJsonWithContext(tenantId, data, contentType, expiresAt, toolId, null, null, null, 0);
    }

    @Override
    public UUID saveJsonWithContext(String tenantId, Object data, String contentType, Instant expiresAt,
                                    UUID toolId, String runId, String stepKey, Integer itemIndex, int epoch) {
        return saveJsonWithContext(tenantId, data, contentType, expiresAt, toolId, runId, stepKey, itemIndex, epoch, null, null);
    }

    @Override
    public UUID saveJsonWithContext(String tenantId, Object data, String contentType, Instant expiresAt,
                                    UUID toolId, String runId, String stepKey, Integer itemIndex, int epoch,
                                    String workflowId, String sourceType) {
        return saveJsonWithContext(tenantId, data, contentType, expiresAt, toolId, runId, stepKey, itemIndex, epoch, 0, workflowId, sourceType);
    }

    @Override
    public UUID saveJsonWithContext(String tenantId, Object data, String contentType, Instant expiresAt,
                                    UUID toolId, String runId, String stepKey, Integer itemIndex, int epoch,
                                    int spawn, String workflowId, String sourceType) {
        logger.debug("Sauvegarde JSON pour tenant: {}, contentType: {}, toolId: {}, runId: {}, stepKey: {}, spawn: {}",
            tenantId, contentType, toolId, runId, stepKey, spawn);

        int sizeBytes = storageUtils.calculateSize(data);
        String organizationId = currentOrganizationId();
        validateQuota(tenantId, organizationId, sizeBytes);

        String checksum = storageUtils.calculateChecksum(data);

        StorageEntity storage = new StorageEntity(
            tenantId,
            contentType,
            data,
            sizeBytes,
            checksum,
            expiresAt
        );
        // Stamp the org explicitly (same value used for the quota check above) so the
        // org-scoped row is never left to the ambient thread context.
        storage.setOrganizationId(organizationId);

        // Set run context for direct querying
        if (runId != null) {
            storage.setRunId(runId);
        }
        if (stepKey != null) {
            storage.setStepKey(stepKey);
        }
        if (itemIndex != null) {
            storage.setItemIndex(itemIndex);
        }
        storage.setEpoch(epoch);
        storage.setSpawn(spawn);

        // Set explorer fields
        if (workflowId != null) {
            storage.setWorkflowId(workflowId);
        }
        if (sourceType != null) {
            storage.setSourceType(sourceType);
        }

        // Generate structure skeleton for intelligent lazy loading
        generateAndSetSkeleton(storage, data);

        applyMappingIfNeeded(storage, toolId, contentType);

        StorageEntity saved = storageRepository.save(storage);
        String breakdownCategory = isFileSourceType(sourceType) ? "FILES" : "STEP_OUTPUTS";
        trackUsageBestEffort(tenantId, breakdownCategory, sizeBytes, organizationId, saved.getId());

        logger.info("JSON sauvegarde ID: {} pour tenant: {}, taille: {} bytes, runId: {}, stepKey: {}, spawn: {}",
            saved.getId(), tenantId, sizeBytes, runId, stepKey, spawn);

        return saved.getId();
    }

    @Override
    public UUID saveBinary(String tenantId, byte[] data, String fileName, String mimeType, Instant expiresAt) {
        return saveBinary(tenantId, data, fileName, mimeType, expiresAt, null);
    }

    @Override
    public UUID saveBinary(String tenantId, byte[] data, String fileName, String mimeType,
                           Instant expiresAt, String sourceType) {
        logger.debug("Sauvegarde binaire pour tenant: {}, fileName: {}", tenantId, fileName);

        int sizeBytes = data != null ? data.length : 0;
        String organizationId = currentOrganizationId();
        validateQuota(tenantId, organizationId, sizeBytes);

        String fileExtension = storageUtils.extractFileExtension(fileName);
        String checksum = storageUtils.calculateChecksum(data);

        StorageEntity storage = new StorageEntity(
            tenantId,
            mimeType,
            data,
            fileName,
            fileExtension,
            mimeType,
            sizeBytes,
            checksum,
            expiresAt
        );
        storage.setOrganizationId(organizationId);

        if (sourceType != null) {
            storage.setSourceType(sourceType);
        }

        StorageEntity saved = storageRepository.save(storage);
        trackUsageBestEffort(tenantId, "FILES", sizeBytes, organizationId, saved.getId());

        logger.info("Binaire sauvegarde ID: {} pour tenant: {}, taille: {} bytes",
            saved.getId(), tenantId, sizeBytes);

        return saved.getId();
    }

    @Override
    public UUID saveS3FileIndex(String tenantId, String workflowId, String runId, String stepKey,
                                String s3Key, String fileName, String mimeType, long sizeBytes, int epoch,
                                int spawn, Integer itemIndex, String sourceType) {
        return saveS3FileIndex(tenantId, workflowId, runId, stepKey, s3Key, fileName, mimeType, sizeBytes,
                epoch, spawn, itemIndex, sourceType, /* parentFolderId */ null);
    }

    /**
     * Folder-aware variant (V313 + folder-aware generic upload). Stamps
     * {@code parent_folder_id} so a generic upload lands directly in the user's
     * current manual folder. {@code parentFolderId} is validated against the
     * caller's workspace: if it isn't a folder in this org it is dropped to root
     * (logged) rather than failing - the S3 object is already uploaded, so an
     * invalid folder hint must never orphan it.
     */
    public UUID saveS3FileIndex(String tenantId, String workflowId, String runId, String stepKey,
                                String s3Key, String fileName, String mimeType, long sizeBytes, int epoch,
                                int spawn, Integer itemIndex, String sourceType, UUID parentFolderId) {
        logger.debug("Indexing S3 file for tenant: {}, s3Key: {}, fileName: {}, epoch: {}, spawn: {}, parentFolderId: {}",
                tenantId, s3Key, fileName, epoch, spawn, parentFolderId);

        String organizationId = currentOrganizationId();
        validateQuota(tenantId, organizationId, sizeBytes);

        // Workflow producers pass a meaningful sourceType (STEP_OUTPUT, INTERFACE_SCREENSHOT);
        // generic/non-workflow uploads (and the legacy 9-arg overload) fall back to S3_FILE.
        String resolvedSourceType = (sourceType != null && !sourceType.isBlank())
                ? sourceType
                : StorageSourceTypes.S3_FILE;

        // V313 folder placement: keep parentFolderId only if it really is a folder
        // in this workspace; otherwise drop to root (don't orphan the uploaded object).
        UUID resolvedParentFolderId = parentFolderId;
        if (parentFolderId != null
                && storageRepository.findFolderByIdAndOrganizationIdStrict(parentFolderId, organizationId).isEmpty()) {
            logger.warn("Ignoring parentFolderId {} - not a folder in workspace {} (filing at root)",
                    parentFolderId, organizationId);
            resolvedParentFolderId = null;
        }

        StorageEntity storage = new StorageEntity();
        storage.setTenantId(tenantId);
        storage.setOrganizationId(organizationId);
        storage.setContentType(mimeType != null ? mimeType : "application/octet-stream");
        storage.setData("{}");
        storage.setStorageType("S3_FILE");
        storage.setSourceType(resolvedSourceType);
        storage.setS3Key(s3Key);
        storage.setFileName(fileName);
        storage.setFileExtension(storageUtils.extractFileExtension(fileName));
        storage.setMimeType(mimeType);
        storage.setSizeBytes((int) sizeBytes);
        storage.setChecksum(null);
        storage.setCreatedAt(Instant.now());
        storage.setAccessedAt(Instant.now());
        storage.setWorkflowId(workflowId);
        if (runId != null) {
            storage.setRunId(runId);
        }
        if (stepKey != null) {
            storage.setStepKey(stepKey);
        }
        if (itemIndex != null) {
            storage.setItemIndex(itemIndex);
        }
        storage.setEpoch(epoch);
        storage.setSpawn(spawn);
        storage.setParentFolderId(resolvedParentFolderId);

        StorageEntity saved = storageRepository.save(storage);
        trackUsageBestEffort(tenantId, "FILES", sizeBytes, organizationId, saved.getId());

        logger.info("S3 file indexed ID: {} for tenant: {}, s3Key: {}, sourceType: {}, epoch: {}, spawn: {}, parentFolderId: {}",
                saved.getId(), tenantId, s3Key, resolvedSourceType, epoch, spawn, resolvedParentFolderId);
        return saved.getId();
    }

    @Override
    public UUID saveText(String tenantId, String data, String fileName, String mimeType, Instant expiresAt) {
        logger.debug("Sauvegarde texte pour tenant: {}, fileName: {}", tenantId, fileName);

        int sizeBytes = storageUtils.calculateSize(data);
        String organizationId = currentOrganizationId();
        validateQuota(tenantId, organizationId, sizeBytes);

        String fileExtension = storageUtils.extractFileExtension(fileName);
        String checksum = storageUtils.calculateChecksum(data);

        StorageEntity storage = new StorageEntity(
            tenantId,
            mimeType,
            data,
            fileName,
            fileExtension,
            mimeType,
            sizeBytes,
            checksum,
            expiresAt
        );
        storage.setOrganizationId(organizationId);

        StorageEntity saved = storageRepository.save(storage);
        trackUsageBestEffort(tenantId, "FILES", sizeBytes, organizationId, saved.getId());

        logger.info("Texte sauvegarde ID: {} pour tenant: {}, taille: {} bytes",
            saved.getId(), tenantId, sizeBytes);

        return saved.getId();
    }

    @Override
    @Transactional
    public Optional<Object> getById(UUID id, String tenantId) {
        logger.debug("Recuperation donnees ID: {} pour tenant: {}", id, tenantId);

        return storageRepository.findByIdAndTenantId(id, tenantId)
            .filter(this::isAccessible)
            .map(entity -> {
                updateAccessTime(entity);
                return getDataByType(entity);
            });
    }

    /**
     * Read-only version of getById that does NOT update accessed_at.
     * Use this when reading storage data from within a read-only transaction.
     */
    @Transactional(readOnly = true)
    public Optional<Object> getByIdReadOnly(UUID id, String tenantId) {
        logger.debug("Recuperation donnees (read-only) ID: {} pour tenant: {}", id, tenantId);

        return storageRepository.findByIdAndTenantId(id, tenantId)
            .filter(this::isAccessible)
            .map(this::getDataByType);
    }

    @Transactional(readOnly = true)
    public Optional<Object> getByIdReadOnlyForScope(UUID id, String tenantId, String organizationId) {
        requireOrgId(organizationId);
        logger.debug("Recuperation donnees (read-only) ID: {} pour org scope: {}", id, organizationId);

        return storageRepository.findByIdAndOrganizationIdStrict(id, organizationId)
            .filter(this::isAccessible)
            .map(this::getDataByType);
    }

    @Override
    @Transactional
    public Optional<StorageEntity> getEntityById(UUID id, String tenantId) {
        logger.debug("Recuperation entite ID: {} pour tenant: {}", id, tenantId);

        return storageRepository.findByIdAndTenantId(id, tenantId)
            .filter(this::isAccessible)
            .map(entity -> {
                updateAccessTime(entity);
                return entity;
            });
    }

    /**
     * Strict org-scope single fetch. Returns the row only if its
     * {@code organization_id} matches the given {@code organizationId}.
     * Used by the storage explorer's preview/download/delete endpoints.
     *
     * <p>Post-V261 sweep: {@code organizationId} is required on every call
     * (gateway always injects {@code X-Organization-ID}; personal-workspace
     * users resolve to their personal-org UUID). The legacy personal-scope
     * branch (organization_id IS NULL AND tenant_id match) was removed.</p>
     *
     * @throws IllegalArgumentException if {@code organizationId} is null/blank.
     */
    @Transactional
    public Optional<StorageEntity> getEntityByIdForScope(UUID id, String tenantId, String organizationId) {
        requireOrgId(organizationId);
        return storageRepository.findByIdAndOrganizationIdStrict(id, organizationId)
                .filter(this::isAccessible)
                .map(entity -> {
                    updateAccessTime(entity);
                    return entity;
                });
    }

    public int deleteByDateRange(String tenantId, Instant dateFrom, Instant dateTo) {
        int count = storageRepository.softDeleteByDateRange(tenantId, dateFrom, dateTo);
        if (count > 0) {
            quotaService.updateUsage(tenantId);
        }
        return count;
    }

    /**
     * Strict org-scope date-range bulk delete. Deletes ONLY rows tagged with
     * the given {@code organizationId}.
     *
     * <p>Post-V261 sweep: {@code organizationId} is required (gateway always
     * injects it; personal-workspace users resolve to their personal-org UUID).
     * The legacy personal-scope branch was removed.</p>
     *
     * @throws IllegalArgumentException if {@code organizationId} is null/blank.
     */
    public int deleteByDateRangeForScope(String tenantId, String organizationId,
                                          Instant dateFrom, Instant dateTo) {
        return deleteByDateRangeForScope(tenantId, organizationId, dateFrom, dateTo, List.of());
    }

    public int deleteByDateRangeForScope(String tenantId, String organizationId,
                                          Instant dateFrom, Instant dateTo,
                                          Collection<UUID> excludedIds) {
        requireOrgId(organizationId);
        int count = excludedIds == null || excludedIds.isEmpty()
                ? storageRepository.softDeleteByOrgIdAndDateRange(organizationId, dateFrom, dateTo)
                : storageRepository.softDeleteByOrgIdAndDateRangeExcludingIds(
                        organizationId, dateFrom, dateTo, excludedIds);
        if (count > 0) {
            quotaService.updateOrganizationUsage(organizationId);
        }
        return count;
    }

    /**
     * Strict org-scope soft-delete of a VIRTUAL workflow folder's contents (Files browser).
     *
     * <p>Lets a user delete a whole workflow folder, or a single {@code epoch/spawn/iteration}
     * sub-folder, exactly like deleting a manual folder. The {@code address} levels are honoured as
     * "match-or-any": a WORKFLOW-level address wipes the workflow's whole virtual subtree; a deeper
     * address narrows the delete. Files moved out into a manual folder are preserved (they left the
     * virtual tree). Returns the number of file rows removed.
     *
     * @throws IllegalArgumentException if {@code organizationId} is null/blank or {@code address}/its
     *                                  {@code workflowId} is null.
     */
    public int deleteVirtualScopeForScope(String tenantId, String organizationId,
                                          VirtualFolderAddress address, Collection<UUID> excludedIds) {
        requireOrgId(organizationId);
        if (address == null || address.workflowId() == null || address.workflowId().isBlank()) {
            throw new IllegalArgumentException("A virtual workflow-folder address (wf:<id>...) is required");
        }
        int count = excludedIds == null || excludedIds.isEmpty()
                ? storageRepository.softDeleteByVirtualScope(
                        organizationId, address.workflowId(), address.runId(),
                        address.epoch(), address.spawn(), address.itemIndex())
                : storageRepository.softDeleteByVirtualScopeExcludingIds(
                        organizationId, address.workflowId(), address.runId(),
                        address.epoch(), address.spawn(), address.itemIndex(), excludedIds);
        if (count > 0) {
            quotaService.updateOrganizationUsage(organizationId);
        }
        return count;
    }

    public boolean assignFileToProjectForScope(UUID fileId, String organizationId, UUID projectId) {
        requireOrgId(organizationId);
        return storageRepository.updateFileProjectIdForScope(fileId, organizationId, projectId) > 0;
    }

    public boolean removeFileFromProjectForScope(UUID fileId, String organizationId, UUID projectId) {
        requireOrgId(organizationId);
        Optional<StorageEntity> entity = storageRepository.findByIdAndOrganizationIdStrict(fileId, organizationId)
                .filter(e -> e.getFileName() != null)
                .filter(e -> projectId.equals(e.getProjectId()));
        if (entity.isEmpty()) {
            return false;
        }
        return storageRepository.updateFileProjectIdForScope(fileId, organizationId, null) > 0;
    }

    @Transactional(readOnly = true)
    public long countFilesByProjectForScope(UUID projectId, String organizationId) {
        requireOrgId(organizationId);
        return storageRepository.countFilesByProjectIdAndOrganizationId(projectId, organizationId);
    }

    @Transactional(readOnly = true)
    public List<StorageEntity> findFilesByProjectForScope(UUID projectId, String organizationId) {
        requireOrgId(organizationId);
        return storageRepository.findFilesByProjectIdAndOrganizationId(projectId, organizationId);
    }

    public void unassignFilesFromProjectForScope(UUID projectId, String organizationId) {
        requireOrgId(organizationId);
        storageRepository.clearProjectIdForFilesInScope(projectId, organizationId);
    }

    public boolean deleteById(UUID id, String tenantId) {
        logger.debug("Suppression ID: {} pour tenant: {}", id, tenantId);

        return storageRepository.findByIdAndTenantId(id, tenantId)
            .map(entity -> {
                storageRepository.updateStatus(id, StorageStatus.DELETED);
                String breakdownCategory = isFileSourceType(entity.getSourceType()) ? "FILES" : "STEP_OUTPUTS";
                int entitySize = entity.getSizeBytes() != null ? entity.getSizeBytes() : 0;
                breakdownService.trackDelete(tenantId, breakdownCategory, entitySize);
                quotaService.updateUsage(tenantId);
                logger.info("Storage supprime: {}", id);
                return true;
            })
            .orElseGet(() -> {
                logger.warn("Storage non trouve pour suppression: {} tenant: {}", id, tenantId);
                return false;
            });
    }

    /**
     * Strict org-scope single-row delete. Debits the organization quota counter.
     *
     * <p>Post-V261 sweep: {@code organizationId} is required (gateway always
     * injects it; personal-workspace users resolve to their personal-org UUID).
     * The legacy personal-scope branch (tenant_id + organization_id IS NULL) was
     * removed.</p>
     *
     * @throws IllegalArgumentException if {@code organizationId} is null/blank.
     */
    public boolean deleteByIdForScope(UUID id, String tenantId, String organizationId) {
        requireOrgId(organizationId);
        return storageRepository.findByIdAndOrganizationIdStrict(id, organizationId)
                .map(entity -> {
                    // V313: deleting a FOLDER must NOT lose the files inside it. Re-parent the
                    // folder's direct children UP one level (to the folder's own parent - root when
                    // the folder was top-level), THEN delete the folder row. We never cascade-delete
                    // files; only the (empty after re-parent) folder row is removed.
                    if (entity.isFolder()) {
                        UUID grandparentId = entity.getParentFolderId();
                        List<StorageEntity> children = storageRepository
                                .findChildrenByParentFolderIdAndOrganizationId(id, organizationId);
                        for (StorageEntity child : children) {
                            child.setParentFolderId(grandparentId);
                            storageRepository.save(child);
                        }
                    }
                    storageRepository.updateStatus(id, StorageStatus.DELETED);
                    String breakdownCategory = isFileSourceType(entity.getSourceType()) ? "FILES" : "STEP_OUTPUTS";
                    int entitySize = entity.getSizeBytes() != null ? entity.getSizeBytes() : 0;
                    breakdownService.trackDelete(tenantId, breakdownCategory, entitySize, organizationId);
                    quotaService.updateOrganizationUsage(organizationId);
                    return true;
                })
                .orElse(false);
    }

    /**
     * Strict org-scope rename of a single stored file's display name.
     *
     * <p>Only the {@code file_name} column is updated - the {@code s3_key} (the
     * actual object-storage key) is immutable, so the blob is untouched and
     * existing by-id / by-path URLs keep resolving. Mirrors
     * {@link #deleteByIdForScope} for the scope guard.</p>
     *
     * @return {@code true} if a row in scope was found and renamed, {@code false} otherwise.
     * @throws IllegalArgumentException if {@code organizationId} is null/blank.
     */
    public boolean renameByIdForScope(UUID id, String tenantId, String organizationId, String newFileName) {
        requireOrgId(organizationId);
        return storageRepository.findByIdAndOrganizationIdStrict(id, organizationId)
                .map(entity -> {
                    entity.setFileName(newFileName);
                    storageRepository.save(entity);
                    logger.info("Storage renamed: {} -> '{}' (org {})", id, newFileName, organizationId);
                    return true;
                })
                .orElse(false);
    }

    // ========== V313 manual folders ==========

    /**
     * Create a manual folder (V313) in the given org+tenant scope. A folder is a lightweight
     * {@code storage.storage} row with {@code is_folder = true}, the trimmed name in
     * {@code file_name}, a directory sentinel content/storage/source type, {@code size_bytes = 0}
     * and no {@code s3_key} / data payload.
     *
     * <p>The name is required (blank → {@link IllegalArgumentException}), trimmed, and capped at
     * {@value #MAX_FOLDER_NAME_LENGTH} chars. {@code parentFolderId}, when non-null, MUST be an
     * existing manual folder owned by the same org (else {@link IllegalArgumentException}) - this
     * blocks nesting under a file or a cross-org folder.
     *
     * @return the persisted folder entity (carries the generated id).
     * @throws IllegalArgumentException if organizationId is null/blank, the name is blank, or the
     *         parent is not a same-org folder.
     */
    public StorageEntity createFolderForScope(String tenantId, String organizationId,
                                              String name, UUID parentFolderId) {
        requireOrgId(organizationId);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Folder name is required");
        }
        if (trimmed.length() > MAX_FOLDER_NAME_LENGTH) {
            trimmed = trimmed.substring(0, MAX_FOLDER_NAME_LENGTH);
        }
        if (parentFolderId != null) {
            // Parent must be an existing folder in the SAME org - never a file, never cross-org.
            storageRepository.findFolderByIdAndOrganizationIdStrict(parentFolderId, organizationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "parentFolderId is not a folder in this workspace"));
        }

        StorageEntity folder = new StorageEntity();
        folder.setTenantId(tenantId);
        folder.setOrganizationId(organizationId);
        folder.setContentType(FOLDER_CONTENT_TYPE);
        folder.setData("{}"); // satisfy NOT NULL on data
        folder.setStorageType(FOLDER_STORAGE_TYPE);
        folder.setSourceType(FOLDER_SOURCE_TYPE);
        folder.setFileName(trimmed);
        folder.setSizeBytes(0);
        folder.setCreatedAt(Instant.now());
        folder.setAccessedAt(Instant.now());
        folder.setStatus(StorageStatus.ACTIVE);
        folder.setIsFolder(true);
        folder.setParentFolderId(parentFolderId);

        StorageEntity saved = storageRepository.save(folder);
        logger.info("Manual folder created: {} '{}' (org {}, parent {})",
                saved.getId(), trimmed, organizationId, parentFolderId);
        return saved;
    }

    /**
     * Move storage rows (files and/or folders) into a target manual folder, org-scoped. A null
     * {@code targetParentFolderId} moves the rows to the root (top level).
     *
     * <p>Per-id validation (failures are reported, not fatal): the id must resolve to a row in the
     * same org; the target (if non-null) must be a same-org folder; a folder cannot be moved into
     * itself or into one of its own descendants (walks the parent chain to block cycles). Rows that
     * are already under the target are a no-op success.
     *
     * @return {@link MoveResult} with the count moved and a per-id failure list (id + reason).
     * @throws IllegalArgumentException if organizationId is null/blank, or the target is non-null
     *         but not a same-org folder (a bad target fails the whole request, not per-id).
     */
    public MoveResult moveEntriesForScope(String organizationId, Collection<UUID> ids,
                                          UUID targetParentFolderId) {
        requireOrgId(organizationId);
        if (targetParentFolderId != null) {
            storageRepository.findFolderByIdAndOrganizationIdStrict(targetParentFolderId, organizationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "targetParentFolderId is not a folder in this workspace"));
        }

        List<MoveFailure> failures = new ArrayList<>();
        int moved = 0;
        for (UUID id : ids == null ? List.<UUID>of() : ids) {
            if (id == null) {
                continue;
            }
            Optional<StorageEntity> rowOpt = storageRepository.findByIdAndOrganizationIdStrict(id, organizationId);
            if (rowOpt.isEmpty()) {
                failures.add(new MoveFailure(id, "not found in this workspace"));
                continue;
            }
            StorageEntity row = rowOpt.get();
            if (id.equals(targetParentFolderId)) {
                failures.add(new MoveFailure(id, "cannot move a folder into itself"));
                continue;
            }
            // Cycle guard: a folder may not move under one of its own descendants. Walk UP from the
            // target - if we reach this folder, the move would create a cycle.
            if (row.isFolder() && targetParentFolderId != null
                    && isDescendantOf(targetParentFolderId, id, organizationId)) {
                failures.add(new MoveFailure(id, "cannot move a folder into its own descendant"));
                continue;
            }
            row.setParentFolderId(targetParentFolderId);
            storageRepository.save(row);
            moved++;
        }
        logger.info("Move into folder {} (org {}): {} moved, {} failed",
                targetParentFolderId, organizationId, moved, failures.size());
        return new MoveResult(moved, failures);
    }

    /**
     * True if {@code candidateId} is {@code ancestorId} itself or a (transitive) descendant of
     * {@code ancestorId}: walk UP the parent chain from {@code candidateId}; if it passes through
     * {@code ancestorId}, then moving {@code ancestorId} under {@code candidateId} would create a
     * cycle. A depth cap (matching the per-row guard) prevents an infinite loop on any pre-existing
     * corrupt chain.
     */
    private boolean isDescendantOf(UUID candidateId, UUID ancestorId, String organizationId) {
        UUID cursor = candidateId;
        int guard = 0;
        while (cursor != null && guard++ < 10_000) {
            if (cursor.equals(ancestorId)) {
                return true;
            }
            UUID next = storageRepository.findFolderByIdAndOrganizationIdStrict(cursor, organizationId)
                    .map(StorageEntity::getParentFolderId)
                    .orElse(null);
            cursor = next;
        }
        return false;
    }

    /** Outcome of {@link #moveEntriesForScope}: how many rows moved + the per-id failures. */
    public record MoveResult(int movedCount, List<MoveFailure> failed) {}

    /** A single rejected id from a move, with a human-readable reason. */
    public record MoveFailure(UUID id, String reason) {}

    @Override
    @Transactional(readOnly = true)
    public List<StorageEntity> listByTenant(String tenantId) {
        logger.debug("Liste des storages pour tenant: {}", tenantId);
        return storageRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public List<StorageEntity> listByTenantAndSourceType(String tenantId, String sourceType) {
        return storageRepository.findByTenantIdAndSourceType(tenantId, sourceType);
    }

    /**
     * Strict org-scope listing. Returns every row owned by the given
     * {@code organizationId} regardless of which member created it.
     *
     * <p>Post-V261 sweep: {@code organizationId} is required (gateway always
     * injects it; personal-workspace users resolve to their personal-org UUID).
     * The legacy personal-scope branch was removed.</p>
     *
     * @throws IllegalArgumentException if {@code organizationId} is null/blank.
     */
    @Transactional(readOnly = true)
    public List<StorageEntity> listForScope(String tenantId, String organizationId) {
        requireOrgId(organizationId);
        logger.debug("Liste des storages pour org scope: {}", organizationId);
        return storageRepository.findByOrganizationIdStrict(organizationId);
    }

    /**
     * Guard: post-V261 sweep makes {@code organizationId} mandatory on every
     * strict-isolation entry point. Personal-workspace users resolve to their
     * personal-org UUID at the gateway; callers that previously passed null
     * are now bugs.
     */
    private static void requireOrgId(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException(
                    "organizationId is required (post-V261 sweep)");
        }
    }

    @Override
    public int cleanupExpired() {
        logger.info("Nettoyage des storages expires");

        List<StorageEntity> expired = storageRepository.findExpiredStorages(Instant.now());

        for (StorageEntity storage : expired) {
            storageRepository.updateStatus(storage.getId(), StorageStatus.DELETED);
        }

        logger.info("{} storages expires nettoyes", expired.size());
        return expired.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getSkeletonOnly(UUID id, String tenantId) {
        logger.debug("Recuperation skeleton seul ID: {} pour tenant: {}", id, tenantId);
        String skeleton = storageRepository.getSkeletonOnly(id, tenantId);
        return Optional.ofNullable(skeleton);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getValueAtPath(UUID id, String tenantId, String[] path) {
        logger.debug("Extraction valeur path: {} pour storage: {}", String.join(".", path), id);
        String value = storageRepository.extractJsonPath(id, tenantId, path);
        return Optional.ofNullable(value);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getObjectAtPath(UUID id, String tenantId, String[] path) {
        logger.debug("Extraction objet path: {} pour storage: {}", String.join(".", path), id);
        String jsonObject = storageRepository.extractJsonObject(id, tenantId, path);
        return Optional.ofNullable(jsonObject);
    }

    /**
     * Updates the JSON data and data_mapped fields of an existing storage entry.
     *
     * @param id Storage UUID
     * @param tenantId Tenant ID for security
     * @param data New data to store
     * @param dataMapped New mapped data to store
     * @return Updated storage data, or empty if not found
     */
    @Transactional
    public Optional<Object> updateJson(UUID id, String tenantId, Object data, Object dataMapped) {
        logger.debug("Update JSON storage ID: {} for tenant: {}", id, tenantId);

        return storageRepository.findByIdAndTenantId(id, tenantId)
            .filter(this::isAccessible)
            .map(entity -> {
                entity.setData(data);
                if (dataMapped != null) {
                    entity.setDataMapped(dataMapped);
                }

                int oldSize = entity.getSizeBytes() != null ? entity.getSizeBytes() : 0;
                int sizeBytes = storageUtils.calculateSize(data);
                entity.setSizeBytes(sizeBytes);
                entity.setChecksum(storageUtils.calculateChecksum(data));
                generateAndSetSkeleton(entity, data);
                updateAccessTime(entity);

                storageRepository.save(entity);
                String breakdownCategory = isFileSourceType(entity.getSourceType()) ? "FILES" : "STEP_OUTPUTS";
                breakdownService.trackSizeChange(tenantId, breakdownCategory, sizeBytes - oldSize);
                quotaService.updateUsage(tenantId);

                logger.info("JSON updated ID: {} for tenant: {}, size: {} bytes", id, tenantId, sizeBytes);
                return getDataByType(entity);
            });
    }

    /**
     * Strict org-scope JSON update for storage rows exposed through workflow
     * inspection endpoints.
     */
    @Transactional
    public Optional<Object> updateJsonForScope(UUID id, String tenantId, String organizationId,
                                               Object data, Object dataMapped) {
        requireOrgId(organizationId);
        logger.debug("Update JSON storage ID: {} for org scope: {}", id, organizationId);

        return storageRepository.findByIdAndOrganizationIdStrict(id, organizationId)
            .filter(this::isAccessible)
            .map(entity -> {
                entity.setData(data);
                if (dataMapped != null) {
                    entity.setDataMapped(dataMapped);
                }

                int oldSize = entity.getSizeBytes() != null ? entity.getSizeBytes() : 0;
                int sizeBytes = storageUtils.calculateSize(data);
                int deltaBytes = sizeBytes - oldSize;
                if (deltaBytes > 0) {
                    validateQuota(tenantId, organizationId, deltaBytes);
                }
                entity.setSizeBytes(sizeBytes);
                entity.setChecksum(storageUtils.calculateChecksum(data));
                generateAndSetSkeleton(entity, data);
                updateAccessTime(entity);

                storageRepository.save(entity);
                String breakdownCategory = isFileSourceType(entity.getSourceType()) ? "FILES" : "STEP_OUTPUTS";
                breakdownService.trackSizeChange(tenantId, breakdownCategory, deltaBytes, organizationId);
                quotaService.updateOrganizationUsage(organizationId);

                logger.info("JSON updated ID: {} for org: {}, size: {} bytes", id, organizationId, sizeBytes);
                return getDataByType(entity);
            });
    }

    // ========== Methodes privees ==========

    private void validateQuota(String tenantId, String organizationId, long sizeBytes) {
        // System tenants (e.g. "_publications") are not real users - skip quota enforcement
        if (tenantId != null && tenantId.startsWith("_")) {
            return;
        }
        long additionalBytes = Math.max(0L, sizeBytes);
        QuotaStatus quotaStatus = organizationId != null && !organizationId.isBlank()
                ? quotaService.checkOrganizationQuota(organizationId, additionalBytes)
                : quotaService.checkQuota(tenantId, additionalBytes);
        if (quotaStatus == QuotaStatus.HARD_LIMIT_REACHED) {
            throw new QuotaExceededException("Storage quota hard limit reached", tenantId);
        }
    }

    private void updateUsageForScope(String tenantId, String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            quotaService.updateOrganizationUsage(organizationId);
        } else {
            quotaService.updateUsage(tenantId);
        }
    }

    /**
     * Best-effort usage + breakdown tracking AFTER a storage row is persisted. The row is already
     * saved and its id is valid - a tracking failure (a quota recompute error, or the org-less
     * catalog/internal upload path where {@code updateUsage(tenantId)} can throw) must NEVER
     * propagate and discard the caller's storage id. Daily reconciliation repairs any usage drift.
     *
     * <p>Regression guard: an uncaught throw here used to bubble out of the save methods, making
     * {@code S3FileStorageService.indexGenericUpload} return {@code null}. Post opaque-URL cutover
     * that shipped catalog-binary FileRefs (WhatsApp media, image-gen, TTS, …) WITHOUT an {@code id}
     * even though the row was committed - so they rendered broken (the opaque URL needs the id).
     */
    private void trackUsageBestEffort(String tenantId, String breakdownCategory, long sizeBytes,
                                      String organizationId, UUID savedId) {
        try {
            breakdownService.trackSave(tenantId, breakdownCategory, sizeBytes, organizationId);
            updateUsageForScope(tenantId, organizationId);
        } catch (Exception e) {
            logger.warn("Post-save usage/breakdown tracking failed for tenant={} org={} row={} "
                    + "(row persisted; daily reconciliation will repair): {}",
                    tenantId, organizationId, savedId, e.getMessage());
        }
    }

    private String currentOrganizationId() {
        return TenantResolver.currentRequestOrganizationId();
    }

    private void applyMappingIfNeeded(StorageEntity storage, UUID toolId, String contentType) {
        if (toolId == null || !isJsonContent(contentType) || storage.getData() == null) {
            return;
        }

        if (!mappingService.isEnabled()) {
            return;
        }

        try {
            MappingResolutionResult result = mappingService.resolve(toolId, storage.getData());
            if (result != null && result.isSuccess() && result.getPreview() != null) {
                storage.setDataMapped(result.getPreview());
                logger.info("Mapping resolu pour storage ID: {}", storage.getId());
            } else {
                // F6: consistent ERROR level with the catch below - same operational
                // signal (mapping unavailable), same triage routing.
                String error = result != null ? result.getError() : "Resultat null";
                logger.error("Mapping resolution returned no preview for toolId {}: {}", toolId, error);
            }
        } catch (Exception e) {
            // F6: mapping failures upgraded from WARN to ERROR with exception
            // class for ops triage. Sibling concern to skeleton generation.
            logger.error("Mapping resolution failed for toolId {}: [{}] {}",
                    toolId, e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private boolean isJsonContent(String contentType) {
        return contentType != null && contentType.toLowerCase().contains("json");
    }

    private boolean isFileSourceType(String sourceType) {
        return sourceType != null && FILE_SOURCE_TYPES.contains(sourceType);
    }

    private boolean isAccessible(StorageEntity entity) {
        if (entity.isExpired()) {
            logger.warn("Storage expire: {}", entity.getId());
            return false;
        }
        return true;
    }

    private void updateAccessTime(StorageEntity entity) {
        entity.touch();
        storageRepository.updateAccessedAt(entity.getId(), Instant.now());
    }

    private Object getDataByType(StorageEntity entity) {
        return switch (entity.getStorageType()) {
            case "BINARY" -> entity.getDataBinary();
            case "TEXT" -> entity.getDataText();
            default -> entity.getDataAsMap();
        };
    }

    /**
     * Generates and sets the structure skeleton for intelligent lazy loading.
     * The skeleton captures the JSON structure (keys and types) without values,
     * enabling frontend to display the data tree before loading actual values.
     */
    private void generateAndSetSkeleton(StorageEntity storage, Object data) {
        if (data == null) {
            return;
        }

        try {
            JsonNode dataNode = objectMapper.valueToTree(data);
            JsonNode skeleton = skeletonGenerator.generateSkeleton(dataNode);
            storage.setStructureSkeleton(objectMapper.writeValueAsString(skeleton));
            logger.debug("Skeleton genere pour storage, taille skeleton: {} bytes",
                storage.getStructureSkeleton().length());
        } catch (Exception e) {
            // F6: skeleton generation failure used to leave structureSkeleton=null,
            // and the frontend's intelligent lazy-loading interpreted null as
            // "no data produced" → empty tree shown to the user even when the
            // run had real output. Stamp a sentinel so the FE can distinguish
            // "skeleton-unavailable" from "no-data" and render the right
            // fallback ("preview unavailable - click to load full payload").
            // Trigger classes: Jackson cyclic refs in agent outputs, Spring
            // proxies in StepExecutionResult sub-maps, oversize node depth.
            logger.error("Skeleton generation failed for storage {} - stamping sentinel for FE fallback: [{}] {}",
                    storage.getId(), e.getClass().getSimpleName(), e.getMessage(), e);
            try {
                String sentinel = "{\"_skeletonError\":\""
                        + e.getClass().getSimpleName().replace("\"", "'")
                        + "\"}";
                storage.setStructureSkeleton(sentinel);
            } catch (Exception inner) {
                // Defense-in-depth: even if the literal write fails, do nothing
                // - preserve the legacy null behavior rather than rethrow on the
                // save path (skeleton is best-effort by design).
                logger.error("Failed to stamp skeleton sentinel: {}", inner.getMessage());
            }
        }
    }
}
