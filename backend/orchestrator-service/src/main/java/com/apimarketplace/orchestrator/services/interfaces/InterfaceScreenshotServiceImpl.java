package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.interfaces.client.InterfaceFormat;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ResolvedTemplateSnapshot;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link InterfaceScreenshotService} implementation. Best-effort capture: any failure
 * (missing config, render error, sidecar non-2xx, storage error) is logged and swallowed -
 * callers receive {@link Optional#empty()} and continue the workflow without a {@code screenshot}
 * field.
 */
@Service
public class InterfaceScreenshotServiceImpl implements InterfaceScreenshotService {

    private static final Logger logger = LoggerFactory.getLogger(InterfaceScreenshotServiceImpl.class);

    private static final int VIEWPORT_WIDTH = 1280;
    private static final int VIEWPORT_HEIGHT = 800;
    private static final int PLAYWRIGHT_TIMEOUT_MS = 8000;
    /** PDF renders (print layout + font settling) are slower than a screenshot - give them headroom. */
    private static final int PDF_TIMEOUT_MS = 20000;
    /** Load timeout for the video page (the sidecar records AFTER load; recording time is separate). */
    private static final int VIDEO_LOAD_TIMEOUT_MS = 10000;
    private static final String MIME_PNG = "image/png";
    private static final String MIME_PDF = "application/pdf";
    private static final String MIME_MP4 = "video/mp4";
    private static final String DEFAULT_PDF_FORMAT = "A4";
    private static final String SCREENSHOT_ENDPOINT = "/internal/render/screenshot";
    private static final String PDF_ENDPOINT = "/internal/render/pdf";
    private static final String VIDEO_ENDPOINT = "/internal/render/video";
    static final String DEFAULT_VIDEO_PRESET = "vertical";
    /** Mirrors the sidecar's VIDEO_PRESETS (lib.js) - anything else would 400 at render time. */
    static final java.util.Set<String> SUPPORTED_VIDEO_PRESETS =
        java.util.Set.of("vertical", "horizontal", "square");
    static final int DEFAULT_VIDEO_DURATION_SECONDS = 30;
    static final int MIN_VIDEO_DURATION_SECONDS = 5;
    /** Mirrors the sidecar's MAX_VIDEO_DURATION_MS hard ceiling (lib.js). */
    static final int MAX_VIDEO_DURATION_SECONDS = 120;
    /** Mirrors the sidecar's VIDEO_MODES: smooth = offline frame-by-frame (fluid, default),
     *  live = real-time screencast recording (fallback). */
    static final java.util.Set<String> SUPPORTED_VIDEO_MODES = java.util.Set.of("smooth", "live");
    static final String DEFAULT_VIDEO_MODE = "smooth";
    static final int DEFAULT_VIDEO_FPS = 30;
    static final int MIN_VIDEO_FPS = 10;
    static final int MAX_VIDEO_FPS = 60;

    /** Resource key for the backpressure permit. One global key per orchestrator deployment -
     *  the Playwright sidecar is a shared singleton (screenshot + PDF share it) so the cap is
     *  process-global, not per-run. */
    private static final String SEMAPHORE_KEY = "screenshot:sidecar";

    /** Separate permit pool for video renders: a recording holds a sidecar slot for its WHOLE
     *  duration (seconds to minutes), so it gets its own, smaller cap instead of starving the
     *  fast screenshot/PDF traffic behind long-held "screenshot:sidecar" permits. */
    private static final String VIDEO_SEMAPHORE_KEY = "video:sidecar";

    /** Options specific to a VIDEO render; null for the other kinds. {@code explicitPreset} is
     *  the node's videoPreset when one was configured, else null - the dimension decision itself
     *  happens in {@link #buildVideoBody}, once the interface's own format is known (it comes from
     *  the resolved snapshot, which is only available inside doRender). */
    private record VideoOptions(String explicitPreset, int maxDurationSeconds, String mode, int fps) { }

    /** Distinguishes the render outputs, driving the sidecar endpoint, MIME, extension,
     *  filename segment and storage source-type. Keeps the screenshot path byte-identical. */
    private enum RenderKind {
        SCREENSHOT("screenshot", MIME_PNG, "png",
            com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_SCREENSHOT),
        PDF("pdf", MIME_PDF, "pdf",
            com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_PDF),
        VIDEO("video", MIME_MP4, "mp4",
            com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_VIDEO);

        final String segment;
        final String mime;
        final String extension;
        final String sourceType;

        RenderKind(String segment, String mime, String extension, String sourceType) {
            this.segment = segment;
            this.mime = mime;
            this.extension = extension;
            this.sourceType = sourceType;
        }
    }

    private final InterfaceRenderService renderService;
    private final FileStorageService fileStorageService;
    private final WorkflowRunRepository workflowRunRepository;
    private final RestTemplate restTemplate;
    private final RestTemplate videoRestTemplate;
    private final ObjectMapper objectMapper;
    private final String rendererBaseUrl;
    private final DistributedSemaphore screenshotConcurrency;
    private final int maxConcurrent;
    private final int videoMaxConcurrent;
    private final long videoMaxBytes;

    @Autowired
    public InterfaceScreenshotServiceImpl(
        InterfaceRenderService renderService,
        FileStorageService fileStorageService,
        WorkflowRunRepository workflowRunRepository,
        RestTemplate restTemplate,
        @Qualifier("videoRenderRestTemplate") RestTemplate videoRestTemplate,
        ObjectMapper objectMapper,
        @Value("${services.screenshot-renderer-url:}") String rendererBaseUrl,
        DistributedSemaphore screenshotConcurrency,
        @Value("${services.screenshot-renderer.max-concurrent:4}") int maxConcurrent,
        @Value("${services.screenshot-renderer.video-max-concurrent:2}") int videoMaxConcurrent,
        // 100MB (was 50MB). This is an UPLOAD guard, not a memory guard: it is checked after
        // exchange() has already materialised the whole clip as a byte[], so raising it costs
        // no additional heap - it only decides store-vs-drop. 50MB was sized when the sidecar's
        // wall budget capped a busy 1080x1920@60fps clip at ~9s; now that such a clip runs its
        // full 20s a MEASURED prod render weighs 47.4MB, and a heavier page exceeds 50MB. Over
        // the cap the clip is DROPPED (Optional.empty()), so leaving this at 50MB would turn
        // "short video" into "no video" - a strictly worse outcome than the bug being fixed.
        @Value("${services.screenshot-renderer.video-max-bytes:104857600}") long videoMaxBytes
    ) {
        this.renderService = renderService;
        this.fileStorageService = fileStorageService;
        this.workflowRunRepository = workflowRunRepository;
        this.restTemplate = restTemplate;
        this.videoRestTemplate = videoRestTemplate;
        this.objectMapper = objectMapper;
        this.rendererBaseUrl = rendererBaseUrl;
        this.screenshotConcurrency = screenshotConcurrency;
        this.maxConcurrent = maxConcurrent;
        this.videoMaxConcurrent = videoMaxConcurrent;
        this.videoMaxBytes = videoMaxBytes;
    }

    @Override
    public Optional<FileRef> capture(String tenantId, String runId, int epoch, int spawn,
                                     Integer itemIndex, String nodeId, UUID interfaceId) {
        return render(RenderKind.SCREENSHOT, tenantId, runId, epoch, spawn, itemIndex, nodeId,
            interfaceId, null, false, null);
    }

    @Override
    public Optional<FileRef> capturePdf(String tenantId, String runId, int epoch, int spawn,
                                        Integer itemIndex, String nodeId, UUID interfaceId,
                                        String pdfFormat, boolean landscape) {
        // The PDF deliberately ignores the interface's display format - page size is
        // paper-based (pdfFormat A4/Letter/Legal + pdfLandscape).
        return render(RenderKind.PDF, tenantId, runId, epoch, spawn, itemIndex, nodeId,
            interfaceId, pdfFormat, landscape, null);
    }

    @Override
    public Optional<FileRef> captureVideo(String tenantId, String runId, int epoch, int spawn,
                                          Integer itemIndex, String nodeId, UUID interfaceId,
                                          String videoPreset, Integer maxDurationSeconds,
                                          String videoMode, Integer videoFps) {
        int duration = clampVideoDurationSeconds(maxDurationSeconds);
        String mode = normalizeVideoModeOrDefault(videoMode);
        int fps = clampVideoFps(videoFps);
        // Only the EXPLICIT preset is known here. The interface's own format comes from the
        // snapshot resolved inside doRender, so the precedence (explicit preset > interface
        // format > vertical) is applied later, in buildVideoBody.
        String explicitPreset = normalizeVideoPresetOrNull(videoPreset);
        return render(RenderKind.VIDEO, tenantId, runId, epoch, spawn, itemIndex, nodeId,
            interfaceId, null, false, new VideoOptions(explicitPreset, duration, mode, fps));
    }

    /**
     * Normalise the requested render mode; null/blank/unknown falls back to smooth (the
     * offline frame-by-frame renderer - fluid output regardless of sidecar load). 'live'
     * (real-time screencast) stays available as an explicit fallback.
     */
    static String normalizeVideoModeOrDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_VIDEO_MODE;
        }
        String candidate = raw.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_VIDEO_MODES.contains(candidate) ? candidate : DEFAULT_VIDEO_MODE;
    }

    /** Clamp the requested frame rate to [MIN, MAX]; null/non-positive → default 30. */
    static int clampVideoFps(Integer fps) {
        if (fps == null || fps <= 0) {
            return DEFAULT_VIDEO_FPS;
        }
        return Math.max(MIN_VIDEO_FPS, Math.min(fps, MAX_VIDEO_FPS));
    }

    /**
     * Normalise the caller's preset to a sidecar-supported one; null/blank/unknown falls back to
     * vertical. This is the LAST line of defence: the agent/build path already normalises via
     * InterfaceNodeConfig, but a hand-written or imported plan keeps its raw string, and
     * forwarding an unknown preset would 400 at the sidecar (silent absent output) instead of
     * honouring the documented "unknown falls back to vertical" contract.
     */
    static String normalizeVideoPresetOrDefault(String raw) {
        String preset = normalizeVideoPresetOrNull(raw);
        return preset != null ? preset : DEFAULT_VIDEO_PRESET;
    }

    /**
     * Like {@link #normalizeVideoPresetOrDefault} but null for blank/unknown input - lets
     * {@link #buildVideoBody} distinguish "no explicit preset" (the interface's own format may
     * drive the viewport) from an explicit preset (which always wins).
     */
    static String normalizeVideoPresetOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String candidate = raw.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_VIDEO_PRESETS.contains(candidate) ? candidate : null;
    }

    /** Clamp the caller's recording ceiling to [MIN, MAX] seconds; null/non-positive → default. */
    static int clampVideoDurationSeconds(Integer maxDurationSeconds) {
        if (maxDurationSeconds == null || maxDurationSeconds <= 0) {
            return DEFAULT_VIDEO_DURATION_SECONDS;
        }
        return Math.max(MIN_VIDEO_DURATION_SECONDS,
            Math.min(maxDurationSeconds, MAX_VIDEO_DURATION_SECONDS));
    }

    /**
     * Shared render pipeline for both {@link RenderKind}s: acquire the sidecar backpressure permit,
     * resolve + assemble the interface HTML, POST to the kind's endpoint, then upload the returned
     * bytes under the kind's MIME / extension / source-type. Best-effort throughout - any failure
     * logs a warning and returns {@link Optional#empty()} so the workflow continues.
     */
    private Optional<FileRef> render(RenderKind kind, String tenantId, String runId, int epoch,
                                     int spawn, Integer itemIndex, String nodeId, UUID interfaceId,
                                     String pdfFormat, boolean landscape, VideoOptions videoOptions) {
        if (rendererBaseUrl == null || rendererBaseUrl.isBlank()) {
            // WARN, not DEBUG: the user explicitly asked for this render (toggle ON) and the
            // output field will silently be absent. The builder UI warns pre-run via the
            // capabilities endpoint; this is the run-time trace an operator greps for.
            logger.warn("services.screenshot-renderer-url not configured - skipping {} for nodeId={} runId={} "
                + "(enable the optional renderer component to activate interface screenshot/PDF/video outputs)",
                kind.segment, nodeId, runId);
            return Optional.empty();
        }

        // Backpressure: cap concurrent calls to the (shared) Playwright sidecar. The sidecar
        // serialises on a single warm browser; piling unbounded orchestrator threads against it
        // creates RestTemplate read-timeout cascades + RUNNABLE thread pile-up. Uses the same
        // DistributedSemaphore primitive as BrowserAgentModule + EpochConcurrencyLimiter -
        // cross-replica when Redis is wired, in-process otherwise. On capacity exhaustion we
        // skip the render (Optional.empty), honouring the existing best-effort contract: the
        // workflow continues normally, just without the field this run.
        // Video renders hold their slot for the whole recording, so they use a dedicated
        // (smaller) permit pool instead of starving the fast screenshot/PDF traffic.
        String semaphoreKey = kind == RenderKind.VIDEO ? VIDEO_SEMAPHORE_KEY : SEMAPHORE_KEY;
        int permitCap = kind == RenderKind.VIDEO ? videoMaxConcurrent : maxConcurrent;
        String ownerId = nodeId + ":" + runId + ":" + epoch + ":" + spawn + ":" + Thread.currentThread().getId();
        if (!screenshotConcurrency.tryAcquire(semaphoreKey, permitCap, ownerId)) {
            logger.info("Render sidecar at capacity (max={}), skipping {} for nodeId={} runId={} epoch={} spawn={}",
                permitCap, kind.segment, nodeId, runId, epoch, spawn);
            return Optional.empty();
        }

        try {
            return doRender(kind, tenantId, runId, epoch, spawn, itemIndex, nodeId, interfaceId,
                pdfFormat, landscape, videoOptions);
        } finally {
            screenshotConcurrency.release(semaphoreKey, ownerId);
        }
    }

    private Optional<FileRef> doRender(RenderKind kind, String tenantId, String runId, int epoch,
                                       int spawn, Integer itemIndex, String nodeId, UUID interfaceId,
                                       String pdfFormat, boolean landscape, VideoOptions videoOptions) {
        ResolvedTemplateSnapshot snapshot;
        try {
            Optional<ResolvedTemplateSnapshot> opt = renderService.resolveTemplateSnapshot(interfaceId, runId, tenantId, epoch);
            if (opt.isEmpty()) {
                logger.warn("Interface render returned no snapshot for interfaceId={} runId={} epoch={}",
                    interfaceId, runId, epoch);
                return Optional.empty();
            }
            snapshot = opt.get();
        } catch (Exception e) {
            logger.warn("Interface render failed for {} capture: interfaceId={} runId={} epoch={}: {}",
                kind.segment, interfaceId, runId, epoch, e.getMessage());
            return Optional.empty();
        }

        // The format is a property of the INTERFACE, so it arrives with the resolved snapshot
        // (which prefers a run snapshot over the live interface) rather than from the node.
        String format = snapshot.format();

        String assembled = assembleHtml(snapshot.html(), snapshot.css(), snapshot.js(), snapshot.vars(), format);

        byte[] bytes;
        try {
            SidecarResponse rendered = postToSidecar(kind, assembled, pdfFormat, landscape, videoOptions, format);
            bytes = rendered.bytes();
            // A truncated clip still comes back 200 with a VALID, merely shorter mp4, so this
            // is the only signal that the wall-clock budget - not the page's own done flag -
            // ended the recording. Without it a 20s clip silently ships as 9s.
            if (rendered.truncated()) {
                logger.warn("video render hit the sidecar wall-clock budget and was TRUNCATED to {} frames "
                        + "({}s at {}fps, requested up to {}s) for nodeId={} runId={} epoch={}. "
                        + "Lower videoFps or shorten the interface's clip to fit the budget.",
                    rendered.frames(),
                    // Locale.ROOT: a log line is machine-facing, so the decimal separator must not
                    // follow the JVM's default locale (a French default renders "9,0", not "9.0").
                    rendered.frames() != null && videoOptions != null && videoOptions.fps() > 0
                        ? String.format(java.util.Locale.ROOT, "%.1f",
                            rendered.frames() / (double) videoOptions.fps())
                        : "?",
                    videoOptions != null ? videoOptions.fps() : "?",
                    videoOptions != null ? videoOptions.maxDurationSeconds() : "?",
                    nodeId, runId, epoch);
            }
        } catch (RestClientException e) {
            logger.warn("{} sidecar call failed for nodeId={} runId={} epoch={}: {}",
                kind.segment, nodeId, runId, epoch, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("Unexpected error calling {} sidecar for nodeId={} runId={} epoch={}: {}",
                kind.segment, nodeId, runId, epoch, e.getMessage());
            return Optional.empty();
        }

        if (bytes == null || bytes.length == 0) {
            logger.warn("{} sidecar returned empty body for nodeId={} runId={} epoch={}",
                kind.segment, nodeId, runId, epoch);
            return Optional.empty();
        }

        // Screenshots/PDFs are naturally small; a recording is not. Guard storage against a
        // pathological clip (very long + busy animation) - same best-effort skip as every
        // other failure path.
        if (kind == RenderKind.VIDEO && bytes.length > videoMaxBytes) {
            logger.warn("video exceeds max size ({} > {} bytes), skipping upload for nodeId={} runId={} epoch={}",
                bytes.length, videoMaxBytes, nodeId, runId, epoch);
            return Optional.empty();
        }

        String workflowId = resolveWorkflowId(runId);
        if (workflowId == null) {
            logger.warn("Workflow run not found, skipping {} upload for nodeId={} runId={}",
                kind.segment, nodeId, runId);
            return Optional.empty();
        }

        // Name the file after the producing node's label + render kind
        // (e.g. "resultsui_screenshot_epoch_2_spawn_0.png" / "resultsui_pdf_epoch_2_spawn_0.pdf")
        // so it is traceable back to the exact interface node in the file explorer.
        // The spawn segment is REQUIRED for uniqueness: re-spawning the same interface node
        // within one epoch previously produced an identical "..._epoch_N.<ext>" name.
        // Display-only label extraction goes through the sanctioned helper (never the
        // inline `indexOf(':') + 1` alias pattern that caused the split alias-drift bug
        // class - see StepOutputsWriterUsageTest). For "interface:resultsui" this yields
        // "resultsui"; a colon-less / null id falls back to "interface".
        String nodeLabel = (nodeId == null || nodeId.isBlank())
            ? "interface"
            : LabelNormalizer.extractLabelFromKey(nodeId);
        if (nodeLabel == null || nodeLabel.isBlank()) {
            nodeLabel = "interface";
        }
        String fileName = nodeLabel + "_" + kind.segment + "_epoch_" + epoch + "_spawn_" + spawn + "." + kind.extension;
        FileRef fileRef;
        try {
            fileRef = fileStorageService.upload(
                tenantId,
                workflowId,
                runId,
                nodeId,
                fileName,
                kind.mime,
                new ByteArrayInputStream(bytes),
                bytes.length,
                epoch,
                spawn,
                itemIndex,
                kind.sourceType
            );
        } catch (Exception e) {
            logger.warn("{} upload failed for nodeId={} runId={} epoch={} spawn={}: {}",
                kind.segment, nodeId, runId, epoch, spawn, e.getMessage());
            return Optional.empty();
        }

        if (fileRef == null) {
            logger.warn("{} upload returned null FileRef for nodeId={} runId={} epoch={}",
                kind.segment, nodeId, runId, epoch);
            return Optional.empty();
        }

        logger.info("Captured interface {}: nodeId={}, runId={}, epoch={}, spawn={}, bytes={}, path={}",
            kind.segment, nodeId, runId, epoch, spawn, bytes.length, fileRef.path());
        return Optional.of(fileRef);
    }

    /**
     * True when the publisher's HTML template is already a complete document (starts with
     * {@code <!DOCTYPE>} or {@code <html>}). In that case we must NOT wrap it inside another scaffold
     * - we inject our hydration scripts in place.
     */
    static boolean isCompleteHtml(String html) {
        if (html == null || html.isBlank()) {
            return false;
        }
        String trimmed = html.trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("<!doctype") || trimmed.startsWith("<html");
    }

    /**
     * Platform base stylesheet for FRAGMENT templates only - the default theme a bare HTML
     * snippet inherits when wrapped in the scaffold below (system font, border-box sizing,
     * 8px breathing room). Byte-identical to the frontend preview's BASE_IFRAME_CSS in
     * interfaceHtmlUtils.ts (keep both in sync): a fragment must render the same in the
     * builder preview and in the screenshot/video. Complete documents (isCompleteHtml) get
     * NO base CSS on either side - the author owns the whole page.
     */
    static final String FRAGMENT_BASE_CSS =
        "* { box-sizing: border-box; }\n"
            + "body {\n"
            + "  margin: 0;\n"
            + "  padding: 8px;\n"
            + "  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n"
            + "}";

    /**
     * @param format the interface's declared format; drives the scaffold's meta viewport so a
     *               fragment template gets the same coordinate space as the Playwright viewport.
     *               Null falls back to the historical 1280x800.
     */
    private String assembleHtml(String bodyHtml, String css, String js, Map<String, Object> vars,
                                String format) {
        String hydrationScripts = buildHydrationScripts(vars, js);

        if (isCompleteHtml(bodyHtml)) {
            // Publisher provided a full <!DOCTYPE>/<html> document - inject CSS in <head>
            // and our hydration scripts before </body> (or at end if no </body>).
            // Deliberately NO platform base CSS here (see FRAGMENT_BASE_CSS).
            String withCss = (css != null && !css.isBlank())
                ? injectBeforeHeadClose(bodyHtml, "<style>" + css + "</style>")
                : bodyHtml;
            return injectBeforeBodyClose(withCss, hydrationScripts);
        }

        // Fragment template - wrap in our standard scaffold. The scaffold's viewport must match
        // the interface's declared format: it IS the coordinate space the fragment is laid out
        // in, and the sidecar renders at those same dimensions. The platform base CSS comes
        // FIRST so the author's cssTemplate can override every rule (same order as the
        // frontend preview).
        InterfaceFormat.Viewport scaffold = InterfaceFormat.resolveOrDefault(format);
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html><head>");
        sb.append("<meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=")
          .append(scaffold.width()).append(", height=").append(scaffold.height()).append("\">");
        sb.append("<style>").append(FRAGMENT_BASE_CSS).append("</style>");
        if (css != null && !css.isBlank()) {
            sb.append("<style>").append(css).append("</style>");
        }
        sb.append("</head><body>");
        sb.append(bodyHtml != null ? bodyHtml : "");
        sb.append(hydrationScripts);
        sb.append("</body></html>");
        return sb.toString();
    }

    /**
     * Build the {@code <script>window.__RESOLVED_DATA__ = ...;</script>} hydration block plus the
     * publisher's optional {@code js_template} inline script. The JSON payload is escaped so a
     * variable containing {@code </script>} can't truncate the surrounding {@code <script>} tag
     * (browser-side script-tag termination is byte-literal, not JSON-aware).
     */
    private String buildHydrationScripts(Map<String, Object> vars, String js) {
        String resolvedDataJson;
        try {
            resolvedDataJson = objectMapper.writeValueAsString(vars != null ? vars : Map.of());
        } catch (Exception e) {
            resolvedDataJson = "{}";
        }
        // Escape </ to <\/ so embedded strings cannot close the <script> tag.
        resolvedDataJson = resolvedDataJson.replace("</", "<\\/");

        StringBuilder sb = new StringBuilder(256 + resolvedDataJson.length() + (js != null ? js.length() : 0));
        sb.append("<script>window.__RESOLVED_DATA__ = ").append(resolvedDataJson).append(";</script>");
        if (js != null && !js.isBlank()) {
            sb.append("<script>").append(js).append("</script>");
        }
        return sb.toString();
    }

    private static String injectBeforeHeadClose(String html, String insertion) {
        int idx = indexOfIgnoreCase(html, "</head>");
        if (idx < 0) {
            return html + insertion;
        }
        return html.substring(0, idx) + insertion + html.substring(idx);
    }

    private static String injectBeforeBodyClose(String html, String insertion) {
        int idx = indexOfIgnoreCase(html, "</body>");
        if (idx < 0) {
            return html + insertion;
        }
        return html.substring(0, idx) + insertion + html.substring(idx);
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        if (haystack == null || needle == null) {
            return -1;
        }
        return haystack.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    /** Sidecar reply: the rendered bytes plus the smooth-video wall-clock outcome (see
     *  X-Render-Truncated). `truncated` is false for every non-smooth render. */
    private record SidecarResponse(byte[] bytes, boolean truncated, Integer frames) { }

    private SidecarResponse postToSidecar(RenderKind kind, String html, String pdfFormat, boolean landscape,
                                          VideoOptions videoOptions, String format) {
        String endpoint = switch (kind) {
            case PDF -> PDF_ENDPOINT;
            case VIDEO -> VIDEO_ENDPOINT;
            case SCREENSHOT -> SCREENSHOT_ENDPOINT;
        };
        String url = rendererBaseUrl.endsWith("/")
            ? rendererBaseUrl.substring(0, rendererBaseUrl.length() - 1) + endpoint
            : rendererBaseUrl + endpoint;

        Map<String, Object> body = switch (kind) {
            case PDF -> buildPdfBody(html, pdfFormat, landscape);
            case VIDEO -> buildVideoBody(html, videoOptions, format);
            case SCREENSHOT -> buildScreenshotBody(html, format);
        };

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        // RestTemplate's default error handler throws HttpStatusCodeException for non-2xx -
        // it never returns a non-2xx ResponseEntity to this method, so the caller's catch
        // around postToSidecar is the only failure-path we need. Video goes through the
        // long-read-timeout template: a recording legitimately outlives the default 30s window.
        RestTemplate template = kind == RenderKind.VIDEO ? videoRestTemplate : restTemplate;
        ResponseEntity<byte[]> response = template.exchange(url, HttpMethod.POST, entity, byte[].class);
        return new SidecarResponse(
            response.getBody(),
            "true".equalsIgnoreCase(response.getHeaders().getFirst("X-Render-Truncated")),
            parseFrameCount(response.getHeaders().getFirst("X-Render-Frames")));
    }

    /** Header is informational: an unparseable/absent value must never fail a good render. */
    private static Integer parseFrameCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> buildScreenshotBody(String html, String format) {
        InterfaceFormat.Viewport formatViewport = InterfaceFormat.resolve(format);

        Map<String, Object> viewport = new LinkedHashMap<>();
        viewport.put("width", formatViewport != null ? formatViewport.width() : VIEWPORT_WIDTH);
        viewport.put("height", formatViewport != null ? formatViewport.height() : VIEWPORT_HEIGHT);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("html", html);
        body.put("viewport", viewport);
        // No format configured: capture the entire scrollable interface, not just the 1280x800
        // viewport - otherwise tall dashboards / long result grids get cropped and the
        // screenshot can't be trusted for a visual check.
        // Format configured: capture the EXACT WxH frame instead, so the PNG matches the video
        // and every preview surface pixel-for-pixel (that is the point of the shared format).
        body.put("fullPage", formatViewport == null);
        body.put("waitFor", "networkidle");
        body.put("timeoutMs", PLAYWRIGHT_TIMEOUT_MS);
        return body;
    }

    private Map<String, Object> buildPdfBody(String html, String pdfFormat, boolean landscape) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("html", html);
        body.put("format", (pdfFormat != null && !pdfFormat.isBlank()) ? pdfFormat : DEFAULT_PDF_FORMAT);
        body.put("landscape", landscape);
        // Print backgrounds so styled interfaces (coloured cards, banners) render faithfully.
        body.put("printBackground", true);
        // Self-contained print HTML rarely needs networkidle; 'load' is faster + avoids hangs.
        body.put("waitFor", "load");
        body.put("timeoutMs", PDF_TIMEOUT_MS);
        return body;
    }

    /**
     * @param format the interface's own display format, resolved from the snapshot. Dimension
     *               precedence: an explicit (valid) videoPreset wins, else the interface's format
     *               drives the recording viewport, else the vertical preset default.
     */
    private Map<String, Object> buildVideoBody(String html, VideoOptions videoOptions, String format) {
        VideoOptions opts = videoOptions != null
            ? videoOptions
            : new VideoOptions(null, DEFAULT_VIDEO_DURATION_SECONDS,
                DEFAULT_VIDEO_MODE, DEFAULT_VIDEO_FPS);
        InterfaceFormat.Viewport formatViewport =
            opts.explicitPreset() == null ? InterfaceFormat.resolve(format) : null;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("html", html);
        if (formatViewport != null) {
            // The interface's format drives the recording dimensions. An explicit viewport
            // wins over `preset` in the sidecar (resolveVideoViewport), which also floors odd
            // dimensions to even for the H.264 yuv420p encoder.
            Map<String, Object> viewport = new LinkedHashMap<>();
            viewport.put("width", formatViewport.width());
            viewport.put("height", formatViewport.height());
            body.put("viewport", viewport);
        } else {
            body.put("preset", opts.explicitPreset() != null ? opts.explicitPreset() : DEFAULT_VIDEO_PRESET);
        }
        // The orchestrator always requests MP4 (H.264 + faststart): the one container every
        // downstream consumer (social upload APIs, Telegram, browsers) accepts.
        body.put("format", "mp4");
        // smooth = offline frame-by-frame under a virtual clock (every frame perfect);
        // live = real-time screencast (legacy fallback, frames can drop under load).
        body.put("mode", opts.mode());
        body.put("fps", opts.fps());
        body.put("maxDurationMs", opts.maxDurationSeconds() * 1000);
        // The interface's own JS ends the clip early by setting window.__DONE__ = true;
        // otherwise the recording runs the full maxDurationMs. Both are normal endings.
        body.put("waitForDone", true);
        body.put("waitFor", "networkidle");
        // Load timeout only - recording time is governed by maxDurationMs on the sidecar side.
        body.put("timeoutMs", VIDEO_LOAD_TIMEOUT_MS);
        return body;
    }

    /**
     * Resolve the workflow UUID from the public run id. Uses a JPQL projection so the lazy
     * {@code @ManyToOne} workflow association is fetched via a field-access query - safe to call
     * outside an open Hibernate session ({@code spring.jpa.open-in-view=false}).
     */
    private String resolveWorkflowId(String runId) {
        try {
            return workflowRunRepository.findWorkflowIdByRunIdPublic(runId)
                .map(UUID::toString)
                .orElse(null);
        } catch (Exception e) {
            logger.warn("Failed to resolve workflowId for runId={}: {}", runId, e.getMessage());
            return null;
        }
    }
}
