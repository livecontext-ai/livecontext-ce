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
     * The inverse of the renames {@link #buildPlanMap()} applies, for a node coming IN
     * from a stored or imported plan ({@code load}, {@code set_plan}).
     *
     * <p>The session speaks {@code categories}, {@code rules} and {@code content}: the
     * creators write them, {@code modify} merges into them and {@code validate} checks
     * them. The plan speaks {@code classifyCategories}, {@code guardrailRules},
     * {@code classifyParams} and {@code guardrailParams}, which is what the engine and
     * the canvas read. Copying a stored node verbatim left the session blind to its own
     * config: {@code validate} reported a classify node's categories as missing, so a
     * loaded workflow could not be finished, and {@code modify} reported them
     * {@code "(not set)"}. Worse, a guardrail kept BOTH spellings, and because the
     * export only writes {@code guardrailRules} when it is absent, a {@code modify} of
     * its rules answered OK while the engine kept running the old ones.
     *
     * <p>The plan spelling wins when both are present: it is what the last run read and
     * what the canvas edits. It is REMOVED, so the next export regenerates it from the
     * session value rather than carrying a stale copy. The type-derived flags are set
     * here too, because a plan written by the canvas carries the type and no flags.
     */
    public static void adoptPlanSpellings(Map<String, Object> node) {
        Object type = node.get("type");
        if ("classify".equals(type)) {
            node.put("isClassify", true);
        } else if ("guardrail".equals(type)) {
            node.put("isGuardrail", true);
        }
        for (Map.Entry<String, String> rename : planToSessionKeys(node).entrySet()) {
            Object value = node.get(rename.getKey());
            // The export only regenerates a *Params spelling from a String content, so any
            // other shape stays where the engine reads it.
            boolean textOnly = "content".equals(rename.getValue());
            if (value instanceof String text && text.isBlank()) {
                // Nothing to carry over, and left in place it would stop the export from
                // writing the session content back under this key.
                node.remove(rename.getKey());
            } else if (value != null && (!textOnly || value instanceof String)) {
                node.put(rename.getValue(), value);
                node.remove(rename.getKey());
            }
        }
    }

    /**
     * A copy of a session node in the stored plan's spelling, for the checks that hand one
     * node to the engine's parser (the mock checks): the parser reads classify ports from
     * {@code classifyCategories}, so a session node parsed as-is has no ports at all.
     * Mirrors the renames {@link #buildPlanMap()} applies.
     */
    public static Map<String, Object> toPlanSpelling(Map<String, Object> node) {
        Map<String, Object> copy = new LinkedHashMap<>(node);
        if (Boolean.TRUE.equals(copy.get("isClassify"))) {
            Object categories = copy.remove("categories");
            if (categories != null) {
                copy.put("classifyCategories", categories);
            }
            if (copy.get("content") instanceof String content && !copy.containsKey("classifyParams")) {
                copy.put("classifyParams", content);
            }
        } else if (Boolean.TRUE.equals(copy.get("isGuardrail"))) {
            if (copy.get("content") instanceof String content && !copy.containsKey("guardrailParams")) {
                copy.put("guardrailParams", content);
            }
            if (copy.get("rules") != null && !copy.containsKey("guardrailRules")) {
                copy.put("guardrailRules", copy.get("rules"));
            }
        }
        return copy;
    }

    /**
     * The session key a caller-supplied field of this node belongs under, or {@code null}
     * when the field needs no renaming. {@code get_plan} shows the plan spelling, so that is
     * what an agent sends back to {@code modify}; written as-is it would sit beside the
     * session key and be overwritten by it on the next export, answered OK and never run.
     */
    public static String sessionKeyFor(Map<String, Object> node, String key) {
        return planToSessionKeys(node).get(key);
    }

    private static Map<String, String> planToSessionKeys(Map<String, Object> node) {
        if (Boolean.TRUE.equals(node.get("isClassify")) || "classify".equals(node.get("type"))) {
            return CLASSIFY_PLAN_TO_SESSION;
        }
        if (Boolean.TRUE.equals(node.get("isGuardrail")) || "guardrail".equals(node.get("type"))) {
            return GUARDRAIL_PLAN_TO_SESSION;
        }
        return Map.of();
    }

    private static final Map<String, String> CLASSIFY_PLAN_TO_SESSION = Map.of(
            "classifyCategories", "categories",
            "classifyParams", "content");

    private static final Map<String, String> GUARDRAIL_PLAN_TO_SESSION = Map.of(
            "guardrailRules", "rules",
            "guardrailParams", "content");

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
