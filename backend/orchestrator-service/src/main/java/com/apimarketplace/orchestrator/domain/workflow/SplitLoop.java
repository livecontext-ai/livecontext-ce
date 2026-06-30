package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;

import java.util.List;
import java.util.Map;

/**
 * Split loop configuration for parallel iteration over a list of items.
 * Unlike WhileLoop (sequential), Split creates N parallel branches.
 *
 * Example:
 * - list: "{{mcp:api_call.output.items}}" evaluates to [item1, item2, item3]
 * - Creates 3 parallel branches, each processing one item
 * - maxItems: Limits the number of items processed (e.g., maxItems=10)
 * - splitStrategy: "stop-on-error" (default) or "continue-anyway"
 */
public record SplitLoop(
    String loopId,
    String entryStep,
    String list,
    int maxItems,
    String splitStrategy,
    List<SplitStep> steps,
    Map<String, Object> entryScope,
    String decisionNodeId
) {

    /**
     * Constructor without decisionNodeId for backward compatibility
     */
    public SplitLoop(String loopId, String entryStep, String list,
                      int maxItems, String splitStrategy, List<SplitStep> steps,
                      Map<String, Object> entryScope) {
        this(loopId, entryStep, list, maxItems, splitStrategy, steps, entryScope, null);
    }

    public boolean isValid() {
        // list is required to determine what to iterate over
        // maxItems defaults to 100 if not specified
        return loopId != null && entryStep != null && list != null && !list.isBlank();
    }

    public int getStepCount() {
        return steps != null ? steps.size() : 0;
    }

    public String graphNodeId() {
        return LabelNormalizer.coreKey(loopId);
    }

    /**
     * Get the effective maxItems value.
     * Returns the configured maxItems, or 100 as default if not set.
     */
    public int getEffectiveMaxItems() {
        return maxItems > 0 ? maxItems : 100;
    }

    /**
     * Get the effective strategy.
     * Returns "stop-on-error" if not specified.
     */
    public String getEffectiveStrategy() {
        return splitStrategy != null && !splitStrategy.isBlank()
            ? splitStrategy
            : "stop-on-error";
    }
}
