package com.apimarketplace.orchestrator.services.template;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/**
 * Resolves namespace-prefixed variables from WorkflowExecutionContext.
 *
 * === UNIFIED EXPRESSION PATTERN ===
 *
 * ALL node types use the same pattern: {{type:label.output.field}}
 *
 * | Prefix      | Pattern                             | Example                                    |
 * |-------------|-------------------------------------|---------------------------------------------|
 * | trigger:    | {{trigger:label.output.field}}      | {{trigger:webhook.output.user_id}}          |
 * | mcp:        | {{mcp:label.output.field}}          | {{mcp:api_call.output.data}}                |
 * | agent:      | {{agent:label.output.field}}        | {{agent:assistant.output.response}}         |
 * | core:       | {{core:label.output.field}}         | {{core:decision.output.selected_branch}}    |
 * | table:      | {{table:label.output.field}}        | {{table:users.output.rows}}                 |
 *
 * The .output. segment is MANDATORY for all node type outputs.
 * This provides consistency across all expression patterns.
 */
@Service
public class NamespaceResolver {

    private static final Logger logger = LoggerFactory.getLogger(NamespaceResolver.class);

    private final PathNavigator pathNavigator;

    public NamespaceResolver(PathNavigator pathNavigator) {
        this.pathNavigator = pathNavigator;
    }

    /**
     * Resolve a variable path to its value.
     */
    public Object resolveVariable(String variablePath, WorkflowExecutionContext context) {
        if (variablePath == null || variablePath.isEmpty()) {
            return null;
        }

        String[] parts = variablePath.split("\\.", 2);
        String namespace = parts[0];
        String remainingPath = parts.length > 1 ? parts[1] : null;

        // Only treat as namespace if there's a path after it
        if (remainingPath != null) {
            Object result = switch (namespace) {
                case "steps" -> resolveStepsNamespace(remainingPath, context);
                case "triggers" -> resolveTriggersNamespace(remainingPath, context);
                case "data" -> resolveDataNamespace(remainingPath, context);
                case "current_item" -> resolveCurrentItemPath(remainingPath, context);
                case "secrets" -> null; // TODO: Implement secrets resolution
                default -> null;
            };
            if (result != null) {
                return result;
            }
        }

        // Try prefixed variable resolution
        return resolvePrefixedVariable(variablePath, context);
    }

    // ========================================================================
    // MAIN CATEGORY RESOLVERS (4 categories)
    // ========================================================================

    /**
     * Resolve trigger:label.output.path format.
     * Used for ALL trigger types (webhook, chat, schedule, form, datasource, manual, workflow).
     *
     * Expected format: {{trigger:label.output.field}}
     * Example: {{trigger:webhook.output.user_id}}
     *
     * Note: For backwards compatibility, also supports {{trigger:label.field}} (without .output.)
     * but the recommended pattern is with .output. for consistency.
     */
    public Object resolveTriggersNamespace(String path, WorkflowExecutionContext context) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.", 2);
        String triggerLabel = parts[0];
        String remainingPath = parts.length > 1 ? parts[1] : null;

        String normalizedLabel = LabelNormalizer.normalizeLabel(triggerLabel);
        String triggerKey = "trigger:" + normalizedLabel;

        logger.debug("[resolveTriggersNamespace] triggerLabel={}, normalized={}", triggerLabel, normalizedLabel);

        // Try with normalized key first
        Object triggerData = context.getDataItem(triggerKey);
        if (triggerData == null) {
            triggerData = context.getStepOutput(triggerKey);
        }
        // Fallback: try with raw label
        if (triggerData == null) {
            triggerData = context.getDataItem("trigger:" + triggerLabel);
        }
        if (triggerData == null) {
            triggerData = context.getStepOutput("trigger:" + triggerLabel);
        }

        if (triggerData == null || remainingPath == null) {
            return triggerData;
        }

        return navigateWithOutputFallback(triggerData, remainingPath);
    }

    /**
     * Resolve mcp:label.output.path format.
     * Used for ALL action nodes (tools, CRUD).
     *
     * Expected format: {{mcp:label.output.field}}
     * Example: {{mcp:api_call.output.data}}
     *
     * The .output. segment is mandatory for step outputs.
     */
    public Object resolveStepsNamespace(String path, WorkflowExecutionContext context) {
        logger.info("[resolveStepsNamespace] ENTRY: path='{}', contextStepOutputKeys={}",
            path, context.getStepOutputs() != null ? context.getStepOutputs().keySet() : "NULL");

        if (path == null || path.isEmpty()) {
            return context.getStepOutputs();
        }

        String[] parts = path.split("\\.", 2);
        String stepLabel = parts[0];
        String remainingPath = parts.length > 1 ? parts[1] : null;

        logger.debug("[resolveStepsNamespace] path={}, stepLabel={}, remainingPath={}", path, stepLabel, remainingPath);

        String normalizedLabel = LabelNormalizer.normalizeLabel(stepLabel);
        String stepKey = "mcp:" + normalizedLabel;

        logger.info("[resolveStepsNamespace] Looking for stepKey={}, available keys={}", stepKey, context.getStepOutputs().keySet());

        // Try with normalized key first
        Object stepData = context.getStepOutput(stepKey);
        if (stepData == null) {
            stepData = context.getStepOutput("mcp:" + stepLabel);
        }
        if (stepData == null) {
            stepData = context.getStepOutput(stepLabel);
        }

        if (stepData == null) {
            logger.info("[resolveStepsNamespace] stepData NOT FOUND for stepLabel={}, normalized={}", stepLabel, normalizedLabel);
            return null;
        }

        logger.info("[resolveStepsNamespace] stepData FOUND: type={}, keys={}",
            stepData.getClass().getSimpleName(),
            stepData instanceof Map ? ((Map<?,?>)stepData).keySet() : "N/A");

        // Log the actual content structure for debugging
        if (stepData instanceof Map) {
            Map<?,?> stepMap = (Map<?,?>) stepData;
            Object outputObj = stepMap.get("output");
            if (outputObj instanceof Map) {
                Map<?,?> outputMap = (Map<?,?>) outputObj;
                logger.info("[resolveStepsNamespace] output content keys: {}", outputMap.keySet());
                Object dataObj = outputMap.get("data");
                if (dataObj instanceof Map) {
                    Map<?,?> dataMap = (Map<?,?>) dataObj;
                    logger.info("[resolveStepsNamespace] output.data keys: {}", dataMap.keySet());
                } else {
                    logger.info("[resolveStepsNamespace] output.data is NOT a Map: {}", dataObj != null ? dataObj.getClass().getSimpleName() : "NULL");
                }
            } else {
                logger.info("[resolveStepsNamespace] output is NOT a Map: {}", outputObj != null ? outputObj.getClass().getSimpleName() : "NULL");
            }
        }

        if (remainingPath == null) {
            return stepData;
        }

        Object result = navigateWithOutputFallback(stepData, remainingPath);
        logger.info("[resolveStepsNamespace] navigateWithOutputFallback result: path={}, result={}",
            remainingPath, result != null ? result.getClass().getSimpleName() + ":" + (result.toString().length() > 100 ? result.toString().substring(0, 100) + "..." : result) : "NULL");
        return result;
    }

    /**
     * Resolve agent:label.output.field format.
     * Used for ALL AI reasoning nodes (agent, guardrail, classify).
     *
     * Expected format: {{agent:label.output.field}}
     * Examples:
     * - {{agent:assistant.output.response}} for agent response
     * - {{agent:checker.output.passed}} for guardrail passed status
     * - {{agent:classifier.output.category}} for classify category
     *
     * Note: For backwards compatibility, also supports {{agent:label.field}} (without .output.)
     * but the recommended pattern is with .output. for consistency.
     */
    public Object resolveAgentNamespace(String path, WorkflowExecutionContext context) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.", 2);
        String agentLabel = parts[0];
        String remainingPath = parts.length > 1 ? parts[1] : null;

        logger.debug("[resolveAgentNamespace] path={}, agentLabel={}, remainingPath={}", path, agentLabel, remainingPath);

        String normalizedLabel = LabelNormalizer.normalizeLabel(agentLabel);

        // Try with normalized label
        Object agentData = context.getStepOutput("agent:" + normalizedLabel);

        // Try with original label
        if (agentData == null) {
            agentData = context.getStepOutput("agent:" + agentLabel);
        }

        if (agentData == null) {
            logger.debug("[resolveAgentNamespace] Agent data not found for label={}, normalized={}", agentLabel, normalizedLabel);
            return null;
        }

        if (remainingPath == null) {
            return agentData;
        }

        // Use helper for output fallback (handles both wrapped and unwrapped data)
        Object result = navigateWithOutputFallback(agentData, remainingPath);

        // For backwards compatibility: if not found directly and path does NOT start with "output.",
        // try navigating through "output" wrapper.
        // This handles legacy expressions like {{agent:assistant.response}} (without .output.)
        if (result == null && !remainingPath.startsWith("output.") && agentData instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> agentMap = (Map<String, Object>) agentData;
            Object output = agentMap.get("output");
            if (output != null) {
                result = pathNavigator.navigatePath(output, remainingPath);
                logger.debug("[resolveAgentNamespace] Backwards compat: navigated through 'output' wrapper for path '{}', result={}", remainingPath, result);
            }
        }

        return result;
    }

    /**
     * Resolve core:label.output.field format.
     * Used for ALL core flow nodes: Loop, Split, Decision, Switch, Merge, Transform, Wait, Fork.
     *
     * Expected format: {{core:label.output.field}}
     * Examples:
     * - {{core:decision.output.selected_branch}} - Decision selected branch
     * - {{core:switch.output.selected_case}} - Switch selected case
     * - {{core:loop.output.iteration}} - Loop iteration counter (1-based)
     * - {{core:split.output.current_item}} - Split current item
     * - {{core:split.output.current_index}} - Split current index (0-based)
     * - {{core:merge.output.merged_data}} - Merge merged data
     * - {{core:fork.output.branches_count}} - Fork branches count
     *
     * Note: For backwards compatibility, also supports {{core:label.field}} (without .output.)
     * but the recommended pattern is with .output. for consistency.
     */
    public Object resolveCoreNamespace(String path, WorkflowExecutionContext context) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        // Handle special loop metadata (without label prefix)
        // e.g., {{core.index}} or {{core.iteration}}
        if ("index".equals(path)) {
            return context.getCurrentItemIndex();
        }
        if ("iteration".equals(path)) {
            return context.getCurrentIteration();
        }

        String[] parts = path.split("\\.", 2);
        String coreLabel = parts[0];
        String remainingPath = parts.length > 1 ? parts[1] : null;

        logger.debug("[resolveCoreNamespace] path={}, coreLabel={}, remainingPath={}", path, coreLabel, remainingPath);

        String normalizedLabel = LabelNormalizer.normalizeLabel(coreLabel);

        // Try with normalized label
        Object coreData = context.getStepOutput("core:" + normalizedLabel);

        // Try with original label
        if (coreData == null) {
            coreData = context.getStepOutput("core:" + coreLabel);
        }

        // For iteration/current_item, try getting from context directly
        if (coreData == null && remainingPath != null) {
            if ("iteration".equals(remainingPath)) {
                return context.getCurrentIteration();
            }
            if ("current_item".equals(remainingPath)) {
                return context.getDataItem("current_item");
            }
            if ("current_index".equals(remainingPath)) {
                return context.getCurrentItemIndex();
            }
        }

        if (coreData == null) {
            logger.debug("[resolveCoreNamespace] Core data not found for label={}, normalized={}", coreLabel, normalizedLabel);
            return null;
        }

        if (remainingPath == null) {
            return coreData;
        }

        // First try with output fallback (handles both wrapped and unwrapped data)
        Object result = navigateWithOutputFallback(coreData, remainingPath);
        if (result != null) {
            return result;
        }

        // For backwards compatibility: handle legacy special paths without .output.
        // These are deprecated, the unified pattern {{core:label.output.field}} should be used instead.
        if ("item".equals(remainingPath) || "current_item".equals(remainingPath)) {
            Object item = context.getDataItem("current_item");
            if (item != null) {
                logger.debug("[resolveCoreNamespace] Backwards compat: resolved current_item for path '{}'", remainingPath);
                return item;
            }
        }

        // Handle legacy item.field path (without .output.)
        if (remainingPath.startsWith("item.") || remainingPath.startsWith("current_item.")) {
            Object item = context.getDataItem("current_item");
            if (item != null) {
                String fieldPath = remainingPath.contains("current_item.")
                    ? remainingPath.substring("current_item.".length())
                    : remainingPath.substring("item.".length());
                logger.debug("[resolveCoreNamespace] Backwards compat: navigating current_item for field '{}'", fieldPath);
                return pathNavigator.navigatePath(item, fieldPath);
            }
        }

        return null;
    }

    /**
     * Resolve table:label.output.field format.
     * Used for ALL CRUD table operations.
     *
     * Expected format: {{table:label.output.field}}
     * Example: {{table:users.output.rows}}
     */
    public Object resolveTableNamespace(String path, WorkflowExecutionContext context) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.", 2);
        String tableLabel = parts[0];
        String remainingPath = parts.length > 1 ? parts[1] : null;

        String normalizedLabel = LabelNormalizer.normalizeLabel(tableLabel);

        // Try with normalized label
        Object tableData = context.getStepOutput("table:" + normalizedLabel);

        // Try with original label
        if (tableData == null) {
            tableData = context.getStepOutput("table:" + tableLabel);
        }

        if (tableData == null) {
            logger.debug("[resolveTableNamespace] Table data not found for label={}, normalized={}", tableLabel, normalizedLabel);
            return null;
        }

        if (remainingPath == null) {
            return tableData;
        }

        return navigateWithOutputFallback(tableData, remainingPath);
    }

    /**
     * Resolve interface:label.output.field format.
     * Used for interface node outputs (action data, user interactions).
     *
     * Expected format: {{interface:label.output.field}}
     * Example: {{interface:search_page.output.query}}
     */
    public Object resolveInterfaceNamespace(String path, WorkflowExecutionContext context) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.", 2);
        String interfaceLabel = parts[0];
        String remainingPath = parts.length > 1 ? parts[1] : null;

        String normalizedLabel = LabelNormalizer.normalizeLabel(interfaceLabel);

        // Try with normalized label
        Object interfaceData = context.getStepOutput("interface:" + normalizedLabel);

        // Try with original label
        if (interfaceData == null) {
            interfaceData = context.getStepOutput("interface:" + interfaceLabel);
        }

        if (interfaceData == null) {
            logger.debug("[resolveInterfaceNamespace] Interface data not found for label={}, normalized={}", interfaceLabel, normalizedLabel);
            return null;
        }

        if (remainingPath == null) {
            return interfaceData;
        }

        return navigateWithOutputFallback(interfaceData, remainingPath);
    }

    // ========================================================================
    // OTHER NAMESPACE RESOLVERS
    // ========================================================================

    /**
     * Resolve data.label.path format for DataSource data.
     */
    public Object resolveDataNamespace(String path, WorkflowExecutionContext context) {
        if (path == null || path.isEmpty()) {
            return null;
        }

        String[] parts = path.split("\\.", 2);
        String dataSourceLabel = parts[0];
        String remainingPath = parts.length > 1 ? parts[1] : null;

        // Try table: prefix first (V2 format), then legacy prefixes for backwards compatibility
        Object data = context.getDataItem("table:" + dataSourceLabel);
        if (data == null) {
            data = context.getDataItem("ds:" + dataSourceLabel);
        }
        if (data == null) {
            data = context.getDataItem(dataSourceLabel);
        }

        if (data == null || remainingPath == null) {
            return data;
        }

        return navigateWithOutputFallback(data, remainingPath);
    }

    /**
     * Resolve current_item.path format.
     */
    public Object resolveCurrentItemPath(String path, WorkflowExecutionContext context) {
        Object currentItem = context.getDataItem("current_item");
        if (currentItem == null || path == null) {
            return currentItem;
        }
        return pathNavigator.navigatePath(currentItem, path);
    }

    /**
     * Resolve prefixed variables (handles 4 prefix formats).
     */
    @SuppressWarnings("unchecked")
    public Object resolvePrefixedVariable(String variablePath, WorkflowExecutionContext context) {
        logger.info("[resolvePrefixedVariable] Resolving: '{}'", variablePath);

        // Handle namespace formats: mcps., triggers.
        if (variablePath.startsWith("mcps.")) {
            String path = variablePath.substring("mcps.".length());
            Object result = resolveStepsNamespace(path, context);
            if (result != null) return result;
        }

        if (variablePath.startsWith("triggers.")) {
            String path = variablePath.substring("triggers.".length());
            Object result = resolveTriggersNamespace(path, context);
            if (result != null) return result;
        }

        // Handle the 4 simplified prefixes
        if (variablePath.startsWith("trigger:")) {
            String path = variablePath.substring("trigger:".length());
            return resolveTriggersNamespace(path, context);
        }

        if (variablePath.startsWith("mcp:")) {
            String path = variablePath.substring("mcp:".length());
            return resolveStepsNamespace(path, context);
        }

        if (variablePath.startsWith("agent:")) {
            String path = variablePath.substring("agent:".length());
            return resolveAgentNamespace(path, context);
        }

        if (variablePath.startsWith("core:")) {
            String path = variablePath.substring("core:".length());
            return resolveCoreNamespace(path, context);
        }

        if (variablePath.startsWith("table:")) {
            String path = variablePath.substring("table:".length());
            return resolveTableNamespace(path, context);
        }

        if (variablePath.startsWith("interface:")) {
            String path = variablePath.substring("interface:".length());
            return resolveInterfaceNamespace(path, context);
        }

        if (variablePath.startsWith("note:")) {
            // Notes are non-executable visual elements, they produce no output
            return null;
        }

        Object directItemAlias = resolveDirectItemAlias(variablePath, context);
        if (directItemAlias != null) {
            return directItemAlias;
        }

        // Try direct lookup in current_item
        Object currentItem = context.getDataItem("current_item");
        if (currentItem instanceof Map<?, ?> itemMap) {
            if (variablePath.contains(".")) {
                String[] parts = variablePath.split("\\.", 2);
                Object base = ((Map<?, ?>) itemMap).get(parts[0]);
                if (base != null) {
                    Object result = pathNavigator.navigatePath(base, parts[1]);
                    if (result != null) return result;
                }
            } else {
                Object value = ((Map<?, ?>) itemMap).get(variablePath);
                if (value != null) return value;
            }
        }

        // Try step outputs
        Map<String, Object> stepOutputs = context.getStepOutputs();
        if (stepOutputs != null) {
            if (stepOutputs.containsKey(variablePath)) {
                return stepOutputs.get(variablePath);
            }
            if (stepOutputs.containsKey("mcp:" + variablePath)) {
                return stepOutputs.get("mcp:" + variablePath);
            }

            // Search inside each step output
            for (Map.Entry<String, Object> entry : stepOutputs.entrySet()) {
                Object stepOutput = entry.getValue();
                if (stepOutput instanceof Map<?, ?> stepOutputMap) {
                    Map<?, ?> actualOutputMap = stepOutputMap;
                    Object outputWrapper = stepOutputMap.get("output");
                    if (outputWrapper instanceof Map<?, ?>) {
                        actualOutputMap = (Map<?, ?>) outputWrapper;
                    }

                    if (variablePath.contains(".")) {
                        String[] parts = variablePath.split("\\.", 2);
                        Object base = actualOutputMap.get(parts[0]);
                        if (base != null) {
                            Object result = pathNavigator.navigatePath(base, parts[1]);
                            if (result != null) return result;
                        }
                    } else {
                        Object value = actualOutputMap.get(variablePath);
                        if (value != null) return value;
                    }
                }
            }
        }

        // Try global variables
        Map<String, Object> globalVars = context.getGlobalVariables();
        if (globalVars != null && globalVars.containsKey(variablePath)) {
            return globalVars.get(variablePath);
        }

        logger.warn("[resolvePrefixedVariable] Variable '{}' NOT FOUND anywhere", variablePath);
        return null;
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Resolve split item aliases documented for workflow authors:
     * {{item.field}}, {{current_item.field}}, {{index}}, and {{current_index}}.
     */
    private Object resolveDirectItemAlias(String variablePath, WorkflowExecutionContext context) {
        Object currentItem = context.getDataItem("current_item");
        if (currentItem == null && context.getGlobalVariables() != null) {
            currentItem = context.getGlobalVariables().get("current_item");
        }

        if ("item".equals(variablePath) || "current_item".equals(variablePath)) {
            return currentItem;
        }

        if (currentItem != null && variablePath.startsWith("item.")) {
            return pathNavigator.navigatePath(currentItem, variablePath.substring("item.".length()));
        }

        if (currentItem != null && variablePath.startsWith("current_item.")) {
            return pathNavigator.navigatePath(currentItem, variablePath.substring("current_item.".length()));
        }

        if ("index".equals(variablePath) || "current_index".equals(variablePath)) {
            Object currentIndex = context.getDataItem(variablePath);
            if (currentIndex != null) {
                return currentIndex;
            }
            if (context.getGlobalVariables() != null) {
                currentIndex = context.getGlobalVariables().get(variablePath);
                if (currentIndex != null) {
                    return currentIndex;
                }
            }
            return context.getCurrentItemIndex();
        }

        return null;
    }

    /**
     * Navigate a path with fallback for "output." prefix.
     *
     * Data may be stored in different formats:
     * 1. Direct: {field: value} - pattern {{type:label.output.field}} skips "output."
     * 2. With wrapper: {output: {field: value}} - navigates through output
     * 3. With metadata wrapper: {output: {metadata..., output: {field: value}}} - navigates through nested output
     *
     * This method handles all cases transparently.
     */
    @SuppressWarnings("unchecked")
    private Object navigateWithOutputFallback(Object data, String path) {
        logger.debug("[navigateWithOutputFallback] ENTRY: path='{}', dataType={}, dataKeys={}",
            path,
            data != null ? data.getClass().getSimpleName() : "NULL",
            data instanceof Map ? ((Map<?,?>)data).keySet() : "N/A");

        if (data == null || path == null) {
            return data;
        }

        // If path starts with "output." and data doesn't have "output" key, skip the prefix
        if (path.startsWith("output.") && data instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) data;
            Object directResult = pathNavigator.navigatePath(data, path);
            if (directResult != null) {
                return directResult;
            }

            String remainingPath = path.substring("output.".length());
            Object rootLevelResult = pathNavigator.navigatePath(data, remainingPath);
            if (rootLevelResult != null) {
                return rootLevelResult;
            }

            if (!dataMap.containsKey("output")) {
                logger.debug("[navigateWithOutputFallback] Data has no 'output' key, using effectivePath: {}", remainingPath);
                return null;
            }

            Object outputObj = dataMap.get("output");
            Object unwrappedResult = pathNavigator.navigatePath(outputObj, remainingPath);
            if (unwrappedResult != null) {
                return unwrappedResult;
            }

            // Check for double-wrapped output: {output: {metadata..., output: {actual data}}}
            // This happens when step event payload is stored instead of just the output.
            if (outputObj instanceof Map) {
                Map<String, Object> outputMap = (Map<String, Object>) outputObj;
                if (outputMap.containsKey("output")) {
                    logger.info("[navigateWithOutputFallback] Double-wrapped output detected, trying nested output path: {}", remainingPath);
                    Object nestedResult = pathNavigator.navigatePath(outputMap.get("output"), remainingPath);
                    if (nestedResult != null) {
                        logger.info("[navigateWithOutputFallback] Found via nested output path!");
                        return nestedResult;
                    }
                }
            }
        }

        Object result = pathNavigator.navigatePath(data, path);
        logger.debug("[navigateWithOutputFallback] Result after direct navigation: {}", result != null ? "FOUND" : "NULL");

        // Backward compatibility: if direct navigation returned null and path does NOT start
        // with "output.", try unwrapping the "output" wrapper and navigating inside it.
        // This supports shorthand expressions like {{trigger:start.items}} instead of
        // {{trigger:start.output.items}} when data is stored as {output: {items: [...]}}.
        if (result == null && !path.startsWith("output.") && data instanceof Map) {
            Map<String, Object> dataMap = (Map<String, Object>) data;
            Object outputObj = dataMap.get("output");
            if (outputObj != null) {
                result = pathNavigator.navigatePath(outputObj, path);
                if (result != null) {
                    logger.debug("[navigateWithOutputFallback] Found via output unwrap fallback: path={}", path);
                }
            }
        }

        return result;
    }
}
