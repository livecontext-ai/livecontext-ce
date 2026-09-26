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

    /**
     * Termination reason meaning the iteration cap fired while the loop still wanted to run.
     * Mirrors {@code BackEdgeHandler.BACK_EDGE_OVERFLOW_REASON}, which produces it; kept as a
     * literal here because the node package must not depend on the engine package.
     */
    private static final String OVERFLOW_REASON = "max_iterations_reached";

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
        // The configured cap, or its {{...}} template resolved for this run (the parser sets a
        // template aside, and the builder then gave the loop the default 10 without a word).
        int maxIterations = effectiveMaxIterations(context);
        logger.debug("Loop node executing: nodeId={}, condition={}, maxIterations={}, itemId={}",
            nodeId, loopCondition, maxIterations, context.itemId());

        // Determine if we should enter the loop body. The detailed result is KEPT:
        // it was computed and discarded, so a loop reported nothing at all about the
        // condition that decided its path - not the expression, not what it resolved
        // to, not the answer. "Why did my loop exit immediately" had no evidence.
        ConditionOutcome outcome = maxIterations > 0
            ? evaluateConditionDetailed(context, maxIterations)
            : ConditionOutcome.notEvaluated("maxIterations is " + maxIterations);
        boolean enterBody = outcome.result();

        String port = enterBody ? "body" : "exit";
        List<Map<String, Object>> evaluations = List.of(hasCondition()
            ? BranchEvaluationReport.evaluated(0, port, loopCondition, outcome.resolved(),
                outcome.result(), true, outcome.error(), outcome.unresolved())
            : BranchEvaluationReport.fallback(0, port, true));

        // Params come from that same evaluation, never from a second resolution pass.
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        // "(no condition)", the same words the evaluation entry uses. Params saying
        // "(none)" while Output said "(no condition)" is a smaller version of exactly the
        // two-panel disagreement this work removes.
        resolvedParams.put("loopCondition", hasCondition() ? outcome.resolved() : "(no condition)");
        String capTemplate = deferredScalar("loop", "maxIterations");
        resolvedParams.put("maxIterations", capTemplate != null
            ? com.apimarketplace.orchestrator.services.template.ReportedParams.valueFrom(capTemplate, maxIterations)
            : maxIterations);

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
        output.put("selected_path", port);
        // Read by StepDataPersistenceService and the inspector's Condition / Resolved /
        // Result columns. Nothing wrote them, so those columns were empty on every loop.
        output.put("loop_condition", loopCondition);
        output.put("max_iterations", maxIterations);
        output.put("condition_expression", loopCondition);
        output.put("condition_resolved", hasCondition() ? outcome.resolved() : "(no condition)");
        output.put("condition_result", enterBody);
        output.put("evaluations", evaluations);

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

        // If the loop has been terminated (by BackEdgeHandler), route to exit - UNLESS it ran out
        // of iterations while it still wanted to continue. That is a failure, not an exit: the
        // loop never reached its own stopping condition, so the work after it would run on
        // unfinished data. Step-by-step rebuilds routing from the persisted row and would
        // otherwise re-arm the exit that the automatic path deliberately refuses to take.
        Object terminated = result.output().get("terminated");
        if (Boolean.TRUE.equals(terminated)) {
            if (OVERFLOW_REASON.equals(result.output().get("reason"))) {
                logger.info("Loop '{}' hit its iteration limit while still running - not routing to exit", nodeId);
                return List.of();
            }
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

    /** Whether this loop has anything to evaluate, or is a plain counted loop. */
    private boolean hasCondition() {
        return loopCondition != null && !loopCondition.isBlank();
    }

    /**
     * What the loop condition evaluated to, and what it resolved to on the way.
     *
     * @param result whether the body is entered
     * @param resolved the expression with its references substituted
     * @param error evaluation error, null when there was none
     * @param unresolved references that pointed at nothing
     */
    private record ConditionOutcome(boolean result,
                                    String resolved,
                                    String error,
                                    List<TemplateEngine.UnresolvedReference> unresolved) {

        static ConditionOutcome notEvaluated(String why) {
            return new ConditionOutcome(false, "(not evaluated: " + why + ")", null, List.of());
        }
    }

    /**
     * The iteration cap this execution uses: the configured one, or the plan's {@code {{...}}}
     * resolved against the run, which must be a positive whole number or the node fails.
     */
    private int effectiveMaxIterations(ExecutionContext context) {
        String template = deferredScalar("loop", "maxIterations");
        if (template == null) {
            return maxIterations;
        }
        long cap = templateEngine != null
            ? wholeNumber(template, templateEngine.evaluateTemplateWithMap(
                template, EvalContextBuilder.buildStandardEvalContext(context)))
            : resolveDeferredLong("loop", "maxIterations", template, context);
        if (cap <= 0 || cap > Integer.MAX_VALUE) {
            throw new IllegalStateException("loop.maxIterations '" + template + "' resolved to " + cap
                + ": it must be a positive whole number");
        }
        return (int) cap;
    }

    private static long wholeNumber(String template, Object value) {
        if (value instanceof String text) {
            value = text.trim();
        }
        if (value == null || (value instanceof String text && text.isEmpty())) {
            throw new IllegalStateException("loop.maxIterations '" + template
                + "' resolved to nothing. Check that the referenced node ran and that the path exists.");
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException("loop.maxIterations '" + template
                + "' must resolve to a whole number, got '" + value + "'");
        }
    }

    private ConditionOutcome evaluateConditionDetailed(ExecutionContext context, int maxIterations) {
        // No condition or blank means always enter body (controlled by maxIterations only)
        if (!hasCondition()) {
            return new ConditionOutcome(true, "(no condition)", null, List.of());
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
            logger.debug("Loop condition evaluated: nodeId={}, condition={}, resolved={}, result={}",
                nodeId, loopCondition, evalResult.resolvedExpression(), evalResult.result());
            return new ConditionOutcome(evalResult.result(), evalResult.resolvedExpression(),
                evalResult.errorMessage(), evalResult.unresolvedReferences());
        } catch (Exception e) {
            logger.error("Loop condition evaluation failed: nodeId={}, condition={}, error={}",
                nodeId, loopCondition, e.getMessage());
            return new ConditionOutcome(false, loopCondition, e.getMessage(), List.of());
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
