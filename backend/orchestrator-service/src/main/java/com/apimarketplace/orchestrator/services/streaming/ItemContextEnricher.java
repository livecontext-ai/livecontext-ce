package com.apimarketplace.orchestrator.services.streaming;

import com.apimarketplace.orchestrator.domain.workflow.ItemContextStack;
import com.apimarketplace.orchestrator.domain.workflow.StepExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Enriches step execution results with item context metadata.
 * Handles split/loop iteration context propagation to streaming events.
 */
@Component
public class ItemContextEnricher {

    /**
     * Enriches a StepExecutionResult with item context from the execution.
     *
     * @param execution The workflow execution context
     * @param result The step execution result to enrich
     * @return The enriched result (or original if no context)
     */
    public StepExecutionResult enrichWithItemContext(WorkflowExecution execution, StepExecutionResult result) {
        if (execution == null || result == null) {
            return result;
        }

        Optional<ItemContextStack.ItemContext> contextOpt = execution.getCurrentItemContext();
        if (contextOpt.isEmpty()) {
            return result;
        }

        Map<String, Object> enrichedOutput = mergeItemContextMetadata(result.output(), contextOpt.get());
        if (enrichedOutput == result.output()) {
            return result;
        }

        return new StepExecutionResult(
            result.stepId(),
            result.status(),
            result.message(),
            enrichedOutput,
            result.executionTime(),
            result.error()
        );
    }

    /**
     * Merges item context metadata into the output map.
     *
     * @param originalOutput The original output map
     * @param itemContext The item context to merge
     * @return The merged output map (or original if unchanged)
     */
    public Map<String, Object> mergeItemContextMetadata(Map<String, Object> originalOutput,
                                                         ItemContextStack.ItemContext itemContext) {
        if (itemContext == null) {
            return originalOutput;
        }

        Map<String, Object> enriched = new LinkedHashMap<>();
        if (originalOutput != null && !originalOutput.isEmpty()) {
            enriched.putAll(originalOutput);
        }

        boolean modified = false;
        modified |= putScalarIfAbsent(enriched, "item_index", itemContext.index());
        modified |= putScalarIfAbsent(enriched, "itemIndex", itemContext.index());
        modified |= putScalarIfAbsent(enriched, "absoluteIndex", itemContext.index());
        if (itemContext.triggerKey() != null && !itemContext.triggerKey().isBlank()) {
            modified |= putScalarIfAbsent(enriched, "triggerId", itemContext.triggerKey());
        }

        Map<String, Object> contextData = itemContext.data();
        if (contextData != null && !contextData.isEmpty()) {
            Object itemId = contextData.get("itemId");
            if (itemId != null && !enriched.containsKey("itemId")) {
                enriched.put("itemId", itemId);
                modified = true;
            }
            // tenantId intentionally NOT enriched - never expose to frontend
        }

        return modified ? enriched : originalOutput;
    }

    /**
     * Appends item metadata fields to an event data map from raw output.
     *
     * @param eventData The event data map to append to
     * @param rawOutput The raw output containing potential metadata
     */
    public void appendItemMetadata(Map<String, Object> eventData, Map<String, Object> rawOutput) {
        if (eventData == null || rawOutput == null || rawOutput.isEmpty()) {
            return;
        }

        addScalarMetadata(eventData, "itemId", rawOutput.get("itemId"));
        addScalarMetadata(eventData, "triggerId", rawOutput.get("triggerId"));
        addScalarMetadata(eventData, "absoluteIndex", rawOutput.get("absoluteIndex"));
        Object itemIndexValue = rawOutput.containsKey("item_index") ? rawOutput.get("item_index") : rawOutput.get("itemIndex");
        addScalarMetadata(eventData, "itemIndex", itemIndexValue);
        addScalarMetadata(eventData, "tenantId", rawOutput.get("tenantId"));
    }

    /**
     * Puts a scalar value into the target map if the key is absent.
     *
     * @param target The target map
     * @param key The key to check/add
     * @param value The value to add
     * @return true if the value was added
     */
    public boolean putScalarIfAbsent(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || value == null) {
            return false;
        }
        if (target.containsKey(key)) {
            return false;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
            return true;
        }
        return false;
    }

    /**
     * Adds a scalar metadata value to the target map.
     *
     * @param target The target map
     * @param key The key to add
     * @param value The value to add (only if scalar type)
     */
    public void addScalarMetadata(Map<String, Object> target, String key, Object value) {
        if (target == null || key == null || value == null) {
            return;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }

    /**
     * Extracts an integer value from a result's output using multiple possible keys.
     *
     * @param result The step execution result
     * @param keys The keys to try in order
     * @return The integer value, or null if not found
     */
    public Integer extractIntegerFromOutput(StepExecutionResult result, String... keys) {
        if (result == null || result.output() == null) {
            return null;
        }
        Map<String, Object> output = result.output();
        for (String key : keys) {
            Object value = output.get(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            if (value instanceof String str) {
                try {
                    return Integer.parseInt(str);
                } catch (NumberFormatException ignored) {
                    // Try next key
                }
            }
        }
        return null;
    }
}
