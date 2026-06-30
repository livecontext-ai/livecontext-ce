package com.apimarketplace.storage.util;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Extensible MIME type registry following Open/Closed Principle.
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

        // Video
        register(".mp4", "video/mp4");
        register(".webm", "video/webm");
        register(".avi", "video/x-msvideo");
        register(".mov", "video/quicktime");
        register(".mkv", "video/x-matroska");
        register(".m4v", "video/x-m4v");
        register(".ogv", "video/ogg");

        // Audio
        register(".mp3", "audio/mpeg");
        register(".wav", "audio/wav");
        register(".ogg", "audio/ogg");
        register(".flac", "audio/flac");
        register(".aac", "audio/aac");
        register(".m4a", "audio/mp4");
        register(".opus", "audio/opus");
        register(".oga", "audio/ogg");
        register(".weba", "audio/webm");
        register(".pcm", "audio/pcm");
        register(".ulaw", "audio/basic");
        register(".mulaw", "audio/basic");
        register(".alaw", "audio/x-alaw");

        // 3D models
        register(".glb", "model/gltf-binary");
        register(".gltf", "model/gltf+json");
        register(".obj", "model/obj");
        register(".stl", "model/stl");

        // Modern image formats
        register(".avif", "image/avif");
        register(".heic", "image/heic");
        register(".heif", "image/heif");

        // Archives
        register(".zip", "application/zip");
        register(".tar", "application/x-tar");
        register(".gz", "application/gzip");
        register(".rar", "application/vnd.rar");
        register(".7z", "application/x-7z-compressed");
    }

    public void register(String extension, String mimeType) {
        String normalized = extension.startsWith(".") ? extension : "." + extension;
        extensionToMime.put(normalized.toLowerCase(), mimeType);
    }

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
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return guessed != null ? guessed : FileConstants.DEFAULT_MIME_TYPE;
    }

    public String resolve(String filename, byte[] content) {
        String fromName = resolve(filename);
        if (!FileConstants.DEFAULT_MIME_TYPE.equals(fromName)) {
            return fromName;
        }
        if (content != null && content.length > 0) {
            try {
                String fromContent = URLConnection.guessContentTypeFromStream(
                    new ByteArrayInputStream(content)
                );
                if (fromContent != null) {
                    return fromContent;
                }
            } catch (IOException e) {
                // Ignore
            }
        }
        return FileConstants.DEFAULT_MIME_TYPE;
    }

    public boolean isImage(String mimeType) {
        return mimeType != null && mimeType.startsWith("image/");
    }

    public boolean isPreviewable(String mimeType) {
        if (mimeType == null) return false;
        return mimeType.startsWith("image/")
            || mimeType.startsWith("video/")
            || mimeType.startsWith("audio/")
            || mimeType.equals("application/pdf")
            || mimeType.startsWith("text/");
    }
}
