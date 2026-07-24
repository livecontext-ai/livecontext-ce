package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.execution.SignalConfig;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ResolvedTemplateSnapshot;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceScreenshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Interface execution node - renders a UI interface during DAG execution.
 *
 * <p>DAG stops at this node, the interface waits for user actions, and
 * each action fires a branch independently (via InterfaceActionController).
 * The interface stays active (AWAITING_SIGNAL) until explicitly completed.</p>
 *
 * Pattern follows {@link WaitNode} for signal registration and yield.
 */
public class InterfaceNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(InterfaceNode.class);

    private final String interfaceId;
    private final Map<String, String> actionMapping;
    private final boolean isEntryInterface;
    private final boolean generateScreenshot;
    private final boolean exposeRenderedSource;
    private final boolean generatePdf;
    private final String pdfFormat;
    private final boolean pdfLandscape;
    private final boolean generateVideo;
    private final String videoPreset;
    private final Integer videoMaxDurationSeconds;
    private final String videoMode;
    private final Integer videoFps;

    /**
     * Per-field cap on the {@code rendered_html} / {@code rendered_css} / {@code rendered_js}
     * outputs. Caps the exfiltration risk a malicious publisher could create by stuffing a giant
     * template into a published workflow that, once cloned, pipes the rendered string into an
     * outbound node (HTTP, email, agent prompt). Fields exceeding this size are truncated to
     * the first N chars + a WARN log is emitted; the workflow continues normally.
     */
    static final int MAX_RENDERED_FIELD_CHARS = 256 * 1024;

    // Injected via ServiceRegistry
    private UnifiedSignalService signalService;
    private InterfaceScreenshotService screenshotService;
    private InterfaceRenderService renderService;
    private String dagTriggerId;
    private int epoch;

    public InterfaceNode(String nodeId, String interfaceId,
                         Map<String, String> actionMapping, boolean isEntryInterface,
                         boolean generateScreenshot, boolean exposeRenderedSource,
                         boolean generatePdf, String pdfFormat, boolean pdfLandscape,
                         boolean generateVideo, String videoPreset, Integer videoMaxDurationSeconds,
                         String videoMode, Integer videoFps) {
        super(nodeId, NodeType.INTERFACE);
        this.interfaceId = interfaceId;
        this.actionMapping = actionMapping != null ? actionMapping : Map.of();
        this.isEntryInterface = isEntryInterface;
        this.generateScreenshot = generateScreenshot;
        this.exposeRenderedSource = exposeRenderedSource;
        this.generatePdf = generatePdf;
        this.pdfFormat = pdfFormat;
        this.pdfLandscape = pdfLandscape;
        this.generateVideo = generateVideo;
        this.videoPreset = videoPreset;
        this.videoMaxDurationSeconds = videoMaxDurationSeconds;
        this.videoMode = videoMode;
        this.videoFps = videoFps;
    }

    /** Backward-compatible 12-arg constructor: video mode/fps default null (smooth / 30 at render). */
    public InterfaceNode(String nodeId, String interfaceId,
                         Map<String, String> actionMapping, boolean isEntryInterface,
                         boolean generateScreenshot, boolean exposeRenderedSource,
                         boolean generatePdf, String pdfFormat, boolean pdfLandscape,
                         boolean generateVideo, String videoPreset, Integer videoMaxDurationSeconds) {
        this(nodeId, interfaceId, actionMapping, isEntryInterface, generateScreenshot,
            exposeRenderedSource, generatePdf, pdfFormat, pdfLandscape,
            generateVideo, videoPreset, videoMaxDurationSeconds, null, null);
    }

    /** Backward-compatible 9-arg constructor: video options default off (no video output). */
    public InterfaceNode(String nodeId, String interfaceId,
                         Map<String, String> actionMapping, boolean isEntryInterface,
                         boolean generateScreenshot, boolean exposeRenderedSource,
                         boolean generatePdf, String pdfFormat, boolean pdfLandscape) {
        this(nodeId, interfaceId, actionMapping, isEntryInterface, generateScreenshot,
            exposeRenderedSource, generatePdf, pdfFormat, pdfLandscape, false, null, null);
    }

    /** Backward-compatible 6-arg constructor: PDF options default off (no PDF output). */
    public InterfaceNode(String nodeId, String interfaceId,
                         Map<String, String> actionMapping, boolean isEntryInterface,
                         boolean generateScreenshot, boolean exposeRenderedSource) {
        this(nodeId, interfaceId, actionMapping, isEntryInterface, generateScreenshot,
            exposeRenderedSource, false, null, false);
    }

    /** Backward-compatible 5-arg constructor: exposeRenderedSource + PDF options default off. */
    public InterfaceNode(String nodeId, String interfaceId,
                         Map<String, String> actionMapping, boolean isEntryInterface,
                         boolean generateScreenshot) {
        this(nodeId, interfaceId, actionMapping, isEntryInterface, generateScreenshot, false);
    }

    /** Backward-compatible 4-arg constructor: generateScreenshot + exposeRenderedSource + PDF default off. */
    public InterfaceNode(String nodeId, String interfaceId,
                         Map<String, String> actionMapping, boolean isEntryInterface) {
        this(nodeId, interfaceId, actionMapping, isEntryInterface, false, false);
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.info("Interface node executing: nodeId={}, interfaceId={}, itemId={}, generateScreenshot={}, exposeRenderedSource={}",
            nodeId, interfaceId, context.itemId(), generateScreenshot, exposeRenderedSource);

        // Captured outside the try so failure paths still surface the resolved inputs
        // to the inspector "Resolved parameters" panel.
        Map<String, Object> resolvedParams = new java.util.LinkedHashMap<>();
        resolvedParams.put("interfaceId", interfaceId);
        resolvedParams.put("actions", actionMapping.size());
        resolvedParams.put("isEntryInterface", isEntryInterface);
        resolvedParams.put("generateScreenshot", generateScreenshot);
        resolvedParams.put("exposeRenderedSource", exposeRenderedSource);
        resolvedParams.put("generatePdf", generatePdf);
        if (generatePdf) {
            resolvedParams.put("pdfFormat", pdfFormat != null ? pdfFormat : "A4");
            resolvedParams.put("pdfLandscape", pdfLandscape);
        }
        resolvedParams.put("generateVideo", generateVideo);
        if (generateVideo) {
            // Only the explicit override is knowable here: with no videoPreset the dimensions
            // come from the interface's own format, resolved at render time. Emit the key only
            // when it is actually set - a placeholder string would read as a settable preset
            // value (the enum is vertical|horizontal|square) and mislead.
            if (videoPreset != null) {
                resolvedParams.put("videoPreset", videoPreset);
            }
            resolvedParams.put("videoMaxDurationSeconds",
                videoMaxDurationSeconds != null ? videoMaxDurationSeconds : 30);
            resolvedParams.put("videoMode", videoMode != null ? videoMode : "smooth");
            resolvedParams.put("videoFps", videoFps != null ? videoFps : 30);
        }

        try {
            String runId = context.runId();
            String itemId = context.itemId();

            String effectiveDagTriggerId = SignalContextResolver.resolveDagTriggerId(nodeId, dagTriggerId, context);
            int effectiveEpoch = SignalContextResolver.resolveEpoch(epoch, context);

            boolean hasContinue = actionMapping.containsValue("__continue");

            // For blocking interfaces (__continue), signal service is required - without it
            // the node would return awaitingSignal() with no signal in DB, permanently sticking the workflow.
            if (signalService == null && hasContinue) {
                logger.warn("Interface node has no signal service for blocking interface, failing: nodeId={}", nodeId);
                Map<String, Object> failOutput = new HashMap<>();
                failOutput.put("resolved_params", resolvedParams);
                failOutput.put("interface_id", interfaceId);
                failOutput.put("action_mapping", actionMapping);
                failOutput.put("is_entry_interface", isEntryInterface);
                failOutput.put("error", "Signal service not available for blocking interface node");
                return NodeExecutionResult.failureWithOutput(
                    nodeId,
                    "Signal service not available for blocking interface node",
                    failOutput,
                    0L);
            }

            Map<String, Object> signalConfig = SignalConfig.interfaceSignal(interfaceId, actionMapping);

            if (signalService != null) {
                // splitItemData stays null INTENTIONALLY (unlike UserApprovalNode):
                // the interface UI gets its per-item context from the render API's
                // (epoch, spawn, itemIndex)-tagged items, so duplicating the item on
                // the signal would only bloat the signals/snapshot payloads.
                signalService.registerSignal(
                    runId, itemId, nodeId, effectiveDagTriggerId, effectiveEpoch,
                    SignalType.INTERFACE_SIGNAL, signalConfig, null);
            }

            Map<String, Object> output = new HashMap<>();
            output.put("resolved_params", resolvedParams);
            output.put("interface_id", interfaceId);
            output.put("action_mapping", actionMapping);
            output.put("is_entry_interface", isEntryInterface);

            // Best-effort screenshot capture. Sidecar timeout or absence → log + omit field.
            // Workflow never fails on screenshot capture: it is cosmetic, not semantic.
            if (generateScreenshot) {
                Optional<FileRef> screenshot = captureScreenshot(context, effectiveEpoch);
                screenshot.ifPresent(fileRef -> output.put("screenshot", fileRef));
            }

            // Best-effort PDF render of the interface, same continue-on-failure contract as the
            // screenshot: sidecar error/absence → log + omit the `pdf` field, workflow continues.
            if (generatePdf) {
                Optional<FileRef> pdf = capturePdf(context, effectiveEpoch);
                pdf.ifPresent(fileRef -> output.put("pdf", fileRef));
            }

            // Best-effort MP4 recording of the interface's animation, same continue-on-failure
            // contract: sidecar error/absence → log + omit the `video` field, workflow continues.
            if (generateVideo) {
                Optional<FileRef> video = captureVideo(context, effectiveEpoch);
                video.ifPresent(fileRef -> output.put("video", fileRef));
            }

            // Best-effort source exposure: emit the iframe-equivalent rendered HTML/CSS/JS as
            // three separate string outputs. We delegate to the centralised
            // {@link InterfaceRenderService#resolveTemplateSnapshot} so this path and the screenshot
            // sidecar see the EXACT same {{var|default}} substitution. Render failure → fields stay
            // absent, the workflow does NOT fail (cosmetic, same contract as screenshot). Each field
            // is capped at MAX_RENDERED_FIELD_CHARS (256 KB) to bound the exfiltration risk a
            // malicious publisher could create via publish → clone.
            if (exposeRenderedSource) {
                resolveRenderedSource(context, effectiveEpoch).ifPresent(snapshot -> {
                    String html = capRenderedField("rendered_html", snapshot.html());
                    String css = capRenderedField("rendered_css", snapshot.css());
                    String js = capRenderedField("rendered_js", snapshot.js());
                    if (html != null) output.put("rendered_html", html);
                    if (css != null) output.put("rendered_css", css);
                    if (js != null) output.put("rendered_js", js);
                    logger.info("[InterfaceNode] rendered source exposed: nodeId={}, html_chars={}, css_chars={}, js_chars={}",
                        nodeId,
                        html == null ? 0 : html.length(),
                        css == null ? 0 : css.length(),
                        js == null ? 0 : js.length());
                });
            }

            if (hasContinue) {
                // Blocking: workflow pauses here until user clicks __continue
                logger.info("Interface registered blocking signal (yield): nodeId={}, interfaceId={}, actionCount={}",
                    nodeId, interfaceId, actionMapping.size());
                return NodeExecutionResult.awaitingSignal(nodeId, SignalType.INTERFACE_SIGNAL, output);
            } else {
                // Auto-advance: node completes immediately, successors execute, interface stays clickable for spawns
                logger.info("Interface registered non-blocking signal (auto-advance): nodeId={}, interfaceId={}, actionCount={}",
                    nodeId, interfaceId, actionMapping.size());
                return NodeExecutionResult.success(nodeId, output);
            }

        } catch (Exception e) {
            logger.error("Interface node failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            Map<String, Object> failOutput = new HashMap<>();
            failOutput.put("resolved_params", resolvedParams);
            failOutput.put("interface_id", interfaceId);
            failOutput.put("action_mapping", actionMapping);
            failOutput.put("is_entry_interface", isEntryInterface);
            failOutput.put("error", e.getMessage());
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), failOutput, 0L);
        }
    }

    private Optional<ResolvedTemplateSnapshot> resolveRenderedSource(ExecutionContext context, int effectiveEpoch) {
        if (renderService == null) {
            // WARN, not DEBUG: "feature silently no-op" is a high-confusion failure mode in prod.
            // Toggle is ON but bean is missing → operator needs to see this.
            logger.warn("exposeRenderedSource toggle is ON but no InterfaceRenderService is wired - skipping: nodeId={}", nodeId);
            return Optional.empty();
        }
        UUID parsedInterfaceId;
        try {
            parsedInterfaceId = UUID.fromString(interfaceId);
        } catch (IllegalArgumentException invalid) {
            logger.warn("Cannot expose rendered source: interfaceId is not a valid UUID - nodeId={}, interfaceId={}",
                nodeId, interfaceId);
            return Optional.empty();
        }
        try {
            // Centralised resolution: resolveTemplateSnapshot calls render() then applies
            // {{var|default}} substitution using items[0].data() - same path as the screenshot
            // sidecar consumes, so rendered_html matches the iframe + the captured PNG.
            return renderService.resolveTemplateSnapshot(
                parsedInterfaceId,
                context.runId(),
                context.tenantId(),
                effectiveEpoch
            );
        } catch (Exception e) {
            // Continue-on-failure: cosmetic feature must never break the workflow.
            logger.warn("Rendered source resolution failed (continuing without rendered_html/css/js): nodeId={}, error={}",
                nodeId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Caps a rendered field at {@link #MAX_RENDERED_FIELD_CHARS}. Returns the value unchanged when
     * null or under the cap; truncates to the first N chars + emits a WARN otherwise.
     */
    private String capRenderedField(String fieldName, String value) {
        if (value == null || value.length() <= MAX_RENDERED_FIELD_CHARS) {
            return value;
        }
        logger.warn("[InterfaceNode] {} exceeds {}-char cap (original: {}) - truncating: nodeId={}",
            fieldName, MAX_RENDERED_FIELD_CHARS, value.length(), nodeId);
        return value.substring(0, MAX_RENDERED_FIELD_CHARS);
    }

    private Optional<FileRef> captureScreenshot(ExecutionContext context, int effectiveEpoch) {
        if (screenshotService == null) {
            // WARN, not DEBUG - symmetric with the renderService null-branch above. "Toggle is on
            // but bean is missing" is a high-confusion failure mode that must surface in prod logs.
            logger.warn("generateScreenshot toggle is ON but no InterfaceScreenshotService is wired - skipping capture: nodeId={}", nodeId);
            return Optional.empty();
        }
        try {
            UUID parsedInterfaceId;
            try {
                parsedInterfaceId = UUID.fromString(interfaceId);
            } catch (IllegalArgumentException invalid) {
                logger.warn("Cannot capture screenshot: interfaceId is not a valid UUID - nodeId={}, interfaceId={}",
                    nodeId, interfaceId);
                return Optional.empty();
            }
            return screenshotService.capture(
                context.tenantId(),
                context.runId(),
                effectiveEpoch,
                context.spawn(),
                context.itemIndex(),
                nodeId,
                parsedInterfaceId
            );
        } catch (Exception e) {
            // Continue-on-failure: cosmetic feature must never break the workflow.
            logger.warn("Screenshot capture failed (continuing without screenshot): nodeId={}, error={}",
                nodeId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<FileRef> capturePdf(ExecutionContext context, int effectiveEpoch) {
        if (screenshotService == null) {
            // Same high-confusion "toggle on, bean missing" case as the screenshot branch.
            logger.warn("generatePdf toggle is ON but no InterfaceScreenshotService is wired - skipping PDF: nodeId={}", nodeId);
            return Optional.empty();
        }
        try {
            UUID parsedInterfaceId;
            try {
                parsedInterfaceId = UUID.fromString(interfaceId);
            } catch (IllegalArgumentException invalid) {
                logger.warn("Cannot render PDF: interfaceId is not a valid UUID - nodeId={}, interfaceId={}",
                    nodeId, interfaceId);
                return Optional.empty();
            }
            return screenshotService.capturePdf(
                context.tenantId(),
                context.runId(),
                effectiveEpoch,
                context.spawn(),
                context.itemIndex(),
                nodeId,
                parsedInterfaceId,
                pdfFormat,
                pdfLandscape
            );
        } catch (Exception e) {
            // Continue-on-failure: cosmetic feature must never break the workflow.
            logger.warn("PDF render failed (continuing without pdf): nodeId={}, error={}",
                nodeId, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<FileRef> captureVideo(ExecutionContext context, int effectiveEpoch) {
        if (screenshotService == null) {
            // Same high-confusion "toggle on, bean missing" case as the screenshot branch.
            logger.warn("generateVideo toggle is ON but no InterfaceScreenshotService is wired - skipping video: nodeId={}", nodeId);
            return Optional.empty();
        }
        try {
            UUID parsedInterfaceId;
            try {
                parsedInterfaceId = UUID.fromString(interfaceId);
            } catch (IllegalArgumentException invalid) {
                logger.warn("Cannot record video: interfaceId is not a valid UUID - nodeId={}, interfaceId={}",
                    nodeId, interfaceId);
                return Optional.empty();
            }
            return screenshotService.captureVideo(
                context.tenantId(),
                context.runId(),
                effectiveEpoch,
                context.spawn(),
                context.itemIndex(),
                nodeId,
                parsedInterfaceId,
                videoPreset,
                videoMaxDurationSeconds,
                videoMode,
                videoFps
            );
        } catch (Exception e) {
            // Continue-on-failure: cosmetic feature must never break the workflow.
            logger.warn("Video recording failed (continuing without video): nodeId={}, error={}",
                nodeId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.signalService = registry.getSignalService();
        this.screenshotService = registry.getInterfaceScreenshotService();
        this.renderService = registry.getInterfaceRenderService();
    }

    @Override
    public boolean isBranchingNode() {
        return false;
    }

    public String getInterfaceId() {
        return interfaceId;
    }

    public Map<String, String> getActionMapping() {
        return actionMapping;
    }

    public boolean isGenerateScreenshot() {
        return generateScreenshot;
    }

    public boolean isExposeRenderedSource() {
        return exposeRenderedSource;
    }

    public boolean isGeneratePdf() {
        return generatePdf;
    }

    public String getPdfFormat() {
        return pdfFormat;
    }

    public boolean isPdfLandscape() {
        return pdfLandscape;
    }

    public boolean isGenerateVideo() {
        return generateVideo;
    }

    public String getVideoPreset() {
        return videoPreset;
    }

    public Integer getVideoMaxDurationSeconds() {
        return videoMaxDurationSeconds;
    }

    public String getVideoMode() {
        return videoMode;
    }

    public Integer getVideoFps() {
        return videoFps;
    }

    public void setDagTriggerId(String dagTriggerId) {
        this.dagTriggerId = dagTriggerId;
    }

    public void setEpoch(int epoch) {
        this.epoch = epoch;
    }
}
