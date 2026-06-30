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
    private static final String MIME_PNG = "image/png";
    private static final String SCREENSHOT_ENDPOINT = "/internal/render/screenshot";

    /** Resource key for the backpressure permit. One global key per orchestrator deployment -
     *  the Playwright sidecar is a shared singleton so the cap is process-global, not per-run. */
    private static final String SEMAPHORE_KEY = "screenshot:sidecar";

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
        if (rendererBaseUrl == null || rendererBaseUrl.isBlank()) {
            logger.debug("screenshot-renderer-url not configured - skipping screenshot for nodeId={} runId={}",
                nodeId, runId);
            return Optional.empty();
        }

        // Backpressure: cap concurrent calls to the (shared) Playwright sidecar. The sidecar
        // serialises on a single warm browser; piling unbounded orchestrator threads against it
        // creates RestTemplate read-timeout cascades + RUNNABLE thread pile-up. Uses the same
        // DistributedSemaphore primitive as BrowserAgentModule + EpochConcurrencyLimiter -
        // cross-replica when Redis is wired, in-process otherwise. On capacity exhaustion we
        // skip the capture (Optional.empty), honouring the existing best-effort contract: the
        // workflow continues normally, just without a `screenshot` field this run.
        String ownerId = nodeId + ":" + runId + ":" + epoch + ":" + spawn + ":" + Thread.currentThread().getId();
        if (!screenshotConcurrency.tryAcquire(SEMAPHORE_KEY, maxConcurrent, ownerId)) {
            logger.info("Screenshot sidecar at capacity (max={}), skipping for nodeId={} runId={} epoch={} spawn={}",
                maxConcurrent, nodeId, runId, epoch, spawn);
            return Optional.empty();
        }

        try {
            return doCapture(tenantId, runId, epoch, spawn, itemIndex, nodeId, interfaceId);
        } finally {
            screenshotConcurrency.release(SEMAPHORE_KEY, ownerId);
        }
    }

    private Optional<FileRef> doCapture(String tenantId, String runId, int epoch, int spawn,
                                        Integer itemIndex, String nodeId, UUID interfaceId) {
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
            logger.warn("Interface render failed for screenshot capture: interfaceId={} runId={} epoch={}: {}",
                interfaceId, runId, epoch, e.getMessage());
            return Optional.empty();
        }

        String assembled = assembleHtml(snapshot.html(), snapshot.css(), snapshot.js(), snapshot.vars());

        byte[] pngBytes;
        try {
            pngBytes = postToSidecar(assembled);
        } catch (RestClientException e) {
            logger.warn("Screenshot sidecar call failed for nodeId={} runId={} epoch={}: {}",
                nodeId, runId, epoch, e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            logger.warn("Unexpected error calling screenshot sidecar for nodeId={} runId={} epoch={}: {}",
                nodeId, runId, epoch, e.getMessage());
            return Optional.empty();
        }

        if (pngBytes == null || pngBytes.length == 0) {
            logger.warn("Screenshot sidecar returned empty body for nodeId={} runId={} epoch={}",
                nodeId, runId, epoch);
            return Optional.empty();
        }

        String workflowId = resolveWorkflowId(runId);
        if (workflowId == null) {
            logger.warn("Workflow run not found, skipping screenshot upload for nodeId={} runId={}",
                nodeId, runId);
            return Optional.empty();
        }

        // Name the screenshot after the producing node's label
        // (e.g. "resultsui_screenshot_epoch_2_spawn_0.png") so it is traceable back to the
        // exact interface node in the file explorer, instead of the opaque,
        // collision-prone "interface_screenshot_epoch_N.png". nodeId looks like
        // "interface:resultsui"; strip the "interface:" prefix for the label.
        // The spawn segment is REQUIRED for uniqueness: re-spawning the same interface node
        // within one epoch previously produced an identical "..._epoch_N.png" name.
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
        String fileName = nodeLabel + "_screenshot_epoch_" + epoch + "_spawn_" + spawn + ".png";
        FileRef fileRef;
        try {
            fileRef = fileStorageService.upload(
                tenantId,
                workflowId,
                runId,
                nodeId,
                fileName,
                MIME_PNG,
                new ByteArrayInputStream(pngBytes),
                pngBytes.length,
                epoch,
                spawn,
                itemIndex,
                com.apimarketplace.common.storage.service.StorageSourceTypes.INTERFACE_SCREENSHOT
            );
        } catch (Exception e) {
            logger.warn("Screenshot upload failed for nodeId={} runId={} epoch={} spawn={}: {}",
                nodeId, runId, epoch, spawn, e.getMessage());
            return Optional.empty();
        }

        if (fileRef == null) {
            logger.warn("Screenshot upload returned null FileRef for nodeId={} runId={} epoch={}",
                nodeId, runId, epoch);
            return Optional.empty();
        }

        logger.info("Captured interface screenshot: nodeId={}, runId={}, epoch={}, spawn={}, bytes={}, path={}",
            nodeId, runId, epoch, spawn, pngBytes.length, fileRef.path());
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

    private byte[] postToSidecar(String html) {
        String url = rendererBaseUrl.endsWith("/")
            ? rendererBaseUrl.substring(0, rendererBaseUrl.length() - 1) + SCREENSHOT_ENDPOINT
            : rendererBaseUrl + SCREENSHOT_ENDPOINT;

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
