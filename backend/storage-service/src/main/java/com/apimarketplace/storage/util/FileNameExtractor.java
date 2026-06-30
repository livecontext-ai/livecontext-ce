package com.apimarketplace.storage.util;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Utility for extracting filenames from various sources.
 */
public final class FileNameExtractor {

    private FileNameExtractor() {}

    public static String fromUrl(String url) {
        if (url == null || url.isBlank()) {
            return FileConstants.DEFAULT_FILENAME;
        }
        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isBlank() || path.equals("/")) {
                return FileConstants.DEFAULT_FILENAME;
            }
            int lastSlash = path.lastIndexOf('/');
            String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
            int queryStart = filename.indexOf('?');
            if (queryStart > 0) {
                filename = filename.substring(0, queryStart);
            }
            filename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
            return filename.isBlank() ? FileConstants.DEFAULT_FILENAME : filename;
        } catch (Exception e) {
            return FileConstants.DEFAULT_FILENAME;
        }
    }

    public static String fromStoragePath(String path) {
        if (path == null || path.isBlank()) {
            return FileConstants.DEFAULT_FILENAME;
        }
        int lastSlash = path.lastIndexOf('/');
        String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        int underscore = filename.indexOf('_');
        if (underscore > 0 && underscore <= 36) {
            String prefix = filename.substring(0, underscore);
            if (isUuidLike(prefix)) {
                filename = filename.substring(underscore + 1);
            }
        }
        return filename.isBlank() ? FileConstants.DEFAULT_FILENAME : filename;
    }

    public static String fromContentDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isBlank()) {
            return null;
        }
        int starIndex = contentDisposition.indexOf("filename*=");
        if (starIndex >= 0) {
            String value = contentDisposition.substring(starIndex + 10);
            int semicolon = value.indexOf(';');
            if (semicolon > 0) {
                value = value.substring(0, semicolon);
            }
            int quoteMark = value.indexOf('\'');
            if (quoteMark >= 0) {
                int secondQuote = value.indexOf('\'', quoteMark + 1);
                if (secondQuote >= 0) {
                    String encoded = value.substring(secondQuote + 1).trim();
                    try {
                        return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        // Fall through
                    }
                }
            }
        }
        int index = contentDisposition.indexOf("filename=");
        if (index >= 0) {
            String value = contentDisposition.substring(index + 9);
            int semicolon = value.indexOf(';');
            if (semicolon > 0) {
                value = value.substring(0, semicolon);
            }
            value = value.trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            return value.isBlank() ? null : value;
        }
        return null;
    }

    private static boolean isUuidLike(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        if (s.matches("[a-fA-F0-9-]{8,36}")) {
            return true;
        }
        if (s.matches("[a-zA-Z0-9]{6,12}") && s.matches(".*\\d.*")) {
            return true;
        }
        return false;
    }
}
