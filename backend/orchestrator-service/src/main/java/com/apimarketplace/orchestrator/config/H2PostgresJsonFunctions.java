package com.apimarketplace.orchestrator.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal PostgreSQL JSONB function shims for H2-backed integration and E2E tests.
 *
 * <p>This class intentionally avoids H2-specific parameter or return types because
 * H2 resolves CREATE ALIAS classes through the application classpath during SQL
 * initialization, before test-only classes are always visible.
 */
public final class H2PostgresJsonFunctions {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private H2PostgresJsonFunctions() {
    }

    public static String toJsonb(String value) throws Exception {
        if (value == null) {
            return "null";
        }
        if (looksLikeJson(value)) {
            return value.trim();
        }
        return OBJECT_MAPPER.writeValueAsString(value);
    }

    public static String jsonbSet(String target, String path, String value) throws Exception {
        return jsonbSet(target, path, value, true);
    }

    public static String jsonbSet(String target, String path, String value, Boolean createMissing) throws Exception {
        JsonNode root = parseJsonTarget(target);
        if (!root.isObject() && !root.isArray()) {
            root = JsonNodeFactory.instance.objectNode();
        }

        List<String> segments = parsePath(path);
        if (segments.isEmpty()) {
            return OBJECT_MAPPER.writeValueAsString(parseJsonValue(value));
        }

        JsonNode updated = root.deepCopy();
        setPath(updated, segments, parseJsonValue(value), Boolean.TRUE.equals(createMissing));
        return OBJECT_MAPPER.writeValueAsString(updated);
    }

    public static int hashtext(String value) {
        return value == null ? 0 : value.hashCode();
    }

    public static long hashtextextended(String value, long seed) {
        long hash = 0xcbf29ce484222325L ^ seed;
        if (value != null) {
            for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
                hash ^= (b & 0xffL);
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    public static long pgAdvisoryXactLock(long key) {
        return key;
    }

    private static void setPath(JsonNode root, List<String> segments, JsonNode value, boolean createMissing) {
        JsonNode current = root;
        for (int i = 0; i < segments.size() - 1; i++) {
            String segment = segments.get(i);
            JsonNode next = child(current, segment);
            if ((next == null || next.isMissingNode() || next.isNull()) && createMissing) {
                next = JsonNodeFactory.instance.objectNode();
                assign(current, segment, next);
            }
            current = next;
        }
        assign(current, segments.get(segments.size() - 1), value);
    }

    private static JsonNode child(JsonNode node, String segment) {
        if (node == null) {
            return null;
        }
        if (node.isArray() && isInteger(segment)) {
            return node.path(Integer.parseInt(segment));
        }
        return node.path(segment);
    }

    private static void assign(JsonNode node, String segment, JsonNode value) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.set(segment, value);
            return;
        }
        if (node instanceof ArrayNode arrayNode && isInteger(segment)) {
            int index = Integer.parseInt(segment);
            while (arrayNode.size() <= index) {
                arrayNode.addNull();
            }
            arrayNode.set(index, value);
        }
    }

    private static JsonNode parseJsonTarget(Object value) throws Exception {
        if (value == null) {
            return JsonNodeFactory.instance.objectNode();
        }
        JsonNode node = OBJECT_MAPPER.readTree(normalizeJsonText(toText(value)));
        if (node.isTextual()) {
            String text = node.asText();
            if (looksLikeJson(text)) {
                return OBJECT_MAPPER.readTree(text);
            }
        }
        return node;
    }

    private static JsonNode parseJsonValue(Object value) throws Exception {
        if (value == null) {
            return JsonNodeFactory.instance.nullNode();
        }
        String text = normalizeJsonText(toText(value));
        if (looksLikeJson(text)) {
            return OBJECT_MAPPER.readTree(text);
        }
        return JsonNodeFactory.instance.textNode(text);
    }

    private static List<String> parsePath(Object value) throws Exception {
        String text = toText(value).trim();
        if (text.startsWith("{") && text.endsWith("}")) {
            text = text.substring(1, text.length() - 1);
        }
        List<String> segments = new ArrayList<>();
        for (String segment : text.split(",")) {
            String cleaned = segment.trim();
            if (!cleaned.isEmpty()) {
                segments.add(cleaned);
            }
        }
        return segments;
    }

    private static String toText(Object value) throws Exception {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value instanceof Clob clob) {
            return clob.getSubString(1, Math.toIntExact(clob.length()));
        }
        return String.valueOf(value);
    }

    private static String normalizeJsonText(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("JSON '") && trimmed.endsWith("'")) {
            return trimmed.substring(6, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static boolean looksLikeJson(String text) {
        String trimmed = text.trim();
        return trimmed.equals("null")
            || trimmed.equals("true")
            || trimmed.equals("false")
            || trimmed.startsWith("{")
            || trimmed.startsWith("[")
            || trimmed.startsWith("\"")
            || trimmed.matches("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?");
    }

    private static boolean isInteger(String value) {
        return value.matches("\\d+");
    }
}
