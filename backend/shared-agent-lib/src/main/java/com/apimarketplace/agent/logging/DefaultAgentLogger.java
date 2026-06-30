package com.apimarketplace.agent.logging;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default implementation of AgentLogger.
 * Logs to console with a structured format and stores logs for retrieval.
 */
@Slf4j
@Component
public class DefaultAgentLogger implements AgentLogger {

    private static final String PREFIX = "[AGENT]";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    // Store logs per run for retrieval
    private final Map<String, RunLog> runLogs = new ConcurrentHashMap<>();

    // ==================== LOG ENTRY ====================

    public record LogEntry(
        Instant timestamp,
        String type,  // EXECUTION_START, TOOL_CALL, TOOL_RESULT, ITERATION, ERROR, EXECUTION_END
        String toolName,
        Map<String, Object> arguments,
        Object result,
        boolean success,
        String error,
        long durationMs,
        Map<String, Object> metadata
    ) {
        public String format() {
            String time = TIME_FMT.format(timestamp);
            String status = success ? "✓" : "✗";

            return switch (type) {
                case "EXECUTION_START" -> String.format("%s START | User: \"%s\"", time, truncate((String) metadata.get("userPrompt"), 50));
                case "ITERATION" -> String.format("%s ITER #%d | %d tool calls", time, metadata.get("iteration"), metadata.get("toolCalls"));
                case "TOOL_CALL" -> String.format("%s → %s(%s)", time, toolName, formatArgs(arguments));
                case "TOOL_RESULT" -> String.format("%s %s ← %s [%dms]", time, status, formatResult(result, error), durationMs);
                case "ERROR" -> String.format("%s ✗ ERROR: %s", time, error);
                case "EXECUTION_END" -> String.format("%s END | %s | %d iterations, %d tools [%dms]",
                    time, metadata.get("reason"), metadata.get("iterations"), metadata.get("toolCalls"), durationMs);
                default -> String.format("%s %s", time, type);
            };
        }

        private String formatArgs(Map<String, Object> args) {
            if (args == null || args.isEmpty()) return "";
            List<String> parts = new ArrayList<>();
            for (var entry : args.entrySet()) {
                Object val = entry.getValue();
                if (val instanceof String s) {
                    parts.add(entry.getKey() + "=\"" + truncate(s, 500) + "\"");
                } else if (val instanceof Map || val instanceof List) {
                    String str = val.toString();
                    parts.add(entry.getKey() + "=" + truncate(str, 1000));
                } else {
                    parts.add(entry.getKey() + "=" + val);
                }
            }
            return String.join(", ", parts);
        }

        private String formatResult(Object res, String err) {
            if (!success && err != null) return "ERROR: " + truncate(err, 2000);
            if (res == null) return "OK";
            return truncate(String.valueOf(res), 5000);
        }

        private String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() > max ? s.substring(0, max - 3) + "..." : s;
        }
    }

    public static class RunLog {
        private final String runId;
        private final Instant startTime;
        private final List<LogEntry> entries = new ArrayList<>();
        private String userPrompt;
        private String provider;
        private String model;

        public RunLog(String runId) {
            this.runId = runId;
            this.startTime = Instant.now();
        }

        public void setUserPrompt(String userPrompt) { this.userPrompt = userPrompt; }
        public void setProvider(String provider) { this.provider = provider; }
        public void setModel(String model) { this.model = model; }

        public String getRunId() { return runId; }
        public Instant getStartTime() { return startTime; }
        public List<LogEntry> getEntries() { return entries; }
        public String getUserPrompt() { return userPrompt; }
        public String getProvider() { return provider; }
        public String getModel() { return model; }

        void addEntry(LogEntry entry) { entries.add(entry); }
    }

    // ==================== LOGGING METHODS ====================

    @Override
    public void logExecutionStart(String runId, String userPrompt, String provider, String model) {
        RunLog runLog = new RunLog(runId);
        runLog.setUserPrompt(userPrompt);
        runLog.setProvider(provider);
        runLog.setModel(model);
        runLogs.put(runId, runLog);

        LogEntry entry = new LogEntry(
            Instant.now(), "EXECUTION_START", null, null, null, true, null, 0,
            Map.of("userPrompt", userPrompt != null ? userPrompt : "", "provider", provider, "model", model)
        );
        runLog.addEntry(entry);

        String shortPrompt = userPrompt != null && userPrompt.length() > 60
            ? userPrompt.substring(0, 57) + "..."
            : userPrompt;

        log.info("{} ════════════════════════════════════════════════════════════════", PREFIX);
        log.info("{} [{}] Agent started | {} / {}", PREFIX, shortId(runId), provider, model);
        log.info("{} [{}] User: \"{}\"", PREFIX, shortId(runId), shortPrompt);
        log.info("{} ════════════════════════════════════════════════════════════════", PREFIX);
    }

    @Override
    public void logExecutionEnd(String runId, boolean success, int iterations,
                                 int toolCalls, long durationMs, String reason) {
        RunLog runLog = runLogs.get(runId);
        if (runLog != null) {
            LogEntry entry = new LogEntry(
                Instant.now(), "EXECUTION_END", null, null, null, success, null, durationMs,
                Map.of("iterations", iterations, "toolCalls", toolCalls, "reason", reason)
            );
            runLog.addEntry(entry);
        }

        String status = success ? "✓" : "✗";
        log.info("{} ────────────────────────────────────────────────────────────────", PREFIX);
        log.info("{} [{}] {} Agent ended | {} | {} iterations, {} tool calls [{}ms]",
            PREFIX, shortId(runId), status, reason, iterations, toolCalls, durationMs);
        log.info("{} ════════════════════════════════════════════════════════════════", PREFIX);
    }

    @Override
    public void logToolCallStart(String runId, ToolCall toolCall) {
        RunLog runLog = runLogs.get(runId);
        if (runLog != null) {
            LogEntry entry = new LogEntry(
                Instant.now(), "TOOL_CALL", toolCall.toolName(), toolCall.arguments(),
                null, true, null, 0, Map.of()
            );
            runLog.addEntry(entry);
        }

        String args = formatArgsForLog(toolCall.arguments());
        log.info("{} [{}] → {}({})", PREFIX, shortId(runId), toolCall.toolName(), args);
    }

    @Override
    public void logToolCallEnd(String runId, ToolCall toolCall, ToolResult result, long durationMs) {
        RunLog runLog = runLogs.get(runId);
        boolean success = result.success();

        if (runLog != null) {
            LogEntry entry = new LogEntry(
                Instant.now(), "TOOL_RESULT", toolCall.toolName(), toolCall.arguments(),
                result.content(), success, result.error(), durationMs, Map.of()
            );
            runLog.addEntry(entry);
        }

        String status = success ? "✓" : "✗";
        String resultStr = formatResultForLog(result);
        log.info("{} [{}] {} ← {} [{}ms]", PREFIX, shortId(runId), status, resultStr, durationMs);
    }

    @Override
    public void logIteration(String runId, int iteration, int toolCallsCount) {
        RunLog runLog = runLogs.get(runId);
        if (runLog != null) {
            LogEntry entry = new LogEntry(
                Instant.now(), "ITERATION", null, null, null, true, null, 0,
                Map.of("iteration", iteration, "toolCalls", toolCallsCount)
            );
            runLog.addEntry(entry);
        }

        log.info("{} [{}] ─── Iteration #{} | {} tool call(s) ───",
            PREFIX, shortId(runId), iteration, toolCallsCount);
    }

    @Override
    public void logError(String runId, String message, Throwable error) {
        RunLog runLog = runLogs.get(runId);
        if (runLog != null) {
            LogEntry entry = new LogEntry(
                Instant.now(), "ERROR", null, null, null, false, message, 0, Map.of()
            );
            runLog.addEntry(entry);
        }

        log.error("{} [{}] ✗ {}", PREFIX, shortId(runId), message);
        if (error != null) {
            log.debug("{} [{}] Stack trace:", PREFIX, shortId(runId), error);
        }
    }

    // ==================== REPORT METHODS ====================

    /**
     * Get the log for a specific run.
     */
    public RunLog getRunLog(String runId) {
        return runLogs.get(runId);
    }

    /**
     * Get a formatted report for a run.
     */
    public String getReport(String runId) {
        RunLog runLog = runLogs.get(runId);
        if (runLog == null) return "No logs for run: " + runId;

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════════════════════════════════════════════\n");
        sb.append("AGENT EXECUTION REPORT\n");
        sb.append("═══════════════════════════════════════════════════════════════\n\n");
        sb.append("Run ID: ").append(runId).append("\n");
        sb.append("Provider: ").append(runLog.getProvider()).append(" / ").append(runLog.getModel()).append("\n");
        sb.append("User: \"").append(runLog.getUserPrompt()).append("\"\n");
        sb.append("Started: ").append(TIME_FMT.format(runLog.getStartTime())).append("\n\n");

        sb.append("───────────────────────────────────────────────────────────────\n");
        sb.append("EXECUTION LOG\n");
        sb.append("───────────────────────────────────────────────────────────────\n\n");

        for (LogEntry entry : runLog.getEntries()) {
            sb.append(entry.format()).append("\n");
        }

        sb.append("\n═══════════════════════════════════════════════════════════════\n");
        return sb.toString();
    }

    /**
     * Clear logs for a run.
     */
    public void clearRunLog(String runId) {
        runLogs.remove(runId);
    }

    // ==================== HELPERS ====================

    private String shortId(String id) {
        if (id == null) return "no-run";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    private String formatArgsForLog(Map<String, Object> args) {
        if (args == null || args.isEmpty()) return "";

        // Special handling for workflow
        if (args.containsKey("action")) {
            String action = String.valueOf(args.get("action"));
            List<String> others = new ArrayList<>();
            for (var entry : args.entrySet()) {
                if (!"action".equals(entry.getKey()) && entry.getValue() != null) {
                    others.add(formatSingleArg(entry.getKey(), entry.getValue()));
                }
            }
            return others.isEmpty() ? "action=" + action : "action=" + action + ", " + String.join(", ", others);
        }

        List<String> parts = new ArrayList<>();
        for (var entry : args.entrySet()) {
            parts.add(formatSingleArg(entry.getKey(), entry.getValue()));
        }
        return String.join(", ", parts);
    }

    private String formatSingleArg(String key, Object value) {
        if (value == null) return key + "=null";
        if (value instanceof String s) {
            String truncated = s.length() > 500 ? s.substring(0, 497) + "..." : s;
            return key + "=\"" + truncated + "\"";
        }
        if (value instanceof Map m) {
            String mapStr = m.toString();
            return key + "=" + (mapStr.length() > 1000 ? mapStr.substring(0, 997) + "..." : mapStr);
        }
        if (value instanceof List l) {
            String listStr = l.toString();
            return key + "=" + (listStr.length() > 1000 ? listStr.substring(0, 997) + "..." : listStr);
        }
        return key + "=" + value;
    }

    private String formatResultForLog(ToolResult result) {
        if (!result.success()) {
            String err = result.error();
            return "ERROR: " + (err != null && err.length() > 2000 ? err.substring(0, 1997) + "..." : err);
        }
        Object content = result.content();
        if (content == null) return "OK";
        // Always show full content (up to 5000 chars) for debugging
        String str = content.toString();
        return str.length() > 5000 ? str.substring(0, 4997) + "..." : str;
    }
}
