package com.apimarketplace.common.storage.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API response DTO for Storage Explorer entries.
 *
 * <p>V313 manual folders: a folder is a row with {@code isFolder=true} (the folder name is in
 * {@code fileName}, no {@code s3Key}). {@code parentFolderId} is the manual folder a row is filed
 * under (null = top level). {@code childCount} / {@code previewFiles} are populated for folder
 * rows only (the iOS-style 9-up tile) and stay null/empty for normal files.</p>
 *
 * <p>Phase 2b virtual folders: a VIRTUAL folder (workflow / epoch / spawn / iteration) is a
 * synthetic DTO with {@code id=null} (it is not a persisted row). {@code virtualId} carries its
 * navigation token (e.g. {@code wf:<id>/e0}); {@code virtualKind} is one of
 * {@code WORKFLOW/EPOCH/SPAWN/ITERATION} (null for real rows). {@code spawn} / {@code itemIndex}
 * echo the run-context coordinates so the frontend can localise the display name. The folder's
 * display label is resolved client-side from {@code virtualKind} + {@code workflowName} (+ epoch /
 * spawn / itemIndex), so {@code fileName} stays null for virtual folders.</p>
 */
public record StorageExplorerDto(
    UUID id,
    String storageType,
    String sourceType,
    String fileName,
    String mimeType,
    Integer sizeBytes,
    String formattedSize,
    Instant createdAt,
    String workflowId,
    String workflowName,
    UUID projectId,
    String runId,
    String stepKey,
    Integer epoch,
    String s3Key,
    String contentType,
    boolean isFolder,
    String parentFolderId,
    Integer childCount,
    List<StoragePreviewFile> previewFiles,
    String virtualId,
    String virtualKind,
    Integer spawn,
    Integer itemIndex
) {

    public static StorageExplorerDto from(StorageExplorerProjection p, String workflowName) {
        return from(p, workflowName, null, null);
    }

    /**
     * Build a DTO, optionally carrying the folder-only aggregates. {@code childCount} and
     * {@code previewFiles} are passed only for folder rows (computed in one batched pass by the
     * service); for normal files they are null/empty.
     */
    public static StorageExplorerDto from(StorageExplorerProjection p, String workflowName,
                                          Integer childCount, List<StoragePreviewFile> previewFiles) {
        return new StorageExplorerDto(
            p.id(),
            p.storageType(),
            p.sourceType(),
            p.fileName(),
            p.mimeType(),
            p.sizeBytes(),
            formatBytes(p.sizeBytes()),
            p.createdAt(),
            p.workflowId(),
            workflowName,
            p.projectId(),
            p.runId(),
            p.stepKey(),
            p.epoch(),
            p.s3Key(),
            p.contentType(),
            p.isFolder(),
            p.parentFolderId() != null ? p.parentFolderId().toString() : null,
            p.isFolder() ? childCount : null,
            p.isFolder() ? (previewFiles != null ? previewFiles : List.of()) : null,
            // Real rows (persisted file / manual folder) carry no virtual-folder coordinates.
            null,
            null,
            null,
            null
        );
    }

    /**
     * Build a synthetic VIRTUAL folder DTO (Phase 2b). A virtual folder is never a persisted row:
     * {@code id} is null and {@code parentFolderId} is null. It is rendered as a folder
     * ({@code isFolder=true}) with the iOS-style 9-up preview tile ({@code childCount} +
     * {@code previewFiles}). The display name is resolved client-side from {@code virtualKind}
     * + {@code workflowName} (+ epoch / spawn / itemIndex), so {@code fileName} stays null.
     *
     * @param virtualId       navigation token for the folder (e.g. {@code wf:<id>/e0}); the child
     *                        listing is fetched by passing this back as {@code parentFolderId}.
     * @param virtualKind     {@code WORKFLOW} / {@code EPOCH} / {@code SPAWN} / {@code ITERATION}.
     * @param workflowId      the {@code workflow_id} this subtree belongs to (string UUID).
     * @param workflowName    resolved workflow display name (null → frontend falls back to a label).
     * @param epoch           epoch coordinate (null at WORKFLOW level).
     * @param spawn           spawn coordinate (null above SPAWN level).
     * @param itemIndex       iteration coordinate (null above ITERATION level).
     * @param childCount      number of files grouped under this folder.
     * @param previewFiles    ≤9 preview files (newest first, any type) for the tile.
     * @param createdAt       latest {@code created_at} among the grouped files (folder "modified" time).
     */
    public static StorageExplorerDto virtualFolder(
            String virtualId, String virtualKind, String workflowId, String workflowName,
            Integer epoch, Integer spawn, Integer itemIndex, int childCount,
            List<StoragePreviewFile> previewFiles, Instant createdAt) {
        return new StorageExplorerDto(
            null,            // id - not a persisted row
            "FOLDER",        // storageType
            "FOLDER",        // sourceType
            null,            // fileName - localised client-side from virtualKind + workflowName
            null,            // mimeType
            null,            // sizeBytes
            "0 B",           // formattedSize - a folder has no own size
            createdAt,
            workflowId,
            workflowName,
            null,            // projectId
            null,            // runId
            null,            // stepKey
            epoch,
            null,            // s3Key
            null,            // contentType
            true,            // isFolder
            null,            // parentFolderId - virtual folders live outside the manual-folder tree
            childCount,
            previewFiles != null ? previewFiles : List.of(),
            virtualId,
            virtualKind,
            spawn,
            itemIndex
        );
    }

    /**
     * Return a copy with {@code createdAt} replaced. Used to stamp a MANUAL folder's last-activity
     * date (the {@code MAX(child.created_at)} the service batches) into the same field a VIRTUAL
     * workflow folder already carries, so every folder exposes one uniform "last modified" instant the
     * frontend can sort + display by. A {@code null} replacement is ignored (keeps the original).
     */
    public StorageExplorerDto withCreatedAt(Instant newCreatedAt) {
        if (newCreatedAt == null) {
            return this;
        }
        return new StorageExplorerDto(
            id, storageType, sourceType, fileName, mimeType, sizeBytes, formattedSize, newCreatedAt,
            workflowId, workflowName, projectId, runId, stepKey, epoch, s3Key, contentType, isFolder,
            parentFolderId, childCount, previewFiles, virtualId, virtualKind, spawn, itemIndex);
    }

    private static String formatBytes(Integer bytes) {
        if (bytes == null || bytes == 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        double size = bytes;
        while (size >= 1024 && unitIndex < units.length - 1) {
            size /= 1024;
            unitIndex++;
        }
        if (unitIndex == 0) return bytes + " B";
        return String.format("%.1f %s", size, units[unitIndex]);
    }
}
