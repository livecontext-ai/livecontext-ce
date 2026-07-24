package com.apimarketplace.orchestrator.services.media;

import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP client for the media-processing endpoint of the optional renderer sidecar
 * ({@code POST {services.screenshot-renderer-url}/internal/media}).
 *
 * <p>Responsibilities (the {@code core:media} node delegates the whole I/O leg here):
 * <ol>
 *   <li>Download the resolved input FileRefs from storage under the OWNER tenant
 *       (same contract as FilesToolsProvider.readImageBytes: the internal storage
 *       download authorizes by the key's tenant prefix, and every accepted key is
 *       guarded to belong to the executing workflow's tenant first).</li>
 *   <li>POST a multipart request: one {@code spec} JSON part
 *       ({@code {operation, options, inputs:[{name, role, trackIndex?}]}}) plus one
 *       binary part per input, named {@code input0..inputN}.</li>
 *   <li>Interpret the response: {@code probe} answers JSON (passed through verbatim),
 *       the other operations answer raw media bytes with
 *       {@code X-Media-Duration-Seconds} / {@code X-Media-Operation} headers.</li>
 *   <li>Guard binary results against {@code services.screenshot-renderer.video-max-bytes}
 *       (same guard as interface video renders) and upload them to storage as a
 *       {@code STEP_OUTPUT} file, returning the canonical FileRef.</li>
 * </ol>
 *
 * <p>Backpressure: media jobs (ffmpeg transcodes) hold a sidecar slot for their whole
 * duration, so they use their own {@link DistributedSemaphore} pool
 * ({@code media:sidecar}, cap {@code services.screenshot-renderer.media-max-concurrent},
 * default 2) instead of competing with screenshot/PDF/video-render traffic.
 *
 * <p>Unlike the best-effort screenshot pipeline, every failure here THROWS
 * {@link MediaRenderException} with an agent-actionable message: the media output IS
 * the node's purpose, so the node must fail loudly rather than continue without it.
 */
@Service
public class MediaRenderService {

    private static final Logger logger = LoggerFactory.getLogger(MediaRenderService.class);

    static final String MEDIA_ENDPOINT = "/internal/media";
    /** Dedicated permit pool: an ffmpeg job holds its slot for the whole transcode. */
    static final String SEMAPHORE_KEY = "media:sidecar";
    static final String DURATION_HEADER = "X-Media-Duration-Seconds";
    /** frame only: the ACTUAL timestamp the still was taken at (after default/clamp). */
    static final String TIMESTAMP_HEADER = "X-Media-Timestamp-Seconds";
    /** Mirrors the renderer's MAX_MEDIA_INPUT_BYTES (300MB) for the pre-flight size check. */
    static final long MAX_TOTAL_INPUT_BYTES = 300L * 1024 * 1024;

    /** Failure of a media render, carrying an agent-actionable message. */
    public static class MediaRenderException extends RuntimeException {
        public MediaRenderException(String message) {
            super(message);
        }

        public MediaRenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * One resolved input for the render: the multipart part name ({@code input0..inputN},
     * assigned by the node so option maps can reference it via {@code source_part}), the
     * role the renderer treats it as ({@code video} | {@code audio} | {@code image} |
     * {@code input}), the mix-track/concat-clip index it belongs to (null outside those
     * lists), and the RAW FileRef map it resolved to.
     */
    public record MediaInput(String name, String role, Integer trackIndex, Map<String, Object> fileRef) { }

    /** Union of the two response shapes. */
    public sealed interface MediaResult permits ProbeResult, FileResult { }

    /** The probe operation's JSON payload, passed through verbatim (renderer-computed). */
    public record ProbeResult(Map<String, Object> fields) implements MediaResult { }

    /**
     * A produced media file, uploaded to storage, plus its duration when reported.
     * {@code timestampSeconds} is frame-only: the ACTUAL timestamp the still was taken
     * at (after the default-middle and end-clamp), null for every other operation.
     */
    public record FileResult(FileRef file, Double durationSeconds, Double timestampSeconds) implements MediaResult {
        public FileResult(FileRef file, Double durationSeconds) {
            this(file, durationSeconds, null);
        }
    }

    private final FileStorageService fileStorageService;
    private final RestTemplate mediaRestTemplate;
    private final ObjectMapper objectMapper;
    private final String rendererBaseUrl;
    private final DistributedSemaphore mediaConcurrency;
    private final int maxConcurrent;
    private final long videoMaxBytes;

    @Autowired
    public MediaRenderService(
        FileStorageService fileStorageService,
        // Long-read-timeout template: an ffmpeg transcode legitimately outlives the
        // default 30s window, exactly like an interface video recording.
        @Qualifier("videoRenderRestTemplate") RestTemplate mediaRestTemplate,
        ObjectMapper objectMapper,
        @Value("${services.screenshot-renderer-url:}") String rendererBaseUrl,
        DistributedSemaphore mediaConcurrency,
        @Value("${services.screenshot-renderer.media-max-concurrent:2}") int maxConcurrent,
        // Reuses the interface-video upload guard (100MB default): checked AFTER the
        // bytes are already materialised, so it only decides store-vs-fail.
        @Value("${services.screenshot-renderer.video-max-bytes:104857600}") long videoMaxBytes
    ) {
        this.fileStorageService = fileStorageService;
        this.mediaRestTemplate = mediaRestTemplate;
        this.objectMapper = objectMapper;
        this.rendererBaseUrl = rendererBaseUrl;
        this.mediaConcurrency = mediaConcurrency;
        this.maxConcurrent = maxConcurrent;
        this.videoMaxBytes = videoMaxBytes;
    }

    /** True when the optional renderer component is configured on this installation. */
    public boolean isEnabled() {
        return rendererBaseUrl != null && !rendererBaseUrl.isBlank();
    }

    /**
     * Run one media operation end to end: download inputs, POST to the sidecar,
     * upload the result.
     *
     * @param tenantId   executing workflow's tenant - every input key must belong to it
     * @param workflowId workflow UUID (storage path segment)
     * @param runId      run id (storage path segment)
     * @param nodeId     producing node key ({@code core:<label>}) - names the stored file
     * @param epoch      run epoch to stamp on the stored file
     * @param spawn      spawn to stamp on the stored file
     * @param itemIndex  split item index (null outside a split body)
     * @param operation  {@code probe} | {@code mux_audio} | {@code mix} | {@code extract_audio}
     *                   | {@code concat} | {@code frame} | {@code overlay}
     * @param options    operation options (contract params minus the file expressions;
     *                   mix tracks and concat clips reference their binary part via
     *                   {@code source_part})
     * @param inputs     resolved inputs in part order ({@code input0..inputN})
     * @return {@link ProbeResult} for probe, {@link FileResult} for the file-producing ops
     * @throws MediaRenderException on any failure, with an agent-actionable message
     */
    public MediaResult render(String tenantId, String workflowId, String runId, String nodeId,
                              int epoch, int spawn, Integer itemIndex,
                              String operation, Map<String, Object> options, List<MediaInput> inputs) {
        if (!isEnabled()) {
            // The node normally pre-checks isEnabled() and produces the richer message;
            // this is the defensive backstop for direct callers.
            throw new MediaRenderException(
                "Media processing is not available: the media renderer component is not enabled on this installation.");
        }

        String ownerId = nodeId + ":" + runId + ":" + epoch + ":" + spawn + ":" + Thread.currentThread().getId();
        if (!mediaConcurrency.tryAcquire(SEMAPHORE_KEY, maxConcurrent, ownerId)) {
            throw new MediaRenderException(
                "The media renderer is already processing its maximum number of jobs (" + maxConcurrent
                    + "). Run the workflow again in a moment.");
        }
        try {
            return doRender(tenantId, workflowId, runId, nodeId, epoch, spawn, itemIndex,
                operation, options, inputs);
        } finally {
            mediaConcurrency.release(SEMAPHORE_KEY, ownerId);
        }
    }

    private MediaResult doRender(String tenantId, String workflowId, String runId, String nodeId,
                                 int epoch, int spawn, Integer itemIndex,
                                 String operation, Map<String, Object> options, List<MediaInput> inputs) {
        // Pre-flight: the renderer rejects requests above its input cap AFTER the bytes
        // travelled; FileRef sizes are already known, so refuse before buffering anything.
        long declaredTotal = 0;
        for (MediaInput input : inputs) {
            Object size = input.fileRef().get("size");
            if (size instanceof Number n && n.longValue() > 0) {
                declaredTotal += n.longValue();
            }
        }
        if (declaredTotal > MAX_TOTAL_INPUT_BYTES) {
            throw new MediaRenderException(
                "The input files total " + declaredTotal + " bytes, above the media renderer's "
                    + MAX_TOTAL_INPUT_BYTES + " byte limit. Use smaller files or trim them upstream "
                    + "(trim_start_seconds/trim_end_seconds), then run again.");
        }

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("spec", specPart(operation, options, inputs));

        for (MediaInput input : inputs) {
            byte[] bytes = downloadInput(tenantId, input);
            body.add(input.name(), binaryPart(input, bytes));
        }

        String url = rendererBaseUrl.endsWith("/")
            ? rendererBaseUrl.substring(0, rendererBaseUrl.length() - 1) + MEDIA_ENDPOINT
            : rendererBaseUrl + MEDIA_ENDPOINT;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL));

        ResponseEntity<byte[]> response;
        try {
            response = mediaRestTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(body, headers), byte[].class);
        } catch (HttpStatusCodeException e) {
            throw new MediaRenderException(rendererErrorMessage(operation, e), e);
        } catch (RestClientException e) {
            // Deliberately no e.getMessage() here: Spring I/O messages embed the internal
            // renderer URL, which must not reach the agent-visible failure text.
            throw new MediaRenderException(
                "The media renderer did not answer for operation '" + operation
                    + "'. Retry the run; if it keeps failing, "
                    + "tell the user the media renderer component appears unhealthy.", e);
        }

        byte[] resultBytes = response.getBody();
        MediaType contentType = response.getHeaders().getContentType();

        if (contentType != null && contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return new ProbeResult(parseJsonBody(resultBytes, operation));
        }

        if (resultBytes == null || resultBytes.length == 0) {
            throw new MediaRenderException(
                "The media renderer returned an empty result for operation '" + operation + "'. Retry the run.");
        }
        if (resultBytes.length > videoMaxBytes) {
            throw new MediaRenderException(
                "The produced media file is too large to store (" + resultBytes.length + " bytes, limit "
                    + videoMaxBytes + "). Shorten the inputs (trim_start_seconds/trim_end_seconds) or lower "
                    + "audio_bitrate, then run again.");
        }

        Double durationSeconds = parseDurationSeconds(response.getHeaders().getFirst(DURATION_HEADER));
        Double timestampSeconds = parseDurationSeconds(response.getHeaders().getFirst(TIMESTAMP_HEADER));
        String mime = contentType != null ? contentType.toString() : "application/octet-stream";
        String extension = extensionFor(contentType);

        String nodeLabel = (nodeId == null || nodeId.isBlank())
            ? "media"
            : LabelNormalizer.extractLabelFromKey(nodeId);
        if (nodeLabel == null || nodeLabel.isBlank()) {
            nodeLabel = "media";
        }
        String fileName = nodeLabel + "_" + operation + "_epoch_" + epoch + "_spawn_" + spawn + "." + extension;

        FileRef fileRef;
        try {
            fileRef = fileStorageService.upload(
                tenantId, workflowId, runId, nodeId, fileName, mime, resultBytes,
                epoch, spawn, itemIndex,
                com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT);
        } catch (Exception e) {
            throw new MediaRenderException(
                "The media result could not be stored (" + e.getMessage() + "). Retry the run.", e);
        }
        if (fileRef == null) {
            throw new MediaRenderException("The media result could not be stored. Retry the run.");
        }

        logger.info("Media {} produced: nodeId={}, runId={}, epoch={}, spawn={}, bytes={}, durationSeconds={}, timestampSeconds={}, path={}",
            operation, nodeId, runId, epoch, spawn, resultBytes.length, durationSeconds, timestampSeconds, fileRef.path());
        return new FileResult(fileRef, durationSeconds, timestampSeconds);
    }

    /**
     * Download one input's bytes. Ownership contract: the internal storage download is
     * authorized by the KEY-OWNER tenant prefix, and a workflow may only feed the media
     * node files of its own tenant - so a foreign or malformed key fails here explicitly
     * instead of surfacing as a silent empty download.
     */
    private byte[] downloadInput(String tenantId, MediaInput input) {
        Map<String, Object> fileRef = input.fileRef();
        Object pathValue = fileRef != null ? fileRef.get("path") : null;
        String key = pathValue instanceof String s ? s : null;
        if (key == null || key.isBlank()) {
            throw new MediaRenderException(
                "Media input '" + describe(input) + "' has no storage path - map the WHOLE FileRef "
                    + "output of an upstream node (e.g. {{core:download.output.file}}).");
        }
        // Defense-in-depth mirror of the public_link node: the map is plan-shapeable
        // (a code node can emit any {path}), so refuse traversal segments outright.
        if (key.contains("..")) {
            throw new MediaRenderException(
                "Media input '" + describe(input) + "' has an invalid storage path - refusing to read it.");
        }
        // Ownership guard: only tenant-owned keys. After this check the executing tenant
        // IS the key-owner tenant the download contract requires.
        if (!key.startsWith(tenantId + "/")) {
            logger.warn("Media input REFUSED for foreign key: tenant={}, key={}", tenantId, key);
            throw new MediaRenderException(
                "Media input '" + describe(input) + "' does not belong to this workflow's tenant - refusing to read it.");
        }
        Optional<byte[]> bytes = fileStorageService.download(tenantId, key);
        if (bytes.isEmpty() || bytes.get().length == 0) {
            throw new MediaRenderException(
                "Media input '" + describe(input) + "' could not be read from storage (the file may have "
                    + "been deleted or the run's files expired). Re-produce the file upstream and run again.");
        }
        return bytes.get();
    }

    private static String describe(MediaInput input) {
        if (input.trackIndex() != null) {
            return input.role() + " track " + input.trackIndex();
        }
        return input.role() + " (" + input.name() + ")";
    }

    private HttpEntity<String> specPart(String operation, Map<String, Object> options, List<MediaInput> inputs) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("operation", operation);
        spec.put("options", options != null ? options : Map.of());
        spec.put("inputs", inputs.stream().map(input -> {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("name", input.name());
            descriptor.put("role", input.role());
            if (input.trackIndex() != null) {
                descriptor.put("trackIndex", input.trackIndex());
            }
            return descriptor;
        }).toList());

        String json;
        try {
            json = objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new MediaRenderException(
                "The media request could not be serialised (" + e.getMessage() + "). Check the node's params.", e);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private static HttpEntity<ByteArrayResource> binaryPart(MediaInput input, byte[] bytes) {
        Object nameValue = input.fileRef() != null ? input.fileRef().get("name") : null;
        String fileName = nameValue instanceof String s && !s.isBlank() ? s : input.name();
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
        HttpHeaders headers = new HttpHeaders();
        Object mimeValue = input.fileRef() != null ? input.fileRef().get("mimeType") : null;
        MediaType partType = MediaType.APPLICATION_OCTET_STREAM;
        if (mimeValue instanceof String mime && !mime.isBlank()) {
            try {
                partType = MediaType.parseMediaType(mime);
            } catch (Exception ignored) {
                // Unknown MIME string: keep octet-stream, the renderer probes content anyway.
            }
        }
        headers.setContentType(partType);
        return new HttpEntity<>(resource, headers);
    }

    private Map<String, Object> parseJsonBody(byte[] body, String operation) {
        if (body == null || body.length == 0) {
            throw new MediaRenderException(
                "The media renderer returned an empty result for operation '" + operation + "'. Retry the run.");
        }
        try {
            return objectMapper.readValue(body, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            throw new MediaRenderException(
                "The media renderer returned an unreadable result for operation '" + operation
                    + "' (" + e.getMessage() + "). Retry the run.", e);
        }
    }

    /**
     * Turn a renderer 4xx/5xx into the agent-actionable message. The renderer answers a
     * JSON body {@code {error, code, stderr_tail?}}; known codes include UNKNOWN_OPERATION,
     * INVALID_SPEC, VALUE_OUT_OF_RANGE, TRACKS_LIMIT, DUCK_REF_INVALID, MISSING_INPUT,
     * INPUT_TOO_LARGE, FFMPEG_FAILED (with stderr_tail), MEDIA_TIMEOUT (HTTP 504) and
     * BUSY (HTTP 429). Timeout and busy get dedicated guidance; FFMPEG_FAILED surfaces
     * its stderr tail so the agent can fix the inputs.
     */
    private String rendererErrorMessage(String operation, HttpStatusCodeException e) {
        String error = null;
        String code = null;
        String stderrTail = null;
        try {
            Map<String, Object> parsed = objectMapper.readValue(
                e.getResponseBodyAsByteArray(), new TypeReference<Map<String, Object>>() { });
            error = parsed.get("error") instanceof String s ? s : null;
            code = parsed.get("code") instanceof String s ? s : null;
            stderrTail = parsed.get("stderr_tail") instanceof String s ? s : null;
        } catch (Exception ignored) {
            // Non-JSON error body: fall back to the status line below.
        }

        int status = e.getStatusCode().value();
        if (status == 504 || "MEDIA_TIMEOUT".equals(code)) {
            return "Media operation '" + operation + "' timed out: it exceeded this installation's render "
                + "budget" + (error != null && !error.isBlank() ? " (" + error + ")" : "") + ". Use shorter "
                + "inputs (trim_start_seconds/trim_end_seconds) or smaller files, then run again.";
        }
        if (status == 429 || "BUSY".equals(code)) {
            return "The media renderer queue is full right now for operation '" + operation + "'. Retry "
                + "when fewer media operations run concurrently.";
        }

        StringBuilder message = new StringBuilder("Media operation '").append(operation).append("' failed: ");
        message.append(error != null && !error.isBlank()
            ? error
            : "the media renderer answered " + status);
        if (stderrTail != null && !stderrTail.isBlank()) {
            String tail = stderrTail.strip();
            if (tail.length() > 500) {
                tail = tail.substring(tail.length() - 500);
            }
            message.append(" | processing detail: ").append(tail);
        }
        message.append(". Fix the inputs/params accordingly and run again.");
        return message.toString();
    }

    private static Double parseDurationSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            // Informational header: an unparseable value must never fail a good render.
            return null;
        }
    }

    private static String extensionFor(MediaType contentType) {
        if (contentType == null) {
            return "bin";
        }
        String value = (contentType.getType() + "/" + contentType.getSubtype()).toLowerCase(Locale.ROOT);
        return switch (value) {
            case "video/mp4" -> "mp4";
            case "audio/mpeg", "audio/mp3" -> "mp3";
            case "audio/wav", "audio/x-wav", "audio/wave" -> "wav";
            case "audio/aac" -> "aac";
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            default -> "bin";
        };
    }
}
