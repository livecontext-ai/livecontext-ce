package com.apimarketplace.trigger.security;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redacts secret-named values from trigger configuration maps before they
 * leave the server (DTO serialisation, logs, error messages, audit traces).
 *
 * <p>Trigger configs are stored as untyped {@code Map<String, Object>} blobs
 * (webhook auth config, OAuth credentials, JWT keys, basic-auth passwords,
 * arbitrary header values, …). Annotating individual entity fields with
 * {@code @JsonIgnore} doesn't help - the secrets live as <em>map keys</em>
 * inside the JSON payload, not as typed columns. This redactor strips them
 * by key-name pattern.
 *
 * <p>Design v3.5 §L4 + audit feedback: secrets must be redacted by an active
 * sanitiser, not relied-upon-defensible omission. The trigger list endpoint
 * surfacing app-owned webhooks/forms/chats must not leak the auth secret to
 * the application owner UI.
 *
 * <h3>Detection rules</h3>
 *
 * <p>Key-name matching is <strong>case-insensitive substring</strong>: the
 * key is lower-cased and tested with {@code contains(pattern)} against each
 * pattern in {@link #SECRET_KEY_PATTERNS}. Matching is intentionally broad -
 * a single short pattern catches many concrete key spellings:
 *
 * <ul>
 *   <li>{@code password} / {@code passwd} - both forms</li>
 *   <li>{@code secret} - catches {@code clientSecret}, {@code SECRET_KEY},
 *       {@code app_secret}, etc.</li>
 *   <li>{@code token} - catches {@code refresh_token}, {@code accessToken},
 *       {@code AUTH_TOKEN}, {@code signed_token}, etc.</li>
 *   <li>{@code api_key} / {@code apikey} - both snake- and camelCase</li>
 *   <li>{@code private_key} / {@code privatekey}</li>
 *   <li>{@code signing_key}</li>
 *   <li>{@code auth_value} / {@code authvalue} - for header-value-style fields</li>
 *   <li>{@code credentials}</li>
 * </ul>
 *
 * <p>The pattern set is the source of truth ({@link #SECRET_KEY_PATTERNS});
 * any reader who needs to know "is X redacted?" should call
 * {@link #isSecretKey} rather than reading the doc.
 *
 * <p>The redactor walks nested maps and lists recursively. Non-string values
 * with secret-keyed entries are still redacted (the value is replaced wholesale).
 *
 * <h3>Output</h3>
 *
 * <p>Redacted values become the literal string {@value #REDACTED}. Non-secret
 * entries are passed through unchanged. The original map is <em>not</em>
 * mutated - a defensive copy is returned.
 *
 * <h3>Allowlist exceptions</h3>
 *
 * <p>The {@code webhookToken} / {@code token} entry on a public webhook URL
 * is part of the URL itself ({@code https://example.com/webhook/wh_…}); it is
 * a public identifier, not a secret. Callers that need to surface the URL
 * should NOT route the URL through this redactor - render the URL field
 * separately and only redact the auth-config map.
 */
public final class TriggerConfigRedactor {

    /** The string that replaces redacted secret values. */
    public static final String REDACTED = "***";

    /**
     * Marker value used in place of a map/list whose recursion would hit a
     * cycle (the same map/list reachable from itself through any path).
     * Defensive: a malformed config built by an external library could
     * theoretically produce one; better to mark it than crash.
     */
    public static final String CYCLE_MARKER = "<cycle>";

    /**
     * Lower-cased substrings that mark a key as a secret. Order is irrelevant
     * (each is tested as {@code keyLower.contains(pattern)}).
     */
    private static final Set<String> SECRET_KEY_PATTERNS = Set.of(
            "password",
            "passwd",
            "secret",
            "token",
            "api_key",
            "apikey",
            "private_key",
            "privatekey",
            "signing_key",
            "auth_header_value",
            "authheadervalue",
            "auth_value",
            "authvalue",
            "header_value",
            "headervalue",
            "credentials"
    );

    private TriggerConfigRedactor() {}

    /**
     * @return {@code true} when the key name matches a known secret pattern
     *         (case-insensitive, substring match).
     */
    public static boolean isSecretKey(String key) {
        if (key == null || key.isEmpty()) return false;
        String lower = key.toLowerCase();
        for (String pattern : SECRET_KEY_PATTERNS) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    /**
     * Returns a defensive copy of {@code config} with all secret-keyed entries
     * replaced by {@link #REDACTED}. Nested maps and lists are walked
     * recursively. {@code null} input returns {@code null}.
     *
     * <p>Cycle-safe: maps or lists reachable from themselves through any
     * recursive path are short-circuited to {@link #CYCLE_MARKER} instead of
     * recursing infinitely.
     */
    public static Map<String, Object> redact(Map<String, Object> config) {
        if (config == null) return null;
        Map<Object, Object> seen = new IdentityHashMap<>();
        seen.put(config, Boolean.TRUE);
        Map<String, Object> out = new LinkedHashMap<>(config.size());
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            out.put(entry.getKey(), redactValue(entry.getKey(), entry.getValue(), seen));
        }
        return out;
    }

    /**
     * Convenience overload for {@code Map<String, String>} (the shape used by
     * {@code StandaloneWebhookDto.authConfig}). Secret entries are replaced
     * with {@link #REDACTED}.
     */
    public static Map<String, String> redactStringMap(Map<String, String> config) {
        if (config == null) return null;
        Map<String, String> out = new LinkedHashMap<>(config.size());
        for (Map.Entry<String, String> entry : config.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (isSecretKey(key) && value != null && !value.isEmpty()) {
                out.put(key, REDACTED);
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object redactValue(String key, Object value, Map<Object, Object> seen) {
        if (value == null) return null;
        // Recurse into nested maps regardless of key (a non-secret key may
        // contain a sub-map that itself has secret entries).
        if (value instanceof Map<?, ?> nested) {
            // Cycle guard: if we've already entered this map on the current
            // path, return the marker rather than recursing infinitely.
            if (seen.containsKey(nested)) return CYCLE_MARKER;
            seen.put(nested, Boolean.TRUE);
            try {
                Map<String, Object> typed = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : nested.entrySet()) {
                    String k = String.valueOf(e.getKey());
                    typed.put(k, redactValue(k, e.getValue(), seen));
                }
                // If the OUTER key is a secret, redact the whole sub-tree
                // wholesale (defensive: don't leak structure of a secret).
                return isSecretKey(key) ? REDACTED : typed;
            } finally {
                seen.remove(nested);
            }
        }
        if (value instanceof List<?> list) {
            if (seen.containsKey(list)) return CYCLE_MARKER;
            seen.put(list, Boolean.TRUE);
            try {
                // Lists may contain maps; walk each element. Don't redact
                // list-as-a-whole by key (the outer key being secret was
                // already handled at scalar level).
                List<Object> out = new java.util.ArrayList<>(list.size());
                for (Object item : list) {
                    if (item instanceof Map<?, ?> || item instanceof List<?>) {
                        out.add(redactValue("", item, seen));
                    } else {
                        out.add(item);
                    }
                }
                return isSecretKey(key) ? REDACTED : out;
            } finally {
                seen.remove(list);
            }
        }
        // Scalar: redact iff the key is a secret AND the value is non-empty.
        if (isSecretKey(key)) {
            if (value instanceof CharSequence cs && cs.length() == 0) return value;
            return REDACTED;
        }
        return value;
    }
}
