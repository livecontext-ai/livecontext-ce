package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    /** Marks an outputSchema field whose value comes from a response header, not the body. */
    private static final String SOURCE_HEADER = "header";

    private final ObjectMapper objectMapper;

    /**
     * Project the response onto the tool's typed output schema.
     *
     * @param rawResponse      raw API response (Map / List / scalar / null)
     * @param outputSchemaJson JSONB string from {@code api_tools.output_schema} (may be null)
     * @return projected output, or {@code rawResponse} unchanged when no schema is declared
     */
    /**
     * Project the response, then add any field the schema sources from a RESPONSE HEADER.
     *
     * <p>Some providers return the value a later call needs in a header rather than in the body.
     * LinkedIn's video upload is the case this exists for: each part is PUT to a signed URL that
     * answers 201 with an EMPTY body and the part's identifier in {@code ETag}, and
     * {@code finalizeUpload} will not accept the video without those identifiers. The execution
     * layer has always captured the headers; they were dropped one layer above, so no endpoint
     * could ever declare one and the whole upload flow was unreachable.
     *
     * <p>A field opts in with {@code "source": "header"} and is looked up by its {@code key},
     * case-insensitively, because HTTP header names are. Anything without that marker reads from
     * the body exactly as before, so every existing tool projects unchanged.
     *
     * @param responseHeaders response headers, may be null or empty
     */
    public Object project(Object rawResponse, String outputSchemaJson,
                          Map<String, String> responseHeaders) {
        return project(rawResponse, outputSchemaJson, responseHeaders, false);
    }

    /**
     * Same, and when {@code keepUndeclared} is true every field the schema does NOT declare is
     * kept as the provider sent it, beside the declared ones (which are still typed and recursed).
     *
     * <p>This is for a call that explicitly asked the provider for more fields than the seed
     * declares (X {@code tweet.fields=entities,attachments}, a Graph {@code $select}, a Jira
     * {@code expand}): the provider answered, and dropping the answer would hand the caller a
     * green result missing exactly what it asked for, with nothing saying so. The caller decides,
     * see {@link RequestedFieldSelection}.
     */
    public Object project(Object rawResponse, String outputSchemaJson,
                          Map<String, String> responseHeaders, boolean keepUndeclared) {
        Object projected = project(rawResponse, outputSchemaJson, keepUndeclared);
        List<String> headerKeys = headerSourcedKeys(outputSchemaJson);
        if (headerKeys.isEmpty()) {
            return projected;
        }
        // A header-sourced field has to land somewhere. The body of such a call is typically
        // empty, so the projection is an empty Map; starting a fresh one when it is anything
        // else (null, a list) keeps the declared field reachable instead of silently lost.
        Map<String, Object> out = new LinkedHashMap<>();
        if (projected instanceof Map<?, ?> projectedMap) {
            for (Map.Entry<?, ?> e : projectedMap.entrySet()) {
                String key = String.valueOf(e.getKey());
                // A header-sourced field reads from the HEADER and from nowhere else. The body
                // projection above does not know about the marker, so a body field of the same
                // name would otherwise survive here and be handed back as if the provider had
                // sent the header - a wrong value, silently, with no way for a caller to tell.
                // Dropping it first makes the header the only source, present or absent.
                if (headerKeys.contains(key)) {
                    continue;
                }
                out.put(key, e.getValue());
            }
        } else if (projected != null) {
            log.debug("OutputProjector: header-sourced fields declared on a non-object projection; "
                    + "keeping the projection under 'data'");
            out.put("data", projected);
        }
        Map<String, String> lookup = caseInsensitive(responseHeaders);
        for (String key : headerKeys) {
            String value = lookup.get(key.toLowerCase(Locale.ROOT));
            if (value != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    /** The keys the schema declares as coming from a header. Root level only. */
    private List<String> headerSourcedKeys(String outputSchemaJson) {
        if (outputSchemaJson == null || outputSchemaJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode schema = objectMapper.readTree(outputSchemaJson);
            if (!schema.isArray()) {
                return List.of();
            }
            List<String> keys = new ArrayList<>();
            for (JsonNode field : schema) {
                if (SOURCE_HEADER.equalsIgnoreCase(field.path("source").asText(""))) {
                    String key = field.path("key").asText("");
                    if (!key.isBlank()) {
                        keys.add(key);
                    }
                }
            }
            return keys;
        } catch (Exception e) {
            log.warn("OutputProjector: could not read the output schema for header-sourced fields ({})",
                    e.getMessage());
            return List.of();
        }
    }

    /**
     * HTTP header names are case-insensitive, and providers disagree on the casing they send
     * ({@code ETag}, {@code etag}). Matching on the exact spelling a seed author happened to type
     * would make the field resolve for one provider and silently vanish for the next.
     */
    private Map<String, String> caseInsensitive(Map<String, String> headers) {
        Map<String, String> out = new LinkedHashMap<>();
        if (headers == null) {
            return out;
        }
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return out;
    }

    public Object project(Object rawResponse, String outputSchemaJson) {
        return project(rawResponse, outputSchemaJson, false);
    }

    private Object project(Object rawResponse, String outputSchemaJson, boolean keepUndeclared) {
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
            if (rawResponse instanceof String text) {
                return placeUnparsed(text, schema);
            }
            if (rawResponse instanceof JsonLines lines) {
                String wholeBodyKey = singleStringBodyKey(schema);
                if (wholeBodyKey != null) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put(wholeBodyKey, lines.text());
                    return out;
                }
                // Otherwise the records are projected like any root array, below.
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
                        projectedList.add(projectAgainstFields(element, schema, keepUndeclared));
                    } else {
                        projectedList.add(objectMapper.convertValue(element, Object.class));
                    }
                }
                return projectedList;
            }

            return projectAgainstFields(responseNode, schema, keepUndeclared);
        } catch (Exception e) {
            log.warn("OutputProjector: failed to project response, returning raw ({})", e.getMessage());
            return rawResponse;
        }
    }

    /**
     * Where an answer the projection cannot read field by field goes: a TEXT body (CSV, XML, plain
     * text, an HTML page an endpoint declared as text), or the file reference the base64
     * dehydrator made of one (see {@link #placeUnparsed(Object, String)}).
     *
     * <p>Object projection would drop it: it is not an object, so every declared field came back
     * missing and the call reported success with an EMPTY result, charged, green on the
     * tool-health board. 212 seed endpoints declare {@code response.type=text}, all with a schema
     * (InfluxDB {@code csv}, AssemblyAI {@code content}, Jenkins {@code output}, 143 AWS XML
     * endpoints under an object key the XML never fills).
     *
     * <ul>
     *   <li>When the schema declares exactly ONE body field and it is a string, the body is that
     *       field ({@code {"csv": "<the csv>"}}): the declared name is what workflows and the
     *       variable picker already reference.</li>
     *   <li>Otherwise a text is returned as it is (the caller receives it under {@code data}), and
     *       any other value under an explicit {@code data} key, so a file reference is never merged
     *       into the result's own keys. Nothing is invented for an object-typed or multi-field
     *       schema (the AWS XML case).</li>
     * </ul>
     */
    private Object placeUnparsed(Object body, JsonNode schema) {
        String key = singleStringBodyKey(schema);
        Map<String, Object> out = new LinkedHashMap<>();
        if (key != null) {
            out.put(key, body);
            return out;
        }
        if (body instanceof String) {
            return body;
        }
        out.put("data", body);
        return out;
    }

    /**
     * {@link #placeUnparsed(Object, JsonNode)} for a caller holding the schema as JSON: the
     * execution path uses it for a text body the base64 dehydrator turned into a file reference
     * before projection (a 64 KB+ base64-looking text), which object projection would drop.
     */
    public Object placeUnparsed(Object body, String outputSchemaJson) {
        if (body == null || outputSchemaJson == null || outputSchemaJson.isBlank()) {
            return body;
        }
        try {
            JsonNode schema = objectMapper.readTree(outputSchemaJson);
            return schema.isArray() ? placeUnparsed(body, schema) : body;
        } catch (Exception e) {
            log.warn("OutputProjector: could not read the output schema to place a text body ({})", e.getMessage());
            return body;
        }
    }

    /**
     * The key of the schema's only body field when that field is a string, else null. Counted the
     * way {@link #projectAgainstFields} reads a schema: an entry with a blank key or type is
     * skipped, and a header-sourced field is not a body field (the header layer adds it). A
     * {@code root} string field counts: it names the whole body, which is exactly this case.
     */
    private String singleStringBodyKey(JsonNode schema) {
        JsonNode only = null;
        int bodyFields = 0;
        for (JsonNode field : schema) {
            String key = field.path("key").asText("");
            String type = field.path("type").asText("");
            if (key.isBlank() || type.isBlank()
                    || SOURCE_HEADER.equalsIgnoreCase(field.path("source").asText(""))) {
                continue;
            }
            bodyFields++;
            only = field;
        }
        return bodyFields == 1 && "string".equalsIgnoreCase(only.path("type").asText(""))
                ? only.path("key").asText("")
                : null;
    }

    /**
     * Project a JsonNode response against an array of OutputFieldDef-shaped entries.
     * The response is treated as an object whose fields match the schema's {@code key}s.
     */
    private Map<String, Object> projectAgainstFields(JsonNode responseNode, JsonNode schemaArray,
                                                     boolean keepUndeclared) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (responseNode == null || responseNode.isNull() || !responseNode.isObject()) {
            return out;
        }
        for (JsonNode field : schemaArray) {
            String key  = field.path("key").asText("");
            String type = field.path("type").asText("");
            if (key.isBlank() || type.isBlank()) continue;
            if (field.path("root").asBoolean(false)) {
                out.put(key, projectField(responseNode, type, field.path("children"), keepUndeclared));
                continue;
            }
            JsonNode value = responseNode.get(key);
            if (value == null || value.isNull()) continue;
            out.put(key, projectField(value, type, field.path("children"), keepUndeclared));
        }
        if (keepUndeclared) {
            // Declared keys first (typed), then whatever else the provider sent, untouched.
            Set<String> declared = declaredBodyKeys(schemaArray);
            Iterator<Map.Entry<String, JsonNode>> fields = responseNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                if (!out.containsKey(e.getKey()) && !declared.contains(e.getKey())) {
                    out.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class));
                }
            }
        }
        return out;
    }

    /**
     * The body keys the schema really projects: the same entries the loop above reads. A root
     * field names no body key, and an entry with a blank key or type is skipped by that loop, so
     * counting either as declared would drop a body key that nothing projects.
     */
    private Set<String> declaredBodyKeys(JsonNode schemaArray) {
        Set<String> keys = new HashSet<>();
        for (JsonNode field : schemaArray) {
            String key = field.path("key").asText("");
            if (!key.isBlank() && !field.path("type").asText("").isBlank()
                    && !field.path("root").asBoolean(false)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * Project a single field value according to its declared type.
     */
    private Object projectField(JsonNode value, String type, JsonNode childrenSchema,
                                boolean keepUndeclared) {
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
                    return projectAgainstFields(value, childrenSchema, keepUndeclared);
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
                            items.add(projectAgainstFields(element, childrenSchema, keepUndeclared));
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
