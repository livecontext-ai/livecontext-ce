package com.apimarketplace.agent.tools.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Strips secrets / credentials / tokens from tool-call JSON payloads
 * before they're surfaced to a reviewer or creator agent.
 *
 * <p>Tool-call args/results frequently contain OAuth access tokens, refresh
 * tokens, API keys, webhook secrets, internal authorization headers - the
 * things {@code auth.platform_credentials} is designed to keep server-side.
 * Showing them to a reviewer (even a same-tenant one) is a privilege
 * escalation: the assignee had write-time access to the credential, the
 * reviewer should only see WHAT was done, not the SECRET used to do it.
 *
 * <p>Two layers of defense:
 * <ol>
 *   <li>Field-name regex - any key that smells like a secret (e.g. {@code Authorization},
 *       {@code apiKey}, {@code refresh_token}) is replaced with the literal
 *       {@code [REDACTED]} regardless of value type.</li>
 *   <li>Sensitive tool name allowlist - calls to known credential-handling
 *       tools (catalog tools, auth-service-backed tools) get their args
 *       stripped completely (replaced with {@code "[REDACTED:credential-tool]"})
 *       so we don't leak through fields the regex misses.</li>
 * </ol>
 *
 * <p>Redaction is one-way and applied on the server BEFORE serialization
 * to the caller. Callers can never opt out.
 */
@Slf4j
@Component
public class ToolCallRedactor {

    /**
     * Field-name patterns that mark a key as carrying a secret. Case-insensitive.
     * Matches any key containing one of these substrings.
     */
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i).*(token|secret|password|passwd|pwd|"
          + "apikey|api[_-]?key|"
          + "authorization|authorisation|auth[_-]?header|bearer|"
          + "credential|cred[_-]?id|"
          + "private[_-]?key|client[_-]?secret|"
          + "refresh[_-]?token|access[_-]?token|id[_-]?token|"
          + "session[_-]?id|cookie|csrf|"
          + "x-api-key|x-auth|x-secret"
          + ").*");

    /**
     * Tool names that are known to operate on credentials at the platform level.
     * Their entire args/results are redacted - the reviewer sees the tool was
     * invoked but not WHAT was passed (which is always credential material).
     */
    private static final Set<String> CREDENTIAL_TOOLS = Set.of(
            "credential",       // platform credential CRUD
            "oauth2",           // oauth flows
            "auth",             // auth-service tools
            "catalog"           // catalog tool exec sometimes carries platform tokens
    );

    private static final String REDACTED = "[REDACTED]";
    private static final String REDACTED_TOOL_BODY = "[REDACTED:credential-tool]";

    /** Cap for redaction recursion depth to bound runtime on adversarial JSON. */
    private static final int MAX_DEPTH = 16;

    private final ObjectMapper objectMapper;

    public ToolCallRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Redact a JSON payload (string form). Used for the {@code tool_calls} and
     * {@code content} columns of {@code conversation.messages} that may carry
     * tool args/results.
     *
     * <p>If the input isn't valid JSON, it's returned unchanged - best-effort.
     * If the caller-known tool name is a credential tool (e.g. {@code credential},
     * {@code oauth2}), the entire body is replaced.
     */
    public String redactJsonString(String json, String toolName) {
        if (json == null || json.isBlank()) return json;

        if (toolName != null && isCredentialTool(toolName)) {
            return "\"" + REDACTED_TOOL_BODY + "\"";
        }

        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode redacted = redactNode(root, 0);
            return objectMapper.writeValueAsString(redacted);
        } catch (Exception e) {
            // Not JSON, or malformed - fall back to scrub-by-regex on the raw string
            // to catch the obvious secret patterns. Worst case: the field doesn't
            // match any regex and we return as-is (acceptable: the field-name layer
            // is the strict guarantee, this raw fallback is opportunistic).
            log.debug("ToolCallRedactor: payload not JSON, falling back to raw scrub: {}",
                    e.getMessage());
            return scrubRawString(json);
        }
    }

    /**
     * Redact a plain key/value map (used for tool args carried as {@code Map<String, Object>}).
     */
    public Object redactMap(Object value, String toolName) {
        if (value == null) return null;
        if (toolName != null && isCredentialTool(toolName)) return REDACTED_TOOL_BODY;

        try {
            String json = objectMapper.writeValueAsString(value);
            String redacted = redactJsonString(json, toolName);
            return objectMapper.readValue(redacted, Object.class);
        } catch (Exception e) {
            log.debug("ToolCallRedactor: failed to round-trip map, returning [REDACTED]: {}",
                    e.getMessage());
            return REDACTED;
        }
    }

    private boolean isCredentialTool(String toolName) {
        if (toolName == null || toolName.isBlank()) return false;
        String lower = toolName.toLowerCase();
        // Match exact or prefix-based: 'credential', 'credential.create', etc.
        for (String tool : CREDENTIAL_TOOLS) {
            if (lower.equals(tool) || lower.startsWith(tool + ".") || lower.startsWith(tool + "_")) {
                return true;
            }
        }
        return false;
    }

    private JsonNode redactNode(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) {
            // Defensive cap. At depth 16 we replace with a marker rather than recurse.
            return objectMapper.getNodeFactory().textNode(REDACTED);
        }
        if (node == null || node.isNull()) return node;

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            ObjectNode result = objectMapper.createObjectNode();
            obj.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                JsonNode val = entry.getValue();
                if (SECRET_FIELD.matcher(key).matches()) {
                    result.put(key, REDACTED);
                } else {
                    result.set(key, redactNode(val, depth + 1));
                }
            });
            return result;
        }

        if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            ArrayNode result = objectMapper.createArrayNode();
            for (JsonNode item : arr) {
                result.add(redactNode(item, depth + 1));
            }
            return result;
        }

        // Scalar - leave it. Field-name layer is what catches secrets;
        // raw scalar values are not scanned (a string "abc123" might be a
        // password OR a UUID - we cannot tell from value alone).
        return node;
    }

    /**
     * Last-resort scrub when the input isn't valid JSON. Replace the value
     * portion of obvious "secret: …" patterns with [REDACTED]. Won't catch
     * everything; that's why the JSON-aware path is preferred.
     */
    private String scrubRawString(String raw) {
        // "key": "value" or 'key': 'value' (loose)
        return raw.replaceAll(
                "(?i)\"(token|secret|password|api[_-]?key|authorization|bearer|refresh[_-]?token|access[_-]?token)\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"" + REDACTED + "\"");
    }
}
