package com.apimarketplace.orchestrator.execution.v2.nodes.merge;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.OutputUnwrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * First Available Merge Strategy.
 *
 * This strategy:
 * - Takes data from the FIRST source that has completed successfully
 * - Does NOT wait for all sources
 * - Useful for fallback patterns
 *
 * Use case: Try API A, if fails try API B, etc.
 * Example: Primary source with fallback.
 */
public class FirstAvailableStrategy implements MergeStrategy {

    private static final Logger logger = LoggerFactory.getLogger(FirstAvailableStrategy.class);

    @Override
    public String name() {
        return "FIRST_AVAILABLE";
    }

    @Override
    public boolean canMerge(List<String> sourceNodeIds, ExecutionContext context) {
        // Can merge if at least ONE source has completed successfully
        // OR if all sources have completed (even if all failed)
        boolean hasSuccess = sourceNodeIds.stream()
            .anyMatch(context::isSuccess);

        boolean allCompleted = sourceNodeIds.stream()
            .allMatch(context::isCompleted);

        if (hasSuccess) {
            return true;
        }

        if (allCompleted) {
            logger.debug("FIRST_AVAILABLE: All sources completed but none successful");
            return true;
        }

        logger.debug("FIRST_AVAILABLE: Waiting for first successful source");
        return false;
    }

    @Override
    public Map<String, Object> merge(List<String> sourceNodeIds, ExecutionContext context) {
        Map<String, Object> mergedData = new LinkedHashMap<>();

        // Find first successful source
        String selectedSource = null;
        Object selectedData = null;

        for (String sourceId : sourceNodeIds) {
            if (context.isSuccess(sourceId)) {
                Optional<Object> output = context.getStepOutput(sourceId);
                if (output.isPresent()) {
                    selectedSource = sourceId;
                    // Unwrap the "output" wrapper added by ExecutionContext.withResult()
                    selectedData = unwrapOutput(output.get());
                    break;
                }
            }
        }

        mergedData.put("strategy", name());
        mergedData.put("source_count", sourceNodeIds.size());
        mergedData.put("selected_source", selectedSource);

        if (selectedData != null) {
            mergedData.put("data", selectedData);
            mergedData.put("has_data", true);

            // Extract items if list
            if (selectedData instanceof List) {
                mergedData.put("merged_items", selectedData);
                mergedData.put("item_count", ((List<?>) selectedData).size());
            } else if (selectedData instanceof Map) {
                mergedData.put("merged_items", List.of(selectedData));
                mergedData.put("item_count", 1);
            }
        } else {
            mergedData.put("data", null);
            mergedData.put("has_data", false);
            mergedData.put("merged_items", List.of());
            mergedData.put("item_count", 0);
        }

        logger.debug("FIRST_AVAILABLE merge completed: selected={}, hasData={}",
            selectedSource, selectedData != null);

        return mergedData;
    }

    /**
     * Unwraps the "output" wrapper added by ExecutionContext.withResult().
     * Delegates to {@link OutputUnwrapper#unwrapOutputObject(Object)}.
     */
    private Object unwrapOutput(Object data) {
        return OutputUnwrapper.unwrapOutputObject(data);
    }

    @Override
    public boolean shouldSkip(List<String> sourceNodeIds, ExecutionContext context) {
        // Skip only if ALL sources completed and NONE succeeded
        boolean allCompleted = sourceNodeIds.stream()
            .allMatch(context::isCompleted);
        boolean anySuccess = sourceNodeIds.stream()
            .anyMatch(context::isSuccess);

        return allCompleted && !anySuccess;
    }

    @Override
    public String getSkipReason(List<String> sourceNodeIds, ExecutionContext context) {
        return "No source completed successfully";
    }
}
