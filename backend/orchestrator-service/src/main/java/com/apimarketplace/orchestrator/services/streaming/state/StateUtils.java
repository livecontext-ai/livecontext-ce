package com.apimarketplace.orchestrator.services.streaming.state;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Utility methods for workflow state management.
 * Provides sanitization, extraction, and filtering utilities used by RunStateStore.
 */
public final class StateUtils {

    private StateUtils() {
        // Utility class - no instantiation
    }

    /**
     * Fields to keep when sanitizing step payloads.
     * All other fields are stripped to reduce memory and network overhead.
     */
    public static final Set<String> STEP_FIELDS_TO_KEEP = Set.of(
        "runId",
        "stepAlias",
        "normalizedStepId",
        "originalStepId",
        "status",
        "uiStatus",
        "backendStatus",
        "message",
        "executionTime",
        "timestamp",
        "statusCounts",
        "triggerId",
        "loopId",
        "loopIteration",
        "loopNodeId",
        "loopIterationMetrics",
        "loopCompletedItems",
        "retryIndex",
        "maxRetries",
        "error",
        "errorType",
        "itemId",
        "itemIndex",
        "absoluteIndex",
        // Browser-agent live-view coordinates. Forwarded by
        // StepEventBuilder.appendItemMetadata for agent:browser_agent
        // step outputs so the frontend BrowserAgentNode can hand them
        // to BrowserLiveCdpPanel and open the WS bridge. Allowlisted
        // here so the sanitize pass doesn't strip them before they
        // reach the snapshot the SSE consumer reads.
        "session_id",
        "cdp_token",
        "cdp_ws_url",
        "step_index",
        "last_action",
        "cost_usd",
        "run_id",
        "node_id"
    );

    /**
     * Extract an integer value from payload by trying multiple keys.
     *
     * @param payload The source map
     * @param keys The keys to try in order
     * @return The extracted integer, or null if not found
     */
    public static Integer extractInteger(Map<String, Object> payload, String... keys) {
        for (String key : keys) {
            Object value = payload.get(key);
            if (value instanceof Number num) {
                return num.intValue();
            }
        }
        return null;
    }

    /**
     * Extract the effective status from a payload.
     * Prefers backendStatus over status if both are present.
     *
     * @param payload The source map
     * @return The effective status string (never null, may be empty)
     */
    public static String extractStatus(Map<String, Object> payload) {
        String status = Objects.toString(payload.get("status"), "");
        String backendStatus = Objects.toString(payload.get("backendStatus"), "");
        return !backendStatus.isEmpty() ? backendStatus : status;
    }

    /**
     * Strip the prefix from a value (e.g., "mcp:my_step" -> "my_step").
     *
     * @param value The value to strip
     * @return The stripped value, or null if input is null
     */
    public static String stripPrefix(String value) {
        if (value == null) return null;
        int idx = value.lastIndexOf(':');
        return idx > 0 ? value.substring(idx + 1) : value;
    }

    /**
     * Extract the node label from a step ID.
     * Same as stripPrefix but with a more descriptive name.
     *
     * @param stepId The step ID (e.g., "mcp:my_step")
     * @return The node label (e.g., "my_step")
     */
    public static String extractNodeLabel(String stepId) {
        return stripPrefix(stepId);
    }

    /**
     * Clone a value for safe storage.
     * Maps and Lists are shallow-copied.
     *
     * @param value The value to clone
     * @return A cloned copy of the value
     */
    @SuppressWarnings("unchecked")
    public static Object cloneValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    copy.put(String.valueOf(k), v);
                }
            });
            return copy;
        }
        if (value instanceof List<?> list) {
            return List.copyOf(list);
        }
        return value;
    }

    /**
     * Strip item scope suffix from an identifier.
     * Removes "#item-N" suffixes used for loop item tracking.
     *
     * @param candidate The value to process
     * @return The stripped value, or null if empty/null
     */
    public static String stripItemScope(Object candidate) {
        if (candidate == null) {
            return null;
        }
        String value = String.valueOf(candidate).trim();
        int itemIndex = value.indexOf("#item-");
        if (itemIndex > 0) {
            return value.substring(0, itemIndex);
        }
        return value.isEmpty() ? null : value;
    }

    /**
     * Check if an edge has any activity (running, completed, or skipped).
     *
     * @param payload The edge payload
     * @return true if the edge has any activity
     */
    public static boolean hasEdgeActivity(Map<String, Object> payload) {
        if (payload == null) {
            return false;
        }
        long running = toLong(payload.get("running"));
        long completed = toLong(payload.get("completed"));
        long skipped = toLong(payload.get("skipped"));
        return running > 0 || completed > 0 || skipped > 0;
    }

    /**
     * Convert a value to a long.
     * Handles Numbers and String representations.
     *
     * @param value The value to convert
     * @return The long value, or 0 if conversion fails
     */
    public static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String str) {
            try {
                return Long.parseLong(str);
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    /**
     * Checks if a node ID represents a virtual loop node that should be filtered from streaming.
     * Virtual loop nodes are internal implementation details and should not be exposed.
     *
     * Handles multiple formats:
     * - "core:while::controller" (with ::)
     * - "core:whilecontroller" (without :: - from edge generation)
     * - "core:while::condition_checker" (with ::)
     * - "core:whilecondition_checker" (without :: - from edge generation)
     *
     * @param nodeId The node ID to check
     * @return true if this is a virtual loop node
     */
    public static boolean isVirtualLoopNodeId(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return false;
        }
        // Check for both formats: with :: separator and without
        return nodeId.contains("::controller")
            || nodeId.contains("::condition_checker")
            || nodeId.endsWith("controller")  // catches "core:whilecontroller"
            || nodeId.endsWith("condition_checker")  // catches "core:whilecondition_checker"
            || nodeId.contains("controller->")  // catches edge IDs like "core:whilecontroller->..."
            || nodeId.contains("condition_checker->")  // catches edge IDs
            || nodeId.contains("->loop:whilecontroller")  // catches reverse edge IDs
            || nodeId.contains("->loop:whilecondition_checker");
    }

    /**
     * Sanitize a step payload to keep only relevant fields.
     *
     * @param stepId The step ID
     * @param payload The raw payload
     * @return A sanitized payload with only STEP_FIELDS_TO_KEEP
     */
    public static Map<String, Object> sanitizeStepPayload(String stepId, Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compact = new LinkedHashMap<>();
        for (String key : STEP_FIELDS_TO_KEEP) {
            Object value = payload.get(key);
            if (value != null) {
                compact.put(key, cloneValue(value));
            }
        }
        sanitizeIdentifiers(compact, stepId, payload);
        Object counts = payload.get("statusCounts");
        if (counts == null && payload.get("itemMetrics") instanceof Map<?, ?> metrics) {
            compact.put("statusCounts", cloneValue(metrics));
        }
        compact.putIfAbsent("runId", payload.get("runId"));
        compact.putIfAbsent("timestamp", payload.get("timestamp"));
        return compact;
    }

    /**
     * Sanitize identifiers in the target map.
     * Ensures normalizedStepId, stepAlias, and id are properly set.
     *
     * @param target The target map to update
     * @param stepId The original step ID
     * @param payload The source payload
     */
    public static void sanitizeIdentifiers(Map<String, Object> target,
                                           String stepId,
                                           Map<String, Object> payload) {
        String normalized = stripItemScope(target.get("normalizedStepId"));
        if (normalized == null) {
            normalized = stripItemScope(payload.get("normalizedStepId"));
        }
        if (normalized != null) {
            target.put("normalizedStepId", normalized);
        }

        String alias = stripItemScope(target.get("stepAlias"));
        if (alias == null) {
            alias = stripItemScope(payload.get("stepAlias"));
        }
        if (alias == null) {
            alias = stripItemScope(stepId);
        }
        if (alias == null) {
            alias = stepId;
        }
        target.put("stepAlias", alias);

        String identifier = normalized != null ? normalized : alias;
        if (identifier == null) {
            identifier = stripItemScope(stepId);
        }
        if (identifier == null) {
            identifier = stepId;
        }
        target.put("id", identifier);
        if (normalized == null) {
            target.put("normalizedStepId", identifier);
        }
    }
}
