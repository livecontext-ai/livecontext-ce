package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.orchestrator.tools.workflow.builder.validation.*;
import com.apimarketplace.orchestrator.tools.workflow.builder.viewer.WorkflowErrorChecker;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Single source of truth for workflow plan validation.
 * Orchestrates specialized sub-validators AND the legacy WorkflowErrorChecker
 * so every caller (validate action, finish action, save from loaded edit session)
 * gets the SAME verdict.
 *
 * Key rules enforced:
 * - Trigger: 0 inputs, 1+ outputs, only 1 per workflow
 * - Step/Agent: 1+ inputs (merge allowed), 1+ outputs (fork allowed)
 * - Decision: EXACTLY 1 input, N exclusive outputs via port edges
 * - Loop: EXACTLY 1 input, N exclusive outputs via port edges
 * - No cycles (except loop body back to loop)
 * - All nodes must be reachable from trigger
 * - Per-node-type required params (classify categories, crud columns, split list, etc.)
 * - MCP tool id must exist in the catalog
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBuilderValidator {

    private final TriggerValidator triggerValidator;
    private final StepValidator stepValidator;
    private final CoreValidator coreValidator;
    private final EdgeValidator edgeValidator;
    private final GraphValidation graphValidator;
    private final ReferenceValidator referenceValidator;
    private final NodeStructureValidator nodeStructureValidator;
    private final OptionalComponentValidator optionalComponentValidator;
    private final WorkflowErrorChecker workflowErrorChecker;

    /**
     * Validation result with errors and warnings.
     * Carries both typed entries (from sub-validators) and the agent-facing Map
     * format (with "fix" hint) merged from {@link WorkflowErrorChecker}.
     */
    @Data
    @Builder
    public static class ValidationResult {
        private boolean valid;
        @Builder.Default
        private List<ValidationError> errors = new ArrayList<>();
        @Builder.Default
        private List<ValidationWarning> warnings = new ArrayList<>();
        @Builder.Default
        private List<Map<String, Object>> agentErrors = new ArrayList<>();
        @Builder.Default
        private List<Map<String, Object>> agentWarnings = new ArrayList<>();
        @Builder.Default
        private Map<String, Object> summary = new LinkedHashMap<>();

        public void addError(String code, String nodeId, String message) {
            errors.add(new ValidationError(code, nodeId, null, message));
        }

        public void addError(String code, String nodeId, String field, String message) {
            errors.add(new ValidationError(code, nodeId, field, message));
        }

        public void addWarning(String code, String nodeId, String message) {
            warnings.add(new ValidationWarning(code, nodeId, message));
        }
    }

    public record ValidationError(String code, String nodeId, String field, String message) {}
    public record ValidationWarning(String code, String nodeId, String message) {}

    /**
     * Validate a workflow builder session.
     * Runs every sub-validator AND the legacy error checker, then merges into
     * a unified result. Callers must treat {@code errors.isEmpty()} (equivalently
     * {@code valid == true}) as the only green signal.
     */
    public ValidationResult validate(WorkflowBuilderSession session) {
        ValidationResult result = ValidationResult.builder().build();

        ValidationGraphAnalyzer graph = new ValidationGraphAnalyzer(session);

        triggerValidator.validate(session, graph, result);
        stepValidator.validate(session, result);
        coreValidator.validate(session, result);
        edgeValidator.validate(session, graph, result);
        graphValidator.validate(session, graph, result);
        referenceValidator.validate(session, result);
        nodeStructureValidator.validate(session, result);
        optionalComponentValidator.validate(session, result);

        WorkflowErrorChecker.CheckResult legacy = workflowErrorChecker.checkForErrors(session);
        mergeLegacy(result, legacy);

        result.setSummary(Map.ofEntries(
                Map.entry("triggers", session.getTriggers().size()),
                Map.entry("mcps", session.getMcps().size()),
                Map.entry("cores", session.getCores().size()),
                Map.entry("interfaces", session.getInterfaces().size()),
                Map.entry("tables", session.getTables().size()),
                Map.entry("edges", session.getEdges().size())
        ));

        result.setValid(result.getErrors().isEmpty() && result.getAgentErrors().isEmpty());
        return result;
    }

    /**
     * Merge legacy {@link WorkflowErrorChecker} findings. Agent-facing Maps with
     * {type, node, message, fix} are kept as-is in {@code agentErrors/agentWarnings}
     * so the LLM sees the same structure it has always seen. Typed entries are
     * reserved for structural rules emitted by the sub-validators.
     */
    private void mergeLegacy(ValidationResult result, WorkflowErrorChecker.CheckResult legacy) {
        if (legacy.errors() != null && !legacy.errors().isEmpty()) {
            for (Map<String, Object> err : legacy.errors()) {
                result.getAgentErrors().add(new LinkedHashMap<>(err));
            }
        }
        if (legacy.warnings() != null && !legacy.warnings().isEmpty()) {
            for (Map<String, Object> warn : legacy.warnings()) {
                result.getAgentWarnings().add(new LinkedHashMap<>(warn));
            }
        }
    }

    /**
     * Render the unified result into the agent-facing format used by the
     * {@code validate} action, the {@code finish} guard, and the {@code save}
     * guard. All three paths now produce IDENTICAL payloads for the same
     * session - that is the whole point of this component.
     */
    public Map<String, Object> toAgentFormat(ValidationResult result) {
        List<Map<String, Object>> errors = new ArrayList<>();
        for (ValidationError e : result.getErrors()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", e.code());
            if (e.nodeId() != null) m.put("node", e.nodeId());
            if (e.field() != null) m.put("field", e.field());
            m.put("message", e.message());
            errors.add(m);
        }
        errors.addAll(result.getAgentErrors());

        List<Map<String, Object>> warnings = new ArrayList<>();
        for (ValidationWarning w : result.getWarnings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", w.code());
            if (w.nodeId() != null) m.put("node", w.nodeId());
            m.put("message", w.message());
            warnings.add(m);
        }
        warnings.addAll(result.getAgentWarnings());

        boolean canCreate = errors.isEmpty();
        String message;
        if (errors.isEmpty() && warnings.isEmpty()) {
            message = "No issues found! Workflow is valid.";
        } else if (errors.isEmpty()) {
            message = warnings.size() + " warning(s) found. Workflow can be created.";
        } else {
            message = errors.size() + " error(s) must be fixed before creating workflow.";
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("error_count", errors.size());
        out.put("warning_count", warnings.size());
        out.put("can_create", canCreate);
        out.put("message", message);
        return out;
    }
}
