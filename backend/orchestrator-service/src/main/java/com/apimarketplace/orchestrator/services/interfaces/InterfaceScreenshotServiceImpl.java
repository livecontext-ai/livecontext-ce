package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ResolvedTemplateSnapshot;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String MIME_PNG = "image/png";
    private static final String MIME_PDF = "application/pdf";
    private static final String DEFAULT_PDF_FORMAT = "A4";
    private static final String SCREENSHOT_ENDPOINT = "/internal/render/screenshot";
    private static final String PDF_ENDPOINT = "/internal/render/pdf";

    /** Resource key for the backpressure permit. One global key per orchestrator deployment -
     *  the Playwright sidecar is a shared singleton (screenshot + PDF share it) so the cap is
     *  process-global, not per-run. */
    private static final String SEMAPHORE_KEY = "screenshot:sidecar";

    /** Distinguishes the two render outputs, driving the sidecar endpoint, MIME, extension,
     *  filename segment and storage source-type. Keeps the screenshot path byte-identical. */
    private enum RenderKind {
        SCREENSHOT("screenshot", MIME_PNG, "png",
            com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_SCREENSHOT),
        PDF("pdf", MIME_PDF, "pdf",
            com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_PDF);

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
    private final ObjectMapper objectMapper;
    private final String rendererBaseUrl;
    private final DistributedSemaphore screenshotConcurrency;
    private final int maxConcurrent;

    @Autowired
    public InterfaceScreenshotServiceImpl(
        InterfaceRenderService renderService,
        FileStorageService fileStorageService,
        WorkflowRunRepository workflowRunRepository,
        RestTemplate restTemplate,
        ObjectMapper objectMapper,
        @Value("${services.screenshot-renderer-url:}") String rendererBaseUrl,
        DistributedSemaphore screenshotConcurrency,
        @Value("${services.screenshot-renderer.max-concurrent:4}") int maxConcurrent
    ) {
        this.renderService = renderService;
        this.fileStorageService = fileStorageService;
        this.workflowRunRepository = workflowRunRepository;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.rendererBaseUrl = rendererBaseUrl;
        this.screenshotConcurrency = screenshotConcurrency;
        this.maxConcurrent = maxConcurrent;
    }

    @Override
    public Optional<FileRef> capture(String tenantId, String runId, int epoch, int spawn,
                                     Integer itemIndex, String nodeId, UUID interfaceId) {
        return render(RenderKind.SCREENSHOT, tenantId, runId, epoch, spawn, itemIndex, nodeId,
            interfaceId, null, false);
    }

    @Override
    public Optional<FileRef> capturePdf(String tenantId, String runId, int epoch, int spawn,
                                        Integer itemIndex, String nodeId, UUID interfaceId,
                                        String pdfFormat, boolean landscape) {
        return render(RenderKind.PDF, tenantId, runId, epoch, spawn, itemIndex, nodeId,
            interfaceId, pdfFormat, landscape);
    }

    /**
     * Shared render pipeline for both {@link RenderKind}s: acquire the sidecar backpressure permit,
     * resolve + assemble the interface HTML, POST to the kind's endpoint, then upload the returned
     * bytes under the kind's MIME / extension / source-type. Best-effort throughout - any failure
     * logs a warning and returns {@link Optional#empty()} so the workflow continues.
     */
    private Optional<FileRef> render(RenderKind kind, String tenantId, String runId, int epoch,
                                     int spawn, Integer itemIndex, String nodeId, UUID interfaceId,
                                     String pdfFormat, boolean landscape) {
        if (rendererBaseUrl == null || rendererBaseUrl.isBlank()) {
            logger.debug("screenshot-renderer-url not configured - skipping {} for nodeId={} runId={}",
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
        String ownerId = nodeId + ":" + runId + ":" + epoch + ":" + spawn + ":" + Thread.currentThread().getId();
        if (!screenshotConcurrency.tryAcquire(SEMAPHORE_KEY, maxConcurrent, ownerId)) {
            logger.info("Render sidecar at capacity (max={}), skipping {} for nodeId={} runId={} epoch={} spawn={}",
                maxConcurrent, kind.segment, nodeId, runId, epoch, spawn);
            return Optional.empty();
        }

        try {
            return doRender(kind, tenantId, runId, epoch, spawn, itemIndex, nodeId, interfaceId, pdfFormat, landscape);
        } finally {
            screenshotConcurrency.release(SEMAPHORE_KEY, ownerId);
        }
    }

    private Optional<FileRef> doRender(RenderKind kind, String tenantId, String runId, int epoch,
                                       int spawn, Integer itemIndex, String nodeId, UUID interfaceId,
                                       String pdfFormat, boolean landscape) {
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

        String assembled = assembleHtml(snapshot.html(), snapshot.css(), snapshot.js(), snapshot.vars());

        byte[] bytes;
        try {
            bytes = postToSidecar(kind, assembled, pdfFormat, landscape);
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

    private String assembleHtml(String bodyHtml, String css, String js, Map<String, Object> vars) {
        String hydrationScripts = buildHydrationScripts(vars, js);

        if (isCompleteHtml(bodyHtml)) {
            // Publisher provided a full <!DOCTYPE>/<html> document - inject CSS in <head>
            // and our hydration scripts before </body> (or at end if no </body>).
            String withCss = (css != null && !css.isBlank())
                ? injectBeforeHeadClose(bodyHtml, "<style>" + css + "</style>")
                : bodyHtml;
            return injectBeforeBodyClose(withCss, hydrationScripts);
        }

        // Fragment template - wrap in our standard scaffold.
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!DOCTYPE html><html><head>");
        sb.append("<meta charset=\"utf-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=")
          .append(VIEWPORT_WIDTH).append(", height=").append(VIEWPORT_HEIGHT).append("\">");
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

    private byte[] postToSidecar(RenderKind kind, String html, String pdfFormat, boolean landscape) {
        String endpoint = kind == RenderKind.PDF ? PDF_ENDPOINT : SCREENSHOT_ENDPOINT;
        String url = rendererBaseUrl.endsWith("/")
            ? rendererBaseUrl.substring(0, rendererBaseUrl.length() - 1) + endpoint
            : rendererBaseUrl + endpoint;

        Map<String, Object> body = kind == RenderKind.PDF
            ? buildPdfBody(html, pdfFormat, landscape)
            : buildScreenshotBody(html);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        // RestTemplate's default error handler throws HttpStatusCodeException for non-2xx -
        // it never returns a non-2xx ResponseEntity to this method, so the caller's catch
        // around postToSidecar is the only failure-path we need.
        ResponseEntity<byte[]> response = restTemplate.exchange(url, HttpMethod.POST, entity, byte[].class);
        return response.getBody();
    }

    private Map<String, Object> buildScreenshotBody(String html) {
        Map<String, Object> viewport = new LinkedHashMap<>();
        viewport.put("width", VIEWPORT_WIDTH);
        viewport.put("height", VIEWPORT_HEIGHT);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("html", html);
        body.put("viewport", viewport);
        // Capture the entire scrollable interface, not just the 1280x800 viewport - otherwise
        // tall dashboards / long result grids get cropped and the screenshot can't be trusted
        // for a visual check.
        body.put("fullPage", true);
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
