package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused tests for the classify routing hot-path:
 * {@link AgentNode#getNextNodes} → {@code getClassifyNextNodes} →
 * {@link AgentNode#findCategoryIndex}.
 *
 * <p>Why this class exists separately from {@code AgentNodeTest}:
 * {@code AgentNodeTest.java} is excluded by {@code pom.xml:281} due to unrelated
 * compile/runtime issues. Adding regression tests there would be dead code. This
 * class targets only the classify routing surface and stays compilable/runnable.</p>
 *
 * <p>Regression for the prod bug observed on run_<id>: an LLM
 * returning a category label with French accents ("Réseaux") or extra whitespace
 * ("  finance ") was dropped to {@code -1} by the previous {@code equalsIgnoreCase}
 * match → no port selected → item silently lost. Post-fix, both sides are run
 * through {@code LabelNormalizer.normalizeLabel} so the slug match succeeds.</p>
 */
@DisplayName("AgentNode - classify routing with LabelNormalizer-backed match")
class AgentNodeClassifyRoutingTest {

    private static Agent classifyAgent(List<String> categoryLabels) {
        List<Map<String, Object>> categories = new java.util.ArrayList<>();
        for (String label : categoryLabels) {
            categories.add(Map.of("label", label));
        }
        return new Agent(
            "agent-classify-x",
            "classify",
            "Test Classifier",
            null,
            null,
            "openai",
            "gpt-4o",
            null,
            "Classify the email",
            0.7,
            4096,
            10,
            5,
            List.of(),
            null,
            Map.of(),
            categories,
            null,
            List.of(),
            null,
            null
        );
    }

    private static ExecutionNode stubSuccessor(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }

    private static NodeExecutionResult classifyResult(String selectedCategory) {
        Map<String, Object> output = new HashMap<>();
        output.put("selected_category", selectedCategory);
        // node_type intentionally absent - simulates the post-mapper DB read
        // and forces routing through agentConfig.type() / getClassifyNextNodes
        // instead of the index-based getSelectedPort short-circuit.
        return NodeExecutionResult.success("agent:classifier", output);
    }

    @Test
    @DisplayName("getNextNodes: LLM returns accented label \"Réseaux\" → routes to category configured as \"reseaux\"")
    void getNextNodesRoutesAccentedLabelToNormalizedSlug() {
        Agent agent = classifyAgent(List.of("finance", "reseaux", "tech"));
        AgentNode node = new AgentNode("agent:classifier", agent);
        ExecutionNode finance = stubSuccessor("mcp:apply_finance");
        ExecutionNode reseaux = stubSuccessor("mcp:apply_reseaux");
        ExecutionNode tech = stubSuccessor("mcp:apply_tech");
        node.addCategoryTarget("category_0", finance);
        node.addCategoryTarget("category_1", reseaux);
        node.addCategoryTarget("category_2", tech);

        List<ExecutionNode> next = node.getNextNodes(classifyResult("Réseaux"));

        assertThat(next).extracting(ExecutionNode::getNodeId).containsExactly("mcp:apply_reseaux");
    }

    @Test
    @DisplayName("getNextNodes: LLM returns whitespace+case variant \"  Work Items \" → routes to \"work_items\"")
    void getNextNodesNormalizesWhitespaceAndCase() {
        Agent agent = classifyAgent(List.of("personal", "work_items"));
        AgentNode node = new AgentNode("agent:classifier", agent);
        ExecutionNode personal = stubSuccessor("mcp:personal");
        ExecutionNode workItems = stubSuccessor("mcp:work_items");
        node.addCategoryTarget("category_0", personal);
        node.addCategoryTarget("category_1", workItems);

        List<ExecutionNode> next = node.getNextNodes(classifyResult("  Work Items "));

        assertThat(next).extracting(ExecutionNode::getNodeId).containsExactly("mcp:work_items");
    }

    @Test
    @DisplayName("getNextNodes: LLM invents a label outside the configured list → returns empty (no branch followed)")
    void getNextNodesUnmatchedLabelReturnsEmpty() {
        Agent agent = classifyAgent(List.of("finance", "tech"));
        AgentNode node = new AgentNode("agent:classifier", agent);
        node.addCategoryTarget("category_0", stubSuccessor("mcp:apply_finance"));
        node.addCategoryTarget("category_1", stubSuccessor("mcp:apply_tech"));

        List<ExecutionNode> next = node.getNextNodes(classifyResult("other"));

        assertThat(next).isEmpty();
    }

    @Test
    @DisplayName("getNextNodes: classify without category ports follows linear successors")
    void getNextNodesClassifyWithoutCategoryPortsFollowsLinearSuccessors() {
        Agent agent = classifyAgent(List.of("finance", "tech"));
        AgentNode node = new AgentNode("agent:classifier", agent);
        node.addSuccessor(stubSuccessor("mcp:after_classify"));

        List<ExecutionNode> next = node.getNextNodes(classifyResult("finance"));

        assertThat(next).extracting(ExecutionNode::getNodeId).containsExactly("mcp:after_classify");
    }

    @Test
    @DisplayName("getSkippedChildNodes: when LLM picks \"Réseaux\", finance + tech targets are returned as skipped (NOT reseaux)")
    void getSkippedChildNodesExcludesNormalizedMatch() {
        Agent agent = classifyAgent(List.of("finance", "reseaux", "tech"));
        AgentNode node = new AgentNode("agent:classifier", agent);
        ExecutionNode finance = stubSuccessor("mcp:apply_finance");
        ExecutionNode reseaux = stubSuccessor("mcp:apply_reseaux");
        ExecutionNode tech = stubSuccessor("mcp:apply_tech");
        node.addCategoryTarget("category_0", finance);
        node.addCategoryTarget("category_1", reseaux);
        node.addCategoryTarget("category_2", tech);

        List<ExecutionNode> skipped = node.getSkippedChildNodes(classifyResult("Réseaux"));

        assertThat(skipped)
            .extracting(ExecutionNode::getNodeId)
            .containsExactlyInAnyOrder("mcp:apply_finance", "mcp:apply_tech");
    }

    @Test
    @DisplayName("getSkippedChildNodes: when LLM label matches no category, ALL category targets are skipped")
    void getSkippedChildNodesUnmatchedSkipsAll() {
        Agent agent = classifyAgent(List.of("finance", "tech"));
        AgentNode node = new AgentNode("agent:classifier", agent);
        ExecutionNode finance = stubSuccessor("mcp:apply_finance");
        ExecutionNode tech = stubSuccessor("mcp:apply_tech");
        node.addCategoryTarget("category_0", finance);
        node.addCategoryTarget("category_1", tech);

        List<ExecutionNode> skipped = node.getSkippedChildNodes(classifyResult("other"));

        assertThat(skipped)
            .extracting(ExecutionNode::getNodeId)
            .containsExactlyInAnyOrder("mcp:apply_finance", "mcp:apply_tech");
    }
}
