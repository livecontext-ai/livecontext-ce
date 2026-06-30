package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes a raw-binary request body for catalog API tools whose
 * {@code execution.request.bodyType=raw}.
 *
 * <p>The one body-location parameter designated by
 * {@code execution.request.rawBodyParam} supplies the bytes. Accepted source shapes:
 *
 * <ul>
 *   <li>a {@code FileRef} map ({@code _type:"file", path:…}) - bytes are downloaded
 *       from MinIO via {@link StorageClient}</li>
 *   <li>a raw {@code byte[]}</li>
 *   <li>a string - treated as literal UTF-8 bytes unless it starts with
 *       "base64:" in which case the suffix is base64-decoded</li>
 * </ul>
 *
 * <p>The Content-Type header is set from {@code execution.request.contentType}
 * (default: {@code application/octet-stream}). Callers may override this at runtime
 * by sending a {@code Content-Type} header in the request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RawBinaryBodyEncoder {

    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
    /** Warn when a FileRef-backed body exceeds this size (bytes). Current implementation
     *  buffers the full file in memory; true streaming upload is a follow-up. */
    public static final long LARGE_FILE_WARN_BYTES = 50L * 1024 * 1024; // 50 MB

    @Autowired(required = false)
    private StorageClient storageClient;

    private final ObjectMapper objectMapper;

    /**
     * Resolve the raw bytes for the request body.
     *
     * @return the bytes to send, or an empty array when the param is missing/unresolvable
     *         (the calling HttpExecutionService decides whether to fail or continue)
     */
    public byte[] encode(JsonNode requestSpec, Map<String, Object> parameters, String tenantId) {
        String rawBodyParam = requestSpec.path("rawBodyParam").asText("body");
        Object value = parameters.get(rawBodyParam);
        if (value == null) {
            log.warn("RawBinaryBodyEncoder: rawBodyParam '{}' missing from parameters", rawBodyParam);
            return new byte[0];
        }

        // byte[] direct
        if (value instanceof byte[] arr) {
            return arr;
        }

        // FileRef map → fetch from MinIO
        Map<String, Object> fileRef = coerceToFileRef(value);
        if (fileRef != null) {
            if (storageClient == null) {
                log.error("RawBinaryBodyEncoder: storageClient unavailable, cannot download FileRef");
                return new byte[0];
            }
            String storageKey = (String) fileRef.get("path");
            if (storageKey == null || storageKey.isBlank()) {
                log.error("RawBinaryBodyEncoder: FileRef has no 'path'");
                return new byte[0];
            }
            String tenant = tenantId == null || tenantId.isBlank() ? "anonymous" : tenantId;
            byte[] bytes = storageClient.download(tenant, storageKey);
            if (bytes == null) return new byte[0];
            if (bytes.length > LARGE_FILE_WARN_BYTES) {
                log.warn("RawBinaryBodyEncoder: large FileRef body ({} bytes, key={}) buffered in memory. "
                    + "Streaming upload not yet implemented - watch for heap pressure on concurrent large uploads.",
                    bytes.length, storageKey);
            }
            return bytes;
        }

        // String: optional base64: prefix, else literal UTF-8
        if (value instanceof String s) {
            if (s.startsWith("base64:")) {
                try {
                    return Base64.getDecoder().decode(s.substring("base64:".length()));
                } catch (IllegalArgumentException e) {
                    log.error("RawBinaryBodyEncoder: invalid base64 payload - {}", e.getMessage());
                    return new byte[0];
                }
            }
            return s.getBytes(StandardCharsets.UTF_8);
        }

        log.error("RawBinaryBodyEncoder: unsupported rawBodyParam type {}", value.getClass().getSimpleName());
        return new byte[0];
    }

    /**
     * Resolve the declared Content-Type for the body (default: application/octet-stream).
     */
    public String resolveContentType(JsonNode requestSpec) {
        String declared = requestSpec.path("contentType").asText("");
        return declared.isBlank() ? DEFAULT_CONTENT_TYPE : declared;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceToFileRef(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object type = map.get("_type");
            if ("file".equals(type)) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            if (map.containsKey("path") && map.containsKey("name")) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
        }
        if (value instanceof String s && s.startsWith("{")) {
            try {
                JsonNode node = objectMapper.readTree(s);
                if (node.isObject() && "file".equals(node.path("_type").asText())) {
                    return objectMapper.convertValue(node, Map.class);
                }
            } catch (Exception ignored) { }
        }
        return null;
    }
}
