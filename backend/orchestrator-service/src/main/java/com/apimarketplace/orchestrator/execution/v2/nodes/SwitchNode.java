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
import java.util.Objects;

/**
 * Switch node - Value-based branching (per item, no cross-item mixing).
 *
 * Unlike Decision nodes which evaluate boolean conditions (if/elseif/else),
 * Switch nodes match an expression value against case values.
 *
 * Flow:
 * 1. Evaluate switch expression to get a value
 * 2. Match value against case values in order
 * 3. Return nodes for the FIRST matching case
 * 4. If no case matches, use default branch
 * 5. Other branches are marked as skipped
 *
 * Output contains:
 * - selected_case: the case that matched (index or "default")
 * - skipped_cases: list of case indices that were skipped
 * - switch_value: the evaluated expression value
 * - evaluations: detailed evaluation results for debugging
 *
 * Edge Format:
 * - "core:switch_label:case_0" -> first case
 * - "core:switch_label:case_1" -> second case
 * - "core:switch_label:default" -> default case
 */
public class SwitchNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SwitchNode.class);

    private final String switchExpression;
    private final List<SwitchCase> cases;
    private final TemplateEngine templateEngine;

    public SwitchNode(
            String nodeId,
            String switchExpression,
            List<SwitchCase> cases,
            TemplateEngine templateEngine) {
        super(nodeId, NodeType.SWITCH);
        this.switchExpression = switchExpression;
        this.cases = cases != null ? cases : new ArrayList<>();
        this.templateEngine = templateEngine;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        logger.debug("Switch node executing: nodeId={}, cases={}, itemId={}",
            nodeId, cases.size(), context.itemId());

        // Build evaluation context
        Map<String, Object> evalContext = EvalContextBuilder.buildStandardEvalContext(context);

        // Evaluate switch expression to get the value
        Object switchValue = evaluateSwitchExpression(evalContext);

        // Match against cases
        SwitchEvaluation evaluation = evaluateCases(switchValue);

        // Build resolved_params snapshot for inspector visibility (resolved values)
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("expression", resolveTemplateString(switchExpression, context));
        resolvedParams.put("resolved_value", switchValue);
        resolvedParams.put("cases", cases.size());

        // Build output with evaluation details
        Map<String, Object> output = new HashMap<>();
        output.put("resolved_params", resolvedParams);
        output.put("node_type", "SWITCH");
        output.put("switch_node", nodeId);
        output.put("cases_evaluated", cases.size());
        output.put("switch_expression", switchExpression);
        output.put("switch_value", switchValue);
        // Add resolved expression showing what the template resolved to
        output.put("resolved_switch_expression", formatResolvedExpression(switchExpression, switchValue));
        output.put("selected_case", evaluation.selectedCaseType);
        output.put("selected_case_index", evaluation.selectedCaseIndex);
        output.put("skipped_cases", evaluation.skippedCaseTypes);
        output.put("skipped_case_labels", evaluation.skippedCaseLabels);
        output.put("evaluations", evaluation.evaluationDetails);

        // Add matched case info with label
        if (evaluation.selectedCase != null) {
            output.put("matched_value", evaluation.selectedCase.value());
            output.put("match_result", true);
            String label = evaluation.selectedCase.label();
            output.put("selected_case_label", label != null ? label : evaluation.selectedCaseType);
        } else {
            output.put("match_result", false);
        }

        // Add item context for persistence
        output.put("item_index", context.itemIndex());
        output.put("itemIndex", context.itemIndex());
        output.put("item_id", context.itemId());

        logger.info("Switch evaluated: nodeId={}, value={}, selected={}, skipped={}",
            nodeId, switchValue, evaluation.selectedCaseType, evaluation.skippedCaseTypes);

        return NodeExecutionResult.success(nodeId, output);
    }

    /**
     * Format the resolved expression for human-readable display.
     * Example: "{{trigger:test.output.status}}" with value "active" becomes "active"
     */
    private String formatResolvedExpression(String originalExpr, Object resolvedValue) {
        if (resolvedValue == null) {
            return "null";
        }
        if (resolvedValue instanceof String) {
            return "\"" + resolvedValue + "\"";
        }
        return resolvedValue.toString();
    }

    /**
     * Evaluates the switch expression to get the value to match.
     */
    private Object evaluateSwitchExpression(Map<String, Object> evalContext) {
        if (switchExpression == null || switchExpression.isBlank()) {
            logger.warn("Switch '{}' has no expression, using null", nodeId);
            return null;
        }

        try {
            if (templateEngine != null) {
                Object result = templateEngine.resolveWithMap(switchExpression, evalContext);
                logger.debug("Switch '{}' expression: {} -> {}", nodeId, switchExpression, result);
                return result;
            }

            if (templateAdapter != null) {
                // Fallback to template adapter
                Map<String, Object> resolved = templateAdapter.resolveTemplates(
                    Map.of("__expr__", switchExpression), null);
                return resolved.get("__expr__");
            }

            // No template engine, return expression as-is
            return switchExpression;

        } catch (Exception e) {
            logger.error("Switch '{}' expression evaluation failed: {}", nodeId, e.getMessage());
            return null;
        }
    }

    /**
     * Evaluate all cases and find the matching one.
     */
    private SwitchEvaluation evaluateCases(Object switchValue) {
        SwitchCase selectedCase = null;
        int selectedIndex = -1;
        List<String> skippedTypes = new ArrayList<>();
        List<String> skippedLabels = new ArrayList<>();
        List<Map<String, Object>> evaluationDetails = new ArrayList<>();

        for (int i = 0; i < cases.size(); i++) {
            SwitchCase caseItem = cases.get(i);
            boolean matches;

            if (caseItem.isDefault()) {
                // Default case matches if no other case matched yet
                matches = (selectedCase == null);
            } else {
                // Compare switch value with case value
                matches = valuesMatch(switchValue, caseItem.value());
            }

            String caseType = caseItem.isDefault() ? "default" : "case_" + i;
            String caseLabel = caseItem.label() != null ? caseItem.label() : caseType;
            logger.debug("Case[{}] '{}': value='{}' vs switch='{}' -> {}",
                i, caseType, caseItem.value(), switchValue, matches);

            // Record evaluation
            Map<String, Object> evalEntry = new HashMap<>();
            evalEntry.put("case_type", caseType);
            evalEntry.put("case_label", caseLabel);
            evalEntry.put("case_value", caseItem.value() != null ? caseItem.value() : "");
            evalEntry.put("is_default", caseItem.isDefault());
            evalEntry.put("result", matches);
            evalEntry.put("index", i);
            evaluationDetails.add(evalEntry);

            // First matching case wins (but default only if nothing else matched)
            if (matches && selectedCase == null) {
                if (!caseItem.isDefault()) {
                    selectedCase = caseItem;
                    selectedIndex = i;
                }
            } else if (!matches || selectedCase != null) {
                skippedTypes.add(caseType);
                skippedLabels.add(caseLabel);
            }
        }

        // If no case matched, use default
        if (selectedCase == null) {
            for (int i = 0; i < cases.size(); i++) {
                SwitchCase caseItem = cases.get(i);
                if (caseItem.isDefault()) {
                    selectedCase = caseItem;
                    selectedIndex = i;
                    skippedTypes.remove("default");
                    String label = caseItem.label() != null ? caseItem.label() : "default";
                    skippedLabels.remove(label);
                    break;
                }
            }
        }

        return new SwitchEvaluation(
            selectedCase,
            selectedIndex,
            selectedCase != null ? (selectedCase.isDefault() ? "default" : "case_" + selectedIndex) : null,
            skippedTypes,
            skippedLabels,
            evaluationDetails
        );
    }

    /**
     * Compares switch value with case value.
     * Handles type coercion for common cases.
     */
    private boolean valuesMatch(Object switchValue, Object caseValue) {
        if (switchValue == null && caseValue == null) {
            return true;
        }
        if (switchValue == null || caseValue == null) {
            return false;
        }

        // Direct equality
        if (Objects.equals(switchValue, caseValue)) {
            return true;
        }

        // String comparison (handles number-to-string)
        String switchStr = String.valueOf(switchValue);
        String caseStr = String.valueOf(caseValue);
        if (switchStr.equals(caseStr)) {
            return true;
        }

        // Case-insensitive string comparison
        if (switchStr.equalsIgnoreCase(caseStr)) {
            return true;
        }

        // Numeric comparison
        try {
            double switchNum = toDouble(switchValue);
            double caseNum = toDouble(caseValue);
            return switchNum == caseNum;
        } catch (NumberFormatException e) {
            // Not numbers, stick with string comparison
        }

        return false;
    }

    private double toDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    @Override
    public List<ExecutionNode> getNextNodes(NodeExecutionResult result) {
        Object indexObj = result.output().get("selected_case_index");

        if (indexObj instanceof Integer selectedIndex && selectedIndex >= 0 && selectedIndex < cases.size()) {
            SwitchCase selectedCase = cases.get(selectedIndex);
            logger.debug("Switch '{}' selected case: {} (index={})", nodeId,
                selectedCase.isDefault() ? "default" : "case_" + selectedIndex, selectedIndex);
            return selectedCase.nodes();
        }

        logger.debug("Switch '{}': no case matched", nodeId);
        return List.of();
    }

    /**
     * Get the cases that were skipped (for skip propagation).
     */
    public List<ExecutionNode> getSkippedCaseNodes(NodeExecutionResult result) {
        Object indexObj = result.output().get("selected_case_index");

        // If no selected_case_index in output, skip ALL cases
        if (!(indexObj instanceof Integer)) {
            return getAllCaseNodes();
        }

        int selectedIndex = (Integer) indexObj;

        // If selectedIndex is -1 (no match), ALL cases are skipped
        if (selectedIndex < 0 || selectedIndex >= cases.size()) {
            return getAllCaseNodes();
        }

        // Normal case: skip all cases except the selected one
        List<ExecutionNode> skippedNodes = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            if (i != selectedIndex) {
                skippedNodes.addAll(cases.get(i).nodes());
            }
        }
        return skippedNodes;
    }

    /**
     * Get ALL case nodes (used when no case is selected - all are skipped).
     */
    public List<ExecutionNode> getAllCaseNodes() {
        List<ExecutionNode> allNodes = new ArrayList<>();
        for (SwitchCase caseItem : cases) {
            allNodes.addAll(caseItem.nodes());
        }
        return allNodes;
    }

    /**
     * Add a target node to a specific case (by index).
     */
    public void addTargetToCase(int caseIndex, ExecutionNode target) {
        if (caseIndex >= 0 && caseIndex < cases.size()) {
            cases.get(caseIndex).addNode(target);
            logger.debug("Added target {} to case {}", target.getNodeId(), caseIndex);
        } else {
            logger.warn("Invalid case index {} for switch {}", caseIndex, nodeId);
        }
    }

    /**
     * SwitchNode is a switch node.
     */
    @Override
    public boolean isSwitchNode() {
        return true;
    }

    /**
     * SwitchNode is a branching node - it selects one case based on value matching.
     */
    @Override
    public boolean isBranchingNode() {
        return true;
    }

    /**
     * Returns all case targets mapped by port for port-qualified edge emission.
     * Maps: "case_0" -> [nodes], "case_1" -> [nodes], "default" -> [nodes]
     */
    @Override
    public Map<String, List<ExecutionNode>> getBranchTargetsByPort() {
        Map<String, List<ExecutionNode>> result = new HashMap<>();
        for (int i = 0; i < cases.size(); i++) {
            SwitchCase caseItem = cases.get(i);
            String port = caseItem.isDefault() ? "default" : "case_" + i;
            result.put(port, new ArrayList<>(caseItem.nodes()));
        }
        return result;
    }

    /**
     * Returns the selected port based on execution result.
     * Maps selected_case_index to port name: "case_0", "case_1", "default"
     */
    @Override
    public String getSelectedPort(NodeExecutionResult result) {
        if (result == null || result.output() == null) {
            return null;
        }
        Object indexObj = result.output().get("selected_case_index");
        if (!(indexObj instanceof Integer selectedIndex) || selectedIndex < 0 || selectedIndex >= cases.size()) {
            return null;
        }
        SwitchCase selectedCase = cases.get(selectedIndex);
        return selectedCase.isDefault() ? "default" : "case_" + selectedIndex;
    }

    /**
     * SwitchNode skips split handling - it manages its own control flow.
     */
    @Override
    public boolean skipsSplitHandling() {
        return true;
    }

    /**
     * Returns all child nodes for tree traversal.
     */
    @Override
    public List<ExecutionNode> getAllChildNodes() {
        return getAllCaseNodes();
    }

    /**
     * Returns child nodes that should be skipped based on execution result.
     */
    @Override
    public List<ExecutionNode> getSkippedChildNodes(NodeExecutionResult result) {
        return getSkippedCaseNodes(result);
    }

    /**
     * Polymorphic branch wiring - delegates to addTargetToCase.
     */
    @Override
    public void addBranchTarget(int branchIndex, ExecutionNode target) {
        addTargetToCase(branchIndex, target);
    }

    /**
     * Internal class to hold evaluation result.
     */
    private static class SwitchEvaluation {
        final SwitchCase selectedCase;
        final int selectedCaseIndex;
        final String selectedCaseType;
        final List<String> skippedCaseTypes;
        final List<String> skippedCaseLabels;
        final List<Map<String, Object>> evaluationDetails;

        SwitchEvaluation(SwitchCase selectedCase, int selectedCaseIndex,
                         String selectedCaseType, List<String> skippedCaseTypes,
                         List<String> skippedCaseLabels,
                         List<Map<String, Object>> evaluationDetails) {
            this.selectedCase = selectedCase;
            this.selectedCaseIndex = selectedCaseIndex;
            this.selectedCaseType = selectedCaseType;
            this.skippedCaseTypes = skippedCaseTypes;
            this.skippedCaseLabels = skippedCaseLabels;
            this.evaluationDetails = evaluationDetails;
        }
    }

    @Override
    public void onComplete(ExecutionContext context, NodeExecutionResult result) {
        logger.debug("Switch node completed: nodeId={}", nodeId);
    }

    /**
     * Switch case (case_N or default).
     */
    public static class SwitchCase {
        private final String type;  // "case" or "default"
        private final Object value; // null for "default"
        private final String label;
        private final List<ExecutionNode> nodes;

        public SwitchCase(String type, Object value, String label, List<ExecutionNode> nodes) {
            this.type = type != null ? type : "case";
            this.value = value;
            this.label = label;
            this.nodes = nodes != null ? nodes : new ArrayList<>();
        }

        public SwitchCase(String type, Object value, String label) {
            this(type, value, label, new ArrayList<>());
        }

        public boolean isDefault() {
            return "default".equals(type);
        }

        public String type() {
            return type;
        }

        public Object value() {
            return value;
        }

        public String label() {
            return label;
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

    // Getters
    public String getSwitchExpression() {
        return switchExpression;
    }

    public List<SwitchCase> getCases() {
        return cases;
    }

    // Builder
    public static class Builder {
        private String nodeId;
        private String switchExpression;
        private final List<SwitchCase> cases = new ArrayList<>();
        private TemplateEngine templateEngine;

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder switchExpression(String switchExpression) {
            this.switchExpression = switchExpression;
            return this;
        }

        public Builder templateEngine(TemplateEngine templateEngine) {
            this.templateEngine = templateEngine;
            return this;
        }

        public Builder addCase(Object value, String label, List<ExecutionNode> nodes) {
            cases.add(new SwitchCase("case", value, label, nodes));
            return this;
        }

        public Builder addCase(Object value, String label) {
            return addCase(value, label, new ArrayList<>());
        }

        public Builder addDefault(String label, List<ExecutionNode> nodes) {
            cases.add(new SwitchCase("default", null, label, nodes));
            return this;
        }

        public Builder addDefault(String label) {
            return addDefault(label, new ArrayList<>());
        }

        public SwitchNode build() {
            return new SwitchNode(nodeId, switchExpression, cases, templateEngine);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
