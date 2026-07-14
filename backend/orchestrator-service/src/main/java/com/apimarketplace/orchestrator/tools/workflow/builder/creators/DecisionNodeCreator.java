package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Handles decision and switch node creation for the workflow builder.
 * Decision nodes evaluate conditions to choose ONE branch (exclusive).
 * Switch nodes match a value against cases for multi-way branching.
 *
 * NEW FORMAT (unified):
 *   workflow(action='add_node', type='decision', label='Check', params={conditions: [...]})
 *   - All params are flat (no nested 'decision' object)
 *   - ID is auto-generated (core:normalized_label)
 *
 * @see ControlNodeCreator
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionNodeCreator extends CreatorBase {

    private final WorkflowBuilderSessionStore sessionStore;
    private final ResponseOptimizer responseOptimizer;

    // ==================== Add Decision ====================

    /**
     * Execute add_decision action.
     * Creates an if/else branching node that evaluates conditions to choose ONE branch.
     * NEW FORMAT: All parameters are flat, no nested 'decision' object.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddDecision(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label (flat param)
        String label = safeString(parameters.get("label"));
        if (label == null) label = safeString(parameters.get("name"));
        var labelError = validateLabel(label, "decision");
        if (labelError != null) return labelError;

        // 2. MANDATORY: Trigger must exist before adding decisions
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add decision without a trigger. " +
                "Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Validate conditions (flat param) - tolerant: accept multiple key names and formats
        List<Map<String, Object>> conditions = (List<Map<String, Object>>) parameters.get("conditions");
        // Fallback: try 'condition' (singular) - common LLM hallucination
        if (conditions == null) {
            conditions = tryRecoverConditions(parameters);
        }
        // Fallback: try 'decisionConditions' - plan format key
        if (conditions == null) {
            conditions = (List<Map<String, Object>>) parameters.get("decisionConditions");
        }
        if (conditions == null || conditions.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "DECISION: 'conditions' array is required.\n\n" +
                "FORMAT:\n" +
                "  workflow(action='add_node', type='decision', label='Check Status',\n" +
                "    params={conditions: [\n" +
                "      {condition: '{{mcp:x.output.status}} == \"success\"', label: 'Success'},\n" +
                "      {condition: '{{mcp:x.output.status}} == \"error\"', label: 'Error'},\n" +
                "      {condition: 'default', label: 'Otherwise'}\n" +
                "    ]}, connect_after='...')\n\n" +
                "THEN CONNECT EACH BRANCH TO DIFFERENT TARGETS:\n" +
                "  workflow(action='connect', from='Check Status', to='Handle Success')\n" +
                "  workflow(action='connect', from='Check Status', to='Handle Error')\n" +
                "  workflow(action='connect', from='Check Status', to='Handle Fallback')\n\n" +
                "Branches are defined in 'conditions', then you connect EACH branch to a DIFFERENT target.");
        }

        // 4. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.DECISION.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 5. Resolve connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 6. Build decision node
        Map<String, Object> decisionNode = new LinkedHashMap<>();
        decisionNode.put("id", "core:" + normalizedLabel);
        decisionNode.put("label", label);
        decisionNode.put("type", "decision");
        decisionNode.put("position", calculatePosition(session, NodeType.DECISION));

        // Build conditions list - tolerant: accept "condition", "expression", or "expr"
        // IMPORTANT: Set id and type fields properly
        List<Map<String, Object>> conditionsList = new ArrayList<>();
        int elseifIndex = 0;

        for (int i = 0; i < conditions.size(); i++) {
            Map<String, Object> cond = conditions.get(i);

            // Try multiple keys for expression (agent may use different formats)
            // Variable references are deep-normalized when the node is added to session
            String expression = (String) cond.get("condition");
            if (expression == null) expression = (String) cond.get("expression");
            if (expression == null) expression = (String) cond.get("expr");

            String branchLabel = (String) cond.get("label");
            if (branchLabel == null) branchLabel = (String) cond.get("name");

            // Determine type: first = if, default = else, others = elseif
            String condType;
            if (i == 0) {
                condType = "if";
            } else if ("default".equalsIgnoreCase(expression) || "true".equals(expression)) {
                condType = "else";
                expression = "default"; // Normalize to "default"
            } else {
                condType = "elseif";
            }

            // Generate ID based on type
            String condId;
            if ("if".equals(condType)) {
                condId = normalizedLabel + "-if";
            } else if ("else".equals(condType)) {
                condId = normalizedLabel + "-else";
            } else {
                condId = normalizedLabel + "-elseif-" + elseifIndex;
                elseifIndex++;
            }

            Map<String, Object> conditionMap = new LinkedHashMap<>();
            conditionMap.put("id", condId);
            conditionMap.put("type", condType);
            conditionMap.put("label", branchLabel != null ? branchLabel : defaultBranchLabel(condType, i));
            conditionMap.put("expression", expression != null ? expression : "default");

            conditionsList.add(conditionMap);
        }
        // Auto-add else branch if only "if" exists - AI often omits the catch-all
        if (conditionsList.size() == 1 && "if".equals(conditionsList.get(0).get("type"))) {
            Map<String, Object> elseCond = new LinkedHashMap<>();
            elseCond.put("id", normalizedLabel + "-else");
            elseCond.put("type", "else");
            elseCond.put("label", "Otherwise");
            elseCond.put("expression", "default");
            conditionsList.add(elseCond);
            log.info("Auto-added 'else' branch to decision '{}' (only 'if' was provided)", label);
        }

        // Use "decisionConditions" key to match Core domain object
        decisionNode.put("decisionConditions", conditionsList);

        // 6. Add to session (deep-normalize all variable references) and create edge
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(decisionNode));
        if (connectAfter != null && !connectAfter.isBlank()) {
            createDecisionEdge(session, connectAfter, nodeId, conditions);
        }

        // 6.5. Handle connect_after_loop parameter (loop exits)
        String exitFrom = (String) parameters.get("connect_after_loop");
        if (exitFrom != null && !exitFrom.isBlank()) {
            String resolvedLoopId = session.resolveNodeReference(exitFrom);
            if (!resolvedLoopId.startsWith("core:")) {
                return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "connect_after_loop must reference a loop. Use the loop's label (e.g., 'For Each Item').");
            }
            try {
                session.addPendingLoopExit(resolvedLoopId, nodeId, "decision");
            } catch (IllegalStateException e) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
            }
        }

        // 7. Finalize
        boolean isOrphaned = finalizeNode(session, sessionStore, NodeType.DECISION, nodeId, decisionNode, connectAfter);

        // Progressive validation - check for orphan nodes (only warn if workflow has 3+ nodes)
        Map<String, Object> response = responseOptimizer.buildDecisionResponse(session, nodeId, label, conditions);

        // Show saved params so LLM knows what was actually stored
        // Sanitize: only include label, expression, type, and computed port
        // Strip condition id (uses dashes, confuses LLM) and unknown LLM-provided fields
        List<Map<String, Object>> sanitizedConditions = new ArrayList<>();
        int savedElseifIdx = 0;
        for (int i = 0; i < conditionsList.size(); i++) {
            Map<String, Object> cond = conditionsList.get(i);
            String condType = (String) cond.get("type");
            String port;
            if ("if".equals(condType)) {
                port = "if";
            } else if ("else".equals(condType)) {
                port = "else";
            } else {
                port = "elseif_" + savedElseifIdx;
                savedElseifIdx++;
            }
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("label", cond.get("label"));
            sanitized.put("expression", cond.get("expression"));
            sanitized.put("type", condType);
            sanitized.put("port", port);
            sanitizedConditions.add(sanitized);
        }
        response.put("saved_params", Map.of("conditions", sanitizedConditions));

        // Connection status is already in ResponseOptimizer response (connection.status)
        // Only show progressive_validation for OTHER orphan nodes (not the one just created)
        int totalNodes = session.getTriggers().size() + session.getMcps().size() + session.getCores().size();
        if (totalNodes >= 3) {
            List<String> orphans = session.findOrphanNodes().stream()
                .filter(id -> !id.equals(nodeId))  // Exclude current node (already in connection section)
                .toList();
            if (!orphans.isEmpty()) {
                Map<String, Object> validation = new LinkedHashMap<>();
                validation.put("other_orphan_nodes", orphans.stream()
                    .map(id -> Map.of("id", id, "logical_id", session.getLogicalId(id)))
                    .toList());
                validation.put("hint", "Other nodes are also disconnected. Use workflow(action='connect', from='Source Label', to='Target Label')");
                response.put("progressive_validation", validation);
            }
        }

        return ToolExecutionResult.success(response);
    }

    // ==================== Add Switch ====================

    /**
     * Execute add_switch action.
     * Creates a switch/case node for multi-way branching based on expression value.
     * NEW FORMAT: All parameters are flat, no nested object.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddSwitch(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label (flat param)
        String label = safeString(parameters.get("label"));
        if (label == null) label = safeString(parameters.get("name"));
        var labelError = validateLabel(label, "switch");
        if (labelError != null) return labelError;

        // 2. MANDATORY: Trigger must exist before adding switch
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add switch without a trigger. " +
                "Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.SWITCH.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 4. Resolve connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 5. Parse switch expression (flat param)
        // Variable references are deep-normalized when the node is added to session
        String switchExpression = safeString(parameters.get("expression"));
        if (switchExpression == null) switchExpression = safeString(parameters.get("switchExpression"));
        if (switchExpression == null || switchExpression.isBlank()) {
            switchExpression = "{{input.value}}"; // Default placeholder
        }

        // 6. Parse switch cases (flat param)
        List<Map<String, Object>> casesParam = (List<Map<String, Object>>) parameters.get("cases");
        if (casesParam == null) casesParam = (List<Map<String, Object>>) parameters.get("switchCases");

        List<Map<String, Object>> switchCases = new ArrayList<>();
        if (casesParam != null && !casesParam.isEmpty()) {
            int caseIndex = 0;
            for (Map<String, Object> caseItem : casesParam) {
                String caseType = safeString(caseItem.get("type"));
                if (caseType == null) caseType = "case";

                String caseLabel = safeString(caseItem.get("label"));
                if (caseLabel == null) caseLabel = "default".equals(caseType) ? "Default" : "Case " + caseIndex;

                String caseValue = safeString(caseItem.get("value"));

                Map<String, Object> switchCase = new LinkedHashMap<>();
                switchCase.put("id", nodeId + "-" + caseType + ("case".equals(caseType) ? "-" + caseIndex : ""));
                switchCase.put("type", caseType);
                switchCase.put("label", caseLabel);
                if (caseValue != null && !caseValue.isBlank()) {
                    switchCase.put("value", caseValue);
                }
                switchCases.add(switchCase);

                if ("case".equals(caseType)) caseIndex++;
            }
        } else {
            // Default: two cases + default
            switchCases.add(Map.of("id", nodeId + "-case-0", "type", "case", "label", "Case A", "value", "a"));
            switchCases.add(Map.of("id", nodeId + "-case-1", "type", "case", "label", "Case B", "value", "b"));
            switchCases.add(Map.of("id", nodeId + "-default", "type", "default", "label", "Default"));
        }

        // 7. Build switch node
        Map<String, Object> switchNode = new LinkedHashMap<>();
        switchNode.put("id", "core:" + normalizedLabel);
        switchNode.put("label", label);
        switchNode.put("type", "switch");
        switchNode.put("position", calculatePosition(session, NodeType.SWITCH));
        switchNode.put("switchExpression", switchExpression);
        switchNode.put("switchCases", switchCases);

        // Connect to previous node if specified
        if (connectAfter != null && !connectAfter.isBlank() && !connectAfter.contains(":body")) {
            String resolvedSource = session.resolveNodeReference(connectAfter);
            createSimpleEdge(session, resolvedSource, "core:" + normalizedLabel);
        }

        // Add to session (deep-normalize all variable references)
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(switchNode));

        // 7. Finalize
        boolean isOrphaned = finalizeNode(session, sessionStore, NodeType.SWITCH, nodeId, switchNode, connectAfter);

        // Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "OK");
        response.put("message", "Switch '" + label + "' created with " + switchCases.size() + " cases");
        response.put("node_id", "core:" + normalizedLabel);
        response.put("logical_id", session.getLogicalId(nodeId));
        response.put("switch_expression", switchExpression);
        response.put("cases", switchCases.stream().map(c -> Map.of(
            "type", c.get("type"),
            "label", c.get("label"),
            "port", "default".equals(c.get("type")) ? "default" : "case_" + switchCases.indexOf(c)
        )).toList());
        response.put("hint", "Connect each case using: workflow(action='connect', from='core:" + normalizedLabel + ":case_0', to='Next Step Label')");

        // Show saved params so LLM knows what was actually stored
        // Sanitize: strip dashed IDs, include only label, type, value, and computed port
        List<Map<String, Object>> sanitizedCases = new ArrayList<>();
        int sanitizedCaseIdx = 0;
        for (Map<String, Object> c : switchCases) {
            String caseType = (String) c.get("type");
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("label", c.get("label"));
            sanitized.put("type", caseType);
            if (c.containsKey("value")) sanitized.put("value", c.get("value"));
            sanitized.put("port", "default".equals(caseType) ? "default" : "case_" + sanitizedCaseIdx);
            if (!"default".equals(caseType)) sanitizedCaseIdx++;
            sanitizedCases.add(sanitized);
        }
        response.put("saved_params", Map.of(
            "expression", switchExpression,
            "cases", sanitizedCases
        ));

        return ToolExecutionResult.success(response);
    }

    // ==================== Add Option ====================

    /**
     * Execute add_option action.
     * Creates a multiple choice node where each choice has an expression.
     * First choice whose expression evaluates to true wins.
     * NEW FORMAT: All parameters are flat, no nested object.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddOption(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label (flat param)
        String label = safeString(parameters.get("label"));
        if (label == null) label = safeString(parameters.get("name"));
        var labelError = validateLabel(label, "option");
        if (labelError != null) return labelError;

        // 2. MANDATORY: Trigger must exist before adding option
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add option without a trigger. " +
                "Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Validate choices (flat param)
        List<Map<String, Object>> choices = (List<Map<String, Object>>) parameters.get("choices");
        if (choices == null || choices.isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "OPTION: 'choices' array is required.\n\n" +
                "FORMAT:\n" +
                "  workflow(action='add_node', type='option', label='Route Request',\n" +
                "    params={choices: [\n" +
                "      {label: 'Priority', expression: '{{mcp:analyze.output.priority}} == \"high\"'},\n" +
                "      {label: 'Standard', expression: '{{mcp:analyze.output.priority}} == \"normal\"'},\n" +
                "      {label: 'Low', expression: 'true'}  // fallback\n" +
                "    ]}, connect_after='...')\n\n" +
                "THEN CONNECT EACH CHOICE TO DIFFERENT TARGETS:\n" +
                "  workflow(action='connect', from='Route Request', to='Fast Track')  // choice_0\n" +
                "  workflow(action='connect', from='Route Request', to='Normal Queue')  // choice_1");
        }

        // 4. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.OPTION.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 5. Resolve connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 6. Build option node
        Map<String, Object> optionNode = new LinkedHashMap<>();
        optionNode.put("id", "core:" + normalizedLabel);
        optionNode.put("label", label);
        optionNode.put("type", "option");
        optionNode.put("position", calculatePosition(session, NodeType.OPTION));

        // Build choices list
        List<Map<String, Object>> choicesList = new ArrayList<>();
        int choiceIndex = 0;
        for (Map<String, Object> choice : choices) {
            String choiceLabel = safeString(choice.get("label"));
            if (choiceLabel == null) choiceLabel = safeString(choice.get("name"));
            if (choiceLabel == null) choiceLabel = "Choice " + choiceIndex;

            String expression = safeString(choice.get("expression"));
            if (expression == null) expression = safeString(choice.get("condition"));
            if (expression == null) expression = "true";

            String choiceId = nodeId + "-choice-" + choiceIndex;

            choicesList.add(Map.of(
                "id", choiceId,
                "label", choiceLabel,
                "expression", expression
            ));
            choiceIndex++;
        }
        optionNode.put("optionChoices", choicesList);

        // 6. Add to session (deep-normalize all variable references) and create edge
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(optionNode));
        if (connectAfter != null && !connectAfter.isBlank()) {
            createSimpleEdge(session, connectAfter, nodeId);
        }

        // 7. Finalize
        boolean isOrphaned = finalizeNode(session, sessionStore, NodeType.OPTION, nodeId, optionNode, connectAfter);

        // 8. Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("node_type", "option");
        response.put("node_id", "core:" + normalizedLabel);
        response.put("label", label);
        response.put("choices_count", choicesList.size());

        // Show created choices with ports
        List<Map<String, Object>> choicesInfo = new ArrayList<>();
        for (int i = 0; i < choicesList.size(); i++) {
            Map<String, Object> choiceInfo = new LinkedHashMap<>();
            choiceInfo.put("label", choicesList.get(i).get("label"));
            choiceInfo.put("expression", choicesList.get(i).get("expression"));
            choiceInfo.put("port", "choice_" + i);
            choiceInfo.put("connect_from", "core:" + normalizedLabel + ":choice_" + i);
            choicesInfo.add(choiceInfo);
        }
        response.put("choices", choicesInfo);

        // Connection info
        Map<String, Object> connectionInfo = new LinkedHashMap<>();
        connectionInfo.put("status", connectAfter != null ? "connected" : "orphaned");
        if (connectAfter != null) {
            connectionInfo.put("connected_after", connectAfter);
        }
        response.put("connection", connectionInfo);

        // NEXT pattern
        response.put("NEXT", Map.of(
            "connect_each_choice", "workflow(action='connect', from='core:" + normalizedLabel + ":choice_N', to='Target Label')",
            "example", "workflow(action='connect', from='core:" + normalizedLabel + ":choice_0', to='Priority Handler')",
            "outputs", "{{core:" + normalizedLabel + ".output.selected_choice}}, {{core:" + normalizedLabel + ".output.selected_label}}"
        ));

        // Show saved params so LLM knows what was actually stored
        // Sanitize: strip dashed IDs, include only label, expression, and computed port
        List<Map<String, Object>> sanitizedChoices = new ArrayList<>();
        for (int ci = 0; ci < choicesList.size(); ci++) {
            Map<String, Object> c = choicesList.get(ci);
            Map<String, Object> sanitized = new LinkedHashMap<>();
            sanitized.put("label", c.get("label"));
            sanitized.put("expression", c.get("expression"));
            sanitized.put("port", "choice_" + ci);
            sanitizedChoices.add(sanitized);
        }
        response.put("saved_params", Map.of("choices", sanitizedChoices));

        return ToolExecutionResult.success(response);
    }

    // ==================== Add Approval ====================

    /**
     * Execute add_approval action.
     * Creates an approval node with 3 ports: approved, rejected, timeout.
     * The workflow pauses until a user approves/rejects or the timeout expires.
     */
    @SuppressWarnings("unchecked")
    public ToolExecutionResult executeAddApproval(WorkflowBuilderSession session, Map<String, Object> parameters) {
        // 1. Validate label
        String label = safeString(parameters.get("label"));
        if (label == null) label = safeString(parameters.get("name"));
        var labelError = validateLabel(label, "approval");
        if (labelError != null) return labelError;

        // 2. MANDATORY: Trigger must exist
        if (session.getTriggers().isEmpty()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "TRIGGER REQUIRED FIRST: Cannot add approval without a trigger. " +
                "Create a trigger first using: workflow(action='add_node', type='form', label='...', params={...})");
        }

        // 3. Generate node ID and check uniqueness
        String normalizedLabel = WorkflowBuilderSession.normalizeLabel(label);
        String nodeId = NodeType.APPROVAL.buildNodeId(normalizedLabel);
        var existsError = validateNodeNotExists(session, nodeId, label);
        if (existsError != null) return existsError;

        // 4. Resolve connect_after
        String connectAfter = resolveConnectAfter(parameters, session);
        var connectAfterError = validateConnectAfter(connectAfter, session);
        if (connectAfterError != null) return connectAfterError;

        // 5. Parse approval config
        List<String> approverRoles = new ArrayList<>();
        Object rolesObj = parameters.get("approver_roles");
        if (rolesObj == null) rolesObj = parameters.get("approverRoles");
        if (rolesObj == null) rolesObj = parameters.get("roles");
        if (rolesObj instanceof List<?> rolesList) {
            for (Object r : rolesList) {
                if (r instanceof String s) approverRoles.add(s);
            }
        }

        Integer requiredApprovals = toIntegerOrNull(parameters.get("required_approvals"));
        if (requiredApprovals == null) requiredApprovals = toIntegerOrNull(parameters.get("requiredApprovals"));
        if (requiredApprovals == null) requiredApprovals = 1;

        Long timeoutMs = toLongOrNull(parameters.get("timeout_ms"));
        if (timeoutMs == null) timeoutMs = toLongOrNull(parameters.get("timeoutMs"));
        if (timeoutMs == null) timeoutMs = toLongOrNull(parameters.get("timeout"));
        if (timeoutMs == null) timeoutMs = 86400000L; // 24h default

        // contextTemplate: the message shown to the approver (literal + {{...}}), resolved at yield.
        String contextTemplate = safeString(parameters.get("contextTemplate"));
        if (contextTemplate == null) contextTemplate = safeString(parameters.get("context_template"));

        // Optional external-channel delegation block (channel/credentialId/chatId/
        // messageTemplate/image/allowedUserIds). Sanitized, not verbatim: LLMs routinely
        // quote numbers, and a credentialId stored as the string "40" used to pass
        // creation, WARN at validate, then be silently dropped by the plan parser at
        // run time (no Telegram message, no error). Coerce it to a number here so the
        // session holds the canonical shape; CoreValidator flags unknown channels and
        // missing credential/chatId at validate time.
        Object delegation = sanitizeDelegation(parameters.get("delegation"));

        // 6. Build approval node
        Map<String, Object> approvalConfig = new LinkedHashMap<>();
        approvalConfig.put("approverRoles", approverRoles);
        approvalConfig.put("requiredApprovals", requiredApprovals);
        approvalConfig.put("timeoutMs", timeoutMs);
        if (contextTemplate != null) approvalConfig.put("contextTemplate", contextTemplate);
        if (delegation != null) approvalConfig.put("delegation", delegation);

        Map<String, Object> approvalNode = new LinkedHashMap<>();
        approvalNode.put("id", nodeId);
        approvalNode.put("label", label);
        approvalNode.put("type", "approval");
        approvalNode.put("position", calculatePosition(session, NodeType.APPROVAL));
        approvalNode.put("approval", approvalConfig);

        // 7. Add to session and create edge
        session.getCores().add(LabelNormalizer.normalizeVariableReferencesDeep(approvalNode));
        if (connectAfter != null && !connectAfter.isBlank()) {
            createSimpleEdge(session, connectAfter, nodeId);
        }

        // 8. Finalize
        finalizeNode(session, sessionStore, NodeType.APPROVAL, nodeId, approvalNode, connectAfter);

        // 9. Build response
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("node_type", "approval");
        response.put("node_id", nodeId);
        response.put("label", label);
        response.put("ports", List.of("approved", "rejected", "timeout"));
        response.put("approver_roles", approverRoles);
        response.put("required_approvals", requiredApprovals);
        response.put("timeout_ms", timeoutMs);

        // Connection info
        response.put("connection", Map.of(
            "status", connectAfter != null ? "connected" : "orphaned",
            "connected_after", connectAfter != null ? connectAfter : "none"
        ));

        // Show saved params
        Map<String, Object> savedParams = new LinkedHashMap<>();
        savedParams.put("approverRoles", approverRoles);
        savedParams.put("requiredApprovals", requiredApprovals);
        savedParams.put("timeoutMs", timeoutMs);
        if (contextTemplate != null) savedParams.put("contextTemplate", contextTemplate);
        if (delegation != null) savedParams.put("delegation", delegation);
        response.put("saved_params", savedParams);

        // NEXT pattern
        response.put("NEXT", Map.of(
            "connect_approved", "workflow(action='connect', from='core:" + normalizedLabel + ":approved', to='<approved step>')",
            "connect_rejected", "workflow(action='connect', from='core:" + normalizedLabel + ":rejected', to='<rejected step>')",
            "connect_timeout", "workflow(action='connect', from='core:" + normalizedLabel + ":timeout', to='<timeout step>')",
            "outputs", "{{core:" + normalizedLabel + ".output.resolved_by}}, {{core:" + normalizedLabel + ".output.resolution}}, {{core:" + normalizedLabel + ".output.selected_port}}"
        ));

        return ToolExecutionResult.success(response);
    }

    // ==================== Delegation Helpers ====================

    /**
     * Sanitize the approval delegation block from LLM params. Returns null for a
     * missing/empty/non-map value. Coerces a numeric-string credentialId (LLMs
     * routinely quote numbers) to a Long so every downstream consumer (validator,
     * plan parser, frontend import) sees the canonical numeric shape.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sanitizeDelegation(Object raw) {
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        Map<String, Object> delegation = new LinkedHashMap<>((Map<String, Object>) map);
        Object credentialId = delegation.get("credentialId");
        if (credentialId instanceof String s && !s.isBlank()) {
            try {
                delegation.put("credentialId", Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                // Non-numeric string: leave as-is; CoreValidator flags it.
            }
        }
        return delegation;
    }

    // ==================== Label Helpers ====================

    /**
     * Generates a readable default branch label when the LLM omits it.
     * Prevents expressions from being used as labels (e.g. "{{mcp:x.output}} == true" as label).
     */
    private static String defaultBranchLabel(String condType, int index) {
        return switch (condType) {
            case "if" -> "If Branch";
            case "else" -> "Otherwise";
            case "elseif" -> "Else If " + (index);
            default -> "Branch " + index;
        };
    }

    // ==================== Format Recovery ====================

    /**
     * Attempts to recover conditions from common LLM hallucination formats.
     * <p>
     * Handles:
     * - 'condition' (singular) as a List → use directly
     * - 'condition' (singular) as a Map with {if: {field, operator}, ...} → convert to conditions array
     * - 'condition' (singular) as a Map with {condition, label} → wrap in array
     *
     * @return recovered conditions list, or null if unrecoverable
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> tryRecoverConditions(Map<String, Object> parameters) {
        Object conditionObj = parameters.get("condition");
        if (conditionObj == null) return null;

        // Case 1: 'condition' is already a List (LLM used singular but correct structure)
        if (conditionObj instanceof List) {
            try {
                return (List<Map<String, Object>>) conditionObj;
            } catch (ClassCastException e) {
                return null;
            }
        }

        // Case 2: 'condition' is a Map - hallucinated format
        if (conditionObj instanceof Map) {
            Map<String, Object> conditionMap = (Map<String, Object>) conditionObj;

            // Sub-case 2a: {if: {field, operator}, else: ...} or {if: {expression}} format
            // Convert each key (if/elseif/else) into a condition entry
            if (conditionMap.containsKey("if") || conditionMap.containsKey("else")) {
                return convertIfElseMapToConditions(conditionMap);
            }

            // Sub-case 2b: Single condition object {condition: '...', label: '...'} - wrap in list
            if (conditionMap.containsKey("condition") || conditionMap.containsKey("expression") || conditionMap.containsKey("field")) {
                List<Map<String, Object>> result = new ArrayList<>();
                result.add(convertSingleCondition(conditionMap));
                // Add default else branch
                Map<String, Object> elseBranch = new LinkedHashMap<>();
                elseBranch.put("condition", "default");
                elseBranch.put("label", "Otherwise");
                result.add(elseBranch);
                return result;
            }
        }

        return null;
    }

    /**
     * Converts a hallucinated {if: {...}, else: {...}} map into a proper conditions array.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> convertIfElseMapToConditions(Map<String, Object> conditionMap) {
        List<Map<String, Object>> conditions = new ArrayList<>();

        // Process 'if' branch
        Object ifObj = conditionMap.get("if");
        if (ifObj instanceof Map) {
            conditions.add(convertSingleCondition((Map<String, Object>) ifObj));
        } else if (ifObj instanceof String ifStr) {
            Map<String, Object> ifCond = new LinkedHashMap<>();
            ifCond.put("condition", ifStr);
            ifCond.put("label", "If");
            conditions.add(ifCond);
        }

        // Process 'elseif' / 'else_if' branches
        for (String key : List.of("elseif", "else_if", "elseif_0", "elseif_1", "elseif_2")) {
            Object elseifObj = conditionMap.get(key);
            if (elseifObj instanceof Map) {
                conditions.add(convertSingleCondition((Map<String, Object>) elseifObj));
            } else if (elseifObj instanceof String elseifStr) {
                Map<String, Object> elseifCond = new LinkedHashMap<>();
                elseifCond.put("condition", elseifStr);
                elseifCond.put("label", key);
                conditions.add(elseifCond);
            }
        }

        // Process 'else' branch (always last, always default)
        Object elseObj = conditionMap.get("else");
        Map<String, Object> elseCond = new LinkedHashMap<>();
        if (elseObj instanceof Map) {
            Map<String, Object> elseMap = (Map<String, Object>) elseObj;
            elseCond.put("condition", "default");
            elseCond.put("label", elseMap.getOrDefault("label", "Otherwise"));
        } else if (elseObj instanceof String elseStr) {
            elseCond.put("condition", "default");
            elseCond.put("label", elseStr);
        } else {
            elseCond.put("condition", "default");
            elseCond.put("label", "Otherwise");
        }
        conditions.add(elseCond);

        return conditions.isEmpty() ? null : conditions;
    }

    /**
     * Converts a single hallucinated condition entry into the standard {condition, label} format.
     * Handles various hallucinated keys: field+operator, expression, expr.
     */
    private static Map<String, Object> convertSingleCondition(Map<String, Object> raw) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Handle {field, operator} format first → build expression (takes priority)
        String expression = null;
        if (raw.containsKey("field")) {
            String field = (String) raw.get("field");
            String operator = (String) raw.get("operator");
            expression = buildExpressionFromFieldOperator(field, operator, (String) raw.get("value"));
        }

        // Fallback: try standard expression keys
        if (expression == null) expression = (String) raw.get("condition");
        if (expression == null) expression = (String) raw.get("expression");
        if (expression == null) expression = (String) raw.get("expr");

        result.put("condition", expression != null ? expression : "true");

        // Extract label
        String label = (String) raw.get("label");
        if (label == null) label = (String) raw.get("name");
        result.put("label", label != null ? label : "Branch");

        return result;
    }

    /**
     * Builds a SpEL expression from a hallucinated {field, operator, value} format.
     */
    private static String buildExpressionFromFieldOperator(String field, String operator, String value) {
        if (field == null) return "true";
        if (operator == null) return field;

        return switch (operator.toLowerCase()) {
            case "is_not_empty", "not_empty", "isnotempty" ->
                "{{isempty(" + stripBraces(field) + ")}} == false";
            case "is_empty", "empty", "isempty" ->
                "{{isempty(" + stripBraces(field) + ")}} == true";
            case "equals", "eq", "==" ->
                field + " == " + (value != null ? "\"" + value + "\"" : "true");
            case "not_equals", "neq", "!=" ->
                field + " != " + (value != null ? "\"" + value + "\"" : "true");
            case "contains" ->
                "{{contains(" + stripBraces(field) + ", '" + (value != null ? value : "") + "')}}";
            case "greater_than", "gt", ">" ->
                field + " > " + (value != null ? value : "0");
            case "less_than", "lt", "<" ->
                field + " < " + (value != null ? value : "0");
            default -> field + " " + operator + " " + (value != null ? value : "");
        };
    }

    /**
     * Strips surrounding {{ }} braces from a field reference if present.
     */
    private static String stripBraces(String field) {
        if (field == null) return "";
        String stripped = field.trim();
        if (stripped.startsWith("{{") && stripped.endsWith("}}")) {
            return stripped.substring(2, stripped.length() - 2);
        }
        return stripped;
    }

    // ==================== Decision Condition Expansion ====================

    /**
     * Dynamically expand decision conditions when more connections than ports.
     * Adds 'else' if missing, or 'elseif_N' before else otherwise.
     * Called from both CreatorBase and ConnectionManager autoAssignBranchPort.
     *
     * @param coreNode   the decision core node map (must contain "id")
     * @param conditions the mutable decisionConditions list
     * @param nextIdx    the index of the next connection (== existing connections count)
     * @return the port name assigned to the new condition (e.g. "else" or "elseif_0")
     */
    public static String expandDecisionConditions(Map<String, Object> coreNode,
                                                   List<Map<String, Object>> conditions,
                                                   int nextIdx) {
        String nodeId = (String) coreNode.get("id");
        String normalizedLabel = nodeId != null && nodeId.startsWith("core:") ? nodeId.substring(5) : nodeId;

        boolean hasElse = conditions.stream().anyMatch(c -> "else".equals(c.get("type")));

        if (!hasElse) {
            Map<String, Object> elseCond = new LinkedHashMap<>();
            elseCond.put("id", normalizedLabel + "-else");
            elseCond.put("type", "else");
            elseCond.put("label", "Otherwise");
            elseCond.put("expression", "default");
            conditions.add(elseCond);
            return "else";
        } else {
            // Port index must match the RUNTIME numbering (Core.getDecisionPorts:
            // elseif_N = position in decisionConditions minus 1). The new elseif is
            // inserted just before the trailing else, so its position is
            // conditions.size()-1 post-insert => index conditions.size()-2 pre-insert.
            // The old nextIdx-1 also counted the WIRED else edge, yielding an
            // elseif_N one above the declared port - the same declared-vs-wired
            // desync as the fork/option/classify overflow, just off by one.
            int elseifIdx = Math.max(0, conditions.size() - 2);
            Map<String, Object> elseifCond = new LinkedHashMap<>();
            elseifCond.put("id", normalizedLabel + "-elseif-" + elseifIdx);
            elseifCond.put("type", "elseif");
            elseifCond.put("label", "Else If " + (elseifIdx + 1));
            elseifCond.put("expression", "true");
            // Insert before else (last element)
            conditions.add(conditions.size() - 1, elseifCond);
            return "elseif_" + elseifIdx;
        }
    }

    // ==================== Edge Creation Helpers ====================

    /**
     * V2: Creates a simple entry edge to a decision node.
     * Conditions are stored in Core.decisionConditions.
     * Branch connections are created separately via connect action with ports:
     *   - decision:label:if -> target
     *   - decision:label:else -> target
     *   - decision:label:elseif_N -> target
     */
    private void createDecisionEdge(WorkflowBuilderSession session, String from, String decisionId,
                                    List<Map<String, Object>> conditions) {
        // V2: Simple entry edge to decision node
        createSimpleEdge(session, from, decisionId);
    }
}
