package com.apimarketplace.orchestrator.controllers.file;

import com.apimarketplace.common.web.ContentDispositions;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLConnection;
import java.util.Optional;

/**
 * Controller for serving files directly from local filesystem in CE mode.
 * Only active when storage.type=local (LocalFileStorageService).
 */
@RestController
@ConditionalOnProperty(name = "storage.type", havingValue = "local")
public class FileDownloadController {

    private static final Logger logger = LoggerFactory.getLogger(FileDownloadController.class);

    private final FileStorageService fileStorageService;

    public FileDownloadController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/api/files/download")
    public ResponseEntity<Resource> download(
            @RequestParam String key,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        // Validate key: prevent path traversal
        if (key == null || key.contains("..") || key.startsWith("/") || key.startsWith("\\")) {
            return ResponseEntity.badRequest().build();
        }

        // Audit 2026-05-16 round-3: gate by X-User-ID and key prefix.
        // Local-mode keys are `{tenantId}/{workflowId}/{runId}/{stepAlias}/...`
        // (LocalFileStorageService.buildKey). Strict-tenant scope is correct
        // here - public file sharing in CE-local has no separate ACL surface.
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        int slash = key.indexOf('/');
        String keyTenant = slash > 0 ? key.substring(0, slash) : null;
        if (keyTenant == null || !keyTenant.equals(userIdHeader)) {
            logger.warn("[SCOPE] Cross-tenant download blocked: caller={} keyTenant={} key={}",
                    userIdHeader, keyTenant, key);
            return ResponseEntity.notFound().build();
        }

        Optional<byte[]> content = fileStorageService.download(key);
        if (content.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        byte[] data = content.get();
        String filename = extractFilename(key);
        String mimeType = URLConnection.guessContentTypeFromName(filename);
        if (mimeType == null) {
            mimeType = "application/octet-stream";
        }

        logger.debug("Serving file: key={}, size={}, type={}", key, data.length, mimeType);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(mimeType))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.inline(filename))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    private String extractFilename(String key) {
        int lastSlash = key.lastIndexOf('/');
        return lastSlash >= 0 ? key.substring(lastSlash + 1) : key;
    }
}
