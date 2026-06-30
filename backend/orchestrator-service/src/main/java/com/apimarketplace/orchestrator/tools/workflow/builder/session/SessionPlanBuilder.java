package com.apimarketplace.orchestrator.tools.workflow.builder.session;

import java.util.*;

/**
 * Handles plan building and data sanitization for workflow sessions.
 * Single Responsibility: Building the plan map and sanitizing data.
 */
public class SessionPlanBuilder {

    private final String workflowName;
    private final String workflowDescription;
    private final Map<String, Object> schedule;
    private final List<Map<String, Object>> triggers;
    private final List<Map<String, Object>> mcps;
    private final List<Map<String, Object>> cores;
    private final List<Map<String, Object>> interfaces;
    private final List<Map<String, Object>> tables;
    private final List<Map<String, Object>> notes;
    private final SessionEdgeManager edgeManager;

    public SessionPlanBuilder(
            String workflowName,
            String workflowDescription,
            Map<String, Object> schedule,
            List<Map<String, Object>> triggers,
            List<Map<String, Object>> mcps,
            List<Map<String, Object>> cores,
            List<Map<String, Object>> interfaces,
            List<Map<String, Object>> tables,
            List<Map<String, Object>> notes,
            SessionEdgeManager edgeManager) {
        this.workflowName = workflowName;
        this.workflowDescription = workflowDescription;
        this.schedule = schedule;
        this.triggers = triggers;
        this.mcps = mcps;
        this.cores = cores;
        this.interfaces = interfaces;
        this.tables = tables;
        this.notes = notes;
        this.edgeManager = edgeManager;
    }

    /**
     * Build the current plan as a Map.
     * Sanitizes all strings to remove null bytes that PostgreSQL rejects.
     */
    public Map<String, Object> buildPlanMap() {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("name", workflowName);
        if (workflowDescription != null) {
            plan.put("description", workflowDescription);
        }
        plan.put("triggers", new ArrayList<>(triggers));

        // Separate mcps and agents (agent, classify, guardrail)
        List<Map<String, Object>> regularMcps = new ArrayList<>();
        List<Map<String, Object>> agentNodes = new ArrayList<>();
        for (Map<String, Object> mcp : mcps) {
            // Check if this is any type of AI reasoning node
            boolean isClassify = Boolean.TRUE.equals(mcp.get("isClassify"));
            boolean isGuardrail = Boolean.TRUE.equals(mcp.get("isGuardrail"));
            boolean isAgent = Boolean.TRUE.equals(mcp.get("isAgent"));

            if (isClassify || isGuardrail || isAgent) {
                // Create a copy to avoid modifying the original
                Map<String, Object> agentNode = new LinkedHashMap<>(mcp);

                // Ensure type field is set based on boolean flags
                if (!agentNode.containsKey("type")) {
                    if (isClassify) {
                        agentNode.put("type", "classify");
                    } else if (isGuardrail) {
                        agentNode.put("type", "guardrail");
                    } else {
                        agentNode.put("type", "agent");
                    }
                }

                // Transform property names for frontend compatibility
                if (isClassify) {
                    // Rename 'categories' to 'classifyCategories' for frontend
                    Object categories = agentNode.remove("categories");
                    if (categories != null) {
                        agentNode.put("classifyCategories", categories);
                    }
                    // Rename 'content' to 'classifyParams' if it's an expression
                    Object content = agentNode.get("content");
                    if (content instanceof String && !agentNode.containsKey("classifyParams")) {
                        agentNode.put("classifyParams", content);
                    }
                }

                if (isGuardrail) {
                    // Rename 'content' to 'guardrailParams' for frontend
                    Object content = agentNode.get("content");
                    if (content instanceof String && !agentNode.containsKey("guardrailParams")) {
                        agentNode.put("guardrailParams", content);
                    }
                    // Rename 'rules' (backend format: {key: desc}) to 'guardrailRules' for frontend
                    Object rules = agentNode.get("rules");
                    if (rules != null && !agentNode.containsKey("guardrailRules")) {
                        agentNode.put("guardrailRules", rules);
                    }
                }

                agentNodes.add(agentNode);
            } else {
                regularMcps.add(mcp);
            }
        }
        plan.put("mcps", regularMcps);
        plan.put("agents", agentNodes);

        plan.put("cores", new ArrayList<>(cores));
        plan.put("edges", edgeManager.getPersistableEdges());
        plan.put("interfaces", new ArrayList<>(interfaces));
        plan.put("tables", new ArrayList<>(tables));
        plan.put("notes", new ArrayList<>(notes));

        if (schedule != null && !schedule.isEmpty()) {
            plan.put("schedule", schedule);
        }

        return sanitizeMapRecursively(plan);
    }

    /**
     * Get summary of current plan.
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggers", triggers.size());
        summary.put("mcps", mcps.size());
        summary.put("cores", cores.size());
        summary.put("edges", edgeManager.getPersistableEdges().size());
        summary.put("interfaces", interfaces.size());

        if (schedule != null && schedule.containsKey("cron")) {
            summary.put("schedule", "recurring: " + schedule.get("cron"));
        } else {
            summary.put("schedule", "once (no recurrence)");
        }

        return summary;
    }

    // Sanitization methods

    @SuppressWarnings("unchecked")
    private Map<String, Object> sanitizeMapRecursively(Map<String, Object> map) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = sanitizeString(entry.getKey());
            Object value = sanitizeValueRecursively(entry.getValue());
            sanitized.put(key, value);
        }
        return sanitized;
    }

    @SuppressWarnings("unchecked")
    private Object sanitizeValueRecursively(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return sanitizeString(str);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey() instanceof String ? sanitizeString((String) entry.getKey()) : String.valueOf(entry.getKey());
                sanitized.put(key, sanitizeValueRecursively(entry.getValue()));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            for (Object item : list) {
                sanitized.add(sanitizeValueRecursively(item));
            }
            return sanitized;
        }
        return value;
    }

    private String sanitizeString(String str) {
        if (str == null) {
            return null;
        }
        return str.replace("\u0000", "");
    }
}
