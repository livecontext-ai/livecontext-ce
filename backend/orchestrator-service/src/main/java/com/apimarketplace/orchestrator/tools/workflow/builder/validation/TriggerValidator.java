package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.FormFieldCanonicalizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
 * - A form trigger's fields are ones add_node would accept (a WARNING)
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

            // Rule: a form trigger's fields are ones add_node would accept. A WARNING, never an
            // error: a workflow saved long ago with such a field must stay saveable, the way
            // set_plan imports it (it names the problem, it never refuses).
            if ("form".equals(trigger.get("type")) && trigger.get("params") instanceof Map<?, ?> params
                    && params.containsKey("fields")) {
                @SuppressWarnings("unchecked")
                List<String> issues = FormFieldCanonicalizer.canonicalize(
                        new LinkedHashMap<>((Map<String, Object>) params));
                if (!issues.isEmpty()) {
                    result.addWarning("FORM_FIELD_INVALID", nodeId,
                            "Form trigger '" + label + "' has fields add_node would refuse: "
                            + String.join("; ", issues) + ". Fix them with workflow(action='modify', node='"
                            + label + "', params={fields: [...]}).");
                }
            }
        }
    }
}
