package com.apimarketplace.orchestrator.utils.file;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extensible MIME type registry following Open/Closed Principle.
 * - Open for extension via register()
 * - Closed for modification
 *
 * Replaces duplicate guessMimeType() implementations in:
 * - DownloadFileNode.java
 * - FileToolsProvider.java
 * - FileController.java
 */
@Component
public class MimeTypeRegistry {

    private final Map<String, String> extensionToMime = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        // Images
        register(".png", "image/png");
        register(".jpg", "image/jpeg");
        register(".jpeg", "image/jpeg");
        register(".gif", "image/gif");
        register(".webp", "image/webp");
        register(".svg", "image/svg+xml");
        register(".ico", "image/x-icon");
        register(".bmp", "image/bmp");
        register(".tiff", "image/tiff");
        register(".tif", "image/tiff");

        // Documents
        register(".pdf", "application/pdf");
        register(".doc", "application/msword");
        register(".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        register(".xls", "application/vnd.ms-excel");
        register(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        register(".ppt", "application/vnd.ms-powerpoint");
        register(".pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");

        // Text
        register(".txt", "text/plain");
        register(".csv", "text/csv");
        register(".json", "application/json");
        register(".xml", "application/xml");
        register(".html", "text/html");
        register(".htm", "text/html");
        register(".css", "text/css");
        register(".js", "application/javascript");
        register(".md", "text/markdown");
        register(".yaml", "application/yaml");
        register(".yml", "application/yaml");

        // Media - Video
        register(".mp4", "video/mp4");
        register(".webm", "video/webm");
        register(".avi", "video/x-msvideo");
        register(".mov", "video/quicktime");
        register(".mkv", "video/x-matroska");

        // Media - Audio
        register(".mp3", "audio/mpeg");
        register(".wav", "audio/wav");
        register(".ogg", "audio/ogg");
        register(".flac", "audio/flac");
        register(".aac", "audio/aac");

        // Archives
        register(".zip", "application/zip");
        register(".tar", "application/x-tar");
        register(".gz", "application/gzip");
        register(".rar", "application/vnd.rar");
        register(".7z", "application/x-7z-compressed");
    }

    /**
     * Register a custom MIME type mapping.
     * Thread-safe, can be called at runtime to extend the registry.
     *
     * @param extension File extension with leading dot (e.g., ".xyz")
     * @param mimeType MIME type (e.g., "application/xyz")
     */
    public void register(String extension, String mimeType) {
        String normalized = extension.startsWith(".") ? extension : "." + extension;
        extensionToMime.put(normalized.toLowerCase(), mimeType);
    }

    /**
     * Resolve MIME type for a filename.
     * Resolution order:
     * 1. Known extension in registry
     * 2. JDK URLConnection.guessContentTypeFromName
     * 3. Default octet-stream
     *
     * @param filename Filename with extension
     * @return MIME type, never null
     */
    public String resolve(String filename) {
        if (filename == null || filename.isBlank()) {
            return FileConstants.DEFAULT_MIME_TYPE;
        }

        String lower = filename.toLowerCase();
        int dotIndex = lower.lastIndexOf('.');
        if (dotIndex > 0) {
            String ext = lower.substring(dotIndex);
            String known = extensionToMime.get(ext);
            if (known != null) {
                return known;
            }
        }

        // Fallback to JDK detection
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return guessed != null ? guessed : FileConstants.DEFAULT_MIME_TYPE;
    }

    /**
     * Resolve MIME type with content analysis fallback.
     * Tries filename first, then content magic bytes.
     *
     * @param filename Filename with extension
     * @param content File content bytes
     * @return MIME type, never null
     */
    public String resolve(String filename, byte[] content) {
        // Try filename first
        String fromName = resolve(filename);
        if (!FileConstants.DEFAULT_MIME_TYPE.equals(fromName)) {
            return fromName;
        }

        // Try content analysis
        if (content != null && content.length > 0) {
            try {
                String fromContent = URLConnection.guessContentTypeFromStream(
                    new ByteArrayInputStream(content)
                );
                if (fromContent != null) {
                    return fromContent;
                }
            } catch (IOException e) {
                // Ignore and return default
            }
        }

        return FileConstants.DEFAULT_MIME_TYPE;
    }

}
