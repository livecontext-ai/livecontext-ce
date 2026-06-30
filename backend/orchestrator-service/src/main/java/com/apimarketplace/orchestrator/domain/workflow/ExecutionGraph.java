package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.WorkflowUtils;
import java.util.*;
import java.util.stream.Collectors;

/**
 * V2 Execution Graph - Pure graph model with ports.
 *
 * Builds dependencies from simple from/to edges where:
 * - from: "nodeType:label:port" (e.g., "core:check:if", "core:process:body")
 * - to: "nodeType:label" (e.g., "mcp:fetch", "core:process")
 *
 * Dependencies are built by extracting the node key (without port) from edges.
 */
public class ExecutionGraph {

    private Map<String, Set<String>> dependencies;
    private Map<String, Set<String>> dependents;
    private Map<Integer, List<String>> executionLevels;
    private int maxLevel;
    private boolean isValid;
    private boolean hasCycles;

    /**
     * Default constructor for JSON deserialization.
     */
    public ExecutionGraph() {
        this.dependencies = new HashMap<>();
        this.dependents = new HashMap<>();
        this.executionLevels = new HashMap<>();
        this.maxLevel = 0;
        this.isValid = false;
        this.hasCycles = false;
    }

    private ExecutionGraph(Map<String, Set<String>> dependencies,
                           Map<String, Set<String>> dependents,
                           Map<Integer, List<String>> executionLevels,
                           int maxLevel, boolean isValid, boolean hasCycles) {
        this.dependencies = dependencies;
        this.dependents = dependents;
        this.executionLevels = executionLevels;
        this.maxLevel = maxLevel;
        this.isValid = isValid;
        this.hasCycles = hasCycles;
    }

    /**
     * Build execution graph from workflow plan.
     *
     * V2 format: Simple edges with from/to, ports indicate branching.
     */
    public static ExecutionGraph build(WorkflowPlan plan) {
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();

        // Initialize all nodes
        Set<String> allNodeIds = new HashSet<>(plan.getAllStepIds());
        for (String nodeId : allNodeIds) {
            dependencies.put(nodeId, new HashSet<>());
            dependents.put(nodeId, new HashSet<>());
        }

        // Add core nodes (decision, loop, split, merge) to the graph
        for (Core coreNode : plan.getCores()) {
            String nodeKey = coreNode.getNormalizedKey();
            if (nodeKey != null) {
                ensureNodeExists(nodeKey, dependencies, dependents);
                allNodeIds.add(nodeKey);
            }
        }

        // Build dependencies from V2 edges
        for (Edge edge : plan.getEdges()) {
            String from = edge.from();
            String to = edge.to();

            if (from == null || to == null) {
                continue;
            }

            // Skip iterate-port edges (loop-back connections don't create DAG dependencies)
            if ("iterate".equals(EdgeRefParser.getPort(to))) {
                continue;
            }

            // Get node keys (without ports)
            String fromKey = EdgeRefParser.getNodeKey(from);
            String toKey = EdgeRefParser.getNodeKey(to);

            if (fromKey == null || toKey == null) {
                continue;
            }

            // Ensure both nodes exist
            ensureNodeExists(fromKey, dependencies, dependents);
            ensureNodeExists(toKey, dependencies, dependents);
            allNodeIds.add(fromKey);
            allNodeIds.add(toKey);

            // Add dependency: to depends on from
            dependencies.get(toKey).add(fromKey);
            dependents.get(fromKey).add(toKey);
        }

        // Add params template dependencies
        addStepParamsDependencies(plan, dependencies, dependents, allNodeIds);
        addEdgeParamsDependencies(plan, dependencies, dependents, allNodeIds);

        // Detect cycles
        boolean hasCycles = detectCycles(dependencies, allNodeIds);

        // Calculate execution levels
        Map<Integer, List<String>> levels = calculateExecutionLevels(dependencies, allNodeIds);
        int maxLevel = levels.keySet().stream().mapToInt(Integer::intValue).max().orElse(0);

        // Validate graph
        boolean isValid = !hasCycles && !levels.isEmpty() &&
                          levels.values().stream().mapToInt(List::size).sum() == allNodeIds.size();

        return new ExecutionGraph(dependencies, dependents, levels, maxLevel, isValid, hasCycles);
    }

    private static void ensureNodeExists(String nodeId,
                                         Map<String, Set<String>> dependencies,
                                         Map<String, Set<String>> dependents) {
        if (nodeId == null) {
            return;
        }
        dependencies.computeIfAbsent(nodeId, __ -> new HashSet<>());
        dependents.computeIfAbsent(nodeId, __ -> new HashSet<>());
    }

    /**
     * Add dependencies from step params templates (V2 format).
     */
    private static void addStepParamsDependencies(WorkflowPlan plan,
                                                 Map<String, Set<String>> dependencies,
                                                 Map<String, Set<String>> dependents,
                                                 Set<String> allNodeIds) {
        for (Step step : plan.getMcps()) {
            if (step == null) {
                continue;
            }
            String normalized = step.getNormalizedKey();
            if (normalized == null || !allNodeIds.contains(normalized)) {
                continue;
            }
            Map<String, Object> params = plan.getStepParams(normalized);
            extractTemplateDependenciesFromParams(params, normalized, dependencies, dependents, allNodeIds);
        }

        for (Agent agent : plan.getAgents()) {
            if (agent == null) {
                continue;
            }
            String normalized = agent.getNormalizedKey();
            if (normalized == null || !allNodeIds.contains(normalized)) {
                continue;
            }
            // Extract from agent params if present
            if (agent.params() != null) {
                extractTemplateDependenciesFromParams(agent.params(), normalized, dependencies, dependents, allNodeIds);
            }
        }
    }

    /**
     * Add dependencies from edge params templates.
     */
    private static void addEdgeParamsDependencies(WorkflowPlan plan,
                                                 Map<String, Set<String>> dependencies,
                                                 Map<String, Set<String>> dependents,
                                                 Set<String> allNodeIds) {
        for (Edge edge : plan.getEdges()) {
            String to = edge.to();
            if (to == null) {
                continue;
            }

            String toKey = EdgeRefParser.getNodeKey(to);
            if (toKey == null || !allNodeIds.contains(toKey)) {
                continue;
            }

            // Extract dependencies from edge.params()
            if (edge.params() != null) {
                extractTemplateDependenciesFromParams(edge.params(), toKey, dependencies, dependents, allNodeIds);
            }
        }
    }

    private static void extractTemplateDependenciesFromParams(Map<String, Object> params,
                                                             String consumerStepId,
                                                             Map<String, Set<String>> dependencies,
                                                             Map<String, Set<String>> dependents,
                                                             Set<String> allNodeIds) {
        if (params == null || params.isEmpty() || consumerStepId == null) {
            return;
        }

        for (Object value : params.values()) {
            List<String> templateBases = extractTemplateBases(value);
            for (String base : templateBases) {
                String normalizedBase = WorkflowUtils.normalizeStepId(base);
                if (isTrackableStepReference(normalizedBase) && normalizedBase != null && allNodeIds.contains(normalizedBase)) {
                    ensureNodeExists(consumerStepId, dependencies, dependents);
                    ensureNodeExists(normalizedBase, dependencies, dependents);
                    dependencies.get(consumerStepId).add(normalizedBase);
                    dependents.get(normalizedBase).add(consumerStepId);
                }
            }
        }
    }

    private static List<String> extractTemplateBases(Object value) {
        String template = null;
        if (value instanceof String str) {
            template = str;
        } else if (value instanceof Map<?, ?> map) {
            Object candidate = map.containsKey("template") ? map.get("template") : map.get("value");
            if (candidate instanceof String strCandidate) {
                template = strCandidate;
            }
        }
        if (template == null) {
            return Collections.emptyList();
        }

        List<String> bases = new ArrayList<>();
        // Mirrors TemplateEngine.EXPRESSION_PATTERN - handles SpEL string literals containing `}`.
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{\\{((?:'(?:[^'\\\\]|\\\\.)*'|[^}|])+?)(?:\\|[^}]*)?}}");
        java.util.regex.Matcher matcher = pattern.matcher(template);
        while (matcher.find()) {
            String token = matcher.group(1).trim().toLowerCase(java.util.Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            int dotIndex = token.indexOf('.');
            String base = dotIndex < 0 ? token : token.substring(0, dotIndex);
            bases.add(base);
        }
        return bases;
    }

    /**
     * Determine if a template reference should create a dependency.
     */
    private static boolean isTrackableStepReference(String base) {
        if (base == null) {
            return false;
        }
        return base.startsWith("mcp:") || base.startsWith("core:") || base.startsWith("agent:")
            || base.startsWith("trigger:") || base.startsWith("table:");
    }

    /**
     * Detect cycles using DFS.
     */
    private static boolean detectCycles(Map<String, Set<String>> dependencies, Set<String> nodes) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String stepId : nodes) {
            if (!visited.contains(stepId)) {
                if (hasCycleDFS(stepId, dependencies, visited, recursionStack)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean hasCycleDFS(String stepId, Map<String, Set<String>> dependencies,
                                       Set<String> visited, Set<String> recursionStack) {
        visited.add(stepId);
        recursionStack.add(stepId);

        Set<String> deps = dependencies.get(stepId);
        if (deps != null) {
            for (String dependency : deps) {
                if (!visited.contains(dependency)) {
                    if (hasCycleDFS(dependency, dependencies, visited, recursionStack)) {
                        return true;
                    }
                } else if (recursionStack.contains(dependency)) {
                    return true;
                }
            }
        }

        recursionStack.remove(stepId);
        return false;
    }

    /**
     * Calculate execution levels using Kahn's algorithm.
     */
    private static Map<Integer, List<String>> calculateExecutionLevels(Map<String, Set<String>> dependencies,
                                                                       Set<String> nodes) {
        Map<Integer, List<String>> levels = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        // Calculate in-degree for each node
        for (String node : nodes) {
            inDegree.put(node, dependencies.getOrDefault(node, Collections.emptySet()).size());
        }

        // Start with nodes that have no dependencies (level 0)
        Queue<String> queue = new LinkedList<>();
        for (String stepId : nodes) {
            if (inDegree.get(stepId) == 0) {
                queue.offer(stepId);
                levels.computeIfAbsent(0, k -> new ArrayList<>()).add(stepId);
            }
        }

        int currentLevel = 0;
        Set<String> processed = new HashSet<>();

        while (!queue.isEmpty()) {
            int levelSize = queue.size();
            List<String> nextLevelNodes = new ArrayList<>();

            for (int i = 0; i < levelSize; i++) {
                String currentNode = queue.poll();
                processed.add(currentNode);

                // Find dependents and reduce their in-degree
                Set<String> nodeDependents = new HashSet<>();
                for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
                    if (entry.getValue().contains(currentNode)) {
                        nodeDependents.add(entry.getKey());
                    }
                }

                for (String dependent : nodeDependents) {
                    inDegree.put(dependent, inDegree.get(dependent) - 1);
                    if (inDegree.get(dependent) == 0 && !processed.contains(dependent)) {
                        nextLevelNodes.add(dependent);
                    }
                }
            }

            if (!nextLevelNodes.isEmpty()) {
                currentLevel++;
                levels.put(currentLevel, new ArrayList<>(nextLevelNodes));
                queue.addAll(nextLevelNodes);
            }
        }

        return levels;
    }

    // ===== GETTERS =====

    public Set<String> getDependencies(String stepId) {
        return new HashSet<>(dependencies.getOrDefault(stepId, new HashSet<>()));
    }

    public Set<String> getDependents(String stepId) {
        return new HashSet<>(dependents.getOrDefault(stepId, new HashSet<>()));
    }

    public List<String> getStepsAtLevel(int level) {
        return new ArrayList<>(executionLevels.getOrDefault(level, new ArrayList<>()));
    }

    public int getLevel(String stepId) {
        for (Map.Entry<Integer, List<String>> entry : executionLevels.entrySet()) {
            if (entry.getValue().contains(stepId)) {
                return entry.getKey();
            }
        }
        return -1;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public boolean isValid() {
        return isValid;
    }

    public boolean hasCycles() {
        return hasCycles;
    }

    public Map<Integer, List<String>> getAllLevels() {
        return new HashMap<>(executionLevels);
    }

    public Map<String, Set<String>> getAllDependencies() {
        Map<String, Set<String>> copy = new HashMap<>();
        dependencies.forEach((child, parents) -> copy.put(child, new HashSet<>(parents)));
        return copy;
    }

    public boolean canExecute(String stepId, Set<String> completedSteps) {
        Set<String> deps = getDependencies(stepId);
        return completedSteps.containsAll(deps);
    }

    public List<String> getParallelSteps(String stepId) {
        int level = getLevel(stepId);
        if (level == -1) return new ArrayList<>();

        return getStepsAtLevel(level).stream()
                                     .filter(id -> !id.equals(stepId))
                                     .collect(Collectors.toList());
    }

    public List<String> getCriticalPath() {
        List<String> criticalPath = new ArrayList<>();

        List<String> lastLevelSteps = getStepsAtLevel(maxLevel);
        if (lastLevelSteps.isEmpty()) return criticalPath;

        String currentStep = lastLevelSteps.get(0);
        criticalPath.add(currentStep);

        while (true) {
            Set<String> deps = getDependencies(currentStep);
            if (deps.isEmpty()) break;

            String nextStep = deps.stream()
                                  .max(Comparator.comparingInt(this::getLevel))
                                  .orElse(null);

            if (nextStep == null) break;

            criticalPath.add(0, nextStep);
            currentStep = nextStep;
        }

        return criticalPath;
    }

    public GraphStatistics getStatistics() {
        int totalSteps = dependencies.size();
        int totalEdges = dependencies.values().stream().mapToInt(Set::size).sum();
        int levelsCount = executionLevels.size();

        Map<Integer, Integer> stepsPerLevel = executionLevels.entrySet().stream()
                                                             .collect(Collectors.toMap(
                                                                     Map.Entry::getKey,
                                                                     entry -> entry.getValue().size()
                                                                                      ));

        return new GraphStatistics(totalSteps, totalEdges, levelsCount, maxLevel,
                                   isValid, hasCycles, stepsPerLevel);
    }

    public String toTextRepresentation() {
        StringBuilder sb = new StringBuilder();
        sb.append("ExecutionGraph {\n");
        sb.append("  Valid: ").append(isValid).append("\n");
        sb.append("  Has Cycles: ").append(hasCycles).append("\n");
        sb.append("  Max Level: ").append(maxLevel).append("\n");
        sb.append("  Execution Levels:\n");

        for (int level = 0; level <= maxLevel; level++) {
            List<String> steps = getStepsAtLevel(level);
            sb.append("    Level ").append(level).append(": ").append(steps).append("\n");
        }

        sb.append("}");
        return sb.toString();
    }

    public record GraphStatistics(int totalSteps, int totalEdges, int levelsCount,
                                  int maxLevel, boolean isValid, boolean hasCycles,
                                  Map<Integer, Integer> stepsPerLevel) {}

    // ===== SETTERS FOR JSON DESERIALIZATION =====

    public void setDependencies(Map<String, Set<String>> dependencies) {
        this.dependencies = dependencies;
    }

    public void setDependents(Map<String, Set<String>> dependents) {
        this.dependents = dependents;
    }

    public void setExecutionLevels(Map<Integer, List<String>> executionLevels) {
        this.executionLevels = executionLevels;
    }

    public void setMaxLevel(int maxLevel) {
        this.maxLevel = maxLevel;
    }

    public void setValid(boolean valid) {
        this.isValid = valid;
    }

    public void setHasCycles(boolean hasCycles) {
        this.hasCycles = hasCycles;
    }
}
