package com.apimarketplace.orchestrator.execution.v2.nodes;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression: prod log on 2026-05-13 emitted 56 WARN/day with
 * {@code Classify node agent:classify has no selected_category or no category
 * targets...} firing on the redisMessageListener thread BEFORE the agent had
 * executed in that slice - {@code ReadyNodeCalculator.processSuccessors} probes
 * {@code getNextNodes()} on a freshly-reconstructed snapshot whose output map is
 * empty, then the actual classify success path reruns ~1s later with a populated
 * output. Pre-fix: both paths WARN the same way. Post-fix: empty-output probe
 * logs at DEBUG, populated-output-with-missing-selected_category WARNs as a real
 * LLM contract failure.
 */
@DisplayName("AgentNode - classify reconstruction probe logs at DEBUG; real failure logs at WARN")
class AgentNodeClassifyReconstructionLogTest {

    private Logger nodeLogger;
    private Level previous;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        nodeLogger = (Logger) LoggerFactory.getLogger(AgentNode.class);
        previous = nodeLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        nodeLogger.addAppender(appender);
        nodeLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        nodeLogger.detachAppender(appender);
        nodeLogger.setLevel(previous);
    }

    private static Agent classifyAgent(List<String> labels) {
        List<Map<String, Object>> categories = new java.util.ArrayList<>();
        for (String label : labels) categories.add(Map.of("label", label));
        return new Agent(
            "agent-classify-recon",
            "classify",
            "Test Classifier",
            null, null, "openai", "gpt-4o", null,
            "Classify",
            0.7, 4096, 10, 5,
            List.of(), null, Map.of(),
            categories,
            null, List.of(), null, null
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

    private static AgentNode wiredClassifier() {
        Agent agent = classifyAgent(List.of("finance", "tech"));
        AgentNode node = new AgentNode("agent:classify", agent);
        node.addCategoryTarget("category_0", stubSuccessor("mcp:apply_finance"));
        node.addCategoryTarget("category_1", stubSuccessor("mcp:apply_tech"));
        return node;
    }

    @Test
    @DisplayName("Reconstruction probe (empty output) logs at DEBUG, NOT WARN - silences the 56/day prod noise")
    void emptyOutputProbeLogsAtDebug() {
        AgentNode node = wiredClassifier();
        NodeExecutionResult probe = NodeExecutionResult.success("agent:classify", new HashMap<>());

        List<ExecutionNode> next = node.getNextNodes(probe);

        assertThat(next).isEmpty();
        boolean warnFired = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("Classify node agent:classify"));
        assertThat(warnFired)
            .as("Empty-output probe (reconstruction-time speculative traversal) must NOT WARN")
            .isFalse();

        boolean debugFired = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.DEBUG
            && e.getFormattedMessage().contains("probed during reconstruction"));
        assertThat(debugFired)
            .as("Reconstruction probe must still log at DEBUG so ad-hoc tracing keeps working")
            .isTrue();
    }

    @Test
    @DisplayName("Populated output without selected_category WARNs as a real LLM contract failure - preserves the signal")
    void populatedOutputMissingCategoryWarns() {
        AgentNode node = wiredClassifier();
        Map<String, Object> output = new HashMap<>();
        output.put("status", "completed");
        output.put("model_response", "ambiguous");
        // selected_category intentionally absent - LLM returned no usable label.
        NodeExecutionResult realFailure = NodeExecutionResult.success("agent:classify", output);

        List<ExecutionNode> next = node.getNextNodes(realFailure);

        assertThat(next).isEmpty();
        boolean warnFired = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("produced no selected_category despite populated output"));
        assertThat(warnFired)
            .as("Real LLM-contract failure (output populated, no selected_category) MUST WARN to stay visible")
            .isTrue();
    }

    @Test
    @DisplayName("No category targets configured (builder misconfig) WARNs regardless of output state")
    void missingTargetsAlwaysWarns() {
        Agent agent = classifyAgent(List.of("finance"));
        AgentNode node = new AgentNode("agent:classify", agent);
        // intentionally no addCategoryTarget calls - builder forgot to wire branches
        NodeExecutionResult result = NodeExecutionResult.success("agent:classify",
            Map.of("selected_category", "finance"));

        List<ExecutionNode> next = node.getNextNodes(result);

        assertThat(next).isEmpty();
        boolean warnFired = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("has no category targets configured"));
        assertThat(warnFired)
            .as("Missing-targets misconfig is a builder bug - always WARN")
            .isTrue();
    }
}
