package com.apimarketplace.orchestrator.utils;

import com.apimarketplace.orchestrator.domain.workflow.Edge;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Utility for comparing two workflow plans by topology (node-id set + directed
 * edge set). Used to validate that a live-edited plan can safely replace a run's
 * frozen plan without invalidating the {@code StateSnapshot}, which indexes
 * every per-node counter by node id.
 *
 * <p>Node content (params, prompts, config, labels that don't change the
 * normalized id) is deliberately ignored - those don't invalidate snapshot
 * counters. Only structural changes (added/removed nodes, rewired edges) are
 * flagged as incompatible.
 *
 * <p>Notes are intentionally excluded from {@code nodeIds} - they're visual-only
 * and never execute or appear in StateSnapshot.
 */
public final class PlanTopology {

    private static final Logger logger = LoggerFactory.getLogger(PlanTopology.class);
    private static final String TOPOLOGY_CHECK_TENANT = "topology-check";

    private PlanTopology() {}

    /**
     * @return {@code true} iff both plans parse successfully and share the same
     *         node-id set and the same directed edge set. Returns {@code false}
     *         on null inputs or parse failure (defensive - caller preserves the
     *         frozen plan rather than crashing on a malformed swap).
     */
    public static boolean areCompatible(Map<String, Object> oldPlan, Map<String, Object> newPlan) {
        if (oldPlan == null || newPlan == null) return false;
        if (oldPlan == newPlan) return true;
        try {
            WorkflowPlan oldParsed = WorkflowPlanParser.parse(oldPlan, TOPOLOGY_CHECK_TENANT);
            WorkflowPlan newParsed = WorkflowPlanParser.parse(newPlan, TOPOLOGY_CHECK_TENANT);
            if (!collectNodeIds(oldParsed).equals(collectNodeIds(newParsed))) return false;
            return collectEdgeKeys(oldParsed).equals(collectEdgeKeys(newParsed));
        } catch (Exception e) {
            logger.debug("[PlanTopology] Comparison failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Describe the topology difference between two plans as a short "added=[...]
     * removed=[...] edgesAdded=[...] edgesRemoved=[...]" string - useful for
     * operator-facing WARN logs when {@link #areCompatible} returns {@code false}.
     * Returns {@code "<unparseable>"} if either plan fails to parse.
     */
    public static String describeDiff(Map<String, Object> oldPlan, Map<String, Object> newPlan) {
        if (oldPlan == null || newPlan == null) return "<null-plan>";
        try {
            WorkflowPlan oldParsed = WorkflowPlanParser.parse(oldPlan, TOPOLOGY_CHECK_TENANT);
            WorkflowPlan newParsed = WorkflowPlanParser.parse(newPlan, TOPOLOGY_CHECK_TENANT);
            Set<String> oldNodes = collectNodeIds(oldParsed);
            Set<String> newNodes = collectNodeIds(newParsed);
            Set<String> oldEdges = collectEdgeKeys(oldParsed);
            Set<String> newEdges = collectEdgeKeys(newParsed);
            return "added=" + diffSorted(newNodes, oldNodes)
                    + " removed=" + diffSorted(oldNodes, newNodes)
                    + " edgesAdded=" + diffSorted(newEdges, oldEdges)
                    + " edgesRemoved=" + diffSorted(oldEdges, newEdges);
        } catch (Exception e) {
            return "<unparseable>";
        }
    }

    private static Set<String> collectNodeIds(WorkflowPlan plan) {
        Set<String> ids = new HashSet<>();
        // Notes are visual-only; they never execute or appear in StateSnapshot.
        plan.getTriggers().forEach(t -> addIfNotNull(ids, t.getNormalizedKey()));
        plan.getMcps().forEach(s -> addIfNotNull(ids, s.getNormalizedKey()));
        plan.getAgents().forEach(a -> addIfNotNull(ids, a.getNormalizedKey()));
        plan.getCores().forEach(c -> addIfNotNull(ids, c.getNormalizedKey()));
        plan.getTables().forEach(t -> addIfNotNull(ids, t.getNormalizedKey()));
        plan.getInterfaces().forEach(i -> addIfNotNull(ids, i.getNormalizedKey()));
        return ids;
    }

    private static Set<String> collectEdgeKeys(WorkflowPlan plan) {
        Set<String> keys = new HashSet<>();
        for (Edge e : plan.getEdges()) {
            keys.add(e.from() + "->" + e.to());
        }
        return keys;
    }

    private static void addIfNotNull(Set<String> ids, String key) {
        if (key != null) ids.add(key);
    }

    private static Set<String> diffSorted(Set<String> a, Set<String> b) {
        Set<String> diff = new TreeSet<>(a);
        diff.removeAll(b);
        return diff;
    }
}
