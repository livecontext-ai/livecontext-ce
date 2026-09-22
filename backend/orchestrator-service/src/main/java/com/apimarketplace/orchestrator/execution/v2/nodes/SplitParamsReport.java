package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.services.template.ReportedParams;
import com.apimarketplace.orchestrator.services.template.ResolvedValuePreview;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one shape a split reports its configuration in.
 *
 * <p>A split has two producers - {@code SplitNodeExecutor} on the live path and
 * {@link SplitNode} on the legacy sequential one - and three exit paths between
 * them (spawned, empty, failed). {@code list} used to mean something different on
 * each: the executor reported the CONFIGURED expression, the node reported the
 * RESOLVED value on its spawn path and the expression on every other, and the
 * pre-resolved entry point reported nothing at all. A reader comparing two rows of
 * one workflow was comparing two different questions, and a test had written the
 * divergence down as intentional rather than fixing it.
 *
 * <p>Two rules hold this shape together:
 *
 * <ol>
 *   <li><b>{@code list} is always the expression the author wrote.</b> It is the
 *       plan's own key and the plan's own value, so it reads the same on every path
 *       and matches what the Edit view shows. Resolving it for display was also how
 *       the value got coerced to a String: a list of ten rows reported as
 *       {@code "[{id=1}, {id=2}, …]"} while the node's own output held the real
 *       array.</li>
 *   <li><b>{@code listResolved} is what that expression actually evaluated to</b>,
 *       described in one bounded line, taken from the evaluation that decided how
 *       many items the split spawns. It is the answer to the only question asked of
 *       a split that produced nothing: an empty array, or a wrapper object the split
 *       could not iterate. It is absent - never blank, never a placeholder - on a
 *       path where no expression was evaluated.</li>
 * </ol>
 *
 * <p>{@code maxItems} and {@code splitStrategy} keep their presence rule: an unset
 * cap or an absent strategy is not a configured value, and rendering it would tell
 * the reader they set something they did not.
 */
public final class SplitParamsReport {

    /** The list expression as the author wrote it. */
    public static final String LIST = "list";
    /** What {@link #LIST} evaluated to, bounded for display. */
    public static final String LIST_RESOLVED = "listResolved";
    /** The configured cap, absent when unset. */
    public static final String MAX_ITEMS = "maxItems";
    /** The configured failure strategy, absent when the plan declares none. */
    public static final String SPLIT_STRATEGY = "splitStrategy";
    /** How many items the split ended up with. */
    public static final String ITEM_COUNT = "itemCount";
    /** Why the split could not spawn, present on the failure path only. */
    public static final String ERROR = "error";

    private SplitParamsReport() {
    }

    /**
     * @param listExpression the configured expression, null on the pre-resolved entry point
     * @param maxItems       the configured cap, 0 when unset
     * @param splitStrategy  the configured strategy, null when the plan declares none
     * @param listResolved   what the expression evaluated to, null when none was evaluated
     * @param itemCount      the item count, null when the split never got one
     */
    public static Map<String, Object> build(String listExpression,
                                            int maxItems,
                                            String splitStrategy,
                                            String listResolved,
                                            Integer itemCount) {
        Map<String, Object> params = new LinkedHashMap<>();
        // Blank-aware, like FindNode's own guard: a whitespace expression is not a
        // configured value, and the two collection nodes must not differ on what counts
        // as configured in a class whose point is one node, one vocabulary.
        if (listExpression != null && !listExpression.isBlank()) {
            params.put(LIST, listExpression);
        }
        if (listResolved != null) {
            params.put(LIST_RESOLVED, listResolved);
        }
        if (maxItems > 0) {
            params.put(MAX_ITEMS, maxItems);
        }
        if (splitStrategy != null) {
            params.put(SPLIT_STRATEGY, splitStrategy);
        }
        if (itemCount != null) {
            params.put(ITEM_COUNT, itemCount);
        }
        // Through the gate like every other reported map, at the ONE place both producers
        // share: `list` is an author expression with no length limit, and this row is
        // written per item of every split. The `error` key is added by the caller AFTER
        // this returns, so it carries its own bound - see putError below.
        return ReportedParams.forReport(params);
    }

    /**
     * Records why a split produced nothing, bounded.
     *
     * <p>Put on the map AFTER {@link #build} has been through the gate, so it needs its own
     * bound: the message names the shape the unwrapper found, and
     * {@code OutputUnwrapper.describeNonListShape} caps the number of keys it lists but not
     * their length. Twenty keys of four kilobytes is an eighty-kilobyte "error" on the row of
     * every item of every split - the failure {@code ResolvedValuePreview} exists to prevent,
     * reached through the one key that skipped it.
     */
    public static void putError(Map<String, Object> params, String reason) {
        params.put(ERROR, ResolvedValuePreview.shorten(reason));
    }
}
