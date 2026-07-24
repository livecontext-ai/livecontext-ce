package com.apimarketplace.common.storage.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Strips the U+0000 (NUL) codepoint out of JSON destined for a PostgreSQL
 * {@code jsonb} column.
 *
 * <p><b>Why:</b> PostgreSQL can NEVER store U+0000 in TEXT/JSONB - a payload
 * containing it fails the INSERT with SQLSTATE 22P05 (unsupported Unicode
 * escape sequence), poisons the transaction, and (pre-fix) silently cost a
 * workflow step its entire output blob while the step still reported
 * COMPLETED. Stripping the codepoint at the serialization funnel is a no-op
 * relative to any achievable persisted state (the byte could never land), so
 * it cannot mask other corruption.
 *
 * <p><b>Detection-then-clean contract:</b> callers first serialize normally,
 * then probe the produced JSON text with {@link #containsNulEscape(String)} -
 * a single {@code indexOf} on the hit-free hot path. Jackson encodes a real
 * U+0000 codepoint as the six-character escape sequence backslash-u0000; a
 * LITERAL backslash followed by "u0000" in the source data is encoded with a
 * doubled backslash, whose tail also matches the probe. That false positive
 * is deliberate and harmless: on detection we never regex-replace the
 * serialized text (which would corrupt the literal). Instead
 * {@link #stripNulCodepoints} re-parses the JSON and removes the actual
 * U+0000 codepoint from decoded string values and field names, then
 * re-serializes. A literal backslash-u0000 decodes to backslash + "u0000"
 * (no NUL codepoint) and passes through untouched.
 */
public final class JsonNulSanitizer {

    /**
     * The escape-sequence probe: the six characters backslash, 'u', '0',
     * '0', '0', '0' (how Jackson encodes a U+0000 codepoint in JSON text).
     */
    private static final String NUL_ESCAPE = "\\" + "u0000";

    /** The actual codepoint removed from decoded strings and field names. */
    private static final char NUL_CHAR = (char) 0;
    private static final String NUL_STRING = String.valueOf(NUL_CHAR);

    /**
     * Re-parse reader that preserves numeric fidelity across the round-trip:
     * BigDecimal/BigInteger nodes re-serialize losslessly, and the exact-
     * BigDecimal node factory keeps trailing zeros ({@code 1.100} stays
     * {@code 1.100} - the default factory normalizes it to {@code 1.1}).
     */
    private static final ObjectMapper LOSSLESS_READER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .setNodeFactory(com.fasterxml.jackson.databind.node.JsonNodeFactory.withExactBigDecimals(true));

    private JsonNulSanitizer() {}

    /**
     * Cheap probe: does this serialized JSON text contain the backslash-u0000
     * escape sequence? The overwhelmingly common hit-free payload pays exactly
     * one {@code indexOf}.
     */
    public static boolean containsNulEscape(String json) {
        return json != null && json.indexOf(NUL_ESCAPE) >= 0;
    }

    /**
     * Parse {@code json}, deep-strip the actual U+0000 codepoint from every
     * decoded string value and object field name, and re-serialize.
     * Surrounding text is preserved ("a" + NUL + "b" becomes "ab"); a literal
     * backslash-u0000 in the data decodes without any NUL codepoint and is
     * therefore NOT altered.
     *
     * @throws JsonProcessingException if the text is not valid JSON (callers
     *         produced it with Jackson one line earlier, so this is unreachable
     *         in practice; surfacing it keeps failures honest)
     */
    public static String stripNulCodepoints(String json) throws JsonProcessingException {
        JsonNode tree = LOSSLESS_READER.readTree(json);
        JsonNode cleaned = stripNode(tree);
        return LOSSLESS_READER.writeValueAsString(cleaned);
    }

    private static JsonNode stripNode(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.textValue();
            return text.indexOf(NUL_CHAR) >= 0
                    ? TextNode.valueOf(text.replace(NUL_STRING, ""))
                    : node;
        }
        if (node.isObject()) {
            ObjectNode source = (ObjectNode) node;
            ObjectNode rebuilt = LOSSLESS_READER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                if (name.indexOf(NUL_CHAR) >= 0) {
                    name = name.replace(NUL_STRING, "");
                }
                rebuilt.set(name, stripNode(field.getValue()));
            }
            return rebuilt;
        }
        if (node.isArray()) {
            ArrayNode source = (ArrayNode) node;
            ArrayNode rebuilt = LOSSLESS_READER.createArrayNode();
            for (JsonNode child : source) {
                rebuilt.add(stripNode(child));
            }
            return rebuilt;
        }
        return node;
    }
}
