package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Analyzer for WorkflowPlan structure detection (merges, split).
 * Extracted from WorkflowPlan for Single Responsibility Principle.
 */
public final class WorkflowPlanAnalyzer {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPlanAnalyzer.class);

    private WorkflowPlanAnalyzer() {}

    // ===== MERGE DETECTION =====

    /**
     * Detect merge nodes from edges.
     * A merge node receives edges from multiple distinct source nodes.
     */
    public static Map<String, MergeNode> detectMergeNodes(List<Edge> edges) {
        Map<String, MergeNode> detected = new HashMap<>();
        Map<String, Set<String>> incomingEdges = new HashMap<>();

        for (Edge edge : edges) {
            if (edge.from() == null || edge.to() == null) continue;

            String toKey = EdgeRefParser.getNodeKey(edge.to());
            String fromKey = EdgeRefParser.getNodeKey(edge.from());

            if (toKey != null && fromKey != null) {
                incomingEdges.computeIfAbsent(toKey, k -> new HashSet<>()).add(fromKey);
            }
        }

        for (Map.Entry<String, Set<String>> entry : incomingEdges.entrySet()) {
            List<String> sources = new ArrayList<>(entry.getValue());
            if (sources.size() > 1) {
                String stepId = entry.getKey();
                if (isTrueMergeNode(edges, stepId, sources)) {
                    detected.put(stepId, new MergeNode(stepId, sources, "QUEUE_1_TO_1"));
                    logger.debug("Detected merge node: {} with sources: {}", stepId, sources);
                }
            }
        }

        return detected;
    }

    private static boolean isTrueMergeNode(List<Edge> edges, String stepId, List<String> sources) {
        Map<String, BranchGroup> branchGroups = groupSourcesByBranchType(edges, stepId, sources);
        int effectiveSources = branchGroups.values().stream()
            .mapToInt(BranchGroup::effectiveCount)
            .sum();
        return effectiveSources > 1;
    }

    private static Map<String, BranchGroup> groupSourcesByBranchType(List<Edge> edges, String stepId, List<String> sources) {
        Map<String, BranchGroup> groups = new LinkedHashMap<>();

        for (String source : sources) {
            BranchGrouping grouping = determineBranchGroupType(edges, source, stepId);
            BranchGroup group = groups.computeIfAbsent(grouping.key(), k -> new BranchGroup(grouping.exclusive()));
            group.addSource(source);
        }

        return groups;
    }

    private static BranchGrouping determineBranchGroupType(List<Edge> edges, String sourceNodeKey, String targetStepId) {
        for (Edge edge : edges) {
            if (edge.from() == null || edge.to() == null) continue;

            String edgeFromKey = EdgeRefParser.getNodeKey(edge.from());
            String edgeToKey = EdgeRefParser.getNodeKey(edge.to());

            if (!sourceNodeKey.equals(edgeFromKey) || !targetStepId.equals(edgeToKey)) continue;

            var fromRef = EdgeRefParser.parse(edge.from());
            if (fromRef == null) continue;

            // Check port type to determine if this is a decision/switch or loop branch
            // nodeType from EdgeRefParser is "core", not "decision"/"switch"/"loop"
            // Use getPortType() to determine the actual control flow type
            String portType = EdgeRefParser.getPortType(fromRef.port());
            if ("decision".equals(portType) || "switch".equals(portType)) {
                return new BranchGrouping("decision_" + fromRef.getNodeKey(), true);
            }

            if ("loop".equals(portType) && fromRef.hasPort()) {
                return new BranchGrouping("loop_" + fromRef.getNodeKey(), true);
            }
        }

        return new BranchGrouping("standard_" + sourceNodeKey, false);
    }

    private static final class BranchGroup {
        private final List<String> sources = new ArrayList<>();
        private final boolean exclusive;

        BranchGroup(boolean exclusive) { this.exclusive = exclusive; }
        void addSource(String source) { sources.add(source); }
        int effectiveCount() { return sources.isEmpty() ? 0 : (exclusive ? 1 : sources.size()); }
    }

    private record BranchGrouping(String key, boolean exclusive) {}

    // ===== SPLIT DETECTION =====

    /**
     * Detect Split loops from cores with type="split".
     */
    public static Map<String, SplitLoop> detectSplitLoops(
            List<Core> coreNodes, List<Edge> edges) {
        logger.debug("[Split Detection] Starting - cores: {}, edges: {}",
            coreNodes != null ? coreNodes.size() : 0,
            edges != null ? edges.size() : 0);

        Map<String, SplitLoop> detected = new HashMap<>();
        if (coreNodes == null) return detected;

        for (Core coreNode : coreNodes) {
            if (!"split".equals(coreNode.type())) continue;

            String label = coreNode.label();
            if (label == null || label.isBlank()) {
                logger.warn("[Split Detection] Split core {} has no label, skipping", coreNode.id());
                continue;
            }

            String normalizedLabel = LabelNormalizer.normalizeLabel(label);
            String splitNodeKey = "core:" + normalizedLabel;

            // Find entry step
            String entryStep = null;
            Map<String, Object> entryScope = new HashMap<>();

            for (Edge edge : edges) {
                if (edge.to() == null) continue;
                String toKey = EdgeRefParser.getNodeKey(edge.to());
                if (splitNodeKey.equals(toKey)) {
                    entryStep = EdgeRefParser.getNodeKey(edge.from());
                    if (edge.params() != null) entryScope.putAll(edge.params());
                    break;
                }
            }

            // Find body steps from edges (skip interface nodes - they are UI-only, not execution nodes)
            List<SplitStep> steps = new ArrayList<>();
            for (Edge edge : edges) {
                if (edge.from() == null) continue;
                String fromKey = EdgeRefParser.getNodeKey(edge.from());
                if (splitNodeKey.equals(fromKey) && edge.to() != null) {
                    String toKey = EdgeRefParser.getNodeKey(edge.to());
                    if (toKey != null && !toKey.startsWith("interface:") &&
                            steps.stream().noneMatch(s -> toKey.equals(s.stepId()))) {
                        steps.add(new SplitStep(toKey, toKey));
                    }
                }
            }

            SplitLoop splitLoop = new SplitLoop(
                normalizedLabel, entryStep, coreNode.list(),
                coreNode.maxItems() != null ? coreNode.maxItems() : 100,
                coreNode.splitStrategy() != null ? coreNode.splitStrategy() : "continue-anyway",
                steps, entryScope, null
            );

            detected.put(normalizedLabel, splitLoop);
            logger.debug("[Split Detection] Added split '{}': entryStep='{}', bodyEdges={}",
                normalizedLabel, entryStep, steps.size());
        }

        return detected;
    }

    // ===== UTILITY METHODS =====

    /**
     * Check if a step is a probable merge node.
     */
    public static boolean isProbableMergeNode(String stepId, List<Edge> edges) {
        if (stepId == null) return false;

        String normalizedId = WorkflowPlanParser.normalizeStepId(stepId);

        long distinctSources = edges.stream()
            .filter(edge -> {
                if (edge.to() == null) return false;
                String toKey = EdgeRefParser.getNodeKey(edge.to());
                return normalizedId.equals(toKey);
            })
            .map(edge -> EdgeRefParser.getNodeKey(edge.from()))
            .filter(Objects::nonNull)
            .distinct()
            .count();

        return distinctSources > 1;
    }

}
