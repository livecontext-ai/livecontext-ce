package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.TemplateEngine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one shape every branching node reports an evaluated branch in.
 *
 * <p>Before this class each node invented its own: a decision said
 * {@code branch_type} / {@code resolved_condition}, an option said
 * {@code choice_label} / {@code resolved_expression}, a switch reported no
 * resolved value per case at all, and a loop reported nothing about its
 * condition. None of them said which branch was SELECTED, so an {@code else} -
 * true by definition, since there is no condition to evaluate - showed
 * {@code result: true} beside a matched {@code if} that also showed
 * {@code result: true}, and the row the run took was indistinguishable from the
 * row it skipped.
 *
 * <p>Two rules hold the shape together:
 *
 * <ol>
 *   <li><b>{@code result} answers a question that was asked.</b> A branch with no
 *       condition ({@code else}, {@code default}) reports {@code null}, not a
 *       tautological {@code true}. {@code outcome} and {@code selected} carry what
 *       happened instead.</li>
 *   <li><b>{@code resolved} comes from the evaluation that decided.</b> Never from a
 *       second resolution pass: the two resolvers in this codebase render an absent
 *       value differently (one {@code null}, the other an empty string), so a node
 *       that re-resolved for display showed an expression which had decided nothing,
 *       contradicting its own output panel.</li>
 * </ol>
 */
public final class BranchEvaluationReport {

    /** The port this branch routes through, the same string the edge carries. */
    public static final String BRANCH = "branch";
    /** The condition as the author wrote it, empty when the branch has none. */
    public static final String CONDITION = "condition";
    /** The condition with its references substituted, as evaluated. */
    public static final String RESOLVED = "resolved";
    /** What the condition evaluated to, null when there is no condition. */
    public static final String RESULT = "result";
    /** Whether the run took this branch. At most one entry is true. */
    public static final String SELECTED = "selected";
    /** Why this branch was or was not taken. One of {@link Outcome}. */
    public static final String OUTCOME = "outcome";
    /** Evaluation error, absent when there was none. */
    public static final String ERROR = "error";
    /** References that pointed at nothing, absent when every reference resolved. */
    public static final String UNRESOLVED = "unresolved";
    /** Position among the branches, for stable ordering. */
    public static final String INDEX = "index";

    /**
     * The keys every branching node must report. Pinned by
     * {@code BranchEvaluationContractTest} so a node cannot quietly go back to a
     * private spelling.
     */
    public static final Set<String> REQUIRED_KEYS =
            Set.of(BRANCH, CONDITION, RESOLVED, RESULT, SELECTED, OUTCOME, INDEX);

    public enum Outcome {
        /** The condition was true and this branch was taken. */
        MATCHED("matched"),
        /** The condition was false. */
        NOT_MATCHED("not_matched"),
        /** The condition was true but an earlier branch had already won. */
        MATCHED_NOT_SELECTED("matched_not_selected"),
        /**
         * The run took this path although the condition was false. Only a loop does
         * this: its condition selects BETWEEN two ports rather than gating one, so a
         * false condition is exactly why the exit port was taken. Without this value
         * the taken path reported {@code not_matched}, contradicting the invariant
         * every other node upholds - selected implies matched or fallback.
         */
        TAKEN("taken"),
        /** No condition to evaluate: an else or a default. */
        FALLBACK("fallback"),
        /** The condition could not be evaluated. */
        ERROR("error");

        private final String wire;

        Outcome(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    private BranchEvaluationReport() {
    }

    /**
     * A branch that carried a condition.
     *
     * @param resolved the expression the evaluation actually decided on
     * @param unresolved references that pointed at nothing, may be null or empty
     */
    public static Map<String, Object> evaluated(int index,
                                                String branch,
                                                String condition,
                                                String resolved,
                                                boolean result,
                                                boolean selected,
                                                String error,
                                                List<TemplateEngine.UnresolvedReference> unresolved) {
        Map<String, Object> entry = base(index, branch);
        entry.put(CONDITION, condition == null ? "" : condition);
        entry.put(RESOLVED, resolved);
        entry.put(RESULT, result);
        entry.put(SELECTED, selected);
        entry.put(OUTCOME, outcomeOf(result, selected, error).wire());
        if (error != null && !error.isBlank()) {
            entry.put(ERROR, error);
        }
        if (unresolved != null && !unresolved.isEmpty()) {
            entry.put(UNRESOLVED, describeUnresolved(unresolved));
        }
        return entry;
    }

    /**
     * A branch with nothing to evaluate: an {@code else}, a {@code default}.
     *
     * <p>{@code result} is null on purpose. Reporting {@code true} here is what made
     * a skipped else look exactly like a matched if.
     */
    public static Map<String, Object> fallback(int index, String branch, boolean selected) {
        Map<String, Object> entry = base(index, branch);
        entry.put(CONDITION, "");
        entry.put(RESOLVED, "(no condition)");
        entry.put(RESULT, null);
        entry.put(SELECTED, selected);
        entry.put(OUTCOME, Outcome.FALLBACK.wire());
        return entry;
    }

    /**
     * A branch matched on a value rather than on a boolean expression: a switch case.
     *
     * @param subject the value the switch resolved to, rendered for display
     * @param candidate the value this case is tested against
     */
    public static Map<String, Object> matched(int index,
                                              String branch,
                                              String candidate,
                                              String subject,
                                              boolean result,
                                              boolean selected) {
        Map<String, Object> entry = base(index, branch);
        entry.put(CONDITION, candidate == null ? "" : candidate);
        entry.put(RESOLVED, subject + " == " + (candidate == null ? "null" : candidate));
        entry.put(RESULT, result);
        entry.put(SELECTED, selected);
        entry.put(OUTCOME, outcomeOf(result, selected, null).wire());
        return entry;
    }

    /**
     * The parameters panel, derived from the SAME entries the output panel shows, so
     * the two cannot disagree about what a condition resolved to.
     *
     * @return one entry per branch, keyed by its port, holding the resolved expression
     */
    public static Map<String, Object> resolvedParams(List<Map<String, Object>> evaluations) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (evaluations == null) {
            return params;
        }
        for (Map<String, Object> entry : evaluations) {
            Object branch = entry.get(BRANCH);
            if (branch != null) {
                params.put(branch.toString(), entry.get(RESOLVED));
            }
        }
        return params;
    }

    private static Map<String, Object> base(int index, String branch) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(INDEX, index);
        entry.put(BRANCH, branch);
        return entry;
    }

    private static Outcome outcomeOf(boolean result, boolean selected, String error) {
        if (error != null && !error.isBlank()) {
            return Outcome.ERROR;
        }
        if (result) {
            return selected ? Outcome.MATCHED : Outcome.MATCHED_NOT_SELECTED;
        }
        return selected ? Outcome.TAKEN : Outcome.NOT_MATCHED;
    }

    private static List<Map<String, Object>> describeUnresolved(
            List<TemplateEngine.UnresolvedReference> unresolved) {
        return unresolved.stream()
                .map(ref -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("reference", ref.reference());
                    item.put("reason", ref.reason());
                    return item;
                })
                .toList();
    }
}
