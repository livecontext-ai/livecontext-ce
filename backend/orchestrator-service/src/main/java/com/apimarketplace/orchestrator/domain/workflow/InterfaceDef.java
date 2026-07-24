package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.Map;

/**
 * Interface definition from the workflow plan.
 * Represents a UI interface node that participates in DAG execution.
 *
 * <p>There is deliberately no display format here: it belongs to the interface entity itself (an
 * interface's HTML is authored for one fixed viewport width, so its shape is intrinsic to it) and
 * the render path resolves it from there. A plan written before that move may still carry a
 * {@code format} key on the node; it is ignored.
 *
 * @param id                    UUID of the interface entity
 * @param label                 Display label
 * @param actionMapping         CSS selector to action target key mapping
 * @param variableMapping       Template variable to workflow expression mapping
 * @param showPreview           Whether to show preview on canvas
 * @param position              Canvas position {x, y}
 * @param isEntryInterface      Whether this interface is the entry point (shows first in multi-interface views)
 * @param generateScreenshot    Whether to capture a PNG screenshot of the rendered interface and expose it
 *                              as the {@code screenshot} FileRef output for downstream nodes
 * @param exposeRenderedSource  Whether to expose the resolved HTML/CSS/JS templates as
 *                              {@code rendered_html} / {@code rendered_css} / {@code rendered_js}
 *                              string outputs for downstream nodes (e.g. email body, debugging)
 * @param generatePdf           Whether to render the interface to a PDF and expose it as the
 *                              {@code pdf} FileRef output for downstream nodes
 * @param pdfFormat             Page size for the PDF ({@code A4} / {@code Letter} / {@code Legal});
 *                              null falls back to A4. Only meaningful when {@code generatePdf} is true
 * @param pdfLandscape          Whether the PDF is rendered in landscape orientation (default false).
 *                              Only meaningful when {@code generatePdf} is true
 * @param generateVideo         Whether to record the interface's animation to an MP4 and expose it
 *                              as the {@code video} FileRef output for downstream nodes
 * @param videoPreset           Capture format for the video ({@code vertical} / {@code horizontal}
 *                              / {@code square}); null falls back to vertical. Only meaningful when
 *                              {@code generateVideo} is true
 * @param videoMaxDurationSeconds Recording ceiling in seconds (default 30, max 120). The page can
 *                              end the clip earlier by setting {@code window.__DONE__ = true}.
 *                              Only meaningful when {@code generateVideo} is true
 * @param videoMode             {@code smooth} (offline frame-by-frame render, fluid, default) or
 *                              {@code live} (real-time screencast fallback). Only meaningful when
 *                              {@code generateVideo} is true
 * @param videoFps              Output frame rate (10-60, default 30). Only meaningful when
 *                              {@code generateVideo} is true
 */
public record InterfaceDef(
    String id,
    String label,
    Map<String, String> actionMapping,
    Map<String, String> variableMapping,
    Boolean showPreview,
    Map<String, Object> position,
    Boolean isEntryInterface,
    Boolean generateScreenshot,
    Boolean exposeRenderedSource,
    Boolean generatePdf,
    String pdfFormat,
    Boolean pdfLandscape,
    Boolean generateVideo,
    String videoPreset,
    Integer videoMaxDurationSeconds,
    String videoMode,
    Integer videoFps
) {
    /** Backward-compatible 15-arg constructor: video mode/fps default null (smooth / 30 at render). */
    public InterfaceDef(
        String id,
        String label,
        Map<String, String> actionMapping,
        Map<String, String> variableMapping,
        Boolean showPreview,
        Map<String, Object> position,
        Boolean isEntryInterface,
        Boolean generateScreenshot,
        Boolean exposeRenderedSource,
        Boolean generatePdf,
        String pdfFormat,
        Boolean pdfLandscape,
        Boolean generateVideo,
        String videoPreset,
        Integer videoMaxDurationSeconds
    ) {
        this(id, label, actionMapping, variableMapping, showPreview, position, isEntryInterface,
            generateScreenshot, exposeRenderedSource, generatePdf, pdfFormat, pdfLandscape,
            generateVideo, videoPreset, videoMaxDurationSeconds, null, null);
    }

    /** Backward-compatible 12-arg constructor: video options default off (no video output). */
    public InterfaceDef(
        String id,
        String label,
        Map<String, String> actionMapping,
        Map<String, String> variableMapping,
        Boolean showPreview,
        Map<String, Object> position,
        Boolean isEntryInterface,
        Boolean generateScreenshot,
        Boolean exposeRenderedSource,
        Boolean generatePdf,
        String pdfFormat,
        Boolean pdfLandscape
    ) {
        this(id, label, actionMapping, variableMapping, showPreview, position, isEntryInterface,
            generateScreenshot, exposeRenderedSource, generatePdf, pdfFormat, pdfLandscape,
            false, null, null);
    }

    /** Backward-compatible 7-arg constructor: PDF + screenshot + rendered-source toggles default off. */
    public InterfaceDef(
        String id,
        String label,
        Map<String, String> actionMapping,
        Map<String, String> variableMapping,
        Boolean showPreview,
        Map<String, Object> position,
        Boolean isEntryInterface
    ) {
        this(id, label, actionMapping, variableMapping, showPreview, position, isEntryInterface, false, false);
    }

    /** Backward-compatible 8-arg constructor: exposeRenderedSource + PDF options default off. */
    public InterfaceDef(
        String id,
        String label,
        Map<String, String> actionMapping,
        Map<String, String> variableMapping,
        Boolean showPreview,
        Map<String, Object> position,
        Boolean isEntryInterface,
        Boolean generateScreenshot
    ) {
        this(id, label, actionMapping, variableMapping, showPreview, position, isEntryInterface, generateScreenshot, false);
    }

    /** Backward-compatible 9-arg constructor: PDF options default off (no PDF output). */
    public InterfaceDef(
        String id,
        String label,
        Map<String, String> actionMapping,
        Map<String, String> variableMapping,
        Boolean showPreview,
        Map<String, Object> position,
        Boolean isEntryInterface,
        Boolean generateScreenshot,
        Boolean exposeRenderedSource
    ) {
        this(id, label, actionMapping, variableMapping, showPreview, position, isEntryInterface,
            generateScreenshot, exposeRenderedSource, false, null, false);
    }

    /**
     * Get the normalized key for this interface (e.g., "interface:my_form").
     */
    public String getNormalizedKey() {
        return LabelNormalizer.interfaceKey(label);
    }
}
