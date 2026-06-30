package com.apimarketplace.common.storage.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Lightweight projection for Storage Explorer queries.
 * Excludes heavy columns (data, data_binary, data_text, data_mapped, structure_skeleton).
 *
 * <p>{@code isFolder} / {@code parentFolderId} carry the V313 manual-folder columns so the
 * explorer can render folder rows and the "filed-under" relationship without a second query.</p>
 */
public record StorageExplorerProjection(
    UUID id,
    String storageType,
    String sourceType,
    String fileName,
    String mimeType,
    Integer sizeBytes,
    Instant createdAt,
    String workflowId,
    UUID projectId,
    String runId,
    String stepKey,
    Integer epoch,
    String s3Key,
    String contentType,
    boolean isFolder,
    UUID parentFolderId
) {}
