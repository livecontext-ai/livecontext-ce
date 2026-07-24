package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.creators.DecisionNodeCreator;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Handles workflow plan import/export in label-native format.
 * Allows LLMs to get/set complete workflow plans using human-readable labels.
 *
 * Extracted from WorkflowBuilderProvider for Single Responsibility Principle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBuilderPlanExporter {

    private final WorkflowBuilderSessionStore sessionStore;
    private final ToolSchemaFetcher toolSchemaFetcher;

    /**
     * Get the current workflow plan in label-native format.
     */
    public ToolExecutionResult executeGetPlan(WorkflowBuilderSession session) {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("status", "OK");
        result.put("workflow_name", session.getWorkflowName());
        result.put("workflow_description", session.getWorkflowDescription());

        Map<String, Object> plan = buildLabelNativePlan(session);
        result.put("plan", plan);

        result.put("summary", buildSummary(session));

        result.put("modify_and_reimport", Map.of(
            "action", "workflow(action='set_plan', plan={...modified plan...})",
            "validation", "Checks: unique labels, valid edge references, required fields",
            "note", "Use labels directly (e.g., 'My Step'). Ports: 'Decision:if', 'Loop:body'"
        ));

        return ToolExecutionResult.success(result);
    }

    /**
     * Set the workflow plan from a label-native format.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeSetPlan(WorkflowBuilderSession session, Map<String, Object> parameters) {
        Object planObj = parameters.get("plan");
        if (planObj == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'plan' parameter is required. Use get_plan to see the expected format.");
        }
        if (!(planObj instanceof Map)) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "'plan' must be an object with triggers, steps, edges, etc.");
        }

        Map<String, Object> plan = (Map<String, Object>) planObj;

        List<String> errors = validateLabelNativePlan(plan);
        if (!errors.isEmpty()) {
            Map<String, Object> errorResult = new LinkedHashMap<>();
            errorResult.put("status", "ERROR");
            errorResult.put("message", "Plan validation failed");
            errorResult.put("errors", errors);
            errorResult.put("hint", "Fix the errors above and try again. Use get_plan to see a valid plan structure.");
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, errorResult.toString());
        }

        // Clear current session
        session.getTriggers().clear();
        session.getMcps().clear();
        session.getCores().clear();
        session.getEdges().clear();
        session.getInterfaces().clear();
        session.getTables().clear();
        session.getNotes().clear();

        // Import triggers
        importList(plan, "triggers", session.getTriggers());

        // Import mcps
        importList(plan, "mcps", session.getMcps());

        // Import agents (stored as mcps with isAgent=true + isClassify/isGuardrail flags)
        // All agent types need isAgent=true for agent: prefix lookup in SessionNodeFinder
        List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
        if (agents != null) {
            for (Map<String, Object> a : agents) {
                Map<String, Object> agent = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(a));
                agent.put("isAgent", true);
                String agentType = (String) agent.get("type");
                if ("classify".equals(agentType)) {
                    agent.put("isClassify", true);
                } else if ("guardrail".equals(agentType)) {
                    agent.put("isGuardrail", true);
                }
                session.getMcps().add(agent);
            }
        }

        // Import control nodes (auto-correct decision format already done in validation)
        importList(plan, "cores", session.getCores());

        // Import interfaces
        importList(plan, "interfaces", session.getInterfaces());

        // Import tables
        importList(plan, "tables", session.getTables());

        // Import notes
        importList(plan, "notes", session.getNotes());

        // Import edges (convert labels to normalized IDs)
        importEdges(plan, session);

        session.touch();
        sessionStore.save(session);

        return buildSetPlanResult(session);
    }

    @SuppressWarnings("unchecked")
    private void importList(Map<String, Object> plan, String key, List<Map<String, Object>> target) {
        List<Map<String, Object>> items = (List<Map<String, Object>>) plan.get(key);
        if (items != null) {
            for (Map<String, Object> item : items) {
                target.add(LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(item)));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void importEdges(Map<String, Object> plan, WorkflowBuilderSession session) {
        List<Map<String, Object>> edges = (List<Map<String, Object>>) plan.get("edges");
        if (edges == null) return;

        for (Map<String, Object> e : edges) {
            Map<String, Object> edge = new LinkedHashMap<>();

            String fromLabel = (String) e.get("from");
            String toLabel = (String) e.get("to");

            edge.put("from", labelRefToNodeId(session, fromLabel));
            if (toLabel != null) {
                edge.put("to", labelRefToNodeId(session, toLabel));
            }

            // Copy other edge properties
            for (Map.Entry<String, Object> entry : e.entrySet()) {
                String key = entry.getKey();
                if (!key.equals("from") && !key.equals("to")) {
                    edge.put(key, entry.getValue());
                }
            }
            session.getEdges().add(edge);
        }
    }

    private ToolExecutionResult buildSetPlanResult(WorkflowBuilderSession session) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");
        result.put("message", "Plan imported successfully");
        result.put("summary", buildSummary(session));

        List<String> warnings = new ArrayList<>();
        List<String> orphans = session.findOrphanNodes();
        if (!orphans.isEmpty()) {
            warnings.add("Orphan nodes (no incoming connections): " + orphans.size());
        }
        List<String> deadEnds = session.findDeadEndNodes();
        if (!deadEnds.isEmpty()) {
            warnings.add("Dead-end nodes (no outgoing connections): " + deadEnds.size());
        }
        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
        }

        result.put("next_actions", List.of(
            "workflow(action='validate') - Check for issues",
            "workflow(action='describe') - See visual structure",
            "workflow(action='save') - Save the workflow"
        ));

        return ToolExecutionResult.success(result);
    }

    private Map<String, Object> buildSummary(WorkflowBuilderSession session) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("triggers", session.getTriggers().size());
        // Count mcps vs agents separately
        long agentCount = session.getMcps().stream()
                .filter(m -> Boolean.TRUE.equals(m.get("isAgent"))
                        || Boolean.TRUE.equals(m.get("isClassify"))
                        || Boolean.TRUE.equals(m.get("isGuardrail")))
                .count();
        summary.put("mcps", session.getMcps().size() - agentCount);
        if (agentCount > 0) {
            summary.put("agents", agentCount);
        }
        summary.put("cores", session.getCores().size());
        summary.put("edges", session.getEdges().size());
        if (!session.getInterfaces().isEmpty()) {
            summary.put("interfaces", session.getInterfaces().size());
        }
        if (!session.getTables().isEmpty()) {
            summary.put("tables", session.getTables().size());
        }
        if (!session.getNotes().isEmpty()) {
            summary.put("notes", session.getNotes().size());
        }
        return summary;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildLabelNativePlan(WorkflowBuilderSession session) {
        Map<String, Object> plan = new LinkedHashMap<>();

        // Triggers
        List<Map<String, Object>> triggers = new ArrayList<>();
        for (Map<String, Object> t : session.getTriggers()) {
            triggers.add(new LinkedHashMap<>(t));
        }
        plan.put("triggers", triggers);

        // MCPs and Agents (separate)
        List<Map<String, Object>> mcps = new ArrayList<>();
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Map<String, Object> s : session.getMcps()) {
            Map<String, Object> mcp = new LinkedHashMap<>(s);
            // Check if this is an agent-type node (agent, classify, guardrail)
            if (Boolean.TRUE.equals(mcp.get("isClassify"))) {
                // Ensure type field is set for classify nodes
                if (!mcp.containsKey("type")) {
                    mcp.put("type", "classify");
                }
                // Transform property names for frontend compatibility
                // Rename 'categories' to 'classifyCategories'
                Object categories = mcp.remove("categories");
                if (categories != null) {
                    mcp.put("classifyCategories", categories);
                }
                // Rename 'content' to 'classifyParams' if it's an expression
                Object content = mcp.get("content");
                if (content instanceof String && !mcp.containsKey("classifyParams")) {
                    mcp.put("classifyParams", content);
                }
                agents.add(mcp);
            } else if (Boolean.TRUE.equals(mcp.get("isGuardrail"))) {
                // Ensure type field is set for guardrail nodes
                if (!mcp.containsKey("type")) {
                    mcp.put("type", "guardrail");
                }
                // Rename 'content' to 'guardrailParams' for frontend
                Object gContent = mcp.get("content");
                if (gContent instanceof String && !mcp.containsKey("guardrailParams")) {
                    mcp.put("guardrailParams", gContent);
                }
                // Rename 'rules' to 'guardrailRules' for frontend
                Object gRules = mcp.get("rules");
                if (gRules != null && !mcp.containsKey("guardrailRules")) {
                    mcp.put("guardrailRules", gRules);
                }
                agents.add(mcp);
            } else if (Boolean.TRUE.equals(mcp.get("isAgent"))) {
                // Ensure type field is set for agent nodes
                if (!mcp.containsKey("type")) {
                    mcp.put("type", "agent");
                }
                agents.add(mcp);
            } else {
                mcps.add(mcp);
            }
        }
        plan.put("mcps", mcps);
        plan.put("agents", agents);

        // Control nodes
        List<Map<String, Object>> cores = new ArrayList<>();
        for (Map<String, Object> cn : session.getCores()) {
            cores.add(new LinkedHashMap<>(cn));
        }
        plan.put("cores", cores);

        // Edges (convert IDs to labels)
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> e : session.getEdges()) {
            if (Boolean.TRUE.equals(e.get("_visualOnly"))) continue;

            Map<String, Object> edge = new LinkedHashMap<>();
            String from = (String) e.get("from");
            String to = (String) e.get("to");

            edge.put("from", nodeIdToLabelRef(session, from));
            if (to != null) {
                edge.put("to", nodeIdToLabelRef(session, to));
            }

            // Copy other properties
            for (Map.Entry<String, Object> entry : e.entrySet()) {
                String key = entry.getKey();
                if (!key.equals("from") && !key.equals("to") && !key.startsWith("_")) {
                    edge.put(key, entry.getValue());
                }
            }
            edges.add(edge);
        }
        plan.put("edges", edges);

        // Interfaces
        List<Map<String, Object>> interfacesList = new ArrayList<>();
        for (Map<String, Object> iface : session.getInterfaces()) {
            interfacesList.add(new LinkedHashMap<>(iface));
        }
        if (!interfacesList.isEmpty()) {
            plan.put("interfaces", interfacesList);
        }

        // Tables
        List<Map<String, Object>> tablesList = new ArrayList<>();
        for (Map<String, Object> table : session.getTables()) {
            tablesList.add(new LinkedHashMap<>(table));
        }
        if (!tablesList.isEmpty()) {
            plan.put("tables", tablesList);
        }

        // Notes
        List<Map<String, Object>> notesList = new ArrayList<>();
        for (Map<String, Object> note : session.getNotes()) {
            notesList.add(new LinkedHashMap<>(note));
        }
        if (!notesList.isEmpty()) {
            plan.put("notes", notesList);
        }

        return plan;
    }

    private String nodeIdToLabelRef(WorkflowBuilderSession session, String nodeId) {
        if (nodeId == null) return null;

        // Split off the port via EdgeRefParser (single source of truth for the
        // port set) so export recognises EXACTLY the ports import/validation do.
        String[] split = EdgeRefParser.splitPort(nodeId);
        String baseNodeId = split[0];
        String portSuffix = split[1] != null ? ":" + split[1] : "";

        Optional<Map<String, Object>> node = session.findNode(baseNodeId);
        if (node.isPresent()) {
            String label = (String) node.get().get("label");
            if (label != null) {
                return label + portSuffix;
            }
        }

        return nodeId;
    }

    private String labelRefToNodeId(WorkflowBuilderSession session, String labelRef) {
        if (labelRef == null) return null;

        // Split off the port via EdgeRefParser (single source of truth for the
        // port set) so import recognises EXACTLY the ports validation/export do.
        String[] split = EdgeRefParser.splitPort(labelRef);
        String baseLabel = split[0];
        String portSuffix = split[1] != null ? ":" + split[1] : "";

        // Resolve the base endpoint with the SAME canonical resolver that
        // connect/modify/validate use (session.resolveNodeReference). It accepts
        // every edge form the set_plan contract documents and that validateEdges()
        // already vetted - raw label ("My Step"), normalized ("my_step") and a
        // fully-qualified node id ("trigger:sms_form", "mcp:send_sms",
        // "interface:x") - plus cross-prefix recovery. All plan nodes are imported
        // into the session BEFORE importEdges runs, so a valid endpoint resolves to
        // its real id here.
        String resolved = session.resolveNodeReference(baseLabel);
        if (resolved != null && session.nodeExists(resolved)) {
            return resolved + portSuffix;
        }

        // Endpoint did not resolve to a real node. NEVER fabricate an
        // "mcp:<normalized>" id: the old fallback did exactly that and silently
        // corrupted fully-qualified endpoints ("trigger:sms_form" ->
        // "mcp:trigger_sms_form", "mcp:send_sms" -> "mcp:mcp_send_sms"), so set_plan
        // reported success and the NEXT validate() failed with
        // INVALID_EDGE_SOURCE/TARGET. Preserve the (already-validated) reference
        // verbatim so any residual error message stays honest.
        return labelRef;
    }

    /** Section key → normalized type prefix used in normalized keys (e.g. "mcps" → "mcp"). */
    private static final Map<String, String> SECTION_TYPE_PREFIX = Map.of(
        "triggers",   "trigger",
        "mcps",       "mcp",
        "agents",     "agent",
        "cores",      "core",
        "interfaces", "interface",
        "tables",     "table",
        "notes",      "note"
    );

    /**
     * Register a label in {@code allLabels} under all 3 forms accepted by the
     * documented set_plan contract (WorkflowBuilderHelpModule line 87):
     *   1. raw display label, lowercased - {@code "Lister Emails"} → {@code "lister emails"}
     *   2. normalized label only - {@code "lister_emails"}
     *   3. type-prefixed normalized - {@code "mcp:lister_emails"}
     *
     * This lets edges in {@code set_plan} reference nodes by ANY of these forms,
     * matching what the help text promises and what the LLM is trained to produce.
     */
    private static void registerLabelForms(String label, String sectionKey, Set<String> allLabels) {
        if (label == null || label.isBlank()) return;
        allLabels.add(label.toLowerCase());
        String normalized = WorkflowBuilderSession.normalizeLabel(label);
        if (normalized != null && !normalized.isBlank()) {
            allLabels.add(normalized);
            String prefix = SECTION_TYPE_PREFIX.get(sectionKey);
            if (prefix != null) {
                allLabels.add(prefix + ":" + normalized);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> validateLabelNativePlan(Map<String, Object> plan) {
        List<String> errors = new ArrayList<>();
        Set<String> allLabels = new HashSet<>();

        validateNodeList(plan, "triggers", errors, allLabels, true);
        validateNodeList(plan, "mcps", errors, allLabels, false);
        validateMcpToolIds(plan, errors);
        validateAgents(plan, errors, allLabels);
        validateCores(plan, errors, allLabels);
        validateInterfaces(plan, errors, allLabels);
        validateLabelsOnly(plan, "tables", allLabels);
        validateLabelsOnly(plan, "notes", allLabels);
        validateEdges(plan, errors, allLabels);

        return errors;
    }

    /**
     * Verify every MCP node references a real catalog tool. Mirrors the check
     * applied in {@link com.apimarketplace.orchestrator.tools.workflow.builder.creators.McpCreator}
     * so the LLM can't bypass it by submitting a full plan via set_plan with
     * fabricated UUIDs (e.g. duplicating one node UUID across 5 different tools,
     * or hallucinating placeholders like "Label_1").
     *
     * Only NOT_FOUND triggers a hard rejection - UNKNOWN (transient catalog
     * outage) is permissive so a flaky catalog doesn't block legitimate work.
     */
    @SuppressWarnings("unchecked")
    private void validateMcpToolIds(Map<String, Object> plan, List<String> errors) {
        Object obj = plan.get("mcps");
        if (!(obj instanceof List)) return;

        List<Map<String, Object>> mcps = (List<Map<String, Object>>) obj;
        for (int i = 0; i < mcps.size(); i++) {
            Map<String, Object> mcp = mcps.get(i);
            Object idObj = mcp.get("id");
            if (idObj == null) {
                errors.add("mcps[" + i + "]: 'id' is required (must be a real tool UUID from catalog(action='search'))");
                continue;
            }
            String toolId = idObj.toString();
            if (ToolSchemaFetcher.isReservedToolSentinel(toolId)) {
                continue;
            }
            ToolSchemaFetcher.ToolExistence existence = toolSchemaFetcher.checkToolExists(toolId);
            if (existence == ToolSchemaFetcher.ToolExistence.NOT_FOUND) {
                String label = (String) mcp.get("label");
                errors.add("mcps[" + i + "] ('" + (label != null ? label : "?") + "'): tool id '" + toolId
                    + "' does not exist in the catalog. Use catalog(action='search') and copy the exact `id` from the result.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateNodeList(Map<String, Object> plan, String key, List<String> errors,
                                  Set<String> allLabels, boolean requireType) {
        Object obj = plan.get(key);
        if (obj == null) return;

        if (!(obj instanceof List)) {
            errors.add("'" + key + "' must be an array");
            return;
        }

        List<Map<String, Object>> items = (List<Map<String, Object>>) obj;
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String label = (String) item.get("label");

            if (label == null || label.isBlank()) {
                errors.add(key + "[" + i + "]: 'label' is required");
            } else {
                if (allLabels.contains(label.toLowerCase())) {
                    errors.add(key + "[" + i + "]: Duplicate label '" + label + "'");
                }
                registerLabelForms(label, key, allLabels);
            }

            if (requireType) {
                String type = (String) item.get("type");
                if (type == null || type.isBlank()) {
                    errors.add(key + "[" + i + "]: 'type' is required");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateAgents(Map<String, Object> plan, List<String> errors, Set<String> allLabels) {
        Object obj = plan.get("agents");
        if (obj == null) return;

        if (!(obj instanceof List)) {
            errors.add("'agents' must be an array");
            return;
        }

        List<Map<String, Object>> agents = (List<Map<String, Object>>) obj;
        for (int i = 0; i < agents.size(); i++) {
            Map<String, Object> a = agents.get(i);
            String label = (String) a.get("label");
            String type = (String) a.get("type");

            // Label is required for all agent types
            if (label == null || label.isBlank()) {
                errors.add("agents[" + i + "]: 'label' is required");
            } else {
                if (allLabels.contains(label.toLowerCase())) {
                    errors.add("agents[" + i + "]: Duplicate label '" + label + "'");
                }
                registerLabelForms(label, "agents", allLabels);
            }

            // Type is required (agent, classify, guardrail)
            if (type == null || type.isBlank()) {
                errors.add("agents[" + i + "]: 'type' is required (agent, classify, guardrail)");
            } else {
                // Validate type-specific fields
                switch (type) {
                    case "agent":
                        String prompt = (String) a.get("prompt");
                        if (prompt == null || prompt.isBlank()) {
                            errors.add("agents[" + i + "]: 'prompt' is required for agent type");
                        }
                        break;
                    case "classify":
                        Object categories = a.get("classifyCategories");
                        if (!(categories instanceof List) || ((List<?>) categories).isEmpty()) {
                            errors.add("agents[" + i + "]: 'classifyCategories' is required for classify type");
                        }
                        break;
                    case "guardrail":
                        Object rules = a.get("guardrailRules");
                        if (!(rules instanceof List) || ((List<?>) rules).isEmpty()) {
                            errors.add("agents[" + i + "]: 'guardrailRules' is required for guardrail type");
                        }
                        break;
                    default:
                        errors.add("agents[" + i + "]: Unknown type '" + type + "' (expected: agent, classify, guardrail)");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateCores(Map<String, Object> plan, List<String> errors, Set<String> allLabels) {
        Object obj = plan.get("cores");
        if (obj == null) return;

        if (!(obj instanceof List)) {
            errors.add("'cores' must be an array");
            return;
        }

        List<Map<String, Object>> cores = (List<Map<String, Object>>) obj;
        for (int i = 0; i < cores.size(); i++) {
            Map<String, Object> cn = cores.get(i);
            String label = (String) cn.get("label");
            String type = (String) cn.get("type");

            if (label == null || label.isBlank()) {
                errors.add("cores[" + i + "]: 'label' is required");
            } else {
                if (allLabels.contains(label.toLowerCase())) {
                    errors.add("cores[" + i + "]: Duplicate label '" + label + "'");
                }
                registerLabelForms(label, "cores", allLabels);
            }

            if (type == null || type.isBlank()) {
                errors.add("cores[" + i + "]: 'type' is required (decision, switch, loop, split, merge, fork)");
            } else {
                // Normalize "while" alias to "loop" before validation
                if ("while".equals(type)) {
                    type = "loop";
                    cn.put("type", "loop");
                }
                validateCoreType(cn, type, i, errors);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateCoreType(Map<String, Object> cn, String type, int i, List<String> errors) {
        switch (type) {
            case "decision":
                Object conditions = cn.get("decisionConditions");
                if (conditions == null) conditions = cn.get("conditions");
                // Auto-recover from hallucinated 'condition' (singular) format
                if (conditions == null) {
                    List<Map<String, Object>> recovered = DecisionNodeCreator.tryRecoverConditions(cn);
                    if (recovered != null && !recovered.isEmpty()) {
                        cn.put("decisionConditions", recovered);
                        cn.remove("condition");
                        cn.remove("ports"); // LLMs sometimes hallucinate a 'ports' field
                        conditions = recovered;
                        log.info("Auto-recovered decision conditions from hallucinated format for cores[{}]", i);
                    }
                }
                if (conditions == null || !(conditions instanceof List) || ((List<?>) conditions).isEmpty()) {
                    errors.add("cores[" + i + "]: 'decisionConditions' array is required for decision. " +
                        "Format: decisionConditions: [{id: 'x-if', type: 'if', label: 'Match', expression: '{{expr}}'}, " +
                        "{id: 'x-else', type: 'else', label: 'Fallback'}]");
                }
                break;
            case "switch":
                Object cases = cn.get("switchCases");
                if (cases == null || !(cases instanceof List) || ((List<?>) cases).isEmpty()) {
                    errors.add("cores[" + i + "]: 'switchCases' array is required for switch");
                }
                String switchExpr = (String) cn.get("switchExpression");
                if (switchExpr == null || switchExpr.isBlank()) {
                    errors.add("cores[" + i + "]: 'switchExpression' is required for switch");
                }
                break;
            case "loop":
                String loopCondition = (String) cn.get("loopCondition");
                if (loopCondition == null) loopCondition = (String) cn.get("condition");
                if (loopCondition == null || loopCondition.isBlank()) {
                    errors.add("cores[" + i + "]: 'loopCondition' is required for loop");
                }
                break;
            case "split":
                // Accept both "list" (new) and "listExpression" (legacy)
                String listExpr = (String) cn.get("list");
                if (listExpr == null) listExpr = (String) cn.get("listExpression");
                if (listExpr == null) listExpr = (String) cn.get("collection");
                if (listExpr == null || listExpr.isBlank()) {
                    errors.add("cores[" + i + "]: 'list' is required for split");
                }
                break;
            case "merge":
                // Merge nodes don't require additional fields - they just aggregate inputs
                break;
            case "fork":
                // Fork nodes don't require additional fields - they just branch outputs
                break;
            case "transform":
            case "wait":
            case "download_file":
            case "aggregate":
            case "exit":
            case "response":
            case "option":
            case "http_request":
            case "filter":
            case "sort":
            case "limit":
            case "remove_duplicates":
            case "summarize":
            case "date_time":
            case "crypto_jwt":
            case "approval":
            case "data_input":
            case "xml":
            case "compression":
            case "rss":
            case "convert_to_file":
            case "extract_from_file":
            case "compare_datasets":
            case "sub_workflow":
            case "respond_to_webhook":
            case "send_email":
            case "email_inbox":
            case "code":
            case "stop_on_error":
            case "task":
                // These core types have optional configuration - no required fields
                // (email_inbox action requirements are validated in CoreValidator/WorkflowErrorChecker)
                break;
            case "ssh":
                Object sshCfg = cn.get("ssh");
                if (sshCfg instanceof Map<?, ?> sshMap) {
                    if (!(sshMap.get("host") instanceof String h) || h.isBlank()) {
                        errors.add("cores[" + i + "]: 'ssh.host' is required");
                    }
                    if (!(sshMap.get("command") instanceof String c) || c.isBlank()) {
                        errors.add("cores[" + i + "]: 'ssh.command' is required");
                    }
                }
                break;
            case "sftp":
                Object sftpCfg = cn.get("sftp");
                if (sftpCfg instanceof Map<?, ?> sftpMap) {
                    if (!(sftpMap.get("host") instanceof String h) || h.isBlank()) {
                        errors.add("cores[" + i + "]: 'sftp.host' is required");
                    }
                }
                break;
            case "database":
                Object dbCfg = cn.get("database");
                if (dbCfg instanceof Map<?, ?> dbMap) {
                    if (!(dbMap.get("host") instanceof String h) || h.isBlank()) {
                        errors.add("cores[" + i + "]: 'database.host' is required");
                    }
                    if (!(dbMap.get("databaseName") instanceof String d) || d.isBlank()) {
                        errors.add("cores[" + i + "]: 'database.databaseName' is required");
                    }
                    if (!(dbMap.get("query") instanceof String q) || q.isBlank()) {
                        errors.add("cores[" + i + "]: 'database.query' is required");
                    }
                }
                break;
            case "set":
                Object setConfig = cn.get("set");
                if (!(setConfig instanceof Map<?, ?> setMap)) {
                    errors.add("cores[" + i + "]: 'set' config object is required for set");
                } else {
                    Object asg = setMap.get("assignments");
                    if (!(asg instanceof List<?>) || ((List<?>) asg).isEmpty()) {
                        errors.add("cores[" + i + "]: 'set.assignments' array is required and must not be empty");
                    }
                }
                break;
            case "html_extract":
                Object hxConfig = cn.get("htmlExtract");
                if (!(hxConfig instanceof Map<?, ?> hxMap)) {
                    errors.add("cores[" + i + "]: 'htmlExtract' config object is required for html_extract");
                } else {
                    Object src = hxMap.get("sourceHtml");
                    if (!(src instanceof String s) || s.isBlank()) {
                        errors.add("cores[" + i + "]: 'htmlExtract.sourceHtml' is required");
                    }
                    Object hxFields = hxMap.get("fields");
                    if (!(hxFields instanceof List<?>) || ((List<?>) hxFields).isEmpty()) {
                        errors.add("cores[" + i + "]: 'htmlExtract.fields' array is required and must not be empty");
                    }
                }
                break;
            case "public_link":
                Object plParams = cn.get("params");
                if (!(plParams instanceof Map<?, ?> plMap)
                        || !(plMap.get("file") instanceof String plFile) || plFile.isBlank()) {
                    errors.add("cores[" + i + "]: 'params.file' is required for public_link. " +
                        "Format: params: {file: '{{core:dl.output.file}}', ttl_minutes: 240}");
                }
                break;
            case "media":
                // File params accept a template STRING or a literal FileRef OBJECT (Files picker)
                Object mediaParams = cn.get("params");
                if (!(mediaParams instanceof Map<?, ?> mediaMap)) {
                    errors.add("cores[" + i + "]: 'params' with an 'operation' is required for media. " +
                        "Format: params: {operation: 'mux_audio', video: '{{interface:card.output.video}}', audio: '{{core:dl.output.file}}'}");
                    break;
                }
                Object mediaOpValue = mediaMap.get("operation");
                String mediaOp = mediaOpValue instanceof String mediaOpStr ? mediaOpStr.trim().toLowerCase() : null;
                if (mediaOp == null || mediaOp.isBlank()) {
                    errors.add("cores[" + i + "]: 'params.operation' is required for media (one of: probe, mux_audio, mix, extract_audio, concat, frame, overlay)");
                    break;
                }
                switch (mediaOp) {
                    case "probe", "extract_audio", "frame" -> {
                        if (!isMediaFileParam(mediaMap.get("input"))) {
                            errors.add("cores[" + i + "]: 'params.input' is required for media " + mediaOp + ". " +
                                "Format: params: {operation: '" + mediaOp + "', input: '{{core:dl.output.file}}'}");
                        }
                    }
                    case "mux_audio" -> {
                        if (!isMediaFileParam(mediaMap.get("video"))) {
                            errors.add("cores[" + i + "]: 'params.video' is required for media mux_audio. " +
                                "Format: params: {operation: 'mux_audio', video: '{{interface:card.output.video}}', audio: '{{core:dl.output.file}}'}");
                        }
                        if (!isMediaFileParam(mediaMap.get("audio"))) {
                            errors.add("cores[" + i + "]: 'params.audio' is required for media mux_audio. " +
                                "Format: params: {operation: 'mux_audio', video: '{{interface:card.output.video}}', audio: '{{core:dl.output.file}}'}");
                        }
                    }
                    case "mix" -> {
                        if (!(mediaMap.get("tracks") instanceof List<?> mediaTracks) || mediaTracks.isEmpty()) {
                            errors.add("cores[" + i + "]: 'params.tracks' is required for media mix - a non-empty array of 1-8 tracks, each with a 'source'. " +
                                "Format: params: {operation: 'mix', tracks: [{source: '{{core:voice.output.file}}'}]}");
                        } else {
                            if (mediaTracks.size() > 8) {
                                errors.add("cores[" + i + "]: 'params.tracks' accepts at most 8 tracks (got " + mediaTracks.size() + ")");
                            }
                            for (int ti = 0; ti < mediaTracks.size(); ti++) {
                                if (!(mediaTracks.get(ti) instanceof Map<?, ?> trackMap)
                                        || !isMediaFileParam(trackMap.get("source"))) {
                                    errors.add("cores[" + i + "]: 'params.tracks[" + ti + "].source' is required - " +
                                        "the WHOLE FileRef expression of that track's audio, e.g. '{{core:voice.output.file}}'");
                                }
                            }
                        }
                    }
                    case "concat" -> {
                        if (!(mediaMap.get("inputs") instanceof List<?> mediaInputs) || mediaInputs.isEmpty()) {
                            errors.add("cores[" + i + "]: 'params.inputs' is required for media concat - a non-empty array of 1-8 clips, each with a 'source'. " +
                                "Format: params: {operation: 'concat', inputs: [{source: '{{core:clip_a.output.file}}'}, {source: '{{core:clip_b.output.file}}'}]}");
                        } else {
                            if (mediaInputs.size() > 8) {
                                errors.add("cores[" + i + "]: 'params.inputs' accepts at most 8 clips (got " + mediaInputs.size() + ")");
                            }
                            for (int ci = 0; ci < mediaInputs.size(); ci++) {
                                if (!(mediaInputs.get(ci) instanceof Map<?, ?> clipMap)
                                        || !isMediaFileParam(clipMap.get("source"))) {
                                    errors.add("cores[" + i + "]: 'params.inputs[" + ci + "].source' is required - " +
                                        "the WHOLE FileRef expression of that clip, e.g. '{{core:clip_a.output.file}}'");
                                }
                            }
                        }
                    }
                    case "overlay" -> {
                        if (!isMediaFileParam(mediaMap.get("video"))) {
                            errors.add("cores[" + i + "]: 'params.video' is required for media overlay. " +
                                "Format: params: {operation: 'overlay', video: '{{core:clip.output.file}}', image: '{{core:logo.output.file}}'}");
                        }
                        if (!isMediaFileParam(mediaMap.get("image"))) {
                            errors.add("cores[" + i + "]: 'params.image' is required for media overlay. " +
                                "Format: params: {operation: 'overlay', video: '{{core:clip.output.file}}', image: '{{core:logo.output.file}}'}");
                        }
                    }
                    default -> errors.add("cores[" + i + "]: unknown media operation '" + mediaOp + "' (expected: probe, mux_audio, mix, extract_audio, concat, frame, overlay)");
                }
                break;
            default:
                errors.add("cores[" + i + "]: Unknown type '" + type + "' (expected: decision, switch, loop, split, merge, fork, transform, wait, download_file, public_link, media, aggregate, exit, response, option, http_request, filter, sort, limit, remove_duplicates, summarize, date_time, crypto_jwt, approval, data_input, xml, compression, rss, convert_to_file, extract_from_file, compare_datasets, sub_workflow, respond_to_webhook, send_email, email_inbox, code, set, html_extract, task, stop_on_error, ssh, sftp, database)");
        }
    }

    /**
     * A usable media file param: a non-blank expression string ({@code {{...output.file}}})
     * OR a literal FileRef object (the builder's Files picker stores those) with a path.
     */
    private static boolean isMediaFileParam(Object value) {
        if (value instanceof String s) {
            return !s.isBlank();
        }
        return value instanceof Map<?, ?> map && map.get("path") instanceof String p && !p.isBlank();
    }

    @SuppressWarnings("unchecked")
    private void validateInterfaces(Map<String, Object> plan, List<String> errors, Set<String> allLabels) {
        Object obj = plan.get("interfaces");
        if (obj == null) return;

        if (!(obj instanceof List)) {
            errors.add("'interfaces' must be an array");
            return;
        }

        List<Map<String, Object>> interfaces = (List<Map<String, Object>>) obj;
        for (int i = 0; i < interfaces.size(); i++) {
            Map<String, Object> iface = interfaces.get(i);
            String label = (String) iface.get("label");

            if (label == null || label.isBlank()) {
                errors.add("Interface at index " + i + " has no label");
            } else {
                if (allLabels.contains(label.toLowerCase())) {
                    errors.add("interfaces[" + i + "]: Duplicate label '" + label + "'");
                }
                registerLabelForms(label, "interfaces", allLabels);
            }

            // interface_id is required (check both "id" and "interfaceId" keys)
            Object id = iface.get("id");
            if (id == null) id = iface.get("interfaceId");
            if (id == null) {
                String displayLabel = (label != null && !label.isBlank()) ? label : "index " + i;
                errors.add("Interface '" + displayLabel + "' has no interface_id");
            }
        }
    }

    /**
     * Register labels from a node list (tables, notes) into allLabels for edge validation.
     */
    @SuppressWarnings("unchecked")
    private void validateLabelsOnly(Map<String, Object> plan, String key, Set<String> allLabels) {
        Object obj = plan.get(key);
        if (obj == null || !(obj instanceof List)) return;

        List<Map<String, Object>> items = (List<Map<String, Object>>) obj;
        for (Map<String, Object> item : items) {
            String label = (String) item.get("label");
            if (label != null && !label.isBlank()) {
                registerLabelForms(label, key, allLabels);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateEdges(Map<String, Object> plan, List<String> errors, Set<String> allLabels) {
        Object obj = plan.get("edges");
        if (obj == null) return;

        if (!(obj instanceof List)) {
            errors.add("'edges' must be an array");
            return;
        }

        List<Map<String, Object>> edges = (List<Map<String, Object>>) obj;
        for (int i = 0; i < edges.size(); i++) {
            Map<String, Object> e = edges.get(i);
            String from = (String) e.get("from");
            String to = (String) e.get("to");

            if (from == null || from.isBlank()) {
                errors.add("edges[" + i + "]: 'from' is required");
            } else if (!resolveEdgeLabel(from, allLabels)) {
                errors.add("edges[" + i + "]: 'from' references unknown node '" + extractBaseLabel(from) + "'");
            }

            if (to == null || to.isBlank()) {
                errors.add("edges[" + i + "]: 'to' is required");
            } else if (!resolveEdgeLabel(to, allLabels)) {
                errors.add("edges[" + i + "]: 'to' references unknown node '" + extractBaseLabel(to) + "'");
            }
        }
    }

    /**
     * Resolve an edge endpoint against the registered label set, accepting any
     * of the documented forms (raw label, normalized label, type:normalized).
     * Strips port suffixes ({@code :if}, {@code :case_0}, etc.) before lookup.
     */
    private boolean resolveEdgeLabel(String labelRef, Set<String> allLabels) {
        String base = extractBaseLabel(labelRef);
        String lower = base.toLowerCase();
        if (allLabels.contains(lower)) return true;
        // Try without the type prefix (e.g. "mcp:lister_emails" → "lister_emails")
        int colon = lower.indexOf(':');
        if (colon > 0 && colon < lower.length() - 1) {
            String afterPrefix = lower.substring(colon + 1);
            if (allLabels.contains(afterPrefix)) return true;
        }
        // Try the LabelNormalizer form of whatever the user supplied
        String normalized = WorkflowBuilderSession.normalizeLabel(base);
        if (normalized != null && allLabels.contains(normalized)) return true;
        return false;
    }

    private String extractBaseLabel(String labelRef) {
        if (labelRef == null) return "";
        // EdgeRefParser is the single source of truth for the port set, so edge
        // VALIDATION strips exactly the ports import/export resolve - previously
        // this list omitted pass/fail/approved/rejected/timeout/choice_N, so valid
        // guardrail/approval/option-port edges were rejected as "unknown node".
        return EdgeRefParser.splitPort(labelRef)[0];
    }
}
