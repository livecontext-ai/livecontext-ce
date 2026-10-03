package com.apimarketplace.auth.service;

import com.apimarketplace.auth.billing.CreditTierConstants;
import com.apimarketplace.auth.domain.PersonalOfferMatrix;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A personal offer's bonus tiers, read from its matrix: "from this monthly pack, at least this
 * bonus". The offer page draws them as a ladder and the emails say them in a sentence, so both
 * read the same steps.
 *
 * <p>A step is a promise, so it holds on every plan that sells the pack and on every bigger pack:
 * it takes the smallest bonus of the plans selling that pack and of all the packs above it, and a
 * plan or a bigger pack that gives less lowers it, never the reverse. A plan with no cell at a
 * pack it sells gives nothing there (the page shows that pack unavailable with the offer). The
 * largest packs, which the offer page does not list by default, count only where the matrix
 * prices them. Packs with no bonus are left out.
 */
public final class PersonalOfferSteps {

    /** From {@code monthlyCredits} a month up, at least {@code bonusCredits} on the first payment. */
    public record Step(int monthlyCredits, int bonusCredits) {}

    /** The plans a personal offer applies to. */
    private static final List<String> PLANS = List.of("STARTER", "PRO", "TEAM");
    /**
     * The largest pack the offer page lists by default (1,000,000 a month): the frontend's
     * DEFAULT_MAX_TIER_INDEX. Bigger packs can still be reached (a link carrying one), so they
     * count, but only where the matrix has cells for them.
     */
    public static final int OFFERED_MAX_TIER_INDEX = 7;

    private PersonalOfferSteps() { }

    /** The steps, smallest pack first; empty when the matrix gives no bonus anyone can count on. */
    public static List<Step> of(List<PersonalOfferMatrix> rows) {
        if (rows == null || rows.isEmpty()) return List.of();
        // The bonus each plan gives at each pack; a missing cell gives none.
        Map<String, Integer> cell = new HashMap<>();
        for (PersonalOfferMatrix row : rows) {
            cell.put(row.getPlanCode() + ":" + row.getMonthlyCredits(), Math.max(0, row.getBonusCredits()));
        }
        // The bonus every plan selling a pack gives at it (Starter sells the smaller packs only).
        TreeMap<Integer, Integer> everyPlan = new TreeMap<>();
        for (int tier = 0; tier < CreditTierConstants.CREDIT_TIERS.length; tier++) {
            int pack = CreditTierConstants.CREDIT_TIERS[tier];
            boolean hidden = tier > OFFERED_MAX_TIER_INDEX;
            int least = Integer.MAX_VALUE;
            for (String plan : PLANS) {
                if ("STARTER".equals(plan) && tier > CreditTierConstants.STARTER_MAX_TIER_INDEX) continue;
                Integer bonus = cell.get(plan + ":" + pack);
                // A listed pack without a cell gives nothing; a hidden one is only read where priced.
                if (bonus == null && hidden) continue;
                least = Math.min(least, bonus == null ? 0 : bonus);
            }
            if (least != Integer.MAX_VALUE) everyPlan.put(pack, least);
        }
        // From each pack up: the least any bigger pack gives too.
        TreeMap<Integer, Integer> atLeast = new TreeMap<>();
        int floor = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> pack : everyPlan.descendingMap().entrySet()) {
            floor = Math.min(floor, pack.getValue());
            atLeast.put(pack.getKey(), floor);
        }
        List<Step> steps = new ArrayList<>();
        int previous = 0;
        for (Map.Entry<Integer, Integer> step : atLeast.entrySet()) {
            if (step.getValue() <= previous) continue;
            steps.add(new Step(step.getKey(), step.getValue()));
            previous = step.getValue();
        }
        return List.copyOf(steps);
    }
}
