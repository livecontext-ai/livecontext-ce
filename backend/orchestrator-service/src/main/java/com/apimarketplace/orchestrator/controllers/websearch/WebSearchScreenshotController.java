package com.apimarketplace.orchestrator.controllers.websearch;

import com.apimarketplace.orchestrator.services.file.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Serves screenshot images stored in MinIO.
 * Frontend loads screenshots via /api/proxy/websearch/screenshots/{key}.
 */
@Slf4j
@RestController
@RequestMapping("/api/websearch/screenshots")
@ConditionalOnProperty(name = "websearch.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class WebSearchScreenshotController {

    private static final MediaType IMAGE_WEBP = MediaType.parseMediaType("image/webp");

    private final FileStorageService fileStorageService;

    /**
     * GET /api/websearch/screenshots/** - serve a screenshot from MinIO.
     * The key is the full path after /api/websearch/screenshots/ (e.g. screenshots/abc123_def456.webp).
     */
    @GetMapping("/**")
    public ResponseEntity<byte[]> getScreenshot(HttpServletRequest request) {
        // Extract the key from the request path (everything after /api/websearch/screenshots/)
        String fullPath = request.getRequestURI();
        String prefix = "/api/websearch/screenshots/";
        int idx = fullPath.indexOf(prefix);
        if (idx < 0) {
            return ResponseEntity.badRequest().build();
        }
        String key = fullPath.substring(idx + prefix.length());

        // Security: validate key prefix and prevent path traversal
        if (!key.startsWith("screenshots/") || key.contains("..")) {
            log.warn("Invalid screenshot key requested: {}", key);
            return ResponseEntity.badRequest().build();
        }

        Optional<byte[]> content = fileStorageService.download(key);
        if (content.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Detect content type from key extension
        MediaType contentType = key.endsWith(".webp") ? IMAGE_WEBP
                : key.endsWith(".jpg") || key.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                : MediaType.APPLICATION_OCTET_STREAM;

        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
                .body(content.get());
    }
}
