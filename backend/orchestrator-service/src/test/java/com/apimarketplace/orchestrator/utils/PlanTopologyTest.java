package com.apimarketplace.orchestrator.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural equivalence tests for {@link PlanTopology#areCompatible}.
 *
 * <p>Topology = same node-id set + same directed-edge set. Params, prompts,
 * labels-that-don't-change-the-normalized-id are ignored.
 */
@DisplayName("PlanTopology - structural compatibility")
class PlanTopologyTest {

    @Test
    @DisplayName("Compatible when only mcp params differ")
    void compatibleWhenOnlyParamsDiffer() {
        Map<String, Object> oldPlan = planWith(List.of(mcp("Fetch", Map.of("url", "v1"))),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> newPlan = planWith(List.of(mcp("Fetch", Map.of("url", "v2"))),
                List.of(edge("trigger:start", "mcp:fetch")));

        assertThat(PlanTopology.areCompatible(oldPlan, newPlan)).isTrue();
    }

    @Test
    @DisplayName("Incompatible when a node is added")
    void incompatibleWhenNodeAdded() {
        Map<String, Object> oldPlan = planWith(List.of(mcp("Fetch", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch")));
        Map<String, Object> newPlan = planWith(
                List.of(mcp("Fetch", Map.of()), mcp("Transform", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch"), edge("mcp:fetch", "mcp:transform")));

        assertThat(PlanTopology.areCompatible(oldPlan, newPlan)).isFalse();
    }

    @Test
    @DisplayName("Incompatible when a node is removed")
    void incompatibleWhenNodeRemoved() {
        Map<String, Object> oldPlan = planWith(
                List.of(mcp("Fetch", Map.of()), mcp("Transform", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch"), edge("mcp:fetch", "mcp:transform")));
        Map<String, Object> newPlan = planWith(List.of(mcp("Fetch", Map.of())),
                List.of(edge("trigger:start", "mcp:fetch")));

        assertThat(PlanTopology.areCompatible(oldPlan, newPlan)).isFalse();
    }

    @Test
    @DisplayName("Incompatible when an edge is rewired")
    void incompatibleWhenEdgeRewired() {
        List<Map<String, Object>> nodes = List.of(mcp("Fetch", Map.of()), mcp("Transform", Map.of()));
        Map<String, Object> oldPlan = planWith(nodes,
                List.of(edge("trigger:start", "mcp:fetch"), edge("mcp:fetch", "mcp:transform")));
        Map<String, Object> newPlan = planWith(nodes,
                List.of(edge("trigger:start", "mcp:transform"), edge("mcp:transform", "mcp:fetch")));

        assertThat(PlanTopology.areCompatible(oldPlan, newPlan)).isFalse();
    }

    @Test
    @DisplayName("Null inputs always return false")
    void nullInputsReturnFalse() {
        Map<String, Object> plan = planWith(List.of(), List.of());
        assertThat(PlanTopology.areCompatible(null, plan)).isFalse();
        assertThat(PlanTopology.areCompatible(plan, null)).isFalse();
        assertThat(PlanTopology.areCompatible(null, null)).isFalse();
    }

    @Test
    @DisplayName("Diff description lists added and removed nodes")
    void diffDescribesAddedAndRemovedNodes() {
        Map<String, Object> oldPlan = planWith(List.of(mcp("A", Map.of()), mcp("B", Map.of())),
                List.of(edge("trigger:start", "mcp:a"), edge("mcp:a", "mcp:b")));
        Map<String, Object> newPlan = planWith(List.of(mcp("A", Map.of()), mcp("C", Map.of())),
                List.of(edge("trigger:start", "mcp:a"), edge("mcp:a", "mcp:c")));

        String diff = PlanTopology.describeDiff(oldPlan, newPlan);

        assertThat(diff).contains("added=[mcp:c]").contains("removed=[mcp:b]");
        assertThat(diff).contains("edgesAdded=[mcp:a->mcp:c]").contains("edgesRemoved=[mcp:a->mcp:b]");
    }

    // Helpers

    private Map<String, Object> planWith(List<Map<String, Object>> mcps, List<Map<String, Object>> edges) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", List.of(Map.of("type", "webhook", "label", "start")));
        plan.put("mcps", mcps);
        plan.put("agents", List.of());
        plan.put("cores", List.of());
        plan.put("tables", List.of());
        plan.put("interfaces", List.of());
        plan.put("edges", edges);
        return plan;
    }

    private Map<String, Object> mcp(String label, Map<String, Object> params) {
        Map<String, Object> step = new HashMap<>();
        step.put("label", label);
        step.put("service", "http");
        step.put("action", "get");
        step.put("params", new HashMap<>(params));
        return step;
    }

    private Map<String, Object> edge(String from, String to) {
        return Map.of("from", from, "to", to);
    }
}
