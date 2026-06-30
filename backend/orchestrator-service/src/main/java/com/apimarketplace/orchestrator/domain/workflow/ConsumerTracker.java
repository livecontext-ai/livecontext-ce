package com.apimarketplace.orchestrator.domain.workflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.apimarketplace.orchestrator.utils.WorkflowUtils;

/**
 * Tracks consumer dependencies and manages template reference registration.
 * Used to determine when step outputs can be garbage collected.
 */
public class ConsumerTracker {

    // Mirrors TemplateEngine.EXPRESSION_PATTERN - handles SpEL string literals containing `}`.
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{((?:'(?:[^'\\\\]|\\\\.)*'|[^}|])+?)(?:\\|[^}]*)?\\}\\}");

    private final Map<String, Set<String>> remainingConsumers = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> dynamicDependencies = new ConcurrentHashMap<>();

    public void initialize(WorkflowPlan plan) {
        ExecutionGraph graph = plan.getExecutionGraph();
        Set<String> allStepIds = plan.getAllStepIds();

        for (String stepId : allStepIds) {
            Set<String> dependents = graph.getDependents(stepId);
            if (dependents == null || dependents.isEmpty()) continue;
            Set<String> consumers = ConcurrentHashMap.newKeySet();
            consumers.addAll(dependents);
            remainingConsumers.put(stepId, consumers);
        }
    }

    public void registerFromPlan(WorkflowPlan plan) {
        // Register from step params
        for (Step step : plan.getMcps()) {
            if (step == null) continue;
            String stepKey = step.getNormalizedKey();
            if (stepKey != null) {
                registerTemplatesFromParams(plan.getStepParams(stepKey), stepKey);
            }
        }

        // Register from edge params
        for (Edge edge : plan.getEdges()) {
            if (edge.to() != null) {
                String toKey = com.apimarketplace.orchestrator.utils.EdgeRefParser.getNodeKey(edge.to());
                registerTemplatesFromParams(edge.params(), toKey);
            }
        }

        // Register from Core conditions
        for (Core coreNode : plan.getCores()) {
            if (coreNode == null) continue;
            String nodeKey = coreNode.getNormalizedKey();

            if (coreNode.decisionConditions() != null) {
                for (Core.DecisionCondition condition : coreNode.decisionConditions()) {
                    if (condition != null && condition.expression() != null) {
                        registerConditionReferences(condition.expression(), nodeKey);
                    }
                }
            }

            if (coreNode.loopCondition() != null) {
                registerConditionReferences(coreNode.loopCondition(), nodeKey);
            }

            if (coreNode.list() != null) {
                registerConditionReferences(coreNode.list(), nodeKey);
            }
        }
    }

    public void markDependenciesConsumed(String stepId, WorkflowPlan plan) {
        if (stepId == null) return;
        String normalizedConsumer = WorkflowUtils.normalizeStepId(stepId);

        Set<String> dependencies = new HashSet<>();
        Set<String> staticDeps = plan.getExecutionGraph().getDependencies(normalizedConsumer);
        if (staticDeps != null) dependencies.addAll(staticDeps);
        Set<String> dynamicDeps = dynamicDependencies.remove(normalizedConsumer);
        if (dynamicDeps != null) dependencies.addAll(dynamicDeps);

        for (String dependency : dependencies) {
            String normalizedDependency = WorkflowUtils.normalizeStepId(dependency);
            releaseConsumer(normalizedDependency, normalizedConsumer);
        }
    }

    public void releaseConsumer(String sourceStepId, String consumerStepId) {
        if (sourceStepId == null || consumerStepId == null) return;
        String normalizedSource = WorkflowUtils.normalizeStepId(sourceStepId);
        String normalizedConsumer = WorkflowUtils.normalizeStepId(consumerStepId);
        if (normalizedSource == null || normalizedConsumer == null) return;

        Set<String> consumers = remainingConsumers.get(normalizedSource);
        if (consumers == null) return;
        consumers.remove(normalizedConsumer);
    }

    public boolean hasPendingConsumers(String stepId) {
        Set<String> consumers = remainingConsumers.get(stepId);
        return consumers != null && !consumers.isEmpty();
    }

    public void removeConsumerTracking(String stepId) {
        remainingConsumers.remove(stepId);
    }

    public void registerTemplateDependency(String sourceStepId, String consumerStepId) {
        String normalizedSource = WorkflowUtils.normalizeStepId(sourceStepId);
        String normalizedConsumer = WorkflowUtils.normalizeStepId(consumerStepId);
        if (normalizedSource == null || normalizedConsumer == null) return;
        if (normalizedSource.equals(normalizedConsumer)) return;

        dynamicDependencies.computeIfAbsent(normalizedConsumer, k -> ConcurrentHashMap.newKeySet()).add(normalizedSource);
        remainingConsumers.computeIfAbsent(normalizedSource, k -> ConcurrentHashMap.newKeySet()).add(normalizedConsumer);
    }

    private void registerTemplatesFromParams(Map<String, Object> params, String consumerId) {
        if (params == null || params.isEmpty() || consumerId == null) return;
        for (Object value : params.values()) {
            for (String base : extractTemplateBases(value)) {
                if (isTrackableReference(base)) {
                    registerTemplateDependency(base, consumerId);
                }
            }
        }
    }

    private void registerConditionReferences(String expression, String consumerId) {
        if (expression == null || consumerId == null) return;
        for (String base : extractTemplateBases(expression)) {
            if (isTrackableReference(base)) {
                registerTemplateDependency(base, consumerId);
            }
        }
    }

    private List<String> extractTemplateBases(Object value) {
        String template = null;
        if (value instanceof String str) {
            template = str;
        } else if (value instanceof Map<?, ?> map) {
            Object candidate = map.containsKey("template") ? map.get("template") : map.get("value");
            if (candidate instanceof String strCandidate) {
                template = strCandidate;
            }
        }
        if (template == null) return Collections.emptyList();

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        List<String> bases = new ArrayList<>();
        while (matcher.find()) {
            String token = matcher.group(1).trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) continue;
            int dotIndex = token.indexOf('.');
            String base = dotIndex < 0 ? token : token.substring(0, dotIndex);
            bases.add(base);
        }
        return bases;
    }

    private boolean isTrackableReference(String base) {
        if (base == null) return false;
        return base.startsWith("mcp:") || base.startsWith("core:") || base.startsWith("trigger:")
                || base.startsWith("table:") || base.startsWith("agent:");
    }

    public void clear() {
        remainingConsumers.clear();
        dynamicDependencies.clear();
    }
}
