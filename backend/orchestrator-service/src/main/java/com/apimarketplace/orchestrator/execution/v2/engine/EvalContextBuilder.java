package com.apimarketplace.orchestrator.execution.v2.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared utility for building evaluation contexts from ExecutionContext.
 *
 * <p>Used by condition-evaluating nodes (Decision, Switch, Option) and data-collecting
 * nodes (Aggregate) to prepare a flat map of variables for SpEL expression evaluation.
 *
 * <p>The evaluation context is built in layers:
 * <ol>
 *   <li>Trigger data at top level + under "trigger" key</li>
 *   <li>All step outputs (keyed by node ID)</li>
 *   <li>Extracted trigger output fields at top level (with metadata exclusion)</li>
 *   <li>Item context (item_index, item_id)</li>
 * </ol>
 *
 * <p>This class replaces the duplicated {@code buildEvalContext()} methods previously
 * found in DecisionNode, SwitchNode, OptionNode, and AggregateNode.
 */
public final class EvalContextBuilder {

    private static final Logger logger = LoggerFactory.getLogger(EvalContextBuilder.class);

    /**
     * Metadata keys to exclude when extracting trigger output fields to top level.
     * These are internal/system keys that should not pollute the evaluation namespace.
     */
    private static final Set<String> TRIGGER_OUTPUT_EXCLUDED_KEYS = Set.of(
        "trigger_id",
        "trigger_data",
        "item_id",
        "item_index",
        "httpstatus",
        "itemIndex",
        "currentIteration",
        "iteration"
    );

    private EvalContextBuilder() {
        // Utility class - no instantiation
    }

    /**
     * Builds a standard evaluation context for condition-evaluating nodes.
     *
     * <p>This is the full version used by Decision, Switch, and Option nodes.
     * It includes:
     * <ul>
     *   <li>Trigger data at top level and under "trigger" key</li>
     *   <li>All step outputs keyed by node ID</li>
     *   <li>Trigger output fields extracted to top level (excluding metadata keys)</li>
     *   <li>Item context (item_index, item_id)</li>
     * </ul>
     *
     * @param context the execution context for the current item
     * @return a flat map suitable for SpEL expression evaluation
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildStandardEvalContext(ExecutionContext context) {
        Map<String, Object> evalContext = new HashMap<>();

        // 1. Add trigger data at top level (for expressions like {{user_id}})
        Map<String, Object> triggerData = context.triggerData();
        if (triggerData != null) {
            evalContext.putAll(triggerData);
            evalContext.put("trigger", triggerData);
        }

        // 2. Add all step outputs
        Map<String, Object> stepOutputs = context.getAllStepOutputs();
        evalContext.putAll(stepOutputs);

        // 3. Extract resolved inputs from trigger node and add at top level
        // Note: ExecutionContext.withResult() wraps output in { "output": { ... } } structure
        for (Map.Entry<String, Object> entry : stepOutputs.entrySet()) {
            if (entry.getKey().startsWith("trigger:") && entry.getValue() instanceof Map) {
                Map<String, Object> wrapper = (Map<String, Object>) entry.getValue();
                // Extract actual output from the "output" wrapper added by withResult()
                Object outputObj = wrapper.get("output");
                if (outputObj instanceof Map) {
                    Map<String, Object> triggerOutput = (Map<String, Object>) outputObj;
                    for (Map.Entry<String, Object> input : triggerOutput.entrySet()) {
                        String key = input.getKey();
                        // Skip metadata keys
                        if (!TRIGGER_OUTPUT_EXCLUDED_KEYS.contains(key)) {
                            evalContext.put(key, input.getValue());
                        }
                    }
                }
            }
        }

        // 4. Add item context
        evalContext.put("item_index", context.itemIndex());
        evalContext.put("item_id", context.itemId());

        // 5. Add split context (item, index, items) from globalData
        // When a node is inside a split scope, SplitAwareNodeExecutor stores these in globalData.
        // Condition-evaluating nodes (Decision, Switch) skip split handling but need these variables
        // for SpEL expressions like {{item.value}}.
        context.getGlobalData("item").ifPresent(item -> evalContext.put("item", item));
        context.getGlobalData("index").ifPresent(idx -> evalContext.put("index", idx));
        context.getGlobalData("items").ifPresent(items -> evalContext.put("items", items));

        logger.debug("EvalContextBuilder standard context built: keys={}", evalContext.keySet());

        return evalContext;
    }

    /**
     * Builds an evaluation context for data-collecting nodes like Aggregate.
     *
     * <p>This variant extracts output fields from ALL step outputs (not just triggers),
     * without applying the metadata exclusion filter. This is useful when the node
     * needs access to all available data for field extraction.
     *
     * <p>It includes:
     * <ul>
     *   <li>Trigger data at top level and under "trigger" key</li>
     *   <li>All step outputs keyed by node ID</li>
     *   <li>All output fields from all steps extracted to top level</li>
     *   <li>Item context (item_index, item_id)</li>
     * </ul>
     *
     * @param context the execution context for the current item
     * @return a flat map suitable for SpEL expression evaluation
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> buildAggregateEvalContext(ExecutionContext context) {
        Map<String, Object> evalContext = new HashMap<>();

        // 1. Add trigger data
        Map<String, Object> triggerData = context.triggerData();
        if (triggerData != null) {
            evalContext.putAll(triggerData);
            evalContext.put("trigger", triggerData);
        }

        // 2. Add all step outputs
        Map<String, Object> stepOutputs = context.getAllStepOutputs();
        evalContext.putAll(stepOutputs);

        // 3. Extract outputs from wrapped format (all steps, not just triggers)
        for (Map.Entry<String, Object> entry : stepOutputs.entrySet()) {
            if (entry.getValue() instanceof Map) {
                Map<String, Object> wrapper = (Map<String, Object>) entry.getValue();
                Object outputObj = wrapper.get("output");
                if (outputObj instanceof Map) {
                    Map<String, Object> nodeOutput = (Map<String, Object>) outputObj;
                    evalContext.putAll(nodeOutput);
                }
            }
        }

        // 4. Add item context
        evalContext.put("item_index", context.itemIndex());
        evalContext.put("item_id", context.itemId());

        // 5. Add split context (item, index, items) from globalData
        context.getGlobalData("item").ifPresent(item -> evalContext.put("item", item));
        context.getGlobalData("index").ifPresent(idx -> evalContext.put("index", idx));
        context.getGlobalData("items").ifPresent(items -> evalContext.put("items", items));

        return evalContext;
    }

    /**
     * Returns the set of metadata keys excluded from trigger output extraction.
     * Exposed for testing purposes.
     *
     * @return unmodifiable set of excluded key names
     */
    public static Set<String> getExcludedKeys() {
        return TRIGGER_OUTPUT_EXCLUDED_KEYS;
    }
}
