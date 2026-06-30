package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Encodes a {@code multipart/related} request body for catalog API tools whose
 * {@code execution.request.bodyType=multipart_related}.
 *
 * <p>This is the format Google's media-upload endpoints require for
 * {@code uploadType=multipart} - a JSON metadata part followed by a binary media
 * part, glued by a MIME boundary. It differs from {@code bodyType=multipart}
 * ({@link MultipartBodyEncoder}, which produces {@code multipart/form-data} and is
 * rejected by e.g. YouTube {@code videos.insert}).
 *
 * <p>Config under {@code execution.request.multipartRelated}:
 * <pre>
 * {
 *   "metadataFields":   ["snippet", "status"],   // params assembled into the JSON metadata part
 *   "mediaField":       "video",                  // fileRef param supplying the media bytes
 *   "mediaContentType": "video/*"                 // Content-Type of the media part
 * }
 * </pre>
 *
 * <p>The metadata part is a single JSON object whose keys are the
 * {@code metadataFields} param names; each param value (a JSON string or already-parsed
 * node) is parsed and nested under its key - e.g. {@code {"snippet": {...}, "status": {...}}}.
 * The media part bytes are downloaded from MinIO via {@link StorageClient#download(String, String)}
 * from the {@code mediaField} param, which must be a FileRef ({@code _type:"file", path:...})
 * or any map carrying both {@code path} and {@code name} (parity with the sibling encoders).
 *
 * <p>The caller sets {@code Content-Type: multipart/related; boundary=<boundary>} using
 * {@link EncodedBody#boundary()}.
 */
@Component
@Slf4j
public class MultipartRelatedBodyEncoder {

    @Autowired(required = false)
    private StorageClient storageClient;

    private final ObjectMapper objectMapper;

    public MultipartRelatedBodyEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Assembled body bytes plus the boundary the caller must echo in the Content-Type header. */
    public record EncodedBody(byte[] body, String boundary) {}

    /**
     * Build the multipart/related body for a tool execution.
     *
     * @param requestSpec the {@code execution.request} node (reads {@code multipartRelated})
     * @param parameters  the user-supplied parameters map
     * @param tenantId    tenant id (used to download the media fileRef bytes from MinIO)
     * @return assembled body + boundary, or null when the spec/media is unusable (caller fails the call)
     */
    public EncodedBody encode(JsonNode requestSpec, Map<String, Object> parameters, String tenantId) {
        JsonNode cfg = requestSpec.path("multipartRelated");
        if (cfg.isMissingNode() || !cfg.isObject()) {
            log.error("MultipartRelatedBodyEncoder: execution.request.multipartRelated is missing or not an object");
            return null;
        }

        String mediaField = cfg.path("mediaField").asText("");
        String mediaContentType = cfg.path("mediaContentType").asText("application/octet-stream");
        if (mediaField.isBlank()) {
            log.error("MultipartRelatedBodyEncoder: multipartRelated.mediaField is required");
            return null;
        }

        // 1. Assemble the JSON metadata object from the declared metadata fields.
        ObjectNode metadata = objectMapper.createObjectNode();
        JsonNode metaFields = cfg.path("metadataFields");
        if (metaFields.isArray()) {
            for (JsonNode fieldNode : metaFields) {
                String field = fieldNode.asText("");
                if (field.isBlank()) continue;
                Object raw = parameters.get(field);
                if (raw == null) {
                    log.debug("MultipartRelatedBodyEncoder: metadata field '{}' not provided, omitting", field);
                    continue;
                }
                metadata.set(field, parseToNode(raw));
            }
        }

        // 2. Download the media bytes from the fileRef param.
        Object mediaValue = parameters.get(mediaField);
        if (mediaValue == null) {
            log.error("MultipartRelatedBodyEncoder: media field '{}' not provided", mediaField);
            return null;
        }
        if (storageClient == null) {
            log.error("MultipartRelatedBodyEncoder: storageClient unavailable, cannot download media fileRef");
            return null;
        }
        Map<String, Object> fileRef = coerceToFileRef(mediaValue);
        if (fileRef == null) {
            log.error("MultipartRelatedBodyEncoder: media field '{}' is not a FileRef ({})",
                    mediaField, mediaValue.getClass().getSimpleName());
            return null;
        }
        String storageKey = (String) fileRef.get("path");
        if (storageKey == null || storageKey.isBlank()) {
            log.error("MultipartRelatedBodyEncoder: media fileRef has no 'path'");
            return null;
        }
        String tenant = tenantId == null || tenantId.isBlank() ? "anonymous" : tenantId;
        byte[] mediaBytes = storageClient.download(tenant, storageKey);
        if (mediaBytes == null || mediaBytes.length == 0) {
            log.error("MultipartRelatedBodyEncoder: empty media download for storageKey={}", storageKey);
            return null;
        }

        // 3. Assemble the multipart/related body.
        String boundary = "lc_related_" + UUID.randomUUID().toString().replace("-", "");
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.error("MultipartRelatedBodyEncoder: failed to serialize metadata: {}", e.getMessage());
            return null;
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(mediaBytes.length + 512);
            // Part 1 - metadata JSON
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.write(metadataJson.getBytes(StandardCharsets.UTF_8));
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            // Part 2 - media bytes
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + mediaContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(mediaBytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            // Closing boundary
            out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return new EncodedBody(out.toByteArray(), boundary);
        } catch (Exception e) {
            log.error("MultipartRelatedBodyEncoder: failed to assemble body: {}", e.getMessage());
            return null;
        }
    }

    /** Parse a param value (JSON string or already-parsed node/map) into a JsonNode for nesting. */
    private JsonNode parseToNode(Object raw) {
        if (raw instanceof JsonNode node) {
            return node;
        }
        if (raw instanceof String s) {
            String t = s.trim();
            if (t.startsWith("{") || t.startsWith("[")) {
                try {
                    return objectMapper.readTree(t);
                } catch (Exception ignored) {
                    // not valid JSON - fall through to a text node
                }
            }
            return objectMapper.getNodeFactory().textNode(s);
        }
        return objectMapper.valueToTree(raw);
    }

    /** Convert a parameter value to a FileRef map (Map with _type:file, or its JSON-string form). */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceToFileRef(Object value) {
        if (value instanceof Map<?, ?> map) {
            if ("file".equals(map.get("_type")) || (map.containsKey("path") && map.containsKey("name"))) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
        }
        if (value instanceof String s && s.trim().startsWith("{")) {
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
