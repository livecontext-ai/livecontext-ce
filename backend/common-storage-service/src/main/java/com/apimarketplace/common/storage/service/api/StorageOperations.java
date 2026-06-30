package com.apimarketplace.common.storage.service.api;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageSourceTypes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface definissant les operations de stockage (Interface Segregation Principle).
 * Separe les operations de lecture, ecriture et maintenance.
 */
public interface StorageOperations {

    /**
     * Sauvegarde des donnees JSON.
     */
    UUID saveJson(String tenantId, Object data, String contentType, Instant expiresAt);

    /**
     * Sauvegarde des donnees JSON avec resolution de mapping.
     */
    UUID saveJson(String tenantId, Object data, String contentType, Instant expiresAt, UUID toolId);

    /**
     * Sauvegarde des donnees JSON avec contexte de run pour requete directe.
     *
     * @param tenantId Tenant ID
     * @param data Data to store
     * @param contentType Content type
     * @param expiresAt Expiration time (optional)
     * @param toolId Tool ID for mapping resolution (optional)
     * @param runId Workflow run ID (optional, for context querying)
     * @param stepKey Step key with prefix e.g. "mcp:enricher" (optional)
     * @param itemIndex Item index for loops/splits (optional, defaults to 0)
     * @param epoch Trigger epoch number (0 for first execution)
     */
    UUID saveJsonWithContext(String tenantId, Object data, String contentType, Instant expiresAt,
                             UUID toolId, String runId, String stepKey, Integer itemIndex, int epoch);

    /**
     * Saves JSON data with full context including workflow ID, source type, and spawn for Storage Explorer.
     *
     * @param spawn Spawn number for rerun isolation (0 for first execution)
     */
    UUID saveJsonWithContext(String tenantId, Object data, String contentType, Instant expiresAt,
                             UUID toolId, String runId, String stepKey, Integer itemIndex, int epoch,
                             String workflowId, String sourceType);

    /**
     * Saves JSON data with full context including spawn coordinate.
     */
    default UUID saveJsonWithContext(String tenantId, Object data, String contentType, Instant expiresAt,
                             UUID toolId, String runId, String stepKey, Integer itemIndex, int epoch,
                             int spawn, String workflowId, String sourceType) {
        return saveJsonWithContext(tenantId, data, contentType, expiresAt, toolId, runId, stepKey, itemIndex, epoch, workflowId, sourceType);
    }

    /**
     * Sauvegarde des donnees binaires.
     */
    UUID saveBinary(String tenantId, byte[] data, String fileName, String mimeType, Instant expiresAt);

    /**
     * Saves binary data with source type classification for Storage Explorer.
     */
    default UUID saveBinary(String tenantId, byte[] data, String fileName, String mimeType,
                            Instant expiresAt, String sourceType) {
        return saveBinary(tenantId, data, fileName, mimeType, expiresAt);
    }

    /**
     * Creates a lightweight DB index entry for an S3 file (no binary/JSON data stored).
     * Allows browsing S3 files from the Storage Explorer without calling S3 listObjects.
     *
     * <p>Legacy 9-arg overload - delegates to the full context-carrying overload with
     * {@code spawn=0}, {@code itemIndex=null} and {@code sourceType=S3_FILE}. Generic /
     * non-workflow uploads (catalog binaries, image-gen, avatars, run clones) keep using
     * this path so they land on the epoch-0, run-context-less default.</p>
     */
    default UUID saveS3FileIndex(String tenantId, String workflowId, String runId, String stepKey,
                                 String s3Key, String fileName, String mimeType, long sizeBytes, int epoch) {
        return saveS3FileIndex(tenantId, workflowId, runId, stepKey, s3Key, fileName, mimeType,
                sizeBytes, epoch, 0, null, StorageSourceTypes.S3_FILE);
    }

    /**
     * Full context-carrying S3 index entry. Used by WORKFLOW file producers so the stored row
     * carries the real run coordinates ({@code epoch}, {@code spawn}, {@code itemIndex}) and a
     * meaningful {@code sourceType}, enabling later grouping by workflow → epoch → spawn → iteration.
     *
     * @param spawn      Spawn number for rerun isolation within an epoch (0 for first execution)
     * @param itemIndex  Item index for loops/splits (optional, null when not item-scoped)
     * @param sourceType Classification of the producing context (see {@link StorageSourceTypes});
     *                   falls back to {@code S3_FILE} when null/blank
     */
    UUID saveS3FileIndex(String tenantId, String workflowId, String runId, String stepKey,
                         String s3Key, String fileName, String mimeType, long sizeBytes, int epoch,
                         int spawn, Integer itemIndex, String sourceType);

    /**
     * Sauvegarde des donnees texte.
     */
    UUID saveText(String tenantId, String data, String fileName, String mimeType, Instant expiresAt);

    /**
     * Recupere des donnees par ID.
     */
    Optional<Object> getById(UUID id, String tenantId);

    /**
     * Recupere l'entite storage complete.
     */
    Optional<StorageEntity> getEntityById(UUID id, String tenantId);

    /**
     * Supprime des donnees par ID (soft delete).
     */
    boolean deleteById(UUID id, String tenantId);

    /**
     * Liste les storages d'un tenant.
     */
    List<StorageEntity> listByTenant(String tenantId);

    /**
     * Nettoie les storages expires.
     */
    int cleanupExpired();

    // ========== Skeleton/Lazy Loading Operations ==========

    /**
     * Retrieves only the structure skeleton without loading the full data payload.
     * Optimized for frontend lazy loading - returns lightweight schema for tree display.
     *
     * @param id Storage UUID
     * @param tenantId Tenant ID for security
     * @return Optional skeleton JSON string, or empty if not found or no skeleton
     */
    Optional<String> getSkeletonOnly(UUID id, String tenantId);

    /**
     * Extracts a value at a specific JSON path from storage data.
     * Used for lazy loading individual values in the frontend tree view.
     *
     * @param id Storage UUID
     * @param tenantId Tenant ID for security
     * @param path JSON path as array of keys, e.g., ["output", "users", "0", "name"]
     * @return The value at the path as a String, or null if not found
     */
    Optional<String> getValueAtPath(UUID id, String tenantId, String[] path);

    /**
     * Extracts a JSON object at a specific path from storage data.
     * Used for lazy loading sub-objects in the frontend tree view.
     *
     * @param id Storage UUID
     * @param tenantId Tenant ID for security
     * @param path JSON path as array of keys
     * @return The JSON object at the path as a String, or null if not found
     */
    Optional<String> getObjectAtPath(UUID id, String tenantId, String[] path);
}
