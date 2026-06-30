package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/**
 * Adapter for binary format data
 */
@Component
public class BinaryAdapter implements SourceAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(BinaryAdapter.class);
    
    @Override
    public boolean isCollection(SourceSpec sourceSpec, byte[] input) {
        // Binary data is typically not a collection
        return false;
    }
    
    @Override
    public Iterable<?> iterateItems(SourceSpec sourceSpec, byte[] input) {
        // Binary data doesn't have iterable items
        return Collections.emptyList();
    }
    
    @Override
    public Object evalScalar(Object context, String candidate) {
        try {
            if (context instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) context;
                return metadata.get(candidate);
            }
            return null;
        } catch (Exception e) {
            logger.debug("Error evaluating binary scalar: {}", e.getMessage());
            return null;
        }
    }
    
    @Override
    public Iterable<?> evalNodes(Object context, String pathAnyOf) {
        // Binary data doesn't have nodes
        return Collections.emptyList();
    }
    
    @Override
    public Map<String, Object> flatten(byte[] input) {
        Map<String, Object> flattened = new HashMap<>();
        try {
            // Extract metadata from binary data
            extractMetadata(input, flattened);
        } catch (Exception e) {
            logger.debug("Error flattening binary: {}", e.getMessage());
        }
        return flattened;
    }
    
    @Override
    public Object getRoot(byte[] input) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            extractMetadata(input, metadata);
            return metadata;
        } catch (Exception e) {
            logger.debug("Error getting binary root: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extract metadata from binary data
     */
    private void extractMetadata(byte[] input, Map<String, Object> metadata) {
        // File size
        metadata.put("size", input.length);
        
        // MIME type detection based on magic bytes
        String mimeType = detectMimeType(input);
        metadata.put("mime_type", mimeType);
        
        // File extension based on MIME type
        String extension = getExtensionFromMimeType(mimeType);
        metadata.put("extension", extension);
        
        // Hash for identification
        String hash = calculateHash(input);
        metadata.put("hash", hash);
        
        // Check if it's text (even if detected as binary)
        if (isTextData(input)) {
            metadata.put("is_text", true);
            try {
                String content = new String(input, StandardCharsets.UTF_8);
                metadata.put("text_content", content);
                metadata.put("text_length", content.length());
            } catch (Exception e) {
                metadata.put("is_text", false);
            }
        } else {
            metadata.put("is_text", false);
        }
        
        // Magic bytes
        if (input.length >= 4) {
            byte[] magic = Arrays.copyOf(input, Math.min(4, input.length));
            metadata.put("magic_bytes", bytesToHex(magic));
        }
    }
    
    /**
     * Detect MIME type from magic bytes
     */
    private String detectMimeType(byte[] input) {
        if (input.length < 4) {
            return "application/octet-stream";
        }
        
        // Check for common file types
        if (startsWith(input, new byte[]{0x25, 0x50, 0x44, 0x46})) { // %PDF
            return "application/pdf";
        }
        if (startsWith(input, new byte[]{0x50, 0x4B, 0x03, 0x04})) { // ZIP
            return "application/zip";
        }
        if (startsWith(input, new byte[]{(byte)0x89, 0x50, 0x4E, 0x47})) { // PNG
            return "image/png";
        }
        if (startsWith(input, new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF})) { // JPEG
            return "image/jpeg";
        }
        if (startsWith(input, new byte[]{0x47, 0x49, 0x46, 0x38})) { // GIF
            return "image/gif";
        }
        if (startsWith(input, new byte[]{0x52, 0x49, 0x46, 0x46})) { // RIFF (WAV, AVI)
            return "audio/wav";
        }
        
        return "application/octet-stream";
    }
    
    /**
     * Get file extension from MIME type
     */
    private String getExtensionFromMimeType(String mimeType) {
        return switch (mimeType) {
            case "application/pdf" -> "pdf";
            case "application/zip" -> "zip";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "audio/wav" -> "wav";
            default -> "bin";
        };
    }
    
    /**
     * Calculate hash of binary data
     */
    private String calculateHash(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input);
            return bytesToHex(hash);
        } catch (Exception e) {
            logger.debug("Error calculating hash: {}", e.getMessage());
            return "unknown";
        }
    }
    
    /**
     * Check if binary data is actually text
     */
    private boolean isTextData(byte[] input) {
        try {
            String content = new String(input, StandardCharsets.UTF_8);
            // Check if it's valid UTF-8 and contains mostly printable characters
            int printableCount = 0;
            for (char c : content.toCharArray()) {
                if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) || 
                    c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' ||
                    c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' ||
                    c == '"' || c == '\'' || c == '-' || c == '_' || c == '+' || c == '=' ||
                    c == '@' || c == '#' || c == '$' || c == '%' || c == '^' || c == '&' ||
                    c == '*' || c == '/' || c == '\\' || c == '|' || c == '<' || c == '>') {
                    printableCount++;
                }
            }
            return (double) printableCount / content.length() > 0.8;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check if array starts with specific bytes
     */
    private boolean startsWith(byte[] array, byte[] prefix) {
        if (array.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (array[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Convert bytes to hex string
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }
}
