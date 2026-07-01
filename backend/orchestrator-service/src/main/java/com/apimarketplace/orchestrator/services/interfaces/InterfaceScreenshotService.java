package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.file.FileRef;

import java.util.Optional;
import java.util.UUID;

/**
 * Captures a PNG screenshot of a workflow's rendered interface and returns a {@link FileRef}
 * pointing to it under the calling tenant's storage prefix.
 *
 * <p>Used by {@link com.apimarketplace.orchestrator.execution.v2.nodes.InterfaceNode} when the
 * node's {@code generateScreenshot} parameter is true. The capture is best-effort: if the
 * renderer is unreachable, mis-configured, or returns a non-2xx, the implementation MUST log a
 * warning and return {@link Optional#empty()} so the workflow can continue with no
 * {@code screenshot} output field. Workflows opting in to a screenshot accept that the field
 * may be absent - callers should null-guard or use a SpEL pipe default.</p>
 *
 * <p>Each capture creates a fresh storage object - {@code S3FileStorageService.upload} injects a
 * random prefix into the S3 key, so re-running the same workflow within the same epoch produces
 * a new PNG every time rather than overwriting. Stale captures are reaped by the existing S3
 * lifecycle policies that handle the rest of {@code workflow_step_data} artifacts; callers that
 * need exact idempotency must dedupe at the workflow level.</p>
 */
public interface InterfaceScreenshotService {

    /**
     * Render the interface and persist a PNG capture.
     *
     * <p>The implementation derives any extra storage segments (e.g. workflow id) from the run id
     * via the standard repositories - the caller does not need to know them.</p>
     *
     * <p>Legacy 5-arg overload - delegates to the spawn-aware overload with {@code spawn=0,
     * itemIndex=null}. Prefer {@link #capture(String, String, int, int, Integer, String, UUID)}
     * so re-runs within the same epoch (different spawns) do not collide on the filename.</p>
     *
     * @param tenantId     tenant owning the run (S3 prefix root)
     * @param runId        run identifier (epoch namespacing)
     * @param epoch        epoch index
     * @param nodeId       interface node id (e.g. {@code interface:my_form})
     * @param interfaceId  UUID of the interface entity to render
     * @return a {@link FileRef} for the captured PNG, or empty when capture failed (toggle was set but
     *         the renderer was unreachable / unconfigured / returned non-2xx).
     */
    default Optional<FileRef> capture(
        String tenantId,
        String runId,
        int epoch,
        String nodeId,
        UUID interfaceId
    ) {
        return capture(tenantId, runId, epoch, 0, null, nodeId, interfaceId);
    }

    /**
     * Render the interface and persist a PNG capture, carrying the full run coordinates so the
     * stored file is grouped by workflow → epoch → spawn → iteration and the filename is unique
     * across spawns (the old {@code ..._epoch_N.png} name collided when the same interface node was
     * re-spawned within one epoch).
     *
     * @param spawn      spawn index for rerun isolation within the epoch (0 for first execution)
     * @param itemIndex  item index for loop/split contexts (optional, null when not item-scoped)
     */
    Optional<FileRef> capture(
        String tenantId,
        String runId,
        int epoch,
        int spawn,
        Integer itemIndex,
        String nodeId,
        UUID interfaceId
    );

    /**
     * Render the interface to a PDF and persist it, mirroring
     * {@link #capture(String, String, int, int, Integer, String, UUID)} but producing a PDF (via
     * the renderer's {@code /internal/render/pdf} endpoint) instead of a PNG. Same best-effort
     * contract: any failure logs a warning and returns {@link Optional#empty()} so the workflow
     * continues without a {@code pdf} output field.
     *
     * @param pdfFormat   page size ({@code A4} / {@code Letter} / {@code Legal}); null/blank → A4
     * @param landscape   true to render in landscape orientation
     * @return a {@link FileRef} for the captured PDF, or empty when the render/upload failed.
     */
    Optional<FileRef> capturePdf(
        String tenantId,
        String runId,
        int epoch,
        int spawn,
        Integer itemIndex,
        String nodeId,
        UUID interfaceId,
        String pdfFormat,
        boolean landscape
    );
}
