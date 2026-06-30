package com.apimarketplace.orchestrator.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized logging for agent sessions.
 * Tracks ALL tool calls to understand agent decision-making.
 *
 * Log format:
 * [AGENT] [conversation-id] timestamp TOOL_NAME(params) → result/error
 *
 * Usage:
 *   // At conversation start
 *   logger.startConversation(conversationId, userMessage);
 *
 *   // Before each tool call
 *   String callId = logger.logToolCall(conversationId, toolName, params);
 *
 *   // After tool execution
 *   logger.logToolResult(callId, result, success);
 *
 *   // At conversation end
 *   logger.endConversation(conversationId);
 *   String report = logger.getConversationReport(conversationId);
 */
@Slf4j
@Component
public class AgentSessionLogger {

    private static final String LOG_PREFIX = "[AGENT]";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    // Store logs per conversation
    private final Map<String, ConversationLog> conversationLogs = new ConcurrentHashMap<>();

    // ==================== DATA STRUCTURES ====================

    public record ToolCall(
        String callId,
        Instant timestamp,
        String toolName,
        Map<String, Object> parameters,
        Object result,
        boolean success,
        String error,
        long durationMs
    ) {
        public String formatCompact() {
            String time = TIME_FMT.format(timestamp);
            String status = success ? "✓" : "✗";
            String params = formatParams(parameters);
            String duration = durationMs > 0 ? String.format(" (%dms)", durationMs) : "";

            if (success) {
                String resultStr = formatResult(result);
                return String.format("%s %s %s(%s)%s → %s", time, status, toolName, params, duration, resultStr);
            } else {
                return String.format("%s %s %s(%s)%s → ERROR: %s", time, status, toolName, params, duration, error);
            }
        }

        private String formatParams(Map<String, Object> params) {
            if (params == null || params.isEmpty()) return "";

            List<String> parts = new ArrayList<>();
            for (var entry : params.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();

                // Skip large objects, show key info only
                if (value instanceof Map || value instanceof List) {
                    parts.add(key + "={...}");
                } else if (value instanceof String s && s.length() > 30) {
                    parts.add(key + "=\"" + s.substring(0, 27) + "...\"");
                } else if (value != null) {
                    parts.add(key + "=" + value);
                }
            }
            return String.join(", ", parts);
        }

        private String formatResult(Object result) {
            if (result == null) return "null";
            if (result instanceof Map<?, ?> map) {
                // Extract key fields
                List<String> keys = new ArrayList<>();
                if (map.containsKey("status")) keys.add("status=" + map.get("status"));
                if (map.containsKey("node_id")) keys.add("node=" + map.get("node_id"));
                if (map.containsKey("workflow_id")) keys.add("workflow=" + map.get("workflow_id"));
                if (map.containsKey("count")) keys.add("count=" + map.get("count"));
                if (map.containsKey("message")) {
                    String msg = String.valueOf(map.get("message"));
                    if (msg.length() > 40) msg = msg.substring(0, 37) + "...";
                    keys.add("\"" + msg + "\"");
                }
                return keys.isEmpty() ? "{...}" : "{" + String.join(", ", keys) + "}";
            }
            String str = result.toString();
            return str.length() > 50 ? str.substring(0, 47) + "..." : str;
        }
    }

    public static class ConversationLog {
        private final String conversationId;
        private final Instant startTime;
        private final String userMessage;
        private final List<ToolCall> toolCalls = new ArrayList<>();
        private final Map<String, Instant> pendingCalls = new HashMap<>();
        private Instant endTime;
        private String endReason;

        public ConversationLog(String conversationId, String userMessage) {
            this.conversationId = conversationId;
            this.startTime = Instant.now();
            this.userMessage = userMessage;
        }

        public String getConversationId() { return conversationId; }
        public Instant getStartTime() { return startTime; }
        public String getUserMessage() { return userMessage; }
        public List<ToolCall> getToolCalls() { return toolCalls; }
        public Instant getEndTime() { return endTime; }

        void setEndTime(Instant endTime) { this.endTime = endTime; }
        void setEndReason(String reason) { this.endReason = reason; }
        String getEndReason() { return endReason; }

        void addPendingCall(String callId) {
            pendingCalls.put(callId, Instant.now());
        }

        Instant removePendingCall(String callId) {
            return pendingCalls.remove(callId);
        }

        void addToolCall(ToolCall call) {
            toolCalls.add(call);
        }
    }

    // ==================== CONVERSATION LIFECYCLE ====================

    /**
     * Start tracking a new conversation.
     */
    public void startConversation(String conversationId, String userMessage) {
        ConversationLog convLog = new ConversationLog(conversationId, userMessage);
        conversationLogs.put(conversationId, convLog);

        String truncatedMsg = userMessage.length() > 60
            ? userMessage.substring(0, 57) + "..."
            : userMessage;

        log.info("{} ════════════════════════════════════════════════════════════════", LOG_PREFIX);
        log.info("{} [{}] Conversation started", LOG_PREFIX, shortId(conversationId));
        log.info("{} [{}] User: \"{}\"", LOG_PREFIX, shortId(conversationId), truncatedMsg);
        log.info("{} ════════════════════════════════════════════════════════════════", LOG_PREFIX);
    }

    /**
     * End conversation tracking.
     */
    public void endConversation(String conversationId, String reason) {
        ConversationLog convLog = conversationLogs.get(conversationId);
        if (convLog == null) return;

        convLog.setEndTime(Instant.now());
        convLog.setEndReason(reason);

        // Log summary
        long totalCalls = convLog.getToolCalls().size();
        long successCalls = convLog.getToolCalls().stream().filter(ToolCall::success).count();
        long failedCalls = totalCalls - successCalls;
        long durationSec = Duration.between(convLog.getStartTime(), convLog.getEndTime()).toSeconds();

        // Tool usage breakdown
        Map<String, Long> toolUsage = new LinkedHashMap<>();
        for (ToolCall call : convLog.getToolCalls()) {
            toolUsage.merge(call.toolName(), 1L, Long::sum);
        }

        log.info("{} ────────────────────────────────────────────────────────────────", LOG_PREFIX);
        log.info("{} [{}] Conversation ended: {}", LOG_PREFIX, shortId(conversationId), reason);
        log.info("{} [{}] Duration: {}s | Tool calls: {} ({} success, {} failed)",
            LOG_PREFIX, shortId(conversationId), durationSec, totalCalls, successCalls, failedCalls);

        if (!toolUsage.isEmpty()) {
            List<String> usage = new ArrayList<>();
            for (var entry : toolUsage.entrySet()) {
                usage.add(entry.getKey() + ":" + entry.getValue());
            }
            log.info("{} [{}] Tools used: {}", LOG_PREFIX, shortId(conversationId), String.join(", ", usage));
        }

        log.info("{} ════════════════════════════════════════════════════════════════", LOG_PREFIX);
    }

    // ==================== TOOL CALL LOGGING ====================

    /**
     * Log start of a tool call. Returns a callId to use with logToolResult.
     */
    public String logToolCall(String conversationId, String toolName, Map<String, Object> parameters) {
        String callId = UUID.randomUUID().toString().substring(0, 8);

        ConversationLog convLog = conversationLogs.get(conversationId);
        if (convLog != null) {
            convLog.addPendingCall(callId);
        }

        // Log the call
        String params = formatParamsForLog(toolName, parameters);
        log.info("{} [{}] → {}({})", LOG_PREFIX, shortId(conversationId), toolName, params);

        return callId;
    }

    /**
     * Log result of a tool call.
     */
    public void logToolResult(String conversationId, String callId, String toolName,
                               Map<String, Object> parameters, Object result,
                               boolean success, String error) {

        ConversationLog convLog = conversationLogs.get(conversationId);
        long durationMs = 0;

        if (convLog != null) {
            Instant startTime = convLog.removePendingCall(callId);
            if (startTime != null) {
                durationMs = Duration.between(startTime, Instant.now()).toMillis();
            }

            ToolCall toolCall = new ToolCall(
                callId,
                Instant.now(),
                toolName,
                parameters,
                result,
                success,
                error,
                durationMs
            );
            convLog.addToolCall(toolCall);
        }

        // Log the result
        String status = success ? "✓" : "✗";
        String resultStr = formatResultForLog(result, error, success);
        String duration = durationMs > 0 ? String.format(" [%dms]", durationMs) : "";

        log.info("{} [{}] {} ← {}{}", LOG_PREFIX, shortId(conversationId), status, resultStr, duration);
    }

    /**
     * Quick method to log a complete tool call (start + result).
     */
    public void logToolExecution(String conversationId, String toolName,
                                  Map<String, Object> parameters, Object result,
                                  boolean success, String error, long durationMs) {

        ConversationLog convLog = conversationLogs.get(conversationId);
        String callId = UUID.randomUUID().toString().substring(0, 8);

        if (convLog != null) {
            ToolCall toolCall = new ToolCall(
                callId,
                Instant.now(),
                toolName,
                parameters,
                result,
                success,
                error,
                durationMs
            );
            convLog.addToolCall(toolCall);
        }

        // Single line log
        String status = success ? "✓" : "✗";
        String params = formatParamsForLog(toolName, parameters);
        String resultStr = formatResultForLog(result, error, success);
        String duration = durationMs > 0 ? String.format(" [%dms]", durationMs) : "";

        log.info("{} [{}] {} {}({}) → {}{}",
            LOG_PREFIX, shortId(conversationId), status, toolName, params, resultStr, duration);
    }

    // ==================== REPORT GENERATION ====================

    /**
     * Get a detailed report for a conversation.
     */
    public String getConversationReport(String conversationId) {
        ConversationLog convLog = conversationLogs.get(conversationId);
        if (convLog == null) {
            return "No logs for conversation: " + conversationId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append("AGENT SESSION REPORT\n");
        sb.append("═══════════════════════════════════════════════════════════════\n\n");

        sb.append("Conversation ID: ").append(conversationId).append("\n");
        sb.append("User Message: \"").append(convLog.getUserMessage()).append("\"\n");
        sb.append("Started: ").append(TIME_FMT.format(convLog.getStartTime())).append("\n");

        if (convLog.getEndTime() != null) {
            sb.append("Ended: ").append(TIME_FMT.format(convLog.getEndTime())).append("\n");
            sb.append("Reason: ").append(convLog.getEndReason()).append("\n");
            long durationSec = Duration.between(convLog.getStartTime(), convLog.getEndTime()).toSeconds();
            sb.append("Duration: ").append(durationSec).append("s\n");
        }

        sb.append("\n───────────────────────────────────────────────────────────────\n");
        sb.append("TOOL CALLS (").append(convLog.getToolCalls().size()).append(" total)\n");
        sb.append("───────────────────────────────────────────────────────────────\n\n");

        int callNum = 1;
        for (ToolCall call : convLog.getToolCalls()) {
            sb.append(String.format("%2d. %s\n", callNum++, call.formatCompact()));
        }

        sb.append("\n───────────────────────────────────────────────────────────────\n");
        sb.append("TOOL USAGE SUMMARY\n");
        sb.append("───────────────────────────────────────────────────────────────\n\n");

        Map<String, int[]> toolStats = new LinkedHashMap<>(); // [success, failure]
        for (ToolCall call : convLog.getToolCalls()) {
            int[] stats = toolStats.computeIfAbsent(call.toolName(), k -> new int[]{0, 0});
            if (call.success()) {
                stats[0]++;
            } else {
                stats[1]++;
            }
        }

        for (var entry : toolStats.entrySet()) {
            int[] stats = entry.getValue();
            int total = stats[0] + stats[1];
            sb.append(String.format("  %-30s %d calls (%d success, %d failed)\n",
                entry.getKey(), total, stats[0], stats[1]));
        }

        sb.append("\n═══════════════════════════════════════════════════════════════\n");

        return sb.toString();
    }

    /**
     * Get active conversation IDs.
     */
    public Set<String> getActiveConversations() {
        return conversationLogs.keySet();
    }

    /**
     * Clear logs for a conversation.
     */
    public void clearConversation(String conversationId) {
        conversationLogs.remove(conversationId);
    }

    // ==================== HELPER METHODS ====================

    private String shortId(String id) {
        if (id == null) return "no-conv";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private String formatParamsForLog(String toolName, Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";

        // For workflow, show action prominently
        if ("workflow".equals(toolName)) {
            String action = (String) params.get("action");
            if (action != null) {
                List<String> otherParams = new ArrayList<>();
                for (var entry : params.entrySet()) {
                    if (!"action".equals(entry.getKey()) && entry.getValue() != null) {
                        otherParams.add(formatSingleParam(entry.getKey(), entry.getValue()));
                    }
                }
                return otherParams.isEmpty()
                    ? "action=" + action
                    : "action=" + action + ", " + String.join(", ", otherParams);
            }
        }

        // For catalog, show query
        if ("catalog".equals(toolName)) {
            String query = (String) params.get("query");
            if (query != null) {
                return "query=\"" + truncate(query, 30) + "\"";
            }
        }

        // Generic formatting
        List<String> parts = new ArrayList<>();
        int count = 0;
        for (var entry : params.entrySet()) {
            if (count++ >= 3) {
                parts.add("...");
                break;
            }
            parts.add(formatSingleParam(entry.getKey(), entry.getValue()));
        }
        return String.join(", ", parts);
    }

    private String formatSingleParam(String key, Object value) {
        if (value == null) return key + "=null";
        if (value instanceof String s) {
            return key + "=\"" + truncate(s, 20) + "\"";
        }
        if (value instanceof Map || value instanceof List) {
            return key + "={...}";
        }
        return key + "=" + value;
    }

    @SuppressWarnings("unchecked")
    private String formatResultForLog(Object result, String error, boolean success) {
        if (!success) {
            return "ERROR: " + truncate(error, 50);
        }
        if (result instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) result;
            List<String> parts = new ArrayList<>();

            // Key fields to show
            if (map.containsKey("status")) parts.add("status=" + map.get("status"));
            if (map.containsKey("node_id")) parts.add("node=" + map.get("node_id"));
            if (map.containsKey("session_id")) parts.add("session=" + shortId((String) map.get("session_id")));
            if (map.containsKey("workflow_id")) parts.add("workflow=" + shortId((String) map.get("workflow_id")));
            if (map.containsKey("count")) parts.add("count=" + map.get("count"));

            // Message as last
            if (map.containsKey("message")) {
                parts.add("\"" + truncate(String.valueOf(map.get("message")), 30) + "\"");
            }

            return parts.isEmpty() ? "OK" : String.join(", ", parts);
        }
        return truncate(String.valueOf(result), 50);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}
