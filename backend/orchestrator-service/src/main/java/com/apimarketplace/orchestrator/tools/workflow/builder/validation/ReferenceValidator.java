package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.ExpressionNodeReferences;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates workflow references and credentials.
 *
 * Rules enforced:
 * - References must point to valid nodes
 * - Credentials must be connected for execution
 */
@Slf4j
@Component
public class ReferenceValidator implements WorkflowValidator {

    // Mirrors TemplateEngine.EXPRESSION_PATTERN - handles SpEL string literals containing `}`.
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{((?:'(?:[^'\\\\]|\\\\.)*'|[^}|])+?)(?:\\|[^}]*)?\\}\\}");

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        validateReferences(session, result);
        validateCredentials(session, result);
    }

    private static final Set<String> SKIP_KEYS = Set.of(
            "id", "label", "type", "graphNodeId", "iconSlug", "position", "isAgent",
            "isGuardrail", "isClassify", "datasource_id", "dataSourceId", "tools"
    );

    private void validateReferences(WorkflowBuilderSession session, ValidationResult result) {
        Set<String> validNodeIds = new HashSet<>();

        for (Map<String, Object> trigger : session.getTriggers()) {
            validNodeIds.add("trigger:" + WorkflowBuilderSession.normalizeLabel((String) trigger.get("label")));
        }
        for (Map<String, Object> step : session.getMcps()) {
            validNodeIds.add(getStepNodeId(step));
        }
        for (Map<String, Object> cn : session.getCores()) {
            validNodeIds.add(getCoreId(cn));
        }
        for (Map<String, Object> iface : session.getInterfaces()) {
            String label = (String) iface.get("label");
            if (label != null && !label.isBlank()) {
                validNodeIds.add("interface:" + WorkflowBuilderSession.normalizeLabel(label));
            }
        }
        for (Map<String, Object> table : session.getTables()) {
            String label = (String) table.get("label");
            if (label != null && !label.isBlank()) {
                validNodeIds.add("table:" + WorkflowBuilderSession.normalizeLabel(label));
            }
        }

        for (Map<String, Object> step : session.getMcps()) {
            scanNodeReferences(step, getStepNodeId(step), validNodeIds, result);
        }
        for (Map<String, Object> cn : session.getCores()) {
            scanNodeReferences(cn, getCoreId(cn), validNodeIds, result);
        }
        for (Map<String, Object> iface : session.getInterfaces()) {
            String label = (String) iface.get("label");
            if (label != null && !label.isBlank()) {
                scanNodeReferences(iface, "interface:" + WorkflowBuilderSession.normalizeLabel(label), validNodeIds, result);
            }
        }
        for (Map<String, Object> table : session.getTables()) {
            String label = (String) table.get("label");
            if (label != null && !label.isBlank()) {
                scanNodeReferences(table, "table:" + WorkflowBuilderSession.normalizeLabel(label), validNodeIds, result);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void scanNodeReferences(Map<String, Object> node, String nodeId,
                                    Set<String> validNodeIds, ValidationResult result) {
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            if (SKIP_KEYS.contains(entry.getKey())) continue;
            scanValue(entry.getValue(), nodeId, validNodeIds, result);
        }
    }

    @SuppressWarnings("unchecked")
    private void scanValue(Object value, String nodeId,
                           Set<String> validNodeIds, ValidationResult result) {
        if (value instanceof String strValue) {
            for (String ref : extractReferences(strValue)) {
                for (String referencedNode : ExpressionNodeReferences.of(ref)) {
                    if (!validNodeIds.contains(referencedNode)) {
                        result.addWarning("INVALID_REFERENCE", nodeId,
                                "Reference '{{" + ref + "}}' points to unknown node '" + referencedNode + "'.");
                    }
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                scanValue(v, nodeId, validNodeIds, result);
            }
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                scanValue(item, nodeId, validNodeIds, result);
            }
        }
    }

    private List<String> extractReferences(String value) {
        List<String> refs = new ArrayList<>();
        Matcher matcher = VARIABLE_PATTERN.matcher(value);
        while (matcher.find()) {
            refs.add(matcher.group(1));
        }
        return refs;
    }

    /**
     * Validate that all required credentials are available.
     * Missing credentials generate WARNINGS (not errors) because the workflow
     * is structurally valid but won't execute without credentials.
     */
    private void validateCredentials(WorkflowBuilderSession session, ValidationResult result) {
        if (session.hasMissingCredentials()) {
            var missingCreds = session.getMissingCredentials();
            var services = session.getMissingCredentialServices();

            // Add warning for each step with missing credentials
            for (var entry : missingCreds.entrySet()) {
                String nodeId = entry.getKey();
                String serviceName = entry.getValue().get("serviceName");
                String logicalId = session.getLogicalIdOrFail(nodeId);

                result.addWarning("MISSING_CREDENTIAL", nodeId,
                    "Step " + logicalId + " requires " + serviceName + " credential (not connected)");
            }

            // Add summary warning
            if (!services.isEmpty()) {
                result.addWarning("CREDENTIALS_REQUIRED", null,
                    "Workflow requires credentials for: " + String.join(", ", services) +
                    ". Use credential(action='require') to connect them before execution.");
            }
        }
    }

    private String getStepNodeId(Map<String, Object> step) {
        String label = (String) step.get("label");
        Boolean isAgent = (Boolean) step.get("isAgent");
        String prefix = (isAgent != null && isAgent) ? "agent:" : "mcp:";
        return prefix + WorkflowBuilderSession.normalizeLabel(label);
    }

    private String getCoreId(Map<String, Object> cn) {
        String label = (String) cn.get("label");
        return "core:" + WorkflowBuilderSession.normalizeLabel(label);
    }
}
