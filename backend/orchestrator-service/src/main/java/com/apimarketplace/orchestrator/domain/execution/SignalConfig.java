package com.apimarketplace.orchestrator.domain.execution;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Type-safe configuration for signal waits.
 * Stored as JSONB in workflow_signal_waits.signal_config.
 *
 * Each signal type has a factory method that produces a config map
 * with type-specific fields, plus typed accessor methods for reading
 * the config back from JSONB.
 */
public final class SignalConfig {

    private SignalConfig() {
        // Utility class
    }

    // ========================================================================
    // FACTORY METHODS
    // ========================================================================

    /**
     * Create config for a timer signal.
     *
     * @param durationMs wait duration in milliseconds
     * @return config map for JSONB storage
     */
    public static Map<String, Object> timer(long durationMs) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", SignalType.WAIT_TIMER.name());
        config.put("durationMs", durationMs);
        return config;
    }

    /**
     * Create config for a user approval signal.
     *
     * @param approverRoles required roles (e.g., ["manager"])
     * @param requiredApprovals threshold for multi-level approval
     * @param timeout max wait time before auto-timeout
     * @return config map for JSONB storage
     */
    public static Map<String, Object> userApproval(
            List<String> approverRoles,
            int requiredApprovals,
            Duration timeout) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", SignalType.USER_APPROVAL.name());
        config.put("approverRoles", approverRoles != null ? approverRoles : List.of());
        config.put("requiredApprovals", Math.max(1, requiredApprovals));
        config.put("timeoutMs", timeout != null ? timeout.toMillis() : 86400000L); // default 24h
        config.put("receivedApprovals", List.of());
        return config;
    }

    /**
     * Create config for a user approval signal that is ALSO delegated to an
     * external channel (v1: Telegram inline buttons). The delegation map is the
     * node's delegation block with templates ALREADY resolved at yield time
     * (keys: channel, credentialId, chatId, message, image, allowedUserIds,
     * approveLabel, rejectLabel). Persisting
     * it in signal_config makes the outbound sender stateless and restart-safe:
     * everything the channel notifier needs rides with the signal row.
     *
     * @param approverRoles required roles (e.g., ["manager"])
     * @param requiredApprovals threshold for multi-level approval
     * @param timeout max wait time before auto-timeout
     * @param delegation resolved delegation block; null/empty = plain userApproval
     * @return config map for JSONB storage
     */
    public static Map<String, Object> userApprovalWithDelegation(
            List<String> approverRoles,
            int requiredApprovals,
            Duration timeout,
            Map<String, Object> delegation) {
        Map<String, Object> config = userApproval(approverRoles, requiredApprovals, timeout);
        if (delegation != null && !delegation.isEmpty()) {
            config.put("delegation", delegation);
        }
        return config;
    }

    /**
     * Create config for a webhook wait signal.
     *
     * @param webhookToken signed JWT token for webhook callback validation
     * @param timeout max wait time before auto-timeout
     * @return config map for JSONB storage
     */
    public static Map<String, Object> webhookWait(String webhookToken, Duration timeout) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", SignalType.WEBHOOK_WAIT.name());
        config.put("webhookToken", webhookToken);
        config.put("timeoutMs", timeout != null ? timeout.toMillis() : 3600000L); // default 1h
        return config;
    }

    /**
     * Create config for an interface signal.
     *
     * @param interfaceId UUID of the interface
     * @param actionMapping CSS selector to action target key mapping
     * @return config map for JSONB storage
     */
    public static Map<String, Object> interfaceSignal(String interfaceId, Map<String, String> actionMapping) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", SignalType.INTERFACE_SIGNAL.name());
        config.put("interfaceId", interfaceId);
        config.put("actionMapping", actionMapping != null ? actionMapping : Map.of());
        boolean hasContinue = actionMapping != null && actionMapping.containsValue("__continue");
        config.put("blocking", hasContinue);
        return config;
    }

    /**
     * Create config for an agent execution signal (async queue mode).
     *
     * @param correlationId unique ID linking the queue request to this signal
     * @param agentType     the agent type (agent, classify, guardrail)
     * @param provider      LLM provider name
     * @param model         LLM model name
     * @param timeout       max wait time before auto-timeout
     * @return config map for JSONB storage
     */
    public static Map<String, Object> agentExecution(
            String correlationId,
            String agentType,
            String provider,
            String model,
            Duration timeout) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", SignalType.AGENT_EXECUTION.name());
        config.put("correlationId", correlationId);
        config.put("agentType", agentType);
        config.put("provider", provider);
        config.put("model", model);
        config.put("timeoutMs", timeout != null ? timeout.toMillis() : 4200000L); // default 70min
        config.put("blocking", true);
        return config;
    }

    /**
     * Create config for a browser-agent user-takeover signal.
     *
     * Raised when the user clicks/types in the live CDP iframe - the
     * browser-use loop is paused and the user controls the Chromium tab
     * directly. The workflow blocks until the user resumes (mirrors the
     * INTERFACE_SIGNAL with __continue blocking semantic).
     *
     * @param sessionId    the browser-agent session id (matches the
     *                     {@code session_id} returned by the runner)
     * @param runId        the workflow run id
     * @param nodeId       the workflow node id
     * @param cdpToken     short-lived JWT (5 min TTL) the frontend uses to
     *                     authenticate the wss://websearch-host/cdp/{sid} upgrade
     * @param timeout      max idle time before auto-cancel; default 30 min
     * @return config map for JSONB storage
     */
    public static Map<String, Object> browserTakeover(
            String sessionId,
            String runId,
            String nodeId,
            String cdpToken,
            Duration timeout) {
        Map<String, Object> config = new HashMap<>();
        config.put("type", SignalType.BROWSER_USER_TAKEOVER.name());
        config.put("sessionId", sessionId);
        config.put("runId", runId);
        config.put("nodeId", nodeId);
        config.put("cdpToken", cdpToken);
        config.put("timeoutMs", timeout != null ? timeout.toMillis() : 1800000L); // default 30min
        config.put("blocking", true);
        return config;
    }

    // ========================================================================
    // TYPED ACCESSORS (for reading config from JSONB)
    // ========================================================================

    /**
     * Get the signal type from config.
     */
    public static SignalType getType(Map<String, Object> config) {
        if (config == null) return null;
        Object type = config.get("type");
        return type != null ? SignalType.valueOf(type.toString()) : null;
    }

    /**
     * Get timer duration in milliseconds.
     */
    public static long getDurationMs(Map<String, Object> config) {
        if (config == null) return 0;
        Object val = config.get("durationMs");
        return val instanceof Number n ? n.longValue() : 0;
    }

    /**
     * Get approver roles.
     */
    @SuppressWarnings("unchecked")
    public static List<String> getApproverRoles(Map<String, Object> config) {
        if (config == null) return List.of();
        Object val = config.get("approverRoles");
        return val instanceof List<?> list ? (List<String>) list : List.of();
    }

    /**
     * Get required approvals count.
     */
    public static int getRequiredApprovals(Map<String, Object> config) {
        if (config == null) return 1;
        Object val = config.get("requiredApprovals");
        return val instanceof Number n ? n.intValue() : 1;
    }

    /**
     * Get timeout in milliseconds.
     */
    public static long getTimeoutMs(Map<String, Object> config) {
        if (config == null) return 0;
        Object val = config.get("timeoutMs");
        return val instanceof Number n ? n.longValue() : 0;
    }

    /**
     * Get webhook token.
     */
    public static String getWebhookToken(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("webhookToken");
        return val != null ? val.toString() : null;
    }

    /**
     * Get interface ID from config.
     */
    public static String getInterfaceId(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("interfaceId");
        return val != null ? val.toString() : null;
    }

    /**
     * Get action mapping from config.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, String> getActionMapping(Map<String, Object> config) {
        if (config == null) return Map.of();
        Object val = config.get("actionMapping");
        return val instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    /**
     * Get the resolved external-channel delegation block (USER_APPROVAL only).
     * Null when the approval is not delegated.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> getDelegation(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("delegation");
        return val instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /**
     * Get received approvals list (for multi-level approval).
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getReceivedApprovals(Map<String, Object> config) {
        if (config == null) return List.of();
        Object val = config.get("receivedApprovals");
        return val instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    /**
     * Get correlationId from config (for AGENT_EXECUTION signals).
     */
    public static String getCorrelationId(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("correlationId");
        return val != null ? val.toString() : null;
    }

    /**
     * Get agent type from config (for AGENT_EXECUTION signals).
     */
    public static String getAgentType(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("agentType");
        return val != null ? val.toString() : null;
    }

    /**
     * Get LLM provider from config (for AGENT_EXECUTION signals).
     */
    public static String getProvider(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("provider");
        return val != null ? val.toString() : null;
    }

    /**
     * Get LLM model from config (for AGENT_EXECUTION signals).
     */
    public static String getModel(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("model");
        return val != null ? val.toString() : null;
    }

    /**
     * Get browser-agent session id (for BROWSER_USER_TAKEOVER signals).
     */
    public static String getSessionId(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("sessionId");
        return val != null ? val.toString() : null;
    }

    /**
     * Get browser-agent runId (for BROWSER_USER_TAKEOVER signals).
     */
    public static String getRunId(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("runId");
        return val != null ? val.toString() : null;
    }

    /**
     * Get browser-agent nodeId (for BROWSER_USER_TAKEOVER signals).
     */
    public static String getNodeId(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("nodeId");
        return val != null ? val.toString() : null;
    }

    /**
     * Get the CDP authentication JWT (for BROWSER_USER_TAKEOVER signals).
     */
    public static String getCdpToken(Map<String, Object> config) {
        if (config == null) return null;
        Object val = config.get("cdpToken");
        return val != null ? val.toString() : null;
    }

    /**
     * Check if the signal config indicates a blocking signal.
     * Default is true (all signals are blocking unless explicitly marked non-blocking).
     * Interface signals without __continue in actionMapping are non-blocking.
     */
    public static boolean isBlocking(Map<String, Object> config) {
        if (config == null) return true;
        Object val = config.get("blocking");
        return !(val instanceof Boolean b) || b;
    }
}
