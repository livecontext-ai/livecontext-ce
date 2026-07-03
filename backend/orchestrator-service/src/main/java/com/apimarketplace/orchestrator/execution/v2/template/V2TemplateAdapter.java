package com.apimarketplace.orchestrator.execution.v2.template;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.expression.JsonParseException;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapter that bridges V2 ExecutionContext to V1 TemplateEngine.
 *
 * This allows V2 nodes to use the existing SpEL template resolution
 * without duplicating the complex template engine logic.
 *
 * Conversion:
 * - V2 ExecutionContext (immutable record) -> V1 WorkflowExecutionContext (mutable)
 * - V2 triggerData -> V1 dataContext with "trigger:xxx" keys
 * - V2 stepOutputs -> V1 stepOutputs
 * - V2 itemIndex -> V1 currentItemIndex
 */
@Service
public class V2TemplateAdapter {

    private static final Logger logger = LoggerFactory.getLogger(V2TemplateAdapter.class);

    private final TemplateEngine templateEngine;

    public V2TemplateAdapter(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    /**
     * Resolves all templates in the input map using data from the V2 context.
     *
     * @param input Map containing template expressions like "{{trigger:webhook.output.data}}"
     * @param context V2 ExecutionContext with trigger data and step outputs
     * @return Map with all templates resolved to their values
     */
    public Map<String, Object> resolveTemplates(Map<String, Object> input, ExecutionContext context) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        WorkflowExecutionContext v1Context = convertToV1Context(context);
        return resolveRecursive(input, v1Context);
    }

    /**
     * Evaluates a condition expression using data from the V2 context.
     *
     * @param condition Condition expression like "{{mcp:check.output.score}} > 80"
     * @param context V2 ExecutionContext
     * @return true if condition evaluates to true
     */
    public boolean evaluateCondition(String condition, ExecutionContext context) {
        if (condition == null || condition.isEmpty()) {
            return true;
        }

        WorkflowExecutionContext v1Context = convertToV1Context(context);
        return templateEngine.evaluateCondition(condition, v1Context);
    }

    /**
     * Evaluates a condition with detailed result for debugging/logging.
     */
    public TemplateEngine.ConditionEvaluationResult evaluateConditionWithDetails(
            String condition, ExecutionContext context) {

        if (condition == null || condition.isEmpty()) {
            return new TemplateEngine.ConditionEvaluationResult(condition, condition, true, null);
        }

        WorkflowExecutionContext v1Context = convertToV1Context(context);
        return templateEngine.evaluateConditionWithDetails(condition, v1Context);
    }

    /**
     * Evaluates a single template expression.
     *
     * @param template Template expression like "{{trigger:webhook}}"
     * @param context V2 ExecutionContext
     * @return Resolved value (can be String, Number, Map, List, etc.)
     */
    public Object evaluateTemplate(String template, ExecutionContext context) {
        if (template == null || template.isEmpty()) {
            return template;
        }

        WorkflowExecutionContext v1Context = convertToV1Context(context);
        return templateEngine.evaluateTemplate(template, v1Context);
    }

    /**
     * Converts V2 ExecutionContext to V1 WorkflowExecutionContext.
     * This is the bridge between the two systems.
     */
    private WorkflowExecutionContext convertToV1Context(ExecutionContext v2Context) {
        WorkflowExecutionContext v1Context = new WorkflowExecutionContext(
            v2Context.plan() != null ? v2Context.plan().getId() : null,
            v2Context.runId(),
            v2Context.tenantId()
        );

        // Transfer trigger data
        // V2 stores as raw data, V1 expects "trigger:xxx" or "current_item" keys
        if (v2Context.triggerData() != null && !v2Context.triggerData().isEmpty()) {
            // Build current_item with legacy structure: {data: {...}, ...}
            // This is needed for expressions like ${int(current_item.data.user_id)}
            Map<String, Object> currentItem = new HashMap<>(v2Context.triggerData());
            // If data is not already a nested map, wrap it
            if (!currentItem.containsKey("data")) {
                // Create the wrapper structure expected by legacy templates
                Map<String, Object> wrappedItem = new HashMap<>();
                wrappedItem.put("data", currentItem);
                // Also expose data at top level for direct access
                wrappedItem.putAll(currentItem);
                currentItem = wrappedItem;
            }
            // Add as current_item for legacy template resolution
            v1Context.setDataItem("current_item", currentItem);

            // Also add individual trigger entries if available
            for (Map.Entry<String, Object> entry : v2Context.triggerData().entrySet()) {
                String key = entry.getKey();
                // If it's not already prefixed, add with trigger prefix
                if (!key.startsWith("trigger:")) {
                    v1Context.setDataItem(LabelNormalizer.triggerKey(key), entry.getValue());
                }
                v1Context.setDataItem(key, entry.getValue());
            }

            // Add trigger data using normalized label for {{trigger:label.output.field}} syntax
            // Priority: Use trigger output from stepOutputs if available (already executed),
            // otherwise wrap raw triggerData in consistent structure for self-referencing
            if (v2Context.plan() != null && !v2Context.plan().getTriggers().isEmpty()) {
                for (var trigger : v2Context.plan().getTriggers()) {
                    String triggerLabel = trigger.label();
                    if (triggerLabel != null) {
                        String triggerKey = LabelNormalizer.triggerKey(triggerLabel);

                        // Check if trigger has already executed and has output in stepOutputs
                        // This output contains resolved inputs from trigger.input mapping
                        Object triggerOutput = v2Context.stepOutputs() != null
                            ? v2Context.stepOutputs().get(triggerKey)
                            : null;

                        if (triggerOutput != null) {
                            // Use the trigger's execution output (contains resolved inputs)
                            v1Context.setDataItem(triggerKey, triggerOutput);
                            v1Context.setStepOutput(triggerKey, triggerOutput);
                            logger.debug("Set trigger output from stepOutputs: {} (resolved)", triggerKey);
                        } else {
                            // Wrap raw triggerData in { output: {...} } structure for consistent access
                            // Also flatten data.* fields to output level for simpler expressions
                            // This allows both:
                            //   {{trigger:e.output.data.user_id}} -> raw value
                            //   {{trigger:e.output.user_id}} -> also works (flattened)
                            Map<String, Object> outputContent = new HashMap<>(v2Context.triggerData());

                            // Flatten data fields to output level for easier access
                            Object dataObj = v2Context.triggerData().get("data");
                            if (dataObj instanceof Map) {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> dataMap = (Map<String, Object>) dataObj;
                                // Add data fields at output level (don't override existing keys)
                                for (Map.Entry<String, Object> entry : dataMap.entrySet()) {
                                    if (!outputContent.containsKey(entry.getKey())) {
                                        outputContent.put(entry.getKey(), entry.getValue());
                                    }
                                }
                            }

                            Map<String, Object> wrappedTriggerData = new HashMap<>();
                            wrappedTriggerData.put("output", outputContent);
                            v1Context.setDataItem(triggerKey, wrappedTriggerData);
                            v1Context.setStepOutput(triggerKey, wrappedTriggerData);
                            logger.debug("Set trigger data with key: {} (wrapped with flattened data)", triggerKey);
                        }
                    }
                }
            }
        }

        // Transfer step outputs
        logger.info("[V2TemplateAdapter] Transferring stepOutputs from V2: keys={}",
            v2Context.stepOutputs() != null ? v2Context.stepOutputs().keySet() : "NULL");

        if (v2Context.stepOutputs() != null) {
            for (Map.Entry<String, Object> entry : v2Context.stepOutputs().entrySet()) {
                v1Context.setStepOutput(entry.getKey(), entry.getValue());
            }
        }

        // Transfer item index
        v1Context.setCurrentItemIndex(v2Context.itemIndex());

        // Transfer global data from state
        v2Context.getGlobalData("iterations").ifPresent(iter ->
            v1Context.setGlobalVariable("iterations", iter)
        );
        v2Context.getGlobalData("loop_results").ifPresent(results ->
            v1Context.setGlobalVariable("loop_results", results)
        );

        // Transfer the per-run workflow-variable bundle so NamespaceResolver's
        // "vars" branch resolves {{$vars.name}} / {{vars:name}} on every node.
        v2Context.getGlobalData("vars").ifPresent(vars ->
            v1Context.setGlobalVariable("vars", vars)
        );

        // Transfer split item data from globalData
        // SplitAwareNodeExecutor stores: item, index, {splitId}.current_item, {splitId}.current_index
        v2Context.getGlobalData("item").ifPresent(item -> {
            v1Context.setDataItem("item", item);
            v1Context.setDataItem("current_item", item);
            v1Context.setGlobalVariable("item", item);
            v1Context.setGlobalVariable("current_item", item);
        });
        v2Context.getGlobalData("index").ifPresent(index -> {
            v1Context.setDataItem("index", index);
            v1Context.setDataItem("current_index", index);
            v1Context.setGlobalVariable("index", index);
            v1Context.setGlobalVariable("current_index", index);
        });
        v2Context.getGlobalData("items").ifPresent(items -> {
            v1Context.setDataItem("items", items);
            v1Context.setGlobalVariable("items", items);
        });
        v2Context.getGlobalData("current_split_id").ifPresent(splitId -> {
            String fid = splitId.toString();
            v1Context.setGlobalVariable("current_split_id", fid);

            // Transfer splitId.current_item and splitId.current_index
            v2Context.getGlobalData(fid + ".current_item").ifPresent(currentItem -> {
                // Set in stepOutputs with .output structure for {{splitId.output.current_item}} syntax
                Object existingOutput = v1Context.getStepOutputs().get(fid);
                Map<String, Object> outputMap;
                if (existingOutput instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existing = (Map<String, Object>) existingOutput;
                    outputMap = new HashMap<>(existing);
                } else {
                    outputMap = new HashMap<>();
                }

                // Get or create output sub-map
                Object outputObj = outputMap.get("output");
                Map<String, Object> outputSubMap;
                if (outputObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existing = (Map<String, Object>) outputObj;
                    outputSubMap = new HashMap<>(existing);
                } else {
                    outputSubMap = new HashMap<>();
                }

                outputSubMap.put("current_item", currentItem);
                v2Context.getGlobalData(fid + ".current_index").ifPresent(idx ->
                    outputSubMap.put("current_index", idx)
                );

                outputMap.put("output", outputSubMap);
                v1Context.setStepOutput(fid, outputMap);
                v1Context.setDataItem(fid, outputMap);

                logger.debug("[V2TemplateAdapter] Added split item data: splitId={}, currentItem={}",
                    fid, currentItem);
            });
        });

        logger.info("[V2TemplateAdapter] Converted V2 context to V1: triggerKeys={}, stepKeys={}, itemIndex={}",
            v1Context.getDataContext().keySet(),
            v1Context.getStepOutputs().keySet(),
            v1Context.getCurrentItemIndex());

        return v1Context;
    }

    /**
     * Recursively resolves templates in a nested structure.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveRecursive(Map<String, Object> input, WorkflowExecutionContext context) {
        Map<String, Object> resolved = new HashMap<>();

        for (Map.Entry<String, Object> entry : input.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (value instanceof String stringValue) {
                // Resolve string template
                Object resolvedValue = evaluateLeaf(stringValue, context, key);
                resolved.put(key, resolvedValue);

            } else if (value instanceof Map) {
                // Check if it's a template spec: { "template": "...", "required": ... }
                Map<String, Object> mapValue = (Map<String, Object>) value;
                if (mapValue.containsKey("template")) {
                    String template = (String) mapValue.get("template");
                    Object resolvedValue = evaluateLeaf(template, context, key);
                    resolved.put(key, resolvedValue);
                } else {
                    // Recurse into nested map
                    resolved.put(key, resolveRecursive(mapValue, context));
                }

            } else if (value instanceof java.util.List<?> listValue) {
                // Recurse into list elements
                resolved.put(key, resolveListRecursive(listValue, context));

            } else {
                // Keep as is (numbers, booleans, etc.)
                resolved.put(key, value);
            }
        }

        return resolved;
    }

    /**
     * Evaluate a single template leaf and re-throw {@link JsonParseException} with the field name
     * prepended so the inspector / agent see "json() in field 'X' failed: ..." instead of a bare
     * Jackson message with no context. Other exceptions are not wrapped - the engine handles them.
     */
    private Object evaluateLeaf(String template, WorkflowExecutionContext context, String fieldName) {
        try {
            return templateEngine.evaluateTemplate(template, context);
        } catch (JsonParseException e) {
            throw new JsonParseException(
                "json() in field '" + fieldName + "' failed: " + e.getMessage(),
                e.getValuePreview(),
                e
            );
        }
    }

    /**
     * Recursively resolves templates in list elements.
     */
    @SuppressWarnings("unchecked")
    private java.util.List<Object> resolveListRecursive(java.util.List<?> input, WorkflowExecutionContext context) {
        java.util.List<Object> resolved = new java.util.ArrayList<>();
        int idx = 0;
        for (Object item : input) {
            if (item instanceof String stringValue) {
                resolved.add(evaluateLeaf(stringValue, context, "[" + idx + "]"));
            } else if (item instanceof Map) {
                resolved.add(resolveRecursive((Map<String, Object>) item, context));
            } else if (item instanceof java.util.List<?> listValue) {
                resolved.add(resolveListRecursive(listValue, context));
            } else {
                resolved.add(item);
            }
            idx++;
        }
        return resolved;
    }

    /**
     * Checks if the input contains any unresolved templates.
     * Used to determine if a step should be skipped due to missing dependencies.
     */
    public boolean hasUnresolvedTemplates(Map<String, Object> input, ExecutionContext context) {
        if (input == null || input.isEmpty()) {
            return false;
        }

        Map<String, Object> resolved = resolveTemplates(input, context);
        return containsUnresolved(resolved);
    }

    @SuppressWarnings("unchecked")
    private boolean containsUnresolved(Object value) {
        if (value == null) {
            return false;
        }

        if (value instanceof String strVal) {
            // Check for unresolved markers or remaining ${...}
            return strVal.contains("${__UNRESOLVED__:") ||
                   strVal.contains("${") && strVal.contains("}");
        }

        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            for (Object v : map.values()) {
                if (containsUnresolved(v)) {
                    return true;
                }
            }
        }

        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (containsUnresolved(item)) {
                    return true;
                }
            }
        }

        return false;
    }
}
