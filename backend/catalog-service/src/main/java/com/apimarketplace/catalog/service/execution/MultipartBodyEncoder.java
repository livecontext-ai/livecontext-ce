package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.storage.client.StorageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes a {@code multipart/form-data} request body for catalog API tools whose
 * {@code execution.request.bodyType=multipart} (Phase 9 of the typed-execution refactor).
 *
 * Each entry in {@code execution.request.multipartFields[]} declares one part of the body:
 *
 * <pre>
 * { "name": "file",  "source": "fileRef", "paramName": "audio" }
 * { "name": "model", "source": "param",   "paramName": "model" }
 * </pre>
 *
 * Sources:
 * <ul>
 *   <li>{@code "param"}    - pull the value from the regular tool parameters map and add it as a string field</li>
 *   <li>{@code "fileRef"}  - the parameter must be a structured FileRef ({@code _type:"file", path:...}).
 *                            The bytes are downloaded from MinIO via {@link StorageClient#download(String, String)}
 *                            and added as a {@link ByteArrayResource}.</li>
 *   <li>{@code "auto"}     - polymorphic part: inspect the runtime value and choose the encoding.
 *                            A FileRef becomes a binary file part; a Map/Collection (e.g. Telegram
 *                            {@code reply_markup} / {@code caption_entities}) becomes a JSON-serialized
 *                            string (what such APIs require in {@code multipart/form-data}); any scalar
 *                            (a Telegram {@code file_id}, an HTTP URL, a number, a boolean) becomes a
 *                            plain text field. This is the source for a single field that accepts either
 *                            an uploaded file OR a string reference under the same part name.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MultipartBodyEncoder {

    @Autowired(required = false)
    private StorageClient storageClient;

    private final ObjectMapper objectMapper;

    /**
     * Build the multipart body for a tool execution.
     *
     * @param requestSpec  the whole {@code execution.request} node. A bare
     *                     {@code multipartFields} ARRAY is still accepted, so every
     *                     caller written before byte ranges keeps working.
     * @throws ByteRangeException when the endpoint declares a byte range and this call
     *                            cannot satisfy it - the call fails rather than sending
     *                            the whole file, or an empty part, where a slice was asked for
     * @param parameters           the user-supplied parameters map
     * @param tenantId             tenant id (used to download fileRef bytes from MinIO)
     * @return a {@link MultiValueMap} ready to be passed as a {@link org.springframework.http.HttpEntity} body
     */
    public MultiValueMap<String, Object> encode(JsonNode requestSpec,
                                                Map<String, Object> parameters,
                                                String tenantId) {
        // The whole `request` node, not just its multipartFields: a file part may declare a
        // BYTE RANGE, and that lives beside the field list rather than inside it. A bare
        // fields ARRAY is still accepted, so every caller and test that passed one keeps
        // working, and a null spec stays the empty body it has always been.
        JsonNode multipartFieldsJson = requestSpec != null && requestSpec.has("multipartFields")
                ? requestSpec.path("multipartFields") : requestSpec;
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        if (multipartFieldsJson == null || !multipartFieldsJson.isArray()) {
            log.warn("MultipartBodyEncoder: multipartFields is missing or not an array, returning empty body");
            return body;
        }

        for (JsonNode fieldDef : multipartFieldsJson) {
            String partName  = fieldDef.path("name").asText("");
            String source    = fieldDef.path("source").asText("");
            String paramName = fieldDef.path("paramName").asText("");
            if (partName.isBlank() || paramName.isBlank()) {
                log.warn("MultipartBodyEncoder: skipping malformed multipartField {}", fieldDef);
                continue;
            }

            Object paramValue = parameters.get(paramName);
            if (paramValue == null) {
                log.debug("MultipartBodyEncoder: parameter '{}' not provided, skipping part '{}'", paramName, partName);
                continue;
            }

            switch (source) {
                case "param":
                    body.add(partName, String.valueOf(paramValue));
                    break;
                case "fileRef":
                    addFileRefPart(body, partName, paramValue, tenantId, requestSpec, parameters);
                    break;
                case "auto":
                    addAutoPart(body, partName, paramValue, tenantId, requestSpec, parameters);
                    break;
                default:
                    log.warn("MultipartBodyEncoder: unknown source '{}' for part '{}'", source, partName);
            }
        }

        return body;
    }

    /**
     * Resolve a FileRef-shaped value, download bytes from MinIO and append as a multipart resource.
     */
    private void addFileRefPart(MultiValueMap<String, Object> body,
                                String partName,
                                Object paramValue,
                                String tenantId,
                                JsonNode requestSpec,
                                Map<String, Object> parameters) {
        if (storageClient == null) {
            log.error("MultipartBodyEncoder: storageClient unavailable, cannot download fileRef for part '{}'", partName);
            return;
        }

        Map<String, Object> fileRef = coerceToFileRef(paramValue);
        if (fileRef == null) {
            // KNOWINGLY LEFT, not overlooked. Dropping the part sends the request
            // without the file and the provider answers with its own complaint, or
            // succeeds: the same "green run, file gone" shape that
            // FileAttachmentException refuses on the JSON-body path. Turning this
            // into a refusal touches every multipart endpoint in the catalog, so it
            // is its own change with its own end-to-end run.
            log.error("MultipartBodyEncoder: parameter for part '{}' is not a FileRef ({})", partName, paramValue.getClass().getSimpleName());
            return;
        }

        String storageKey = (String) fileRef.get("path");
        String fileName   = (String) fileRef.getOrDefault("name", "upload.bin");
        if (storageKey == null || storageKey.isBlank()) {
            log.error("MultipartBodyEncoder: fileRef for part '{}' has no 'path'", partName);
            return;
        }

        String tenant = tenantId == null || tenantId.isBlank() ? "anonymous" : tenantId;
        byte[] whole = storageClient.download(tenant, storageKey);
        if (whole == null || whole.length == 0) {
            log.error("MultipartBodyEncoder: empty download for storageKey={}", storageKey);
            return;
        }

        // The whole file is buffered in heap. A declared range makes that worse rather
        // than better: StorageClient has no ranged read, so an N-part upload buffers the
        // whole file N times over.
        if (whole.length > ByteRangeSlicer.LARGE_FILE_WARN_BYTES) {
            boolean ranged = !requestSpec.path("rangeFirstByteParam").asText("").isBlank();
            log.warn("MultipartBodyEncoder: part '{}' buffers {} MB in memory{}",
                partName, whole.length / (1024 * 1024),
                ranged ? ", once for every part of this ranged upload" : "");
        }

        // Send one declared slice when the endpoint uploads a large file in parts. X's
        // /2/media/upload/{id}/append is the reason this exists on the multipart path: it
        // keeps ONE url and names the part with a `segment_index` field alongside the bytes,
        // so it cannot be raw binary the way LinkedIn's per-chunk pre-signed urls can.
        // Throws ByteRangeException when a declared range cannot be honoured, which fails
        // the whole call. That is deliberate: `required: true` on the bounds is read only by
        // the node creator and the workflow validators, never by this path, so a step SAVED
        // before the endpoint gained its range would otherwise upload a zero-byte part for
        // ever, answering 2xx each time.
        final byte[] bytes = ByteRangeSlicer.slice(whole, requestSpec, parameters, "MultipartBodyEncoder");

        body.add(partName, new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }

            @Override
            public long contentLength() {
                return bytes.length;
            }
        });
    }

    /**
     * Polymorphic part ({@code source:"auto"}). Picks the encoding from the runtime value type so a
     * single field can carry either an uploaded file or a string reference under the same part name:
     * <ul>
     *   <li>a FileRef ({@code _type:"file"} / {@code path}+{@code name}) - downloaded and added as a
     *       binary file part (reuses {@link #addFileRefPart});</li>
     *   <li>a {@code Map} or {@code Collection} (e.g. Telegram {@code reply_markup},
     *       {@code caption_entities}) - JSON-serialized to a string, because {@code multipart/form-data}
     *       has no native object encoding and these APIs expect a JSON string in the field;</li>
     *   <li>any scalar (file_id, HTTP URL, number, boolean) - added verbatim as a text field.</li>
     * </ul>
     */
    private void addAutoPart(MultiValueMap<String, Object> body,
                             String partName,
                             Object paramValue,
                             String tenantId,
                             JsonNode requestSpec,
                             Map<String, Object> parameters) {
        // FileRef detection runs first so a FileRef Map never falls into the Map->JSON branch.
        // Note: coerceToFileRef also treats any Map carrying both `path` and `name` as a FileRef
        // (not only `_type:"file"`). No Telegram object param collides with that shape; reusing
        // `auto` on an API whose object param happens to carry path+name would upload it instead
        // of JSON-encoding it. Declare such a field `source:"param"` rather than `auto`.
        if (coerceToFileRef(paramValue) != null) {
            addFileRefPart(body, partName, paramValue, tenantId, requestSpec, parameters);
            return;
        }
        if (paramValue instanceof Map || paramValue instanceof java.util.Collection) {
            try {
                body.add(partName, objectMapper.writeValueAsString(paramValue));
            } catch (Exception e) {
                log.warn("MultipartBodyEncoder: failed to JSON-encode part '{}', falling back to toString: {}",
                        partName, e.getMessage());
                body.add(partName, String.valueOf(paramValue));
            }
            return;
        }
        body.add(partName, String.valueOf(paramValue));
    }

    /**
     * {@code scheme://} at the head of a value: a link some provider fetches, never
     * a storage key.
     *
     * <p>Same rule as {@link FileAttachmentResolver}'s, and here for the same
     * reason: a {@code path} holding a URL was read as a key, the download found
     * nothing, and the part was dropped with a message about a missing file.
     * Matched with {@code find()} on an anchored pattern rather than
     * {@code matches()}, so a value carrying a newline is recognised too, exactly
     * as the resolver recognises it.
     */
    private static final java.util.regex.Pattern ABSOLUTE_URL =
            java.util.regex.Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.-]*://");

    private static boolean isAbsoluteUrl(Object path) {
        return path instanceof String s && ABSOLUTE_URL.matcher(s).find();
    }

    /**
     * Convert a parameter value to a FileRef map. Accepts:
     * <ul>
     *   <li>a {@code Map} that already has {@code _type:"file"}</li>
     *   <li>a {@code Map} carrying {@code path} and {@code name}, whose {@code path}
     *       is not an absolute URL</li>
     *   <li>a JSON string representation of such a map</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> coerceToFileRef(Object value) {
        if (value instanceof Map<?, ?> map) {
            Object type = map.get("_type");
            if ("file".equals(type)) {
                return new LinkedHashMap<>((Map<String, Object>) map);
            }
            if (map.containsKey("path") && map.containsKey("name") && !isAbsoluteUrl(map.get("path"))) {
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
