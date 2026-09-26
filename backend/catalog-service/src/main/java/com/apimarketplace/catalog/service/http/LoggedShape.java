package com.apimarketplace.catalog.service.http;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The SHAPE of a tool call's parameters or request body, for logs: key names, types and sizes,
 * never a value.
 *
 * <p><b>Why values cannot be logged.</b> A value can be a credential. A workflow passes one in
 * its parameters through an expression such as {@code {{credential.client_secret}}} (the
 * {@code body_field} integrations: Authorize.Net, Personio, LibreTranslate), and by the time the
 * call reaches this service it is an ordinary string, indistinguishable from any other. Masking
 * the credential's known values instead would mean fetching its data from auth-service on every
 * call, just to write a log line. Values are also user content (message texts, documents), which
 * does not belong in shared logs either.
 *
 * <p>What stays is what diagnoses a call: which keys arrived, how big each value is, and its
 * type. The request URL is NOT shaped, path AND query: it is what says which endpoint was called
 * and with which identifiers, and a credential the platform injects into it (a URL variable or a
 * query key) is masked there by {@link CredentialUrlScrubber}. The one thing this leaves visible
 * is a secret a workflow passes as an ORDINARY path or query parameter; every integration that
 * takes its secret as a parameter today ({@code body_field}) sends it in the body, which is shaped. Output is bounded: at most {@value #MAX_DEPTH} levels and {@value #MAX_ENTRIES} entries
 * per level.
 */
public final class LoggedShape {

    static final int MAX_DEPTH = 3;
    static final int MAX_ENTRIES = 30;

    private LoggedShape() {
    }

    /** A value-free description of {@code value}. */
    public static String of(Object value) {
        return describe(value, 0);
    }

    private static String describe(Object value, int depth) {
        if (value == null) {
            return "null";
        }
        if (value instanceof JsonNode node) {
            return describeJson(node, depth);
        }
        if (value instanceof CharSequence cs) {
            return "<string " + cs.length() + ">";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return "<" + value.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT) + ">";
        }
        if (value instanceof byte[] bytes) {
            return "<bytes " + bytes.length + ">";
        }
        if (value instanceof Map<?, ?> map) {
            if (depth >= MAX_DEPTH) {
                return "{" + map.size() + " keys}";
            }
            StringJoiner j = new StringJoiner(", ", "{", "}");
            int n = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (n++ == MAX_ENTRIES) {
                    j.add("... " + (map.size() - MAX_ENTRIES) + " more");
                    break;
                }
                j.add(e.getKey() + "=" + describe(e.getValue(), depth + 1));
            }
            return j.toString();
        }
        if (value instanceof Collection<?> c) {
            if (depth >= MAX_DEPTH || c.isEmpty()) {
                return "[" + c.size() + " items]";
            }
            StringJoiner j = new StringJoiner(", ", "[", "]");
            int n = 0;
            for (Object item : c) {
                if (n++ == MAX_ENTRIES) {
                    j.add("... " + (c.size() - MAX_ENTRIES) + " more");
                    break;
                }
                j.add(describe(item, depth + 1));
            }
            return j.toString();
        }
        // Multipart parts, resources, anything else: the type is the useful part.
        return "<" + value.getClass().getSimpleName() + ">";
    }

    private static String describeJson(JsonNode node, int depth) {
        if (node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isTextual()) {
            return "<string " + node.textValue().length() + ">";
        }
        if (node.isNumber()) {
            return "<number>";
        }
        if (node.isBoolean()) {
            return "<boolean>";
        }
        if (node.isObject()) {
            if (depth >= MAX_DEPTH) {
                return "{" + node.size() + " keys}";
            }
            StringJoiner j = new StringJoiner(", ", "{", "}");
            int n = 0;
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (n++ == MAX_ENTRIES) {
                    j.add("... " + (node.size() - MAX_ENTRIES) + " more");
                    break;
                }
                j.add(e.getKey() + "=" + describeJson(e.getValue(), depth + 1));
            }
            return j.toString();
        }
        if (node.isArray()) {
            if (depth >= MAX_DEPTH || node.isEmpty()) {
                return "[" + node.size() + " items]";
            }
            StringJoiner j = new StringJoiner(", ", "[", "]");
            int n = 0;
            for (JsonNode item : node) {
                if (n++ == MAX_ENTRIES) {
                    j.add("... " + (node.size() - MAX_ENTRIES) + " more");
                    break;
                }
                j.add(describeJson(item, depth + 1));
            }
            return j.toString();
        }
        // Binary / POJO nodes.
        return "<" + node.getNodeType().name().toLowerCase(java.util.Locale.ROOT) + ">";
    }
}
