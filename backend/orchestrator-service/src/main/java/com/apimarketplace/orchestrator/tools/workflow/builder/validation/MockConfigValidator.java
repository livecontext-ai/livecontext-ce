package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.domain.workflow.NodeMock;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates every {@code mock} block in the session against its node's REAL
 * current type and ports, by parsing a single-node mini plan through
 * {@link WorkflowPlanParser} - the exact validator the execution engine runs at
 * plan-parse time, so validate and runtime can never disagree.
 *
 * <p>Errors, not warnings: an invalid mock block fails {@code WorkflowPlanParser.parse}
 * for EVERY run of the plan (the block is parsed regardless of mock mode), so it
 * must be fixed before finish/execute. The typical drift: a mock's {@code port}
 * was valid when set, then the decision's branches were edited.
 *
 * <p>A mock block on a trigger/note is silently ignored at run time - surfaced
 * here as a warning so the author knows it does nothing.
 */
@Slf4j
@Component
public class MockConfigValidator implements WorkflowValidator {

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        // Agents live inside the session's mcps list - split them to their real plan section.
        for (Map<String, Object> node : session.getMcps()) {
            String type = String.valueOf(node.get("type"));
            boolean isAgent = "agent".equalsIgnoreCase(type) || "classify".equalsIgnoreCase(type)
                    || "guardrail".equalsIgnoreCase(type);
            checkNode(session, result, node, isAgent ? "agents" : "mcps", isAgent ? "agent" : "mcp");
        }
        for (Map<String, Object> node : session.getCores()) {
            checkNode(session, result, node, "cores", "core");
        }
        for (Map<String, Object> node : session.getTables()) {
            checkNode(session, result, node, "tables", "table");
        }
        for (Map<String, Object> node : session.getInterfaces()) {
            checkNode(session, result, node, "interfaces", "interface");
        }
        for (Map<String, Object> node : session.getTriggers()) {
            warnIgnored(result, node, "trigger");
        }
        for (Map<String, Object> node : session.getNotes()) {
            warnIgnored(result, node, "note");
        }
    }

    private void checkNode(WorkflowBuilderSession session, ValidationResult result,
                           Map<String, Object> node, String section, String prefix) {
        if (node == null || !node.containsKey(NodeMock.JSON_KEY) || node.get(NodeMock.JSON_KEY) == null) {
            return;
        }
        Map<String, Object> miniPlan = new LinkedHashMap<>();
        miniPlan.put(section, List.of(node));
        try {
            WorkflowPlanParser.parse(miniPlan, session.getTenantId());
        } catch (IllegalArgumentException e) {
            result.addError("MOCK_INVALID", nodeRef(node, prefix), NodeMock.JSON_KEY,
                    e.getMessage() + " Fix or remove the mock (workflow(action='modify', node='"
                            + node.get("label") + "', mock={})). Guide: workflow(action='help', topics=['mocking']).");
        } catch (RuntimeException e) {
            // A node that fails section parsing for non-mock reasons is another
            // validator's finding - never let the mock check crash validation.
            log.debug("Mock mini-parse skipped for {}: {}", node.get("label"), e.getMessage());
        }
    }

    private void warnIgnored(ValidationResult result, Map<String, Object> node, String prefix) {
        if (node == null || !node.containsKey(NodeMock.JSON_KEY) || node.get(NodeMock.JSON_KEY) == null) {
            return;
        }
        Object mockValue = node.get(NodeMock.JSON_KEY);
        if (mockValue instanceof Map<?, ?> map && map.isEmpty()) {
            return; // cleared block - nothing to warn about
        }
        result.addWarning("MOCK_IGNORED_ON_" + prefix.toUpperCase(java.util.Locale.ROOT),
                nodeRef(node, prefix),
                "A mock block on a " + prefix + " node is ignored at run time - " + prefix
                        + "s are not executed steps. Fake a trigger payload with data_inputs on execute, "
                        + "or move the mock to a downstream node.");
    }

    private static String nodeRef(Map<String, Object> node, String prefix) {
        Object label = node.get("label");
        if (label != null && !String.valueOf(label).isBlank()) {
            return prefix + ":" + WorkflowBuilderSession.normalizeLabel(String.valueOf(label));
        }
        Object id = node.get("id");
        return id != null ? String.valueOf(id) : null;
    }
}
