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
 * Option node - Multiple choice branching with expression evaluation.
 *
 * Flow:
 * 1. Evaluate expression for each choice in order
 * 2. Return nodes for the FIRST matching choice
 * 3. Other choices are marked as skipped in the result
 *
 * Similar to DecisionNode but with N choices instead of if/elseif/else structure.
 * Each choice has a label and an expression to evaluate.
 *
 * Output contains:
 * - selected_choice: the ID of the choice that matched
 * - selected_label: the label of the choice that matched
 * - skipped_choices: list of choice IDs that were skipped
 * - evaluations: detailed evaluation results for debugging
 */
public class OptionNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(OptionNode.class);

    private final List<OptionBranch> choices;
    private final TemplateEngine templateEngine;

    public OptionNode(
            String nodeId,
            List<OptionBranch> choices,
            TemplateEngine templateEngine) {
        super(nodeId, NodeType.OPTION);
        this.choices = choices != null ? choices : new ArrayList<>();
        this.templateEngine = templateEngine;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.debug("Option node executing: nodeId={}, choices={}, itemId={}",
            nodeId, choices.size(), context.itemId());

        // Build evaluation context from ExecutionContext
        Map<String, Object> evalContext = EvalContextBuilder.buildStandardEvalContext(context);

        // Evaluate all choices and determine which one is selected
        OptionEvaluation evaluation = evaluateChoices(evalContext);

        // Build resolved_params snapshot for inspector visibility (resolved values)
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        for (int i = 0; i < choices.size(); i++) {
            OptionBranch choice = choices.get(i);
            String label = choice.label() != null ? choice.label() : "choice_" + i;
            String expr = choice.expression() != null ? choice.expression() : "(no expression)";
            resolvedParams.put(label, resolveTemplateString(expr, context));
        }
        resolvedParams.put("choices", choices.size());

        // Compute selected_branches and skipped_branches (the final persisted shape)
        String selectedLabel = evaluation.selectedChoiceLabel;
        List<String> selectedBranches = selectedLabel != null ? List.of(selectedLabel) : List.of();
        List<String> skippedBranches = evaluation.skippedChoiceLabels;

        // Restructure evaluations: strip internal 'index' field (not part of persisted schema)
        List<Map<String, Object>> persistedEvaluations = new ArrayList<>();
        for (Map<String, Object> evalDetail : evaluation.evaluationDetails) {
            Map<String, Object> e = new HashMap<>();
            e.put("choice_id", evalDetail.get("choice_id"));
            e.put("choice_label", evalDetail.get("choice_label"));
            e.put("expression", evalDetail.get("expression"));
            e.put("resolved_expression", evalDetail.get("resolved_expression"));
            e.put("result", evalDetail.get("result"));
            if (evalDetail.containsKey("error")) {
                e.put("error", evalDetail.get("error"));
            }
            persistedEvaluations.add(e);
        }

        // Build output with evaluation details
        Map<String, Object> output = new HashMap<>();
        output.put("resolved_params", resolvedParams);
        output.put("node_type", "OPTION");
        output.put("option_node", nodeId);
        output.put("choices_evaluated", choices.size());
        output.put("selected_choice", evaluation.selectedChoiceId);
        output.put("selected_label", evaluation.selectedChoiceLabel);
        output.put("selected_choice_index", evaluation.selectedChoiceIndex);
        output.put("selected_branches", selectedBranches);
        output.put("skipped_branches", skippedBranches);
        output.put("evaluations", persistedEvaluations);

        // Add item context for persistence
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        logger.info("Option evaluated: nodeId={}, selected={}, skipped={}",
            nodeId, evaluation.selectedChoiceLabel, evaluation.skippedChoiceIds);

        return NodeExecutionResult.success(nodeId, output);
    }

    /**
     * Evaluate all choices and return evaluation result.
     */
    private OptionEvaluation evaluateChoices(Map<String, Object> evalContext) {
        OptionBranch selectedChoice = null;
        int selectedIndex = -1;
        List<String> skippedIds = new ArrayList<>();
        List<String> skippedLabels = new ArrayList<>();
        List<Map<String, Object>> evaluationDetails = new ArrayList<>();

        for (int i = 0; i < choices.size(); i++) {
            OptionBranch choice = choices.get(i);
            ChoiceEvaluationResult evalResult = choice.evaluateExpressionWithDetails(evalContext, templateEngine, templateAdapter);

            logger.debug("Choice[{}] '{}': expression='{}' resolved='{}' → {}",
                i, choice.label(), choice.expression(), evalResult.resolvedExpression(), evalResult.result());

            // Record evaluation with resolved expression for UI display
            Map<String, Object> evalDetail = new HashMap<>();
            evalDetail.put("choice_id", choice.id());
            evalDetail.put("choice_label", choice.label());
            evalDetail.put("expression", choice.expression() != null ? choice.expression() : "");
            evalDetail.put("resolved_expression", evalResult.resolvedExpression());
            evalDetail.put("result", evalResult.result());
            evalDetail.put("index", i);
            if (evalResult.errorMessage() != null) {
                evalDetail.put("error", evalResult.errorMessage());
            }
            evaluationDetails.add(evalDetail);

            // First matching choice wins
            if (evalResult.result() && selectedChoice == null) {
                selectedChoice = choice;
                selectedIndex = i;
            } else if (selectedChoice == null) {
                // Only add to skipped if not yet selected
                skippedIds.add(choice.id());
                skippedLabels.add(choice.label() != null ? choice.label() : choice.id());
            } else {
                // Already selected, this one is skipped
                skippedIds.add(choice.id());
                skippedLabels.add(choice.label() != null ? choice.label() : choice.id());
            }
        }

        return new OptionEvaluation(
            selectedChoice,
            selectedIndex,
            selectedChoice != null ? selectedChoice.id() : null,
            selectedChoice != null ? selectedChoice.label() : null,
            skippedIds,
            skippedLabels,
            evaluationDetails
        );
    }

    /**
     * Result of evaluating a choice expression.
     */
    public record ChoiceEvaluationResult(
        boolean result,
        String resolvedExpression,
        String errorMessage
    ) {}

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        Object indexObj = result.output().get("selected_choice_index");

        if (indexObj instanceof Integer selectedIndex && selectedIndex >= 0 && selectedIndex < choices.size()) {
            OptionBranch selectedChoice = choices.get(selectedIndex);
            logger.debug("Option '{}' selected choice: {} (index={})", nodeId, selectedChoice.label(), selectedIndex);
            return selectedChoice.nodes();
        }

        logger.debug("Option '{}': no choice matched", nodeId);
        return List.of();
    }

    /**
     * Get the choices that were skipped (for skip propagation).
     */
    @Override
    public List<ExecutionNode> getSkippedChildNodes(NodeExecutionResult result) {
        Object indexObj = result.output().get("selected_choice_index");

        if (!(indexObj instanceof Integer)) {
            return getAllChoiceNodes();
        }

        int selectedIndex = (Integer) indexObj;

        if (selectedIndex < 0 || selectedIndex >= choices.size()) {
            return getAllChoiceNodes();
        }

        List<ExecutionNode> skippedNodes = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            if (i != selectedIndex) {
                skippedNodes.addAll(choices.get(i).nodes());
            }
        }
        return skippedNodes;
    }

    /**
     * Get ALL choice nodes (used when no choice is selected - all are skipped).
     */
    public List<ExecutionNode> getAllChoiceNodes() {
        List<ExecutionNode> allNodes = new ArrayList<>();
        for (OptionBranch choice : choices) {
            allNodes.addAll(choice.nodes());
        }
        return allNodes;
    }

    /**
     * Returns all child nodes for tree traversal.
     * For OptionNode, this includes all choice nodes.
     */
    @Override
    public List<ExecutionNode> getAllChildNodes() {
        return getAllChoiceNodes();
    }

    /**
     * OptionNode is a branching node - it selects one choice based on user selection.
     */
    @Override
    public boolean isBranchingNode() {
        return true;
    }

    /**
     * Returns all choice targets mapped by port for port-qualified edge emission.
     * Maps: "choice_0" -> [nodes], "choice_1" -> [nodes]
     */
    @Override
    public Map<String, List<ExecutionNode>> getBranchTargetsByPort() {
        Map<String, List<ExecutionNode>> result = new HashMap<>();
        for (int i = 0; i < choices.size(); i++) {
            String port = "choice_" + i;
            result.put(port, new ArrayList<>(choices.get(i).nodes()));
        }
        return result;
    }

    /**
     * Returns the selected port based on execution result.
     * Maps selected_choice_index to port name: "choice_0", "choice_1"
     */
    @Override
    public String getSelectedPort(NodeExecutionResult result) {
        if (result == null || result.output() == null) {
            return null;
        }
        Object indexObj = result.output().get("selected_choice_index");
        if (!(indexObj instanceof Integer selectedIndex) || selectedIndex < 0 || selectedIndex >= choices.size()) {
            return null;
        }
        return "choice_" + selectedIndex;
    }

    /**
     * OptionNode is an option node.
     */
    @Override
    public boolean isOptionNode() {
        return true;
    }

    /**
     * Add a target node to a specific choice (by index).
     * Used for late binding when wiring V2 edges after all nodes are created.
     */
    public void addTargetToChoice(int choiceIndex, ExecutionNode target) {
        if (choiceIndex >= 0 && choiceIndex < choices.size()) {
            choices.get(choiceIndex).addNode(target);
            logger.debug("Added target {} to choice {}", target.getNodeId(), choiceIndex);
        } else {
            logger.warn("Invalid choice index {} for option {}", choiceIndex, nodeId);
        }
    }

    /**
     * Polymorphic branch wiring - delegates to addTargetToChoice.
     */
    @Override
    public void addBranchTarget(int branchIndex, ExecutionNode target) {
        addTargetToChoice(branchIndex, target);
    }

    /**
     * Internal class to hold evaluation result.
     */
    private static class OptionEvaluation {
        final OptionBranch selectedChoice;
        final int selectedChoiceIndex;
        final String selectedChoiceId;
        final String selectedChoiceLabel;
        final List<String> skippedChoiceIds;
        final List<String> skippedChoiceLabels;
        final List<Map<String, Object>> evaluationDetails;

        OptionEvaluation(OptionBranch selectedChoice, int selectedChoiceIndex,
                        String selectedChoiceId, String selectedChoiceLabel,
                        List<String> skippedChoiceIds, List<String> skippedChoiceLabels,
                        List<Map<String, Object>> evaluationDetails) {
            this.selectedChoice = selectedChoice;
            this.selectedChoiceIndex = selectedChoiceIndex;
            this.selectedChoiceId = selectedChoiceId;
            this.selectedChoiceLabel = selectedChoiceLabel;
            this.skippedChoiceIds = skippedChoiceIds;
            this.skippedChoiceLabels = skippedChoiceLabels;
            this.evaluationDetails = evaluationDetails;
        }
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        logger.debug("Option node completed: nodeId={}", nodeId);
    }

    /**
     * Option branch (choice with expression).
     */
    public static class OptionBranch {
        private final String id;
        private final String label;
        private final String expression;
        private final List<ExecutionNode> nodes;

        public OptionBranch(String id, String label, String expression, List<ExecutionNode> nodes) {
            this.id = id;
            this.label = label;
            this.expression = expression;
            this.nodes = nodes != null ? nodes : new ArrayList<>();
        }

        /**
         * Evaluate expression with detailed result including resolved expression.
         */
        public ChoiceEvaluationResult evaluateExpressionWithDetails(
                Map<String, Object> context,
                TemplateEngine templateEngine,
                com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter templateAdapter) {

            if (expression == null || expression.isBlank()) {
                logger.warn("Choice '{}' has no expression, returning false", label);
                return new ChoiceEvaluationResult(false, "(no expression)", "No expression defined");
            }

            if (templateEngine == null) {
                logger.warn("No TemplateEngine available for expression: {}", expression);
                return new ChoiceEvaluationResult(false, expression, "No template engine available");
            }

            try {
                var evalResult = templateEngine.evaluateConditionWithDetailsWithMap(expression, context);
                return new ChoiceEvaluationResult(
                    evalResult.result(),
                    evalResult.resolvedExpression(),
                    evalResult.errorMessage()
                );
            } catch (Exception e) {
                logger.error("Expression evaluation failed: expression={}, error={}",
                    expression, e.getMessage());
                return new ChoiceEvaluationResult(false, expression, e.getMessage());
            }
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public String expression() {
            return expression;
        }

        public List<ExecutionNode> nodes() {
            return nodes;
        }

        public void addNode(ExecutionNode node) {
            if (node != null) {
                this.nodes.add(node);
            }
        }
    }

    public static class Builder {
        private String nodeId;
        private final List<OptionBranch> choices = new ArrayList<>();
        private TemplateEngine templateEngine;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder templateEngine(TemplateEngine templateEngine) {
            this.templateEngine = templateEngine;
            return this;
        }

        public Builder addChoice(String id, String label, String expression, List<ExecutionNode> nodes) {
            choices.add(new OptionBranch(id, label, expression, nodes));
            return this;
        }

        public OptionNode build() {
            return new OptionNode(nodeId, choices, templateEngine);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<OptionBranch> getChoices() {
        return choices;
    }
}
