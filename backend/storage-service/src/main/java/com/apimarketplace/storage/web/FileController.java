package com.apimarketplace.storage.web;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.ContentDispositions;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.storage.domain.FileRef;
import com.apimarketplace.storage.service.file.DownloadStream;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.service.file.StorageStreamingMetrics;
import com.apimarketplace.common.storage.signing.ShowcaseUrlSigner;
import com.apimarketplace.storage.util.FileConstants;
import com.apimarketplace.storage.util.FileNameExtractor;
import com.apimarketplace.storage.util.MimeTypeRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Public REST controller for file operations.
 * Handles uploads (workflow + generic), downloads, presigned URLs, and deletion.
 * Disabled in monolith mode (orchestrator FileDownloadController handles local files).
 */
@RestController
@RequestMapping("/api/files")
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    private final FileStorageService fileStorageService;
    private final MimeTypeRegistry mimeTypeRegistry;
    private final TenantResolver tenantResolver;
    private final StorageStreamingMetrics streamingMetrics;
    private final ShowcaseUrlSigner showcaseUrlSigner;
    private final com.apimarketplace.common.storage.url.PublicFileUrlBuilder publicFileUrlBuilder;
    private final Counter signedOkCounter;
    private final Counter signedInvalidSigCounter;
    private final Counter signedExpiredCounter;

    /**
     * Org index over {@code storage.storage}. Lets the read endpoints authorise any
     * member of a file's workspace (org-scoped serve), not just the uploader who
     * namespaced the key with their own user id. Optional: absent in slim unit tests
     * that construct the controller directly; when null the read endpoints fall back to
     * the strict uploader-prefix check.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.apimarketplace.common.storage.service.StorageService storageIndex;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    OrgAccessGuard orgAccessGuard;

    public FileController(FileStorageService fileStorageService,
                          MimeTypeRegistry mimeTypeRegistry,
                          TenantResolver tenantResolver,
                          StorageStreamingMetrics streamingMetrics,
                          ShowcaseUrlSigner showcaseUrlSigner,
                          com.apimarketplace.common.storage.url.PublicFileUrlBuilder publicFileUrlBuilder,
                          MeterRegistry meterRegistry) {
        this.fileStorageService = fileStorageService;
        this.mimeTypeRegistry = mimeTypeRegistry;
        this.tenantResolver = tenantResolver;
        this.streamingMetrics = streamingMetrics;
        this.showcaseUrlSigner = showcaseUrlSigner;
        this.publicFileUrlBuilder = publicFileUrlBuilder;
        this.signedOkCounter = Counter.builder("storage_signed_download_total")
                .description("Showcase signed-URL downloads, by outcome")
                .tag("status", "ok")
                .register(meterRegistry);
        this.signedInvalidSigCounter = Counter.builder("storage_signed_download_total")
                .description("Showcase signed-URL downloads, by outcome")
                .tag("status", "invalid_sig")
                .register(meterRegistry);
        this.signedExpiredCounter = Counter.builder("storage_signed_download_total")
                .description("Showcase signed-URL downloads, by outcome")
                .tag("status", "expired")
                .register(meterRegistry);
    }

    /**
     * Upload a file with workflow context (workflowId, runId, stepAlias).
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam("workflowId") String workflowId,
            @RequestParam("runId") String runId,
            @RequestParam(value = "stepAlias", defaultValue = "upload") String stepAlias,
            HttpServletRequest request) {

        String tenantId = tenantResolver.resolve(request);
        logger.info("File upload: name={}, size={}, workflow={}, run={}, tenant={}",
            file.getOriginalFilename(), file.getSize(), workflowId, runId, tenantId);

        if (file.getSize() > FileConstants.MAX_FILE_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "File too large. Maximum size: " + FileConstants.MAX_FILE_SIZE_MB + " MB"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        try {
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : FileConstants.DEFAULT_FILENAME;
            String mimeType = file.getContentType() != null ? file.getContentType() : FileConstants.DEFAULT_MIME_TYPE;
            var inputStream = file.getInputStream();

            FileRef fileRef = withRequestOrgScope(request, () -> fileStorageService.upload(
                tenantId, workflowId, runId, stepAlias,
                fileName, mimeType, inputStream, file.getSize()));

            return ResponseEntity.ok(fileRef);
        } catch (IOException e) {
            logger.error("Failed to upload file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Upload failed: " + e.getMessage()));
        } catch (QuotaExceededException e) {
            logger.warn("File upload refused by storage quota: tenant={}, error={}", tenantId, e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Storage quota exceeded"));
        }
    }

    /**
     * Generic upload without workflow context.
     * Used by frontend for DataTable files, avatars, chat attachments, etc.
     */
    @PostMapping(value = "/generic-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> genericUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", defaultValue = "general") String category,
            HttpServletRequest request) {

        String tenantId = tenantResolver.resolve(request);
        logger.info("Generic upload: name={}, size={}, category={}, tenant={}",
            file.getOriginalFilename(), file.getSize(), category, tenantId);

        if (file.getSize() > FileConstants.MAX_FILE_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "File too large. Maximum size: " + FileConstants.MAX_FILE_SIZE_MB + " MB"));
        }

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        try {
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : FileConstants.DEFAULT_FILENAME;
            String mimeType = file.getContentType() != null ? file.getContentType() : FileConstants.DEFAULT_MIME_TYPE;
            var inputStream = file.getInputStream();

            // V313 folder-aware upload: place the file in the caller's current manual
            // folder when a parentFolderId form field is supplied (the indexer validates
            // it's a folder in the workspace and drops to root otherwise).
            final UUID parentFolderId = parseFolderId(request.getParameter("parentFolderId"));
            FileRef fileRef = withRequestOrgScope(request, () -> parentFolderId == null
                ? fileStorageService.uploadGeneric(tenantId, category, fileName, mimeType, inputStream, file.getSize())
                : fileStorageService.uploadGeneric(tenantId, category, fileName, mimeType, inputStream, file.getSize(), parentFolderId));

            String storageId = fileRef.id();
            if (storageId == null) {
                // uploadGeneric indexes the row server-side; a null id means indexing failed, so the
                // file can't be served opaquely (the only path post-cutover). Fail loud, don't return
                // a key-leaking fallback.
                logger.error("Generic upload produced no storage id (name={}, tenant={}, category={})",
                        fileName, tenantId, category);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Upload succeeded but the file could not be indexed"));
            }

            return ResponseEntity.ok(Map.of(
                "url", publicFileUrlBuilder.fileUrl(storageId, true),
                "id", storageId,
                "storageKey", fileRef.path(),
                "fileName", fileRef.name(),
                "mimeType", fileRef.mimeType(),
                "size", fileRef.size()
            ));
        } catch (IOException e) {
            logger.error("Failed to upload file: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "Upload failed: " + e.getMessage()));
        } catch (QuotaExceededException e) {
            logger.warn("Generic upload refused by storage quota: tenant={}, category={}, error={}",
                    tenantId, category, e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "Storage quota exceeded"));
        }
    }

    /** Parse the optional {@code parentFolderId} upload form field; null/blank/malformed → null (root).
     *  A non-folder/cross-org id is dropped to root by the indexer, not here. */
    private static UUID parseFolderId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private FileRef withRequestOrgScope(HttpServletRequest request, Supplier<FileRef> action) {
        AtomicReference<FileRef> result = new AtomicReference<>();
        TenantResolver.runWithOrgScope(
                tenantResolver.resolveOrgId(request),
                tenantResolver.resolveOrgRole(request),
                () -> result.set(action.get()));
        return result.get();
    }

    /**
     * Opaque, org-scoped file serve by storage-row UUID - the canonical user-facing endpoint.
     *
     * <p>Resolves the {@code storage.storage} row for {@code id} within the caller's active org
     * ({@code getEntityByIdForScope}), or - for the caller's OWN files - by an owner fast-path
     * ({@code getEntityById(id, tenantId)}, so a browser {@code <img>} that can't send the active-org
     * header still serves the owner's file); 404 (never 403) on miss, then streams the bytes. The raw
     * S3 key (which is namespaced by the owner's user id) is read server-side and NEVER appears in
     * the URL or the response - that is the whole point: this URL leaks no tenant id, unlike the
     * removed legacy {@code /proxy?key=} path. Streaming reuses {@link #streamingBody}; inline
     * BINARY rows (no object-storage key) return their buffered bytes.
     */
    @GetMapping("/by-id/{id}/raw")
    public ResponseEntity<StreamingResponseBody> rawById(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "inline") String disposition,
            HttpServletRequest request) {

        if (storageIndex == null) {
            return ResponseEntity.notFound().build();
        }
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);

        // Org-scoped first (any member of the file's workspace can view it). Then the OWNER
        // fast-path: the uploader can serve their OWN file regardless of the active workspace - a
        // browser <img>/<a>/new-tab request cannot carry X-Active-Organization-ID, so the gateway
        // resolves the caller's DEFAULT org, which may differ from the file's org. Without this,
        // a user's own file 404s whenever a non-default workspace is active. Mirrors canServeKey.
        StorageEntity entity = null;
        if (orgId != null && !orgId.isBlank()) {
            entity = storageIndex.getEntityByIdForScope(id, tenantId, orgId).orElse(null);
        }
        if (entity == null) {
            entity = storageIndex.getEntityById(id, tenantId).orElse(null);
        }
        if (entity == null || entity.getFileName() == null) {
            // 404 (never 403) - don't leak existence; also reject non-file rows (step-output JSON
            // blobs have no file name), matching the files-tool "real files only" model.
            return ResponseEntity.notFound().build();
        }
        if (!canAccessFile(request, entity, id)) {
            return ResponseEntity.notFound().build();
        }

        String fileName = entity.getFileName() != null ? entity.getFileName() : "file";
        String mimeType = entity.getMimeType() != null ? entity.getMimeType()
                : (entity.getContentType() != null ? entity.getContentType() : "application/octet-stream");
        String contentDisposition = ContentDispositions.of(
                "attachment".equalsIgnoreCase(disposition) ? "attachment" : "inline", fileName);

        if (entity.getS3Key() != null) {
            return fileStorageService.openStream(entity.getS3Key())
                .map(ds -> {
                    final long len = ds.contentLength();
                    ResponseEntity.BodyBuilder b = ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                        .header(HttpHeaders.CONTENT_TYPE, mimeType)
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300");
                    if (len >= 0) {
                        b.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(len));
                    }
                    return b.body(streamingBody(ds));
                })
                .orElse(ResponseEntity.notFound().build());
        }

        // Inline content stored in the row (no object-storage key) - BINARY bytes, or TEXT/JSON
        // serialized as UTF-8. Small payloads; return buffered so a list/get `url` resolves for
        // every file type, not just object-storage ones.
        byte[] data = entity.getDataBinary();
        if (data == null) {
            String textual = entity.getDataText();
            if (textual == null && entity.getData() != null) {
                textual = String.valueOf(entity.getData());
            }
            if (textual != null) {
                data = textual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        final byte[] copy = data;
        StreamingResponseBody body = out -> out.write(copy);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
            .header(HttpHeaders.CONTENT_TYPE, mimeType)
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(copy.length))
            .body(body);
    }

    /**
     * Streaming body for an S3 object: streams end-to-end, closes the {@link DownloadStream}
     * (returns the connection to the pool) on success/disconnect, and records metrics. Shared by
     * {@link #rawById} and the HMAC-signed showcase endpoint.
     */
    private StreamingResponseBody streamingBody(DownloadStream ds) {
        final long advertisedLength = ds.contentLength();
        return out -> {
            try (StorageStreamingMetrics.StreamSpan span = streamingMetrics.startStream();
                 DownloadStream s = ds) {
                try {
                    s.stream().transferTo(out);
                    if (advertisedLength > 0) {
                        streamingMetrics.recordBytes(advertisedLength);
                    }
                } catch (IOException e) {
                    streamingMetrics.recordClientDisconnect();
                    logger.debug("Client disconnected mid-stream (by-id): {}", e.getMessage());
                    throw e;
                } catch (RuntimeException e) {
                    streamingMetrics.recordStreamError();
                    throw e;
                }
            }
        };
    }

    private boolean canAccessFile(HttpServletRequest request, StorageEntity entity, UUID id) {
        if (orgAccessGuard == null) {
            return true;
        }
        String orgId = entity.getOrganizationId();
        if (orgId == null || orgId.isBlank()) {
            orgId = tenantResolver.resolveOrgId(request);
        }
        if (orgId == null || orgId.isBlank()) {
            return true;
        }
        String requestOrgId = tenantResolver.resolveOrgId(request);
        String orgRole = orgId.equals(requestOrgId) ? tenantResolver.resolveOrgRole(request) : null;
        return orgAccessGuard.canAccess(orgId, tenantResolver.resolve(request), "file", id.toString(), orgRole);
    }

    /**
     * Public file download via HMAC-signed URL.
     *
     * <p>Anonymous-friendly counterpart to {@link #rawById}: the signature
     * IS the authorisation, so no JWT, no {@code X-User-ID} header, no tenant
     * check. Used by the marketplace showcase render to expose images embedded
     * in a published interface to anonymous visitors without the auth gate
     * the org-scoped {@code /by-id} endpoint enforces.
     *
     * <p><strong>Accepted residual leak (documented exception):</strong> this path signs and serves
     * by the raw s3 {@code key}, which is namespaced {@code {tenantId}/...} - so the publisher's
     * numeric tenant id IS visible in the signed URL to anonymous visitors. The opaque-URL cutover
     * removed this leak everywhere else; it is knowingly kept here because the showcase is rendered
     * WITHOUT a session token (anonymous), so the org-scoped {@code /by-id} endpoint cannot be used.
     * An anonymous by-id + HMAC variant would close it but is out of scope.
     *
     * <p><strong>Validation order matters</strong>: signature first (refuses to
     * disclose whether the key exists for an unsigned/forged request), then
     * expiry, then existence. {@code Cache-Control: private, max-age=900}
     * mirrors the 15-min URL TTL - public CDN caching would survive past the
     * expiry and defeat the gate.
     */
    @GetMapping("/proxy-signed")
    public ResponseEntity<StreamingResponseBody> proxySignedDownload(
            @RequestParam String key,
            @RequestParam long exp,
            @RequestParam(defaultValue = "inline") String disposition,
            @RequestParam String sig) {

        long now = java.time.Instant.now().getEpochSecond();
        boolean validSig = showcaseUrlSigner.verify(key, exp, disposition, sig, now);
        if (!validSig) {
            // Distinguish only for metrics - the response body is identical
            // (don't leak whether the failure was sig-mismatch vs expiry).
            if (exp <= now) {
                signedExpiredCounter.increment();
            } else {
                signedInvalidSigCounter.increment();
            }
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return fileStorageService.openStream(key)
            .map(ds -> {
                String fileName = FileNameExtractor.fromStoragePath(key);
                String mimeType = mimeTypeRegistry.resolve(fileName);
                if (fileName != null && !fileName.contains(".") && mimeType != null) {
                    String ext = mimeTypeToExtension(mimeType);
                    if (ext != null) fileName = fileName + ext;
                }
                String contentDisposition = ContentDispositions.of(
                        "attachment".equalsIgnoreCase(disposition) ? "attachment" : "inline", fileName);

                final long advertisedLength = ds.contentLength();
                StreamingResponseBody body = out -> {
                    try (StorageStreamingMetrics.StreamSpan span = streamingMetrics.startStream();
                         DownloadStream s = ds) {
                        try {
                            s.stream().transferTo(out);
                            if (advertisedLength > 0) streamingMetrics.recordBytes(advertisedLength);
                        } catch (IOException e) {
                            streamingMetrics.recordClientDisconnect();
                            logger.debug("Signed proxy: client disconnected mid-stream key={} ({})",
                                    key, e.getMessage());
                            throw e;
                        } catch (RuntimeException e) {
                            streamingMetrics.recordStreamError();
                            throw e;
                        }
                    }
                };

                signedOkCounter.increment();
                ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                        .header(HttpHeaders.CONTENT_TYPE, mimeType)
                        // PRIVATE: don't let CDN caches (Cloudflare, Caddy) hold
                        // bytes past the URL's expiry - a cached entry would
                        // bypass our signature/expiry gate.
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=900");
                if (advertisedLength >= 0) {
                    builder.header(HttpHeaders.CONTENT_LENGTH, String.valueOf(advertisedLength));
                }
                return builder.body(body);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Delete a file. Only allowed for files owned by the tenant.
     */
    @DeleteMapping
    public ResponseEntity<Void> deleteFile(
            @RequestParam String key,
            HttpServletRequest request) {

        String tenantId = tenantResolver.resolve(request);

        if (!key.startsWith(tenantId + "/")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        boolean deleted = fileStorageService.delete(key);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Map common MIME types to file extensions.
     * Used when the original filename has no extension (e.g., "600" from picsum.photos).
     */
    private String mimeTypeToExtension(String mimeType) {
        if (mimeType == null) return null;
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg" -> ".jpeg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "application/pdf" -> ".pdf";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "text/html" -> ".html";
            case "application/json" -> ".json";
            case "application/xml", "text/xml" -> ".xml";
            case "application/zip" -> ".zip";
            case "application/gzip" -> ".gz";
            case "audio/mpeg" -> ".mp3";
            case "video/mp4" -> ".mp4";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> ".xlsx";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
            default -> null;
        };
    }
}
