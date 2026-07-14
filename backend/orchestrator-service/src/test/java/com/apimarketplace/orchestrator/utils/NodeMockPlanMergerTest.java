package com.apimarketplace.orchestrator.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NodeMockPlanMerger} - the mirror that copies in-run mock
 * edits from a run's plan into the workflow's plan (their durable home). Params
 * and other node content must stay run-scoped: ONLY the {@code mock} key moves.
 */
@DisplayName("NodeMockPlanMerger - mirror run-plan mocks into the workflow plan")
class NodeMockPlanMergerTest {

    private static final Map<String, Object> STATIC_MOCK =
            Map.of("output", Map.of("score", 87));
    private static final Map<String, Object> DISABLED_MOCK =
            Map.of("output", Map.of("score", 87), "enabled", false);

    @Test
    @DisplayName("copies a new mock from the run plan onto the matching workflow node")
    void copiesNewMock() {
        Map<String, Object> runPlan = plan(mcps(node("Fetch", Map.of("url", "run-edit"), STATIC_MOCK)));
        Map<String, Object> wfPlan = plan(mcps(node("Fetch", Map.of("url", "original"), null)));

        Map<String, Object> merged = NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan);

        assertThat(merged).isNotNull();
        Map<String, Object> mergedNode = firstMcp(merged);
        assertThat(mergedNode.get("mock")).isEqualTo(STATIC_MOCK);
        // Params are run-scoped: the workflow keeps ITS value, not the run edit.
        assertThat(((Map<?, ?>) mergedNode.get("params")).get("url")).isEqualTo("original");
    }

    @Test
    @DisplayName("updates a differing mock (e.g. enabled flipped to false)")
    void updatesDifferingMock() {
        Map<String, Object> runPlan = plan(mcps(node("Fetch", Map.of(), DISABLED_MOCK)));
        Map<String, Object> wfPlan = plan(mcps(node("Fetch", Map.of(), STATIC_MOCK)));

        Map<String, Object> merged = NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan);

        assertThat(merged).isNotNull();
        assertThat(firstMcp(merged).get("mock")).isEqualTo(DISABLED_MOCK);
    }

    @Test
    @DisplayName("an EMPTY mock map ({} = documented cleared form) removes the workflow node's mock")
    void emptyMockClears() {
        Map<String, Object> runPlan = plan(mcps(node("Fetch", Map.of(), Map.of())));
        Map<String, Object> wfPlan = plan(mcps(node("Fetch", Map.of(), STATIC_MOCK)));

        Map<String, Object> merged = NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan);

        assertThat(merged).isNotNull();
        assertThat(firstMcp(merged)).doesNotContainKey("mock");
    }

    @Test
    @DisplayName("ABSENT mock key is not a removal: a newer workflow mock survives a stale run payload")
    void absentMockLeavesWorkflowMock() {
        Map<String, Object> runPlan = plan(mcps(node("Fetch", Map.of(), null)));
        Map<String, Object> wfPlan = plan(mcps(node("Fetch", Map.of(), STATIC_MOCK)));

        assertThat(NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan)).isNull();
    }

    @Test
    @DisplayName("returns null when nothing changes (identical mock on both sides)")
    void identicalMockIsNoop() {
        Map<String, Object> runPlan = plan(mcps(node("Fetch", Map.of(), STATIC_MOCK)));
        Map<String, Object> wfPlan = plan(mcps(node("Fetch", Map.of(), new HashMap<>(STATIC_MOCK))));

        assertThat(NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan)).isNull();
    }

    @Test
    @DisplayName("no matching label in the workflow plan (structural drift): nothing to merge")
    void unmatchedNodeIsSkipped() {
        Map<String, Object> runPlan = plan(mcps(node("Removed Node", Map.of(), STATIC_MOCK)));
        Map<String, Object> wfPlan = plan(mcps(node("Fetch", Map.of(), null)));

        assertThat(NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan)).isNull();
    }

    @Test
    @DisplayName("duplicated labels within a section are skipped (ambiguous identity)")
    void duplicateLabelsAreSkipped() {
        Map<String, Object> runPlan = plan(mcps(
                node("Fetch", Map.of(), STATIC_MOCK),
                node("Fetch", Map.of(), STATIC_MOCK)));
        Map<String, Object> wfPlan = plan(mcps(
                node("Fetch", Map.of(), null),
                node("Fetch", Map.of(), null)));

        assertThat(NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan)).isNull();
    }

    @Test
    @DisplayName("merges across sections (cores) and never mutates the inputs")
    void mergesCoresAndKeepsInputsUntouched() {
        Map<String, Object> runCore = node("Check Score", Map.of(), Map.of("port", "if"));
        Map<String, Object> wfCore = node("Check Score", Map.of(), null);
        Map<String, Object> runPlan = plan(Map.of("cores", listOf(runCore)));
        Map<String, Object> wfPlan = plan(Map.of("cores", listOf(wfCore)));

        Map<String, Object> merged = NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan);

        assertThat(merged).isNotNull();
        Map<?, ?> mergedCore = (Map<?, ?>) ((List<?>) merged.get("cores")).get(0);
        assertThat(mergedCore.get("mock")).isEqualTo(Map.of("port", "if"));
        // Inputs untouched: the ORIGINAL workflow plan still has no mock.
        assertThat(wfCore).doesNotContainKey("mock");
        // Deep copy: mutating the merged mock must not reach the run plan's block.
        @SuppressWarnings("unchecked")
        Map<String, Object> mergedMock = (Map<String, Object>) mergedCore.get("mock");
        mergedMock.put("port", "else");
        assertThat(runCore.get("mock")).isEqualTo(Map.of("port", "if"));
    }

    @Test
    @DisplayName("alias-only entries (legacy mcp identity, parser's firstNonBlank(label, alias)) still mirror")
    void aliasOnlyEntriesMirror() {
        Map<String, Object> runNode = new HashMap<>();
        runNode.put("alias", "fetch_data");
        runNode.put("mock", new HashMap<>(STATIC_MOCK));
        Map<String, Object> wfNode = new HashMap<>();
        wfNode.put("alias", "fetch_data");
        Map<String, Object> runPlan = plan(Map.of("mcps", listOf(runNode)));
        Map<String, Object> wfPlan = plan(Map.of("mcps", listOf(wfNode)));

        Map<String, Object> merged = NodeMockPlanMerger.mergedWorkflowPlanOrNull(runPlan, wfPlan);

        assertThat(merged).isNotNull();
        assertThat(firstMcp(merged).get("mock")).isEqualTo(STATIC_MOCK);
    }

    @Test
    @DisplayName("null inputs are a no-op")
    void nullInputsAreNoop() {
        assertThat(NodeMockPlanMerger.mergedWorkflowPlanOrNull(null, plan(Map.of()))).isNull();
        assertThat(NodeMockPlanMerger.mergedWorkflowPlanOrNull(plan(Map.of()), null)).isNull();
    }

    // Helpers

    private static Map<String, Object> plan(Map<String, Object> sections) {
        Map<String, Object> plan = new HashMap<>();
        plan.put("triggers", listOf(node("start", Map.of(), null)));
        plan.put("mcps", new ArrayList<>());
        plan.put("cores", new ArrayList<>());
        plan.put("agents", new ArrayList<>());
        plan.put("tables", new ArrayList<>());
        plan.put("interfaces", new ArrayList<>());
        plan.put("edges", new ArrayList<>());
        plan.putAll(sections);
        return plan;
    }

    @SafeVarargs
    private static Map<String, Object> mcps(Map<String, Object>... nodes) {
        return Map.of("mcps", listOf(nodes));
    }

    @SafeVarargs
    private static List<Map<String, Object>> listOf(Map<String, Object>... nodes) {
        return new ArrayList<>(List.of(nodes));
    }

    private static Map<String, Object> node(String label, Map<String, Object> params, Map<String, Object> mock) {
        Map<String, Object> node = new HashMap<>();
        node.put("label", label);
        node.put("params", new HashMap<>(params));
        if (mock != null) node.put("mock", new HashMap<>(mock));
        return node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstMcp(Map<String, Object> plan) {
        return (Map<String, Object>) ((List<?>) plan.get("mcps")).get(0);
    }
}
