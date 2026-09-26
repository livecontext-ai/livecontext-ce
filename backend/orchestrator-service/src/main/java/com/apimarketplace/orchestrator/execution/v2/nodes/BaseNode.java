package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base implementation of ExecutionNode with common functionality.
 * Concrete node types extend this class.
 *
 * <p>Overrides {@link ExecutionNode#acceptServices(ServiceRegistry)} to receive
 * services polymorphically. Subclasses should override to pull additional
 * services they need.
 *
 * Note: Event emission, persistence, and metrics are handled by
 * V2ExecutionEventService in UnifiedExecutionEngine.traverseTree(),
 * not by individual nodes.
 */
public abstract class BaseNode implements ExecutionNode {

    protected final String nodeId;
    protected final NodeType type;
    protected final List<ExecutionNode> successors;
    protected final List<String> predecessorIds;  // For implicit merge detection

    // Services for node execution
    protected com.apimarketplace.orchestrator.services.interfaces.ToolsGateway toolsGateway;
    protected V2TemplateAdapter templateAdapter;
    // Numeric / boolean config fields the plan wrote as {{...}}, by config key then field name.
    // Set once at build time (CoreNodeBuilder), read-only afterwards: nodes are shared by
    // concurrent items, so each execution rebuilds its OWN effective config from these.
    private Map<String, Map<String, String>> deferredScalars = Map.of();
    // Used by file-producing nodes to resolve a sentinel epoch-0 to the run's real
    // current epoch when stamping a stored file (see resolveStorageEpoch).
    protected com.apimarketplace.orchestrator.repository.WorkflowRunRepository workflowRunRepository;

    protected BaseNode(String nodeId, NodeType type) {
        this.nodeId = nodeId;
        this.type = type;
        this.successors = new ArrayList<>();
        this.predecessorIds = new ArrayList<>();
    }

    /**
     * Sets ToolsGateway service for step execution.
     */
    public void setToolsGateway(com.apimarketplace.orchestrator.services.interfaces.ToolsGateway toolsGateway) {
        this.toolsGateway = toolsGateway;
    }

    /**
     * Sets the template adapter for SpEL template resolution.
     */
    public void setTemplateAdapter(V2TemplateAdapter templateAdapter) {
        this.templateAdapter = templateAdapter;
    }

    /**
     * Accepts services from the registry.
     * Base implementation injects toolsGateway and templateAdapter.
     * Subclasses should override to pull additional services they need.
     *
     * @param registry The service registry containing all available services
     */
    @Override
    public void acceptServices(ServiceRegistry registry) {
        this.toolsGateway = registry.getToolsGateway();
        this.templateAdapter = registry.getTemplateAdapter();
        this.workflowRunRepository = registry.getWorkflowRunRepository();
    }

    /**
     * Resolves the epoch to stamp on a file a workflow file-producer node persists to
     * {@code storage.storage}.
     *
     * <p><b>Why this is not simply {@code context.epoch()}.</b> {@code epoch == 0} is the
     * "unresolved" sentinel in the engine: a node reached via a deferred dispatch path
     * (signal-resume, async agent completion) can carry a {@code context.epoch()} of 0 when
     * the signal/pending it resumes from was registered before the first trigger fire, even
     * though the run has since fired to a real epoch (1, 2, ...). The execution context
     * deliberately keeps that 0 so it loads predecessor outputs / reconstructs state from the
     * epoch where that data actually lives - changing the context epoch would break readiness
     * and template resolution. But the FILE coordinate must not inherit the spurious 0, or the
     * stored file lands under a phantom {@code epoch 0} that never fired and the run's file
     * browser misattributes it.
     *
     * <p>This mirrors exactly what {@code StepDataPersistenceService} already does for the
     * node's {@code workflow_step_data} row
     * ({@code (explicitEpoch > 0) ? explicitEpoch : getCurrentEpochFromRun(...)}), so the file
     * row and its step-data row always agree on the epoch bucket.
     *
     * <p>Returns {@code context.epoch()} verbatim when it is already {@code > 0} (the common
     * inline-fire path), or when the run's current epoch cannot be resolved (best-effort:
     * repository absent in unit tests, or a genuine epoch-0 run that never fired).
     */
    protected int resolveStorageEpoch(ExecutionContext context) {
        int epoch = context.epoch();
        if (epoch > 0) {
            return epoch;
        }
        if (workflowRunRepository == null || context.runId() == null) {
            return epoch;
        }
        try {
            Integer resolved = workflowRunRepository.findByRunIdPublic(context.runId())
                .map(run -> {
                    Map<String, Object> metadata = run.getMetadata();
                    Object value = metadata != null ? metadata.get("currentEpoch") : null;
                    return (value instanceof Number n) ? n.intValue() : null;
                })
                .orElse(null);
            if (resolved != null && resolved > 0) {
                return resolved;
            }
        } catch (Exception e) {
            // Best-effort: never fail a file write because the epoch could not be re-resolved.
        }
        return epoch;
    }

    /**
     * Resolves every {@code {{...}}} in a configured value, keeping the type of what it references.
     *
     * <p>A field that is one whole reference comes back as that value (a map, a list, a number, a
     * file), text around references comes back as text, and maps and lists are resolved all the way
     * down. A reference to nothing (a skipped node, a path that does not exist) is {@code null}.
     *
     * <p>The configured text is never handed back IN PLACE of a value. It used to be, on a null and
     * on any error, and the node then ran with the literal {@code {{...}}}: sent it as an email
     * address, executed it as a query, reported it in the Params column where it read as "this
     * field is not resolved" although its neighbours were. A resolution that throws now fails the
     * node with the expression in the message instead.
     *
     * @throws IllegalStateException when the resolution itself fails
     */
    protected Object resolveTemplateValue(Object configured, ExecutionContext context) {
        if (configured == null || templateAdapter == null) {
            return configured;
        }
        // Structures are walked HERE and only their leaves go to the adapter. Handing it a whole
        // map would let its template-spec rule collapse any map carrying a `template` key (an
        // author's own field) into that one value, and would return an unordered copy.
        if (configured instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), resolveTemplateValue(v, context)));
            return out;
        }
        if (configured instanceof java.util.Collection<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(resolveTemplateValue(v, context)));
            return out;
        }
        if (!(configured instanceof String s) || s.isBlank()) {
            return configured;
        }
        try {
            Map<String, Object> toResolve = new java.util.HashMap<>(1);
            toResolve.put("__v__", configured);
            return templateAdapter.resolveTemplates(toResolve, context).get("__v__");
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                "Could not resolve " + describeForError(configured) + ": " + e.getMessage(), e);
        }
    }

    /**
     * {@link #resolveTemplateValue} for a field the node consumes as TEXT.
     *
     * <p>A structured result is its JSON and a file is its URL
     * ({@link com.apimarketplace.orchestrator.services.TemplateEngine#asText}), the same text the
     * engine produces for a reference embedded in a sentence, never Java's {@code {a=1}}. A
     * reference to nothing is {@code null}, never the template.
     */
    protected String resolveTemplateString(String template, ExecutionContext context) {
        return com.apimarketplace.orchestrator.services.TemplateEngine.asText(
            resolveTemplateValue(template, context));
    }

    public void setDeferredScalars(Map<String, Map<String, String>> deferredScalars) {
        this.deferredScalars = deferredScalars == null ? Map.of() : Map.copyOf(deferredScalars);
    }

    /** The template set aside for {@code configKey.field}, or {@code null}. */
    protected String deferredScalar(String configKey, String field) {
        Map<String, String> fields = deferredScalars.get(configKey);
        return fields == null ? null : fields.get(field);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper DEFERRED_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * The typed config this execution runs with: {@code config} with every numeric / boolean field
     * the plan wrote as a {@code {{...}}} template resolved and converted to the field's type.
     *
     * <p>The plan parser sets those fields aside (a template cannot live in an {@code int}), and
     * the typed config holds the field's default until this runs. Returns {@code config} itself
     * when nothing was set aside, so a node without templates pays nothing.
     *
     * @throws IllegalStateException when a template resolves to nothing, or to a value the field
     *         cannot hold ("abc" for a number): the node fails naming the field, never runs on
     *         the default in its place
     */
    @SuppressWarnings("unchecked")
    protected <T> T withDeferredScalars(String configKey, T config, Class<T> type, ExecutionContext context) {
        Map<String, String> fields = deferredScalars.get(configKey);
        if (fields == null || fields.isEmpty()) {
            return config;
        }
        Map<String, Object> values = recordValues(config);
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            Object value = resolveDeferredScalar(configKey, entry.getKey(), entry.getValue(), context);
            if (isIntegralField(type, entry.getKey())) {
                // Jackson reads 5.7 into an int as 5 without a word: refuse it instead.
                value = resolveDeferredLong(configKey, entry.getKey(), entry.getValue(), context);
            }
            values.put(entry.getKey(), value);
        }
        try {
            return DEFERRED_MAPPER.convertValue(values, type);
        } catch (IllegalArgumentException e) {
            String field = failingField(e);
            String template = field != null ? fields.get(field) : null;
            throw new IllegalStateException(
                (field != null ? configKey + "." + field + (template != null ? " '" + template + "'" : "")
                               : "A templated setting of " + configKey)
                + " resolved to a value it cannot hold: " + rootMessage(e), e);
        }
    }

    /**
     * One templated scalar, resolved: the value itself when it is already a number or boolean,
     * its trimmed text otherwise (Jackson then reads "5" as 5 and "true" as true).
     */
    protected Object resolveDeferredScalar(String configKey, String field, String template, ExecutionContext context) {
        Object resolved = resolveTemplateValue(template, context);
        if (resolved instanceof String text) {
            resolved = text.trim();
        }
        if (resolved == null || (resolved instanceof String text && text.isEmpty())) {
            throw new IllegalStateException(configKey + "." + field + " '" + template
                + "' resolved to nothing. Check that the referenced node ran and that the path exists.");
        }
        if (!(resolved instanceof Number) && !(resolved instanceof Boolean) && !(resolved instanceof String)) {
            throw new IllegalStateException(configKey + "." + field + " '" + template
                + "' must resolve to a number or a boolean, got " + resolved.getClass().getSimpleName());
        }
        return resolved;
    }

    /** {@link #resolveDeferredScalar} for a field that must be an integer. */
    protected long resolveDeferredLong(String configKey, String field, String template, ExecutionContext context) {
        Object value = resolveDeferredScalar(configKey, field, template, context);
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return ((Number) value).longValue();
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException(configKey + "." + field + " '" + template
                + "' must resolve to a whole number, got '" + value + "'");
        }
    }

    /**
     * A config record's values by the names the plan (and so Jackson's reader) uses: the
     * component name, or its {@code @JsonProperty}. Read by reflection rather than serialized,
     * because the serializer names a boolean component {@code isHtml} as {@code html}, and the
     * rebuilt config would then silently lose it whenever another field was templated.
     */
    private static Map<String, Object> recordValues(Object config) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        if (config == null) {
            return values;
        }
        if (!config.getClass().isRecord()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> converted = DEFERRED_MAPPER.convertValue(config, Map.class);
            values.putAll(converted);
            return values;
        }
        for (java.lang.reflect.RecordComponent component : config.getClass().getRecordComponents()) {
            com.fasterxml.jackson.annotation.JsonProperty json =
                component.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            String name = json != null && !json.value().isEmpty() ? json.value() : component.getName();
            try {
                java.lang.reflect.Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                values.put(name, accessor.invoke(config));
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read config field " + name, e);
            }
        }
        return values;
    }

    private static boolean isIntegralField(Class<?> type, String field) {
        if (!type.isRecord()) {
            return false;
        }
        for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
            com.fasterxml.jackson.annotation.JsonProperty json =
                component.getAnnotation(com.fasterxml.jackson.annotation.JsonProperty.class);
            String name = json != null && !json.value().isEmpty() ? json.value() : component.getName();
            if (name.equals(field)) {
                Class<?> t = component.getType();
                return t == int.class || t == long.class || t == short.class
                    || t == Integer.class || t == Long.class || t == Short.class;
            }
        }
        return false;
    }

    /** The config field Jackson could not convert, read from its reference path. */
    private static String failingField(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof com.fasterxml.jackson.databind.JsonMappingException mapping
                    && !mapping.getPath().isEmpty()) {
                return mapping.getPath().get(mapping.getPath().size() - 1).getFieldName();
            }
        }
        return null;
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message != null ? message.split("\n")[0] : root.getClass().getSimpleName();
    }

    private static String describeForError(Object configured) {
        String text = configured instanceof String s ? s : String.valueOf(configured);
        return "'" + (text.length() > 200 ? text.substring(0, 200) + "..." : text) + "'";
    }

    @Override
    public String getNodeId() {
        return nodeId;
    }

    @Override
    public NodeType getType() {
        return type;
    }

    /**
     * Default implementation: can execute if all dependencies are completed.
     *
     * <p>Handles port-based predecessors (e.g., "core:check_item:if") by also checking
     * the base node ID (e.g., "core:check_item") for completion. This is needed because
     * Decision branch targets have predecessors with ports for split-aware routing,
     * but the execution state tracks completion by the base node ID.
     */
    @Override
    public boolean canExecute(ExecutionContext context) {
        // Get dependencies from plan if available
        List<String> dependencies = getDependencies(context);

        // Multi-trigger shared sink: a node with multiple trigger predecessors converges
        // several triggers onto the same DAG (auto-detected as one DAG group). Each trigger
        // fires its OWN epoch - only the current trigger's edge can be completed in this
        // epoch. The other trigger predecessors never fire in this epoch by design, so
        // require only the current trigger's edge (plus all non-trigger dependencies).
        //
        // This mirrors ReadyNodeCalculator.filterForeignTriggerPredecessors. Without this
        // filter, the engine's canExecute returned false → the node was marked SKIPPED
        // ("Prerequisites not met or condition false") even though ReadyNodeCalculator
        // considered it ready - causing shared sinks (wait, transform, mcp, etc.) to skip
        // on every trigger fire in a multi-trigger workflow.
        long triggerDepCount = dependencies.stream()
            .filter(dep -> dep != null && dep.startsWith("trigger:"))
            .count();
        List<String> effectiveDependencies = dependencies;
        String currentTriggerId = context.triggerId();
        if (triggerDepCount > 1 && currentTriggerId != null) {
            effectiveDependencies = dependencies.stream()
                .filter(dep -> dep == null || !dep.startsWith("trigger:") || dep.equals(currentTriggerId))
                .toList();
        }

        // Can execute if all (effective) dependencies are completed.
        // For port-based predecessors (e.g., "core:decision:if"), also check base node ID.
        return effectiveDependencies.stream()
            .allMatch(dep -> {
                if (context.isCompleted(dep)) {
                    return true;
                }
                // Try stripping port: "core:check_item:if" -> "core:check_item"
                com.apimarketplace.orchestrator.utils.EdgeRefParser.EdgeRef ref =
                    com.apimarketplace.orchestrator.utils.EdgeRefParser.parse(dep);
                if (ref != null && ref.port() != null && !ref.port().isEmpty()) {
                    String baseNodeId = ref.nodeType() + ":" + ref.nodeLabel();
                    return context.isCompleted(baseNodeId);
                }
                return false;
            });
    }

    /**
     * Get dependencies for this node.
     * Returns predecessorIds if set (for implicit merge support).
     * Override in subclasses if needed.
     */
    protected List<String> getDependencies(ExecutionContext context) {
        return predecessorIds;  // Return predecessors for implicit merge check
    }

    /**
     * Adds a predecessor node ID.
     * Used for implicit merge detection - nodes with multiple predecessors
     * must wait for all of them to complete.
     */
    public void addPredecessor(String predecessorId) {
        if (!this.predecessorIds.contains(predecessorId)) {
            this.predecessorIds.add(predecessorId);
        }
    }

    /**
     * Sets all predecessor IDs at once.
     */
    public void setPredecessors(List<String> predecessorIds) {
        this.predecessorIds.clear();
        this.predecessorIds.addAll(predecessorIds);
    }

    /**
     * Returns the predecessor node IDs.
     */
    public List<String> getPredecessorIds() {
        return predecessorIds;
    }

    /**
     * Checks if this node is an implicit merge (has multiple predecessors).
     */
    public boolean isImplicitMerge() {
        return predecessorIds.size() > 1;
    }

    /**
     * Lifecycle callback after node execution.
     * Default implementation is empty - event emission, persistence, and metrics
     * are handled by V2ExecutionEventService in UnifiedExecutionEngine.
     * Subclasses can override for node-specific cleanup or side effects.
     */
    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        // Default: no-op
        // V2ExecutionEventService handles all lifecycle events
    }

    /**
     * Default implementation: return all successors if result is success.
     * If the node failed, return empty list - successors should not execute.
     * Override in subclasses that need conditional flow (Decision, Loop).
     */
    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        // If this node failed, do not return successors - they should be skipped
        if (result != null && result.isFailure()) {
            return List.of();
        }
        return successors;
    }

    /**
     * Adds a successor node.
     */
    public void addSuccessor(ExecutionNode successor) {
        this.successors.add(successor);
    }

    /**
     * Sets all successors at once.
     */
    public void setSuccessors(List<ExecutionNode> successors) {
        this.successors.clear();
        this.successors.addAll(successors);
    }

    @Override
    public Map<String, Object> getMetadata() {
        return Map.of(
            "nodeId", nodeId,
            "type", type.name(),
            "successorCount", successors.size()
        );
    }

    /**
     * Returns all direct successors of this node (regardless of conditional logic).
     * This is used for skip propagation to traverse all downstream nodes.
     */
    public List<ExecutionNode> getSuccessors() {
        return successors;
    }

    /**
     * Enriches the node output map with the four mandatory metadata keys required by
     * {@code StepDataPersistenceService} for correct persistence to {@code workflow_step_data}:
     * <ul>
     *   <li>{@code node_type} - the {@link NodeType} name (used by enrichEntityWithNodeTypeFields)</li>
     *   <li>{@code item_index} - the per-item index from the execution context (PRIMARY persistence key)</li>
     *   <li>{@code itemIndex} - camelCase legacy alias kept for backwards compatibility</li>
     *   <li>{@code item_id} - the per-item id from the execution context</li>
     * </ul>
     *
     * <p>Use this helper to never forget mandatory metadata. Forgetting {@code item_index}
     * causes {@code StepDataPersistenceService.recordStep()} to drop the row silently.
     *
     * <p>This method is <b>idempotent</b>: keys already present in {@code result} are left
     * untouched, so a node that needs a custom {@code node_type} string (e.g. SPLIT, CLASSIFY)
     * can set it before calling this helper.
     *
     * @param result  the mutable output map produced by the node (must not be null)
     * @param context the current execution context
     * @return the same map instance, enriched in place
     */
    protected Map<String, Object> enrichWithMetadata(Map<String, Object> result, ExecutionContext context) {
        if (result == null) return result;
        result.putIfAbsent("node_type", this.type.name());
        result.putIfAbsent("item_index", context.itemIndex());
        result.putIfAbsent("itemIndex", context.itemIndex());
        result.putIfAbsent("item_id", context.itemId());
        return result;
    }

    /**
     * Convenience: enrich the result with mandatory metadata then wrap in a successful
     * {@link NodeExecutionResult}. Use this to never forget the mandatory keys.
     *
     * @param result  the node output map
     * @param context the current execution context
     * @return a success NodeExecutionResult with metadata-enriched output
     */
    protected NodeExecutionResult successWithMetadata(Map<String, Object> result, ExecutionContext context) {
        return NodeExecutionResult.success(nodeId, enrichWithMetadata(result, context));
    }

}
