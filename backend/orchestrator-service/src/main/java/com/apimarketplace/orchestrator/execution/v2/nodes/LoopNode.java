package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.EvalContextBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Loop node - While loop with condition evaluation.
 *
 * Evaluates the loop condition on first entry:
 * - If condition is true (and maxIterations > 0): routes to body targets
 * - If condition is false (or maxIterations == 0): routes to exit targets
 *
 * Subsequent iterations are handled by BackEdgeHandler which:
 * - Resets the body subgraph and re-traverses from body entry
 * - Activates exit targets when the loop terminates
 *
 * Output contains:
 * - iteration: current iteration number (0 on first entry)
 * - maxIterations: configured max iterations
 * - terminated: whether the loop has terminated
 *
 * Edge Format:
 * - "core:loop_label:body" -> first body node
 * - "core:loop_label:exit" -> node after loop
 * - "mcp:last_body_step" -> "core:loop_label:iterate" (loop-back, handled by BackEdgeHandler)
 */
public class LoopNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(LoopNode.class);

    private final String loopCondition;
    private final int maxIterations;
    private final TemplateEngine templateEngine;
    private final List<ExecutionNode> bodyTargets;
    private final List<ExecutionNode> exitTargets;

    public LoopNode(String nodeId, String loopCondition, int maxIterations, TemplateEngine templateEngine) {
        super(nodeId, NodeType.LOOP);
        this.loopCondition = loopCondition;
        this.maxIterations = maxIterations;
        this.templateEngine = templateEngine;
        this.bodyTargets = new ArrayList<>();
        this.exitTargets = new ArrayList<>();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.debug("Loop node executing: nodeId={}, condition={}, maxIterations={}, itemId={}",
            nodeId, loopCondition, maxIterations, context.itemId());

        // Determine if we should enter the loop body
        boolean enterBody = false;

        if (maxIterations > 0) {
            // Evaluate condition
            enterBody = evaluateCondition(context);
        }

        // Build resolved_params snapshot for inspector visibility (resolved values)
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        String resolvedCondition = loopCondition != null ? resolveTemplateString(loopCondition, context) : "(none)";
        resolvedParams.put("condition", resolvedCondition);
        resolvedParams.put("maxIterations", maxIterations);

        // Build output
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("resolved_params", resolvedParams);
        output.put("node_type", "LOOP");
        output.put("loop_node", nodeId);
        output.put("iteration", 0);
        output.put("maxIterations", maxIterations);
        // If we don't enter the body, the loop is immediately terminated
        output.put("terminated", !enterBody);
        output.put("enter_body", enterBody);
        output.put("selected_path", enterBody ? "body" : "exit");

        logger.info("Loop node evaluated: nodeId={}, enterBody={}, maxIterations={}, condition={}",
            nodeId, enterBody, maxIterations, loopCondition);

        return NodeExecutionResult.success(nodeId, output);
    }

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        if (result == null || result.output() == null) {
            return exitTargets;
        }

        // On failure, stop the loop - don't route to any targets
        if (result.isFailure()) {
            return List.of();
        }

        // If the loop has been terminated (by BackEdgeHandler), always route to exit
        Object terminated = result.output().get("terminated");
        if (Boolean.TRUE.equals(terminated)) {
            logger.debug("Loop '{}' terminated, routing to exit targets: {}", nodeId, exitTargets.size());
            return new ArrayList<>(exitTargets);
        }

        Object enterBody = result.output().get("enter_body");
        if (Boolean.TRUE.equals(enterBody)) {
            logger.debug("Loop '{}' routing to body targets: {}", nodeId, bodyTargets.size());
            return new ArrayList<>(bodyTargets);
        } else {
            logger.debug("Loop '{}' routing to exit targets: {}", nodeId, exitTargets.size());
            return new ArrayList<>(exitTargets);
        }
    }

    @Override
    public List<ExecutionNode> getSkippedChildNodes(NodeExecutionResult result) {
        if (result == null || result.output() == null) {
            return new ArrayList<>(bodyTargets);
        }

        Object enterBody = result.output().get("enter_body");
        if (Boolean.TRUE.equals(enterBody)) {
            // Body is selected, exit is skipped initially (will be activated by BackEdgeHandler)
            // Don't mark exit as skipped since BackEdgeHandler will activate it later
            return List.of();
        } else {
            // Exit is selected, body is skipped
            return new ArrayList<>(bodyTargets);
        }
    }

    @Override
    public List<ExecutionNode> getAllChildNodes() {
        List<ExecutionNode> all = new ArrayList<>();
        all.addAll(bodyTargets);
        all.addAll(exitTargets);
        return all;
    }

    @Override
    public boolean isLoopNode() {
        return true;
    }

    @Override
    public boolean skipsSplitHandling() {
        return true;
    }

    @Override
    public void addLoopBodyTarget(ExecutionNode target) {
        bodyTargets.add(target);
    }

    @Override
    public void addLoopExitTarget(ExecutionNode target) {
        exitTargets.add(target);
    }

    public List<ExecutionNode> getBodyTargets() {
        return bodyTargets;
    }

    public List<ExecutionNode> getExitTargets() {
        return exitTargets;
    }

    public String getLoopCondition() {
        return loopCondition;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    private boolean evaluateCondition(ExecutionContext context) {
        // No condition or blank means always enter body (controlled by maxIterations only)
        if (loopCondition == null || loopCondition.isBlank()) {
            return true;
        }

        try {
            Map<String, Object> evalContext = EvalContextBuilder.buildStandardEvalContext(context);

            // Provide default loop output with iteration=0 so conditions referencing
            // the loop's own iteration (e.g., "{{core:my_loop.iteration}} < 3") work on first entry
            if (!evalContext.containsKey(nodeId)) {
                Map<String, Object> defaultOutput = new LinkedHashMap<>();
                defaultOutput.put("iteration", 0);
                defaultOutput.put("maxIterations", maxIterations);
                defaultOutput.put("output", Map.of("iteration", 0, "maxIterations", maxIterations));
                evalContext.put(nodeId, defaultOutput);
            }

            var evalResult = templateEngine.evaluateConditionWithDetailsWithMap(loopCondition, evalContext);
            logger.debug("Loop condition evaluated: nodeId={}, condition={}, result={}",
                nodeId, loopCondition, evalResult.result());
            return evalResult.result();
        } catch (Exception e) {
            logger.error("Loop condition evaluation failed: nodeId={}, condition={}, error={}",
                nodeId, loopCondition, e.getMessage());
            return false;
        }
    }

    // Builder
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String nodeId;
        private String loopCondition;
        private int maxIterations = 10;
        private TemplateEngine templateEngine;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder loopCondition(String loopCondition) {
            this.loopCondition = loopCondition;
            return this;
        }

        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        public Builder templateEngine(TemplateEngine templateEngine) {
            this.templateEngine = templateEngine;
            return this;
        }

        public LoopNode build() {
            return new LoopNode(nodeId, loopCondition, maxIterations, templateEngine);
        }
    }
}
