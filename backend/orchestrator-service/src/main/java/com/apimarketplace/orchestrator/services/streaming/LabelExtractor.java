package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.springframework.stereotype.Component;

/**
 * Extracts and normalizes labels for workflow nodes.
 * Used for consistent keying in NodeEventStore and DB queries.
 */
@Component
public class LabelExtractor {

    /**
     * Extracts the NORMALIZED label to use as key in NodeEventStore.
     * Returns the normalized label (lowercase, underscores, alphanumeric only).
     *
     * Example: "list_bases (copy)" -> "list_bases_copy"
     *
     * @param execution The workflow execution context
     * @param stepId The step ID to look up
     * @param aliasToEmit The alias to emit (fallback)
     * @return The normalized label for DB queries
     */
    public String extractLabelForDbQuery(WorkflowExecution execution, String stepId, String aliasToEmit) {
        String rawLabel = null;

        // First try to get label from the plan's step definition
        if (execution != null && execution.getPlan() != null) {
            var stepOpt = execution.getPlan().findStep(stepId);
            if (stepOpt.isPresent() && stepOpt.get().label() != null) {
                rawLabel = stepOpt.get().label();
            }

            // Also try with aliasToEmit
            if (rawLabel == null && aliasToEmit != null && !aliasToEmit.equals(stepId)) {
                stepOpt = execution.getPlan().findStep(aliasToEmit);
                if (stepOpt.isPresent() && stepOpt.get().label() != null) {
                    rawLabel = stepOpt.get().label();
                }
            }

            // Try triggers
            if (rawLabel == null) {
                var triggerOpt = execution.getPlan().findTrigger(stepId);
                if (triggerOpt.isPresent() && triggerOpt.get().label() != null) {
                    rawLabel = triggerOpt.get().label();
                }
            }
        }

        // Fall back to extracting from the aliasToEmit (e.g., "mcp:test" -> "test")
        if (rawLabel == null && aliasToEmit != null) {
            rawLabel = extractRawLabelFromAlias(aliasToEmit);
        }

        // NORMALIZE the label for consistent NodeEventStore keying
        // This ensures "list_bases (copy)" becomes "list_bases_copy"
        if (rawLabel != null) {
            String normalized = LabelNormalizer.normalizeLabel(rawLabel);
            return normalized != null ? normalized : rawLabel;
        }

        return aliasToEmit;
    }

    /**
     * Extracts the raw label from a prefixed alias.
     *
     * @param aliasToEmit The prefixed alias (e.g., "mcp:test", "trigger:webhook")
     * @return The raw label without prefix
     */
    public String extractRawLabelFromAlias(String aliasToEmit) {
        if (aliasToEmit == null) {
            return null;
        }
        if (aliasToEmit.startsWith("mcp:")) {
            return aliasToEmit.substring(4);
        }
        if (aliasToEmit.startsWith("trigger:")) {
            return aliasToEmit.substring(8);
        }
        if (aliasToEmit.startsWith("core:")) {
            return aliasToEmit.substring(5);
        }
        if (aliasToEmit.startsWith("agent:")) {
            return aliasToEmit.substring(6);
        }
        return aliasToEmit;
    }

    /**
     * Extracts the label from a normalized step ID.
     * Delegates to LabelNormalizer.extractLabel().
     *
     * @param normalizedStepId The normalized step ID
     * @return The extracted label
     */
    public String extractLabel(String normalizedStepId) {
        return LabelNormalizer.extractLabel(normalizedStepId);
    }
}
