package com.apimarketplace.common.plan;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ordering of the subscription plan codes, so a feature can say "PRO and above"
 * in one place instead of every caller re-listing the codes above its own bar.
 *
 * <p>Before this class the same tier rule was written by hand wherever it was
 * needed ({@code useWorkspaceEntitlements} checks {@code PRO || TEAM ||
 * startsWith("ENTERPRISE")}, {@code EntitlementGuard.upgradeHintFor} walks the
 * chain again), which is workable for two rules and unworkable for a table of
 * them that an admin edits at runtime.
 *
 * <p><b>Both unknowns fail OPEN.</b> An unrecognised USER plan is treated as
 * unrestricted and an unrecognised REQUIRED plan as no requirement at all. The
 * alternative locks paying customers out of features they bought the moment a
 * new plan code ships ahead of this file, which is a worse failure than a gate
 * that briefly does not apply. {@code null}, blank and the
 * {@code PlanLimitService.NO_SUBSCRIPTION} sentinel are NOT unknowns: they mean
 * "no subscription" and rank as FREE.
 *
 * <p><b>CE is unrestricted.</b> A self-hosted install reports the plan code
 * {@code CE} and has nothing to upgrade to, so plan gating is a cloud-only
 * concept. Callers should ALSO skip the gate by edition; this is the second
 * line of defence for the path where only a plan string is in hand.
 */
public final class PlanTier {

    /** Sentinel written by auth-service when the user has no active subscription. */
    public static final String NO_SUBSCRIPTION = "__NONE__";

    /** Plan code reported by a self-hosted (CE) deployment. */
    public static final String CE = "CE";

    /** The default requirement: available to everyone. */
    public static final String FREE = "FREE";

    private static final int UNRESTRICTED = Integer.MAX_VALUE;

    /**
     * Rank per plan code. Only ORDER matters, not the numbers: they are spaced
     * by one so a tier inserted later can be given a fractional-free slot by
     * renumbering this map alone.
     *
     * <p>{@code PAYG} sits with STARTER on purpose. Paying per call is not a
     * tier upgrade, and treating it as one would hand every metered account the
     * features TEAM is sold for.
     *
     * <p>{@code CREDIT_PACK} is a one-off top-up bought ON TOP of a plan, not a
     * plan, so it ranks with FREE: the account's real plan is the one that
     * answers here.
     */
    private static final Map<String, Integer> RANKS = Map.ofEntries(
            Map.entry(FREE, 0),
            Map.entry("CREDIT_PACK", 0),
            Map.entry("STARTER", 1),
            Map.entry("PAYG", 1),
            Map.entry("PRO", 2),
            Map.entry("TEAM", 3),
            Map.entry("ENTERPRISE_BASIC", 4),
            Map.entry("ENTERPRISE_STANDARD", 4),
            Map.entry("ENTERPRISE_PREMIUM", 4),
            Map.entry("ENTERPRISE_ULTIMATE", 4));

    /**
     * The codes an admin may pick as a requirement, cheapest first. Deliberately
     * shorter than {@link #RANKS}: the four ENTERPRISE SKUs share one rank, so
     * offering all four would present four spellings of one choice.
     */
    private static final List<String> SELECTABLE = List.of(
            FREE, "STARTER", "PRO", "TEAM", "ENTERPRISE");

    private PlanTier() {
    }

    /**
     * The plan codes an admin may require, cheapest first. {@code FREE} means
     * "no requirement" and is what a feature with no row resolves to.
     */
    public static List<String> selectableCodes() {
        return SELECTABLE;
    }

    /** Whether {@code code} is one an admin may store as a requirement. */
    public static boolean isSelectable(String code) {
        return code != null && SELECTABLE.contains(normalize(code));
    }

    /**
     * Whether a user on {@code userPlanCode} may use something that requires
     * {@code requiredPlanCode}.
     *
     * @param userPlanCode     the user's effective plan; null / blank /
     *                         {@link #NO_SUBSCRIPTION} rank as FREE, {@link #CE}
     *                         and unknown codes are unrestricted
     * @param requiredPlanCode the requirement; null / blank / FREE / unknown
     *                         mean no requirement
     */
    public static boolean meets(String userPlanCode, String requiredPlanCode) {
        int required = requiredRank(requiredPlanCode);
        if (required <= 0) {
            return true;
        }
        return userRank(userPlanCode) >= required;
    }

    /**
     * Rank of the plan a USER is on. Unknown codes (and CE) return
     * {@link Integer#MAX_VALUE} so they clear every bar.
     */
    public static int userRank(String planCode) {
        String code = normalize(planCode);
        if (code.isEmpty() || NO_SUBSCRIPTION.equals(code)) {
            return 0;
        }
        if (CE.equals(code)) {
            return UNRESTRICTED;
        }
        Integer rank = RANKS.get(code);
        // An ENTERPRISE SKU this file has not been taught yet still ranks as
        // enterprise rather than as an unknown, because the prefix is the
        // product name and new SKUs are added far more often than new tiers.
        if (rank == null && code.startsWith("ENTERPRISE")) {
            return 4;
        }
        return rank != null ? rank : UNRESTRICTED;
    }

    /**
     * Rank of a REQUIREMENT. Anything this file does not recognise resolves to
     * 0, i.e. no requirement, so a stored typo opens the feature rather than
     * sealing it.
     */
    public static int requiredRank(String planCode) {
        String code = normalize(planCode);
        if (code.isEmpty() || FREE.equals(code)) {
            return 0;
        }
        Integer rank = RANKS.get(code);
        if (rank == null && code.startsWith("ENTERPRISE")) {
            return 4;
        }
        return rank != null ? rank : 0;
    }

    /**
     * Whether {@code planCode} is a PAID plan: a code this file knows, ranked above FREE
     * (STARTER, PAYG, PRO, TEAM and every ENTERPRISE SKU).
     *
     * <p><b>Strict, fails CLOSED</b>, the deliberate opposite of {@link #userRank}: FREE,
     * {@code CREDIT_PACK} (a top-up, not a plan), {@link #CE}, {@link #NO_SUBSCRIPTION},
     * null, blank and any code this file has not been taught are all NOT paid. It exists
     * for rules that spend cloud money on the caller's behalf (the CE cloud link, whose
     * relays run LLM, search and catalog calls on the cloud's keys), where letting an
     * unknown code through costs real money instead of briefly un-gating a feature. Use
     * {@link #meets} for ordinary feature gating, which must keep failing open.
     */
    public static boolean isPaid(String planCode) {
        String code = normalize(planCode);
        if (code.isEmpty()) {
            return false;
        }
        Integer rank = RANKS.get(code);
        if (rank == null && code.startsWith("ENTERPRISE")) {
            rank = 4;
        }
        return rank != null && rank > 0;
    }

    /**
     * The cheapest plan that satisfies {@code requiredPlanCode}, for an upgrade
     * prompt. Returns the requirement itself once normalised, or {@code null}
     * when there is nothing to require.
     */
    public static String normalizeRequirement(String requiredPlanCode) {
        String code = normalize(requiredPlanCode);
        if (code.isEmpty() || FREE.equals(code) || requiredRank(code) <= 0) {
            return null;
        }
        return code;
    }

    private static String normalize(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }
}
