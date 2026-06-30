package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects a raw HTTP response onto a tool's declared {@code output_schema}.
 *
 * Phase 7 of the typed-execution refactor. Behavior:
 * <ul>
 *   <li>If the tool has no {@code output_schema} (legacy / not yet migrated) → returns the
 *       raw response unchanged. This keeps every existing API working unchanged.</li>
 *   <li>If a schema is present → walks the response and copies ONLY the declared fields,
 *       recursing into {@code object} and {@code array} types via {@code children}.</li>
 *   <li>Type {@code fileRef} is recognized but not yet materialised - Phase 8 will inject
 *       a {@link com.apimarketplace.catalog.mapping.adapter.BinaryAdapter}-based handler that
 *       uploads the bytes to MinIO and produces a {@code {_type:"file", path, name, mimeType, size}}
 *       map. Until then, fileRef fields are passed through as-is when the response already
 *       contains a structured {@code _type:"file"} entry.</li>
 *   <li>Validation errors (unknown declared field type, missing required field) are LOGGED
 *       but do not abort execution - the goal is to ship Phase 7 without flaky regressions.</li>
 * </ul>
 *
 * The schema format is the JSONB-serialized form of {@code OutputFieldDef}:
 * {@code [{key, type, description, children?}]}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutputProjector {

    private static final String TYPE_OBJECT  = "object";
    private static final String TYPE_ARRAY   = "array";
    private static final String TYPE_FILEREF = "fileRef";

    private final ObjectMapper objectMapper;

    /**
     * Project the response onto the tool's typed output schema.
     *
     * @param rawResponse      raw API response (Map / List / scalar / null)
     * @param outputSchemaJson JSONB string from {@code api_tools.output_schema} (may be null)
     * @return projected output, or {@code rawResponse} unchanged when no schema is declared
     */
    public Object project(Object rawResponse, String outputSchemaJson) {
        if (outputSchemaJson == null || outputSchemaJson.isBlank()) {
            return rawResponse; // legacy path - no projection
        }
        if (rawResponse == null) {
            return null;
        }
        try {
            JsonNode schema = objectMapper.readTree(outputSchemaJson);
            if (!schema.isArray()) {
                log.debug("OutputProjector: schema is not an array, skipping projection");
                return rawResponse;
            }
            JsonNode responseNode = objectMapper.valueToTree(rawResponse);

            // List-endpoint pattern: API returns a JSON array at the root and the
            // schema declares the SHAPE OF ONE ELEMENT as a flat field-descriptor
            // list (e.g. JSONPlaceholder /comments → [{postId, id, name, email,
            // body}, ...] paired with output_schema=[{key:postId,...}, ...]).
            // Without this branch the array would be projected against an
            // object-only path and collapse to an empty Map. Project each
            // element instead and return the (possibly large) list - the
            // ResponseShaper downstream is responsible for size shaping.
            if (responseNode.isArray()) {
                List<Object> projectedList = new ArrayList<>(responseNode.size());
                for (JsonNode element : responseNode) {
                    if (element.isObject()) {
                        projectedList.add(projectAgainstFields(element, schema));
                    } else {
                        projectedList.add(objectMapper.convertValue(element, Object.class));
                    }
                }
                return projectedList;
            }

            return projectAgainstFields(responseNode, schema);
        } catch (Exception e) {
            log.warn("OutputProjector: failed to project response, returning raw ({})", e.getMessage());
            return rawResponse;
        }
    }

    /**
     * Project a JsonNode response against an array of OutputFieldDef-shaped entries.
     * The response is treated as an object whose fields match the schema's {@code key}s.
     */
    private Map<String, Object> projectAgainstFields(JsonNode responseNode, JsonNode schemaArray) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (responseNode == null || responseNode.isNull() || !responseNode.isObject()) {
            return out;
        }
        for (JsonNode field : schemaArray) {
            String key  = field.path("key").asText("");
            String type = field.path("type").asText("");
            if (key.isBlank() || type.isBlank()) continue;
            if (field.path("root").asBoolean(false)) {
                out.put(key, projectField(responseNode, type, field.path("children")));
                continue;
            }
            JsonNode value = responseNode.get(key);
            if (value == null || value.isNull()) continue;
            out.put(key, projectField(value, type, field.path("children")));
        }
        return out;
    }

    /**
     * Project a single field value according to its declared type.
     */
    private Object projectField(JsonNode value, String type, JsonNode childrenSchema) {
        // A structured FileRef ({_type:"file", ...}) MUST pass through intact, regardless of how the
        // tool's output_schema declares the field - many tools declare a file field as `object` with
        // children listing only the old 5 sub-fields ({_type, path, name, mimeType, size}). Projecting
        // a FileRef against those children silently drops the opaque `id` (and any future field), and
        // the by-id file URL is built from that id - so the file renders broken post opaque-URL cutover.
        if (isStructuredFileRef(value)) {
            return objectMapper.convertValue(value, Object.class);
        }
        switch (type) {
            case TYPE_OBJECT:
                if (value.isObject() && childrenSchema.isArray() && childrenSchema.size() > 0) {
                    return projectAgainstFields(value, childrenSchema);
                }
                return objectMapper.convertValue(value, Object.class);
            case TYPE_ARRAY:
                if (!value.isArray()) {
                    return List.of();
                }
                List<Object> items = new ArrayList<>(value.size());
                if (childrenSchema.isArray() && childrenSchema.size() > 0) {
                    // children describes the SHAPE of one element (object fields)
                    for (JsonNode element : value) {
                        if (isStructuredFileRef(element)) {
                            items.add(objectMapper.convertValue(element, Object.class));
                        } else if (element.isObject()) {
                            items.add(projectAgainstFields(element, childrenSchema));
                        } else {
                            items.add(objectMapper.convertValue(element, Object.class));
                        }
                    }
                } else {
                    for (JsonNode element : value) {
                        items.add(objectMapper.convertValue(element, Object.class));
                    }
                }
                return items;
            case TYPE_FILEREF:
                // Pass through structured FileRef objects; Phase 8 will materialize binaries here.
                return objectMapper.convertValue(value, Object.class);
            default:
                // Scalars: string | number | boolean | datetime - defer to Jackson coercion.
                return objectMapper.convertValue(value, Object.class);
        }
    }

    /** A canonical FileRef - recognised by the {@code _type:"file"} discriminator. */
    private boolean isStructuredFileRef(JsonNode value) {
        return value != null && value.isObject() && "file".equals(value.path("_type").asText(""));
    }
}
