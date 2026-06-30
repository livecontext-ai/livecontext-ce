package com.apimarketplace.auth.credential.service.oauth2.refresh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.regex.Pattern;

/**
 * PII-safe rendering of OAuth2 provider response bodies for logging.
 *
 * <p>Providers may echo refresh_tokens, bearer tokens, or user-identifying strings into their
 * error bodies (Salesforce and Azure AD have done this historically). Never log the raw body.
 * Use {@link #scrub(String)} to parse out only the two RFC 6749 fields we actually need -
 * {@code error} and {@code error_description} - truncated and stripped of token-shaped
 * substrings.
 */
public final class LogSafeBody {

    /** Max chars per field after truncation. Tight enough to stop novella-length error strings. */
    static final int FIELD_CAP = 200;

    /**
     * Token-shape heuristic: any run of ≥20 base64url-ish characters. Catches Google access
     * tokens, Microsoft refresh tokens, Slack {@code xoxp-…} tokens (after the prefix), and
     * Salesforce session IDs. False positives on long opaque IDs in {@code error_description}
     * are acceptable - losing readability on a single log line is cheaper than a token leak.
     */
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{20,}");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LogSafeBody() {}

    /**
     * Parse {@code body} as JSON and extract only {@code error} + {@code error_description},
     * each truncated and token-scrubbed. Returns a compact string safe for structured logs.
     * If the body is not JSON, empty, or unparseable, returns a literal marker - never the
     * original body.
     */
    public static String scrub(String body) {
        if (body == null || body.isEmpty()) {
            return "<empty>";
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root == null || !root.isObject()) {
                return "<non-json:" + body.length() + "b>";
            }
            String code = stringField(root, "error");
            String description = stringField(root, "error_description");
            if (code == null && description == null) {
                return "<no-error-fields>";
            }
            StringBuilder sb = new StringBuilder(FIELD_CAP * 2 + 32);
            sb.append("error=").append(truncate(code));
            if (description != null) {
                sb.append(" error_description=").append(scrubTokens(truncate(description)));
            }
            return sb.toString();
        } catch (Exception parseFailure) {
            return "<unparseable:" + body.length() + "b>";
        }
    }

    private static String stringField(JsonNode node, String field) {
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull() || !child.isTextual()) {
            return null;
        }
        String text = child.asText();
        return text.isEmpty() ? null : text;
    }

    static String truncate(String value) {
        if (value == null) return "<null>";
        if (value.length() <= FIELD_CAP) return value;
        return value.substring(0, FIELD_CAP) + "…";
    }

    static String scrubTokens(String value) {
        if (value == null || value.isEmpty()) return value;
        return TOKEN_SHAPE.matcher(value).replaceAll("<redacted>");
    }
}
