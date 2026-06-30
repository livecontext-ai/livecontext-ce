package com.apimarketplace.orchestrator.tools.workflow.builder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized logging system for workflow builder sessions.
 * Tracks all actions, state transitions, and provides session summaries.
 *
 * Usage:
 *   logger.logAction(session, "add_mcp", params, result);
 *   logger.logError(session, "add_mcp", error);
 *   logger.getSessionLog(sessionId); // Get full log for review
 */
@Slf4j
@Component
public class WorkflowBuilderLogger {

    private static final String LOG_PREFIX = "[WF-BUILDER]";
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    // Store logs per session for review
    private final Map<String, List<LogEntry>> sessionLogs = new ConcurrentHashMap<>();

    // ==================== LOG ENTRY ====================

    public record LogEntry(
        Instant timestamp,
        String action,
        WorkflowBuilderPrompts.Phase phaseBefore,
        WorkflowBuilderPrompts.Phase phaseAfter,
        String nodeId,
        int nodeCount,
        int edgeCount,
        boolean success,
        String message,
        Map<String, Object> details
    ) {
        public String format() {
            String time = TIME_FMT.format(timestamp);
            String status = success ? "✓" : "✗";
            String phaseChange = phaseBefore != phaseAfter
                ? String.format(" [%s→%s]", phaseBefore, phaseAfter)
                : String.format(" [%s]", phaseAfter);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s %s %s%s", time, status, action, phaseChange));

            if (nodeId != null) {
                sb.append(" → ").append(nodeId);
            }

            sb.append(String.format(" (nodes:%d, edges:%d)", nodeCount, edgeCount));

            if (message != null && !message.isBlank()) {
                sb.append(" | ").append(message);
            }

            return sb.toString();
        }
    }

    // ==================== LOGGING METHODS ====================

    /**
     * Log an action execution (success or failure).
     */
    public void logAction(WorkflowBuilderSession session,
                          String action,
                          Map<String, Object> params,
                          Object result,
                          boolean success,
                          String message) {

        if (session == null) {
            logNoSession(action, params, success, message);
            return;
        }

        WorkflowBuilderPrompts.Phase phaseBefore = getPhaseFromHistory(session);
        WorkflowBuilderPrompts.Phase phaseAfter = WorkflowBuilderPrompts.detectPhase(session);

        String nodeId = extractNodeId(result);
        int nodeCount = countNodes(session);
        int edgeCount = session.getEdges().size();

        LogEntry entry = new LogEntry(
            Instant.now(),
            action,
            phaseBefore,
            phaseAfter,
            nodeId,
            nodeCount,
            edgeCount,
            success,
            message,
            extractDetails(params, result)
        );

        // Store in session logs
        sessionLogs.computeIfAbsent(session.getSessionId(), k -> new ArrayList<>()).add(entry);

        // Log to console/file
        String logLine = String.format("%s [%s] %s", LOG_PREFIX, session.getSessionId().substring(0, 8), entry.format());

        if (success) {
            log.info(logLine);
        } else {
            log.warn(logLine);
        }

        // Log phase transition if changed
        if (phaseBefore != phaseAfter) {
            log.info("{} [{}] Phase transition: {} → {}",
                LOG_PREFIX, session.getSessionId().substring(0, 8), phaseBefore, phaseAfter);
        }
    }

    /**
     * Log action for operations without a session (init, insert_after).
     */
    public void logNoSession(String action, Map<String, Object> params, boolean success, String message) {
        String status = success ? "✓" : "✗";
        String time = TIME_FMT.format(Instant.now());

        String logLine = String.format("%s [no-session] %s %s %s", LOG_PREFIX, time, status, action);
        if (message != null) {
            logLine += " | " + message;
        }

        if (success) {
            log.info(logLine);
        } else {
            log.warn(logLine);
        }
    }

    /**
     * Log an error with stack trace.
     */
    public void logError(WorkflowBuilderSession session, String action, Throwable error) {
        String sessionId = session != null ? session.getSessionId().substring(0, 8) : "no-session";
        log.error("{} [{}] Error in {}: {}", LOG_PREFIX, sessionId, action, error.getMessage(), error);

        if (session != null) {
            LogEntry entry = new LogEntry(
                Instant.now(),
                action,
                WorkflowBuilderPrompts.detectPhase(session),
                WorkflowBuilderPrompts.detectPhase(session),
                null,
                countNodes(session),
                session.getEdges().size(),
                false,
                "ERROR: " + error.getMessage(),
                Map.of("exception", error.getClass().getSimpleName())
            );
            sessionLogs.computeIfAbsent(session.getSessionId(), k -> new ArrayList<>()).add(entry);
        }
    }

    /**
     * Log session start.
     */
    public void logSessionStart(WorkflowBuilderSession session) {
        log.info("{} ══════════════════════════════════════════════════", LOG_PREFIX);
        log.info("{} Session started: {} ({})", LOG_PREFIX, session.getWorkflowName(), session.getSessionId());
        log.info("{} ══════════════════════════════════════════════════", LOG_PREFIX);

        sessionLogs.put(session.getSessionId(), new ArrayList<>());
    }

    /**
     * Log session end (create, discard, or save).
     */
    public void logSessionEnd(WorkflowBuilderSession session, String reason) {
        String sessionId = session.getSessionId();
        List<LogEntry> logs = sessionLogs.get(sessionId);

        log.info("{} ──────────────────────────────────────────────────", LOG_PREFIX);
        log.info("{} Session ended: {} | Reason: {}", LOG_PREFIX, session.getWorkflowName(), reason);

        if (logs != null) {
            // Summary stats
            long successCount = logs.stream().filter(LogEntry::success).count();
            long failureCount = logs.stream().filter(e -> !e.success()).count();

            log.info("{} Summary: {} actions ({} success, {} failures)",
                LOG_PREFIX, logs.size(), successCount, failureCount);

            // Phase transitions
            List<String> phaseChanges = new ArrayList<>();
            WorkflowBuilderPrompts.Phase lastPhase = null;
            for (LogEntry entry : logs) {
                if (lastPhase == null || entry.phaseBefore() != lastPhase) {
                    phaseChanges.add(entry.phaseBefore().name());
                }
                if (entry.phaseAfter() != entry.phaseBefore()) {
                    phaseChanges.add(entry.phaseAfter().name());
                }
                lastPhase = entry.phaseAfter();
            }
            log.info("{} Phase flow: {}", LOG_PREFIX, String.join(" → ", phaseChanges));
        }

        log.info("{} ══════════════════════════════════════════════════", LOG_PREFIX);
    }

    // ==================== SESSION LOG RETRIEVAL ====================

    /**
     * Get the full log for a session (for review).
     */
    public List<LogEntry> getSessionLog(String sessionId) {
        return sessionLogs.getOrDefault(sessionId, List.of());
    }

    /**
     * Get a formatted session log as a string.
     */
    public String getFormattedSessionLog(String sessionId) {
        List<LogEntry> logs = getSessionLog(sessionId);
        if (logs.isEmpty()) {
            return "No logs for session: " + sessionId;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("═══ SESSION LOG: ").append(sessionId).append(" ═══\n\n");

        for (LogEntry entry : logs) {
            sb.append(entry.format()).append("\n");
        }

        sb.append("\n═══ END LOG ═══");
        return sb.toString();
    }

    /**
     * Get all active session IDs with logs.
     */
    public Set<String> getActiveSessionIds() {
        return sessionLogs.keySet();
    }

    /**
     * Clear logs for a session (after review).
     */
    public void clearSessionLog(String sessionId) {
        sessionLogs.remove(sessionId);
    }

    // ==================== HELPER METHODS ====================

    private WorkflowBuilderPrompts.Phase getPhaseFromHistory(WorkflowBuilderSession session) {
        List<LogEntry> logs = sessionLogs.get(session.getSessionId());
        if (logs != null && !logs.isEmpty()) {
            return logs.get(logs.size() - 1).phaseAfter();
        }
        return WorkflowBuilderPrompts.detectPhase(session);
    }

    private int countNodes(WorkflowBuilderSession session) {
        return session.getTriggers().size()
             + session.getMcps().size()
             + session.getCores().size();
    }

    @SuppressWarnings("unchecked")
    private String extractNodeId(Object result) {
        if (result instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) result;
            Object nodeId = map.get("node_id");
            if (nodeId != null) return nodeId.toString();
            Object logicalId = map.get("logical_id");
            if (logicalId != null) return logicalId.toString();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDetails(Map<String, Object> params, Object result) {
        Map<String, Object> details = new LinkedHashMap<>();

        // Extract key params
        if (params != null) {
            if (params.containsKey("label")) details.put("label", params.get("label"));
            if (params.containsKey("tool_id")) details.put("tool_id", params.get("tool_id"));
            if (params.containsKey("node_id")) details.put("node_id", params.get("node_id"));
            if (params.containsKey("from")) details.put("from", params.get("from"));
            if (params.containsKey("to")) details.put("to", params.get("to"));
        }

        // Extract key result info
        if (result instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) result;
            if (map.containsKey("workflow_id")) details.put("workflow_id", map.get("workflow_id"));
            if (map.containsKey("error")) details.put("error", map.get("error"));
        }

        return details;
    }

    // ==================== QUICK LOG METHODS ====================

    /**
     * Quick log for successful action.
     */
    public void success(WorkflowBuilderSession session, String action, String message) {
        logAction(session, action, null, null, true, message);
    }

    /**
     * Quick log for failed action.
     */
    public void failure(WorkflowBuilderSession session, String action, String message) {
        logAction(session, action, null, null, false, message);
    }
}
