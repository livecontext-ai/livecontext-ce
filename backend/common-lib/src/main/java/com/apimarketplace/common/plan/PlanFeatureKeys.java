package com.apimarketplace.common.plan;

/**
 * Feature keys of {@code auth.plan_feature_requirement} that more than one module has to name.
 *
 * <p>A key is a string in a table an admin edits, so nothing fails when two modules spell it
 * differently: the gate simply finds no row and lets everyone through. That silence is why the
 * shared ones live here instead of being retyped. Keys used by a single module stay in that
 * module.
 */
public final class PlanFeatureKeys {

    /**
     * Running on the tenant's OWN provider key. Seeded at PRO by {@code V507}; read by the run
     * (auth-client {@code OwnKeyFeatureGate}, which pins the route) and by the price a picker
     * quotes before the run (auth-service {@code LlmCostEstimateService}). The two must answer
     * the same question or the quote is for a route the run will not take.
     */
    public static final String OWN_LLM_KEY = "feature:own_llm_key";

    private PlanFeatureKeys() {
    }
}
