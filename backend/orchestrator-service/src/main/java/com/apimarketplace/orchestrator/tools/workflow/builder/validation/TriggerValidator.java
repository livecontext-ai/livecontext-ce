package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Validates workflow triggers.
 *
 * Rules enforced:
 * - At least one trigger required
 * - Multiple triggers allowed (each creates an independent DAG)
 * - Trigger must have a label
 * - Trigger must have outgoing edges
 */
@Slf4j
@Component
public class TriggerValidator implements WorkflowValidator {

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);
        validateTriggers(session, graph, result);
    }

    /**
     * Validate with an existing graph analyzer (for performance when validating multiple aspects).
     */
    public void validate(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        validateTriggers(session, graph, result);
    }

    private void validateTriggers(WorkflowBuilderSession session, ValidationGraphAnalyzer graph, ValidationResult result) {
        List<Map<String, Object>> triggers = session.getTriggers();

        // Rule: At least one trigger required
        if (triggers.isEmpty()) {
            result.addError("MISSING_TRIGGER", null, "Workflow must have at least one trigger.");
        }

        // Validate each trigger
        for (int i = 0; i < triggers.size(); i++) {
            Map<String, Object> trigger = triggers.get(i);
            String label = (String) trigger.get("label");
            if (label == null || label.isBlank()) {
                result.addError("MISSING_LABEL", "trigger:unknown", "Trigger must have a label.");
                continue;
            }

            String nodeId = "trigger:" + WorkflowBuilderSession.normalizeLabel(label);

            // Rule: Trigger must have outgoing edges
            if (!graph.hasOutgoingEdges(nodeId)) {
                result.addError("TRIGGER_NO_EDGES", "triggers[" + i + "]",
                        "Trigger '" + label + "' has no outgoing edges and will not execute anything. " +
                        "Add a step: workflow(action='add_node', type='<tool-uuid>', ..., connect_after='" + label + "') or workflow(action='connect', from='" + label + "', to='Step Label').");
            }
        }
    }
}
