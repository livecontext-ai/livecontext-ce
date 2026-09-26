package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.template.ReportedParams;
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
        Object switchValue = evaluateSwitchExpression(evalContext, context);

        // Each case value, resolved in the SAME context as the subject. A case written
        // `{{$vars.gold_tier}}` used to be compared as that literal text, so it never matched
        // while the same reference resolved in the subject a few lines above.
        List<Object> caseValues = resolveCaseValues(evalContext, context);

        // Match against cases
        // Computed ONCE: it runs a protected-region scan, a regex sweep and a probe over
        // the subject expression, and both the case rows and the parameters panel need
        // the same answer.
        String subjectDisplay = subjectForDisplay(evalContext, switchValue);
        SwitchEvaluation evaluation = evaluateCases(switchValue, subjectDisplay, caseValues);


        // Build resolved_params snapshot for inspector visibility (resolved values)
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        // The value the cases were compared against, rendered plainly (the quoted form
        // belongs in an expression, not in a parameters panel). Taken from the matching
        // itself: resolveTemplateString is a SECOND resolver that renders an absent
        // value as an empty string, so it could show a subject nothing was compared to.
        resolvedParams.put("switchExpression", subjectDisplay);
        // The resolved SUBJECT is upstream data, or a workspace variable the author pulled
        // in: `valueFrom` withholds a $vars scalar and bounds everything else. It is the
        // one value on this row that is not plan configuration.
        resolvedParams.put("resolved_value", ReportedParams.valueFrom(subjectDisplay, switchValue));
        // One key per CASE with the value it matches on, the way a decision reports
        // one key per branch. "switchCases: 3" told the reader how many cases existed
        // and nothing about which values they were tested against, which is the only
        // question anyone opens this panel to answer.
        for (int i = 0; i < cases.size(); i++) {
            SwitchCase caseItem = cases.get(i);
            String key = caseItem.label() != null && !caseItem.label().isBlank()
                ? caseItem.label()
                : ("default".equals(caseItem.type()) ? "default" : "case_" + i);
            resolvedParams.put(key, "default".equals(caseItem.type()) ? "(default)" : reportedCaseValue(i, caseValues));
        }

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
            // The CONFIGURED case value, as before: this is an output field downstream nodes read,
            // and a resolved `{{$vars.x}}` would publish a workspace variable into the run's data.
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
     * The switch subject as a reader should see it.
     *
     * <p>A subject whose reference points at nothing resolves to null and then quietly
     * takes the default, which looks exactly like a healthy run that chose the default on
     * purpose. Marking it here puts the answer on every case row.
     */
    private String subjectForDisplay(Map<String, Object> evalContext, Object switchValue) {
        // Null OR empty string: resolveWithMap renders an absent value as "" for a mixed
        // template, which is the same empty-string rendering that made the decision's
        // Params column read " == null". Only a CONFIRMED missing namespaced reference
        // gets marked, so a legitimately empty subject is never accused.
        boolean absent = switchValue == null || "".equals(switchValue);
        if (absent && templateEngine != null) {
            List<TemplateEngine.UnresolvedReference> missing =
                templateEngine.findUnresolvedReferences(switchExpression, evalContext);
            if (!missing.isEmpty()) {
                return "<unresolved: " + missing.get(0).reference() + ">";
            }
        }
        // Plain, not quoted. The case values it is compared against are plain, and the
        // parameters panel shows this same string: quoting one side produced the
        // lopsided "active" == archived, which reads as two different kinds of thing.
        return switchValue == null ? "null" : String.valueOf(switchValue);
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
    private Object evaluateSwitchExpression(Map<String, Object> evalContext, ExecutionContext context) {
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
                // The run's own context: a null one made every resolution throw, so this path
                // always fell to the catch below and matched the default.
                return resolveTemplateValue(switchExpression, context);
            }

            // No template engine, return expression as-is
            return switchExpression;

        } catch (Exception e) {
            logger.error("Switch '{}' expression evaluation failed: {}", nodeId, e.getMessage());
            return null;
        }
    }

    /**
     * The value each case is compared on, by index. A literal case value is returned as it is;
     * one containing {@code {{...}}} is resolved against {@code evalContext}, the map the subject
     * itself was resolved against, so both sides of a comparison read the same run. A default
     * case keeps its (null) value.
     */
    private List<Object> resolveCaseValues(Map<String, Object> evalContext, ExecutionContext context) {
        List<Object> values = new ArrayList<>(cases.size());
        for (SwitchCase caseItem : cases) {
            Object value = caseItem.value();
            if (isTemplatedCase(caseItem)) {
                String text = (String) value;
                value = templateEngine != null
                    ? templateEngine.resolveWithMap(text, evalContext)
                    : resolveTemplateValue(text, context);
                // A reference to nothing reads as "" through resolveWithMap and as null through
                // the adapter. Either way it is not a value to compare on: against an empty
                // subject it would MATCH and take the branch from `default`.
                if (value == null || (value instanceof String r && r.isEmpty())
                        || anyReferenceResolvesToNothing(text, evalContext, context)) {
                    value = UNRESOLVED_CASE;
                }
            }
            values.add(value);
        }
        return values;
    }

    private static final java.util.regex.Pattern CASE_REFERENCE = java.util.regex.Pattern.compile("\\{\\{.*?}}");

    /**
     * Whether ANY reference inside a case with text around it resolved to nothing. The whole
     * case then reads as its literal part alone ({@code gold_{{x}}} becomes {@code gold_}), a
     * value the author never wrote, which could match a subject equal to it.
     */
    private boolean anyReferenceResolvesToNothing(String caseText, Map<String, Object> evalContext,
                                                  ExecutionContext context) {
        java.util.regex.Matcher m = CASE_REFERENCE.matcher(caseText);
        int references = 0;
        while (m.find()) {
            references++;
            if (references == 1 && m.start() == 0 && m.end() == caseText.length()) {
                return false; // one whole reference: already judged by its own value
            }
            Object one = templateEngine != null
                ? templateEngine.resolveWithMap(m.group(), evalContext)
                : resolveTemplateValue(m.group(), context);
            if (one == null || (one instanceof String r && r.isEmpty())) {
                return true;
            }
        }
        return false;
    }

    /** Marks a templated case whose reference resolved to nothing: it never matches. */
    private static final Object UNRESOLVED_CASE = new Object() {
        @Override
        public String toString() {
            return "(resolved to nothing)";
        }
    };

    private static boolean isTemplatedCase(SwitchCase caseItem) {
        return !caseItem.isDefault() && caseItem.value() instanceof String text && text.contains("{{");
    }

    /**
     * What the panel shows for case {@code i}: a literal as written, a resolved template through
     * the workspace-variable rule (a case written {@code {{$vars.x}}} must not print the variable).
     */
    private Object reportedCaseValue(int i, List<Object> caseValues) {
        Object value = caseValues.get(i);
        if (value == UNRESOLVED_CASE) {
            return UNRESOLVED_CASE.toString();
        }
        SwitchCase caseItem = cases.get(i);
        return isTemplatedCase(caseItem)
            ? ReportedParams.valueFrom((String) caseItem.value(), value)
            : value;
    }

    /**
     * Evaluate all cases and find the matching one.
     */
    private SwitchEvaluation evaluateCases(Object switchValue, String subject, List<Object> caseValues) {
        // Pass 1: only a NON-default case can win on value, and the first one does.
        boolean[] matches = new boolean[cases.size()];
        int selectedIndex = -1;

        for (int i = 0; i < cases.size(); i++) {
            SwitchCase caseItem = cases.get(i);
            if (caseItem.isDefault()) {
                continue;
            }
            matches[i] = caseValues.get(i) != UNRESOLVED_CASE && valuesMatch(switchValue, caseValues.get(i));
            if (matches[i] && selectedIndex < 0) {
                selectedIndex = i;
            }
        }

        // The default is taken only when nothing else matched, wherever it sits in the
        // list. Deciding that while scanning made it positional: a default declared
        // BEFORE its siblings was tested against a selection that could not exist yet,
        // so it reported a match for a branch the switch did not take.
        if (selectedIndex < 0) {
            for (int i = 0; i < cases.size(); i++) {
                if (cases.get(i).isDefault()) {
                    selectedIndex = i;
                    break;
                }
            }
        }

        // Pass 2: report, now that the winner is known.
        List<String> skippedTypes = new ArrayList<>();
        List<String> skippedLabels = new ArrayList<>();
        List<Map<String, Object>> evaluationDetails = new ArrayList<>(cases.size());

        for (int i = 0; i < cases.size(); i++) {
            SwitchCase caseItem = cases.get(i);
            String caseType = caseItem.isDefault() ? "default" : "case_" + i;
            String caseLabel = caseItem.label() != null ? caseItem.label() : caseType;
            boolean selected = i == selectedIndex;

            Object shownCase = caseItem.isDefault() ? null : reportedCaseValue(i, caseValues);
            logger.debug("Case[{}] '{}': value='{}' -> matched={} selected={}",
                i, caseType, shownCase, matches[i], selected);

            Map<String, Object> entry = caseItem.isDefault()
                ? BranchEvaluationReport.fallback(i, caseType, selected)
                : BranchEvaluationReport.matched(
                    i, caseType,
                    shownCase == null ? null : String.valueOf(shownCase),
                    subject, matches[i], selected);
            // The author named this case; the port (case_0) cannot say "Gold tier".
            entry.put("case_label", caseLabel);
            evaluationDetails.add(entry);

            if (!selected) {
                skippedTypes.add(caseType);
                skippedLabels.add(caseLabel);
            }
        }

        SwitchCase selectedCase = selectedIndex >= 0 ? cases.get(selectedIndex) : null;

        return new SwitchEvaluation(
            selectedCase,
            selectedIndex,
            selectedCase == null ? null : (selectedCase.isDefault() ? "default" : "case_" + selectedIndex),
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
     * Inverse of {@link #getSelectedPort} for the mock mode: maps "case_N" /
     * "default" back to {@code {selected_case_index: N}}. Unknown port falls back
     * to the default {@code selected_port} form (no case selected).
     */
    @Override
    public Map<String, Object> portSelectionOutput(String port) {
        for (int i = 0; i < cases.size(); i++) {
            String candidate = cases.get(i).isDefault() ? "default" : "case_" + i;
            if (candidate.equals(port)) {
                Map<String, Object> out = new HashMap<>();
                out.put("selected_case_index", i);
                return out;
            }
        }
        return super.portSelectionOutput(port);
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
