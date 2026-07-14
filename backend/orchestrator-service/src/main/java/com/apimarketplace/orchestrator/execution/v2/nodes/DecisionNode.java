package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.EvalContextBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decision node - Conditional branching (per item, no cross-item mixing).
 *
 * Flow:
 * 1. Evaluate conditions in order (if, elsif, elsif..., else)
 * 2. Return nodes for the FIRST matching branch
 * 3. Other branches are marked as skipped in the result
 *
 * Output contains:
 * - selected_branch: the branch type that matched
 * - skipped_branches: list of branch types that were skipped
 * - evaluations: detailed evaluation results for debugging
 */
public class DecisionNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(DecisionNode.class);

    private final List<ConditionalBranch> branches;
    private final TemplateEngine templateEngine;

    // NOTE: We do NOT use instance-level caching for per-item decisions.
    // Each item's decision is stored in the NodeExecutionResult.output() and
    // getNextNodes() extracts the selected branch index from there.
    // This avoids race conditions when multiple items execute in parallel.

    public DecisionNode(
            String nodeId,
            List<ConditionalBranch> branches,
            TemplateEngine templateEngine) {
        super(nodeId, NodeType.DECISION);
        this.branches = branches != null ? branches : new ArrayList<>();
        this.templateEngine = templateEngine;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.debug("Decision node executing: nodeId={}, branches={}, itemId={}",
            nodeId, branches.size(), context.itemId());

        // Build evaluation context from ExecutionContext
        Map<String, Object> evalContext = EvalContextBuilder.buildStandardEvalContext(context);

        // Evaluate all branches and determine which one is selected
        DecisionEvaluation evaluation = evaluateBranches(evalContext);
        // NOTE: Do NOT cache evaluation in instance field - race condition with parallel items!
        // Instead, store result in output for getNextNodes() to extract.

        // Build resolved_params snapshot for inspector visibility (resolved values)
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        for (int i = 0; i < branches.size(); i++) {
            ConditionalBranch branch = branches.get(i);
            String key = branch.type() + (i > 0 && "elsif".equals(branch.type()) ? "_" + (i - 1) : "");
            String condition = branch.condition() != null ? branch.condition() : "";
            resolvedParams.put(key, resolveTemplateString(condition, context));
        }
        resolvedParams.put("branches", branches.size());

        // Build output with evaluation details
        Map<String, Object> output = new HashMap<>();
        output.put("resolved_params", resolvedParams);
        output.put("node_type", "DECISION");
        output.put("decision_node", nodeId);
        output.put("branches_evaluated", branches.size());
        output.put("selected_branch", evaluation.selectedBranchType);
        output.put("selected_branch_index", evaluation.selectedBranchIndex);
        output.put("skipped_branches", evaluation.skippedBranchTypes);
        output.put("evaluations", evaluation.evaluationDetails);

        // Add condition info for the selected branch (for persistence)
        if (evaluation.selectedBranch != null) {
            output.put("condition_expression", evaluation.selectedBranch.condition());
            output.put("condition_result", true); // Selected branch condition was true
        } else {
            output.put("condition_result", false); // No branch matched
        }

        // Add item context for persistence
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        logger.info("Decision evaluated: nodeId={}, selected={}, skipped={}",
            nodeId, evaluation.selectedBranchType, evaluation.skippedBranchTypes);

        return NodeExecutionResult.success(nodeId, output);
    }

    /**
     * Evaluate all branches and return evaluation result.
     */
    private DecisionEvaluation evaluateBranches(Map<String, Object> evalContext) {
        ConditionalBranch selectedBranch = null;
        int selectedIndex = -1;
        List<String> skippedTypes = new ArrayList<>();
        List<Map<String, Object>> evaluationDetails = new ArrayList<>();

        for (int i = 0; i < branches.size(); i++) {
            ConditionalBranch branch = branches.get(i);
            BranchEvaluationResult evalResult = branch.evaluateConditionWithDetails(evalContext, templateEngine, templateAdapter);

            logger.debug("Branch[{}] '{}': condition='{}' resolved='{}' → {}",
                i, branch.type(), branch.condition(), evalResult.resolvedExpression(), evalResult.result());

            // Record evaluation with resolved expression for UI display
            Map<String, Object> evalDetail = new HashMap<>();
            evalDetail.put("branch_type", branch.type());
            evalDetail.put("condition", branch.condition() != null ? branch.condition() : "");
            evalDetail.put("resolved_condition", evalResult.resolvedExpression());
            evalDetail.put("result", evalResult.result());
            evalDetail.put("index", i);
            if (evalResult.errorMessage() != null) {
                evalDetail.put("error", evalResult.errorMessage());
            }
            evaluationDetails.add(evalDetail);

            // First matching branch wins
            if (evalResult.result() && selectedBranch == null) {
                selectedBranch = branch;
                selectedIndex = i;
            } else {
                skippedTypes.add(branch.type());
            }
        }

        return new DecisionEvaluation(
            selectedBranch,
            selectedIndex,
            selectedBranch != null ? selectedBranch.type() : null,
            skippedTypes,
            evaluationDetails
        );
    }

    /**
     * Result of evaluating a branch condition.
     */
    public record BranchEvaluationResult(
        boolean result,
        String resolvedExpression,
        String errorMessage
    ) {}

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        Object indexObj = result.output().get("selected_branch_index");
        
        if (indexObj instanceof Integer selectedIndex && selectedIndex >= 0 && selectedIndex < branches.size()) {
            ConditionalBranch selectedBranch = branches.get(selectedIndex);
            logger.debug("Decision '{}' selected branch: {} (index={})", nodeId, selectedBranch.type(), selectedIndex);
            return selectedBranch.nodes();
        }

        logger.debug("Decision '{}': no branch matched", nodeId);
        return List.of();
    }

    /**
     * Get the branches that were skipped (for skip propagation).
     * Uses result output to determine which branch was selected (thread-safe).
     *
     * IMPORTANT: When NO branch is selected (selectedIndex = -1), ALL branches are skipped.
     * This ensures edges are emitted as SKIPPED for all branches when no condition matches.
     */
    @Override
    public List<ExecutionNode> getSkippedChildNodes(NodeExecutionResult result) {
        Object indexObj = result.output().get("selected_branch_index");

        // If no selected_branch_index in output, skip ALL branches
        if (!(indexObj instanceof Integer)) {
            return getAllBranchNodes();
        }

        int selectedIndex = (Integer) indexObj;

        // If selectedIndex is -1 (no match), ALL branches are skipped
        if (selectedIndex < 0) {
            return getAllBranchNodes();
        }

        // Invalid index - skip all branches as fallback
        if (selectedIndex >= branches.size()) {
            return getAllBranchNodes();
        }

        // Normal case: skip all branches except the selected one
        List<ExecutionNode> skippedNodes = new ArrayList<>();
        for (int i = 0; i < branches.size(); i++) {
            if (i != selectedIndex) {
                skippedNodes.addAll(branches.get(i).nodes());
            }
        }
        return skippedNodes;
    }

    /**
     * Get ALL branch nodes (used when no branch is selected - all are skipped).
     */
    public List<ExecutionNode> getAllBranchNodes() {
        List<ExecutionNode> allNodes = new ArrayList<>();
        for (ConditionalBranch branch : branches) {
            allNodes.addAll(branch.nodes());
        }
        return allNodes;
    }

    /**
     * Returns all child nodes for tree traversal.
     * For DecisionNode, this includes all branch nodes (if, elseif, else).
     */
    @Override
    public List<ExecutionNode> getAllChildNodes() {
        return getAllBranchNodes();
    }

    /**
     * DecisionNode is a branching node - it selects one branch based on conditions.
     */
    @Override
    public boolean isBranchingNode() {
        return true;
    }

    /**
     * Returns all branch targets mapped by port for port-qualified edge emission.
     * Maps: "if" -> [nodes], "elseif_0" -> [nodes], "else" -> [nodes]
     */
    @Override
    public Map<String, List<ExecutionNode>> getBranchTargetsByPort() {
        Map<String, List<ExecutionNode>> result = new HashMap<>();
        int elsifCounter = 0;
        for (ConditionalBranch branch : branches) {
            String port = switch (branch.type()) {
                case "if" -> "if";
                case "else" -> "else";
                case "elsif" -> {
                    String p = "elseif_" + elsifCounter;
                    elsifCounter++;
                    yield p;
                }
                default -> branch.type();
            };
            result.put(port, new ArrayList<>(branch.nodes()));
        }
        return result;
    }

    /**
     * Returns the selected port based on execution result.
     * Maps selected_branch_index to port name: "if", "elseif_0", "else"
     */
    @Override
    public String getSelectedPort(NodeExecutionResult result) {
        if (result == null || result.output() == null) {
            return null;
        }
        Object indexObj = result.output().get("selected_branch_index");
        if (!(indexObj instanceof Integer selectedIndex) || selectedIndex < 0 || selectedIndex >= branches.size()) {
            return null;
        }
        return getPortForBranchIndex(selectedIndex);
    }

    /**
     * Inverse of {@link #getSelectedPort} for the mock mode: maps a port name
     * ("if" / "elseif_N" / "else") back to {@code {selected_branch_index: N}} so a
     * mocked decision routes through the exact same output contract as a real one.
     * Unknown port (should not happen - validated at plan parse time) falls back to
     * the default {@code selected_port} form, which this node ignores (no branch
     * selected).
     */
    @Override
    public Map<String, Object> portSelectionOutput(String port) {
        int elsifCounter = 0;
        for (int i = 0; i < branches.size(); i++) {
            ConditionalBranch branch = branches.get(i);
            String candidate = switch (branch.type()) {
                case "if" -> "if";
                case "else" -> "else";
                case "elsif" -> "elseif_" + elsifCounter;
                default -> branch.type();
            };
            if ("elsif".equals(branch.type())) {
                elsifCounter++;
            }
            if (candidate.equals(port)) {
                Map<String, Object> out = new HashMap<>();
                out.put("selected_branch_index", i);
                return out;
            }
        }
        return super.portSelectionOutput(port);
    }

    /**
     * Maps a branch index to its port name.
     */
    private String getPortForBranchIndex(int targetIndex) {
        int elsifCounter = 0;
        for (int i = 0; i < branches.size(); i++) {
            ConditionalBranch branch = branches.get(i);
            if (i == targetIndex) {
                return switch (branch.type()) {
                    case "if" -> "if";
                    case "else" -> "else";
                    case "elsif" -> "elseif_" + elsifCounter;
                    default -> branch.type();
                };
            }
            if ("elsif".equals(branch.type())) {
                elsifCounter++;
            }
        }
        return null;
    }

    /**
     * DecisionNode is a decision node.
     */
    @Override
    public boolean isDecisionNode() {
        return true;
    }

    /**
     * DecisionNode skips split handling - it manages its own control flow.
     */
    @Override
    public boolean skipsSplitHandling() {
        return true;
    }

    /**
     * Add a target node to a specific branch (by index).
     * Used for late binding when wiring V2 edges after all nodes are created.
     */
    public void addTargetToBranch(int branchIndex, ExecutionNode target) {
        if (branchIndex >= 0 && branchIndex < branches.size()) {
            branches.get(branchIndex).addNode(target);
            logger.debug("Added target {} to branch {}", target.getNodeId(), branchIndex);
        } else {
            logger.warn("Invalid branch index {} for decision {}", branchIndex, nodeId);
        }
    }

    /**
     * Polymorphic branch wiring - delegates to addTargetToBranch.
     */
    @Override
    public void addBranchTarget(int branchIndex, ExecutionNode target) {
        addTargetToBranch(branchIndex, target);
    }

    /**
     * Internal class to hold evaluation result.
     */
    private static class DecisionEvaluation {
        final ConditionalBranch selectedBranch;
        final int selectedBranchIndex;
        final String selectedBranchType;
        final List<String> skippedBranchTypes;
        final List<Map<String, Object>> evaluationDetails;

        DecisionEvaluation(ConditionalBranch selectedBranch, int selectedBranchIndex,
                          String selectedBranchType, List<String> skippedBranchTypes,
                          List<Map<String, Object>> evaluationDetails) {
            this.selectedBranch = selectedBranch;
            this.selectedBranchIndex = selectedBranchIndex;
            this.selectedBranchType = selectedBranchType;
            this.skippedBranchTypes = skippedBranchTypes;
            this.evaluationDetails = evaluationDetails;
        }
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        logger.debug("Decision node completed: nodeId={}", nodeId);
        // Event emission will be added later
    }

    /**
     * Conditional branch (if, elsif, else).
     */
    public static class ConditionalBranch {
        private final String type;  // "if", "elsif", "else"
        private final String condition;  // null for "else"
        private final List<ExecutionNode> nodes;

        public ConditionalBranch(String type, String condition, List<ExecutionNode> nodes) {
            this.type = type;
            this.condition = condition;
            this.nodes = nodes != null ? nodes : new ArrayList<>();
        }

        /**
         * Evaluate condition using TemplateEngine (simple boolean result).
         */
        public boolean evaluateCondition(
                Map<String, Object> context,
                TemplateEngine templateEngine,
                com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter templateAdapter) {

            return evaluateConditionWithDetails(context, templateEngine, templateAdapter).result();
        }

        /**
         * Evaluate condition with detailed result including resolved expression.
         * This shows how SpEL resolved the variables, e.g.:
         * - condition: "{{trigger:test.output.user_id%2==1}}"
         * - resolved: "5%2==1" (if user_id=5)
         */
        public BranchEvaluationResult evaluateConditionWithDetails(
                Map<String, Object> context,
                TemplateEngine templateEngine,
                com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter templateAdapter) {

            // "else" branch always matches
            if ("else".equals(type) || condition == null || condition.isBlank()) {
                return new BranchEvaluationResult(true, "else", null);
            }

            if (templateEngine == null) {
                logger.warn("No TemplateEngine available for condition: {}", condition);
                return new BranchEvaluationResult(false, condition, "No template engine available");
            }

            try {
                // Use evaluateConditionWithDetailsWithMap() to get both result and resolved expression
                var evalResult = templateEngine.evaluateConditionWithDetailsWithMap(condition, context);
                return new BranchEvaluationResult(
                    evalResult.result(),
                    evalResult.resolvedExpression(),
                    evalResult.errorMessage()
                );
            } catch (Exception e) {
                logger.error("Condition evaluation failed: condition={}, error={}",
                    condition, e.getMessage());
                return new BranchEvaluationResult(false, condition, e.getMessage());
            }
        }

        public String type() {
            return type;
        }

        public String condition() {
            return condition;
        }

        public List<ExecutionNode> nodes() {
            return nodes;
        }

        /**
         * Add a node to this branch (for late binding during V2 wiring).
         */
        public void addNode(ExecutionNode node) {
            if (node != null) {
                this.nodes.add(node);
            }
        }
    }

    public static class Builder {
        private String nodeId;
        private final List<ConditionalBranch> branches = new ArrayList<>();
        private TemplateEngine templateEngine;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder templateEngine(TemplateEngine templateEngine) {
            this.templateEngine = templateEngine;
            return this;
        }

        public Builder addBranch(String type, String condition, List<ExecutionNode> nodes) {
            branches.add(new ConditionalBranch(type, condition, nodes));
            return this;
        }

        public Builder ifBranch(String condition, List<ExecutionNode> nodes) {
            return addBranch("if", condition, nodes);
        }

        public Builder elsifBranch(String condition, List<ExecutionNode> nodes) {
            return addBranch("elsif", condition, nodes);
        }

        public Builder elseBranch(List<ExecutionNode> nodes) {
            return addBranch("else", null, nodes);
        }

        public DecisionNode build() {
            return new DecisionNode(nodeId, branches, templateEngine);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
