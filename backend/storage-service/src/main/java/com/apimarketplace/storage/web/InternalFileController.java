package com.apimarketplace.storage.web;

import com.apimarketplace.storage.domain.FileRef;
import com.apimarketplace.storage.service.file.DownloadStream;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.service.file.StorageStreamingMetrics;
import com.apimarketplace.storage.util.FileConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Internal API controller for inter-service file operations.
 * Used by orchestrator-service (via storage-client) and other services.
 * No gateway auth required (internal endpoints bypass GatewayAuthenticationFilter).
 * Disabled in monolith mode (direct service calls replace HTTP).
 */
@RestController
@RequestMapping("/api/internal/storage")
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class InternalFileController {

    private static final Logger logger = LoggerFactory.getLogger(InternalFileController.class);

    private final FileStorageService fileStorageService;
    private final StorageStreamingMetrics streamingMetrics;

    public InternalFileController(FileStorageService fileStorageService,
                                  StorageStreamingMetrics streamingMetrics) {
        this.fileStorageService = fileStorageService;
        this.streamingMetrics = streamingMetrics;
    }

    /**
     * Upload a file with workflow context.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("workflowId") String workflowId,
            @RequestParam("runId") String runId,
            @RequestParam(value = "stepAlias", defaultValue = "upload") String stepAlias,
            @RequestHeader("X-User-ID") String tenantId) {

        logger.debug("Internal upload: name={}, workflow={}, run={}, tenant={}",
            file.getOriginalFilename(), workflowId, runId, tenantId);

        try {
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : FileConstants.DEFAULT_FILENAME;
            String mimeType = file.getContentType() != null ? file.getContentType() : FileConstants.DEFAULT_MIME_TYPE;

            FileRef fileRef = fileStorageService.upload(
                tenantId, workflowId, runId, stepAlias,
                fileName, mimeType, file.getInputStream(), file.getSize());

            return ResponseEntity.ok(fileRef);
        } catch (IOException e) {
            logger.error("Internal upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    /**
     * Upload a file with byte array body (for programmatic inter-service calls).
     */
    @PostMapping(value = "/upload-bytes")
    public ResponseEntity<?> uploadBytes(
            @RequestBody byte[] content,
            @RequestParam("workflowId") String workflowId,
            @RequestParam("runId") String runId,
            @RequestParam(value = "stepAlias", defaultValue = "upload") String stepAlias,
            @RequestParam("fileName") String fileName,
            @RequestParam(value = "mimeType", defaultValue = "application/octet-stream") String mimeType,
            // Run context for workflow file producers. Optional + defaulted so legacy callers
            // (and the generic, non-workflow path) stay on the epoch-0, no-context default.
            @RequestParam(value = "epoch", defaultValue = "0") int epoch,
            @RequestParam(value = "spawn", defaultValue = "0") int spawn,
            @RequestParam(value = "itemIndex", required = false) Integer itemIndex,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestHeader("X-User-ID") String tenantId) {

        logger.debug("Internal byte upload: fileName={}, size={}, workflow={}, tenant={}, epoch={}, spawn={}, sourceType={}",
            fileName, content.length, workflowId, tenantId, epoch, spawn, sourceType);

        FileRef fileRef = fileStorageService.upload(
            tenantId, workflowId, runId, stepAlias, fileName, mimeType, content,
            epoch, spawn, itemIndex, sourceType);

        return ResponseEntity.ok(fileRef);
    }

    /**
     * Generic upload without workflow context.
     */
    @PostMapping(value = "/generic-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> genericUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", defaultValue = "general") String category,
            @RequestHeader("X-User-ID") String tenantId) {

        logger.debug("Internal generic upload: name={}, category={}, tenant={}",
            file.getOriginalFilename(), category, tenantId);

        try {
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : FileConstants.DEFAULT_FILENAME;
            String mimeType = file.getContentType() != null ? file.getContentType() : FileConstants.DEFAULT_MIME_TYPE;

            // Don't trust MultipartFile.getSize() - for in-memory ByteArrayResource
            // multipart uploads (storage-client.genericUpload), Spring's multipart
            // resolver can return a stale or truncated value depending on the
            // resolver implementation. Read the actual byte buffer once: it's the
            // authoritative length AND lets the S3 PutObject request set the
            // correct contentLength header without a re-stream.
            byte[] bytes = file.getBytes();
            logger.info("genericUpload received: name={} multipart.getSize={} bytes.length={}",
                fileName, file.getSize(), bytes.length);
            FileRef fileRef = fileStorageService.uploadGeneric(
                tenantId, category, fileName, mimeType,
                new java.io.ByteArrayInputStream(bytes), bytes.length);

            return ResponseEntity.ok(fileRef);
        } catch (IOException e) {
            logger.error("Internal generic upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    /**
     * Download file content by key - streamed end-to-end via
     * {@link StreamingResponseBody} (replaces the legacy {@code byte[]} path
     * after the 2026-05-04 OOM). Used by orchestrator-service (storage-client)
     * for inter-service file fetches; the storage-client still consumes the
     * response as bytes via {@code RestTemplate.exchange(..., byte[].class)},
     * but the wire is now streamed → server-side heap stays flat.
     *
     * <p>Preserves the upstream object's content-type when known so
     * orchestrator callers that route by MIME (e.g. ExtractFromFileNode) keep
     * working. Falls back to {@code application/octet-stream} when missing.
     */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> download(
            @RequestParam String key,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId) {

        // Defense-in-depth: keys are namespaced by tenantId prefix at upload.
        // Refuse cross-tenant download even on the internal route.
        // Audit 2026-05-16 round-2.
        if (!isKeyOwnedByTenant(key, tenantId)) {
            logger.warn("Internal download refused: key='{}' not owned by tenantId='{}'", key, tenantId);
            return ResponseEntity.status(403).build();
        }
        return fileStorageService.openStream(key)
            .map(ds -> {
                String contentType = ds.contentType() != null && !ds.contentType().isBlank()
                    ? ds.contentType()
                    : "application/octet-stream";
                final long advertisedLength = ds.contentLength();

                StreamingResponseBody body = out -> {
                    try (StorageStreamingMetrics.StreamSpan span = streamingMetrics.startStream();
                         DownloadStream s = ds) {
                        try {
                            s.stream().transferTo(out);
                            if (advertisedLength > 0) {
                                streamingMetrics.recordBytes(advertisedLength);
                            }
                        } catch (IOException e) {
                            streamingMetrics.recordClientDisconnect();
                            logger.debug("Client disconnected mid-stream: key={} ({})",
                                key, e.getMessage());
                            throw e;
                        } catch (RuntimeException e) {
                            streamingMetrics.recordStreamError();
                            throw e;
                        }
                    }
                };

                ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, contentType);
                // Forward Content-Length for non-negative values (incl. 0-byte
                // objects) so chunked encoding is only used when we genuinely
                // don't know the size (S3 multipart edge case → -1L).
                if (advertisedLength >= 0) {
                    builder.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(advertisedLength));
                }
                return builder.body(body);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Generate a presigned download URL.
     */
    @PostMapping("/presign")
    public ResponseEntity<Map<String, String>> presign(
            @RequestParam String key,
            @RequestParam(defaultValue = "15") int expiryMinutes,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId) {

        if (!isKeyOwnedByTenant(key, tenantId)) {
            logger.warn("Internal presign refused: key='{}' not owned by tenantId='{}'", key, tenantId);
            return ResponseEntity.status(403).build();
        }
        Duration expiry = Duration.ofMinutes(Math.min(expiryMinutes, 60));
        String url = fileStorageService.generateDownloadUrl(key, expiry);

        return ResponseEntity.ok(Map.of(
            "url", url,
            "expiresIn", String.valueOf(expiry.toSeconds())
        ));
    }

    /**
     * Delete a file by key.
     */
    @DeleteMapping("/delete")
    public ResponseEntity<Map<String, Object>> delete(
            @RequestParam String key,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId) {

        if (!isKeyOwnedByTenant(key, tenantId)) {
            logger.warn("Internal delete refused: key='{}' not owned by tenantId='{}'", key, tenantId);
            return ResponseEntity.status(403).build();
        }
        boolean deleted = fileStorageService.delete(key);
        return ResponseEntity.ok(Map.of("deleted", deleted));
    }

    /**
     * Delete all files for a specific workflow run.
     */
    @DeleteMapping("/delete-run-files")
    public ResponseEntity<Map<String, Object>> deleteRunFiles(
            @RequestParam String workflowId,
            @RequestParam String runId,
            @RequestHeader("X-User-ID") String tenantId) {

        int count = fileStorageService.deleteRunFiles(tenantId, workflowId, runId);
        return ResponseEntity.ok(Map.of("deletedCount", count));
    }

    /**
     * Check if a file exists.
     */
    @GetMapping("/exists")
    public ResponseEntity<Map<String, Object>> exists(
            @RequestParam String key,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId) {

        if (!isKeyOwnedByTenant(key, tenantId)) {
            // Don't leak existence cross-tenant - return false (matches public-route contract).
            return ResponseEntity.ok(Map.of("exists", false));
        }
        boolean fileExists = fileStorageService.exists(key);
        return ResponseEntity.ok(Map.of("exists", fileExists));
    }

    /**
     * Verify that a storage key is owned by the given tenant via prefix.
     * Keys are namespaced {@code <tenantId>/...} at upload time; cross-tenant
     * download/delete is a leak vector. Audit 2026-05-16 round-2.
     */
    private static boolean isKeyOwnedByTenant(String key, String tenantId) {
        if (key == null || tenantId == null || tenantId.isBlank()) return false;
        return key.startsWith(tenantId + "/");
    }
}
