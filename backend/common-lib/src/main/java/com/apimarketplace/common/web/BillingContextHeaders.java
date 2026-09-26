package com.apimarketplace.common.web;

import java.util.Set;

/**
 * Headers the platform writes about its OWN calls and a downstream service then
 * reads as fact, which is why none of them may arrive from a client.
 *
 * <p>The costly half decides how much a call costs. A caller able to set those
 * could name a cheap model, a quantity of one for a ten second video, a unit
 * that makes its call look small against the published rate, or an existing
 * billing scope whose pin bypasses the delinquent-account refusal.
 *
 * <p>The quieter half says WHO made the call: {@code X-Lc-Workflow-Id} and
 * {@code X-Lc-Node-Id} attribute a catalogue execution to the workflow node that
 * asked for it, and nothing else carries that (a step id is deliberately not
 * reused here, because it shapes the billing ledger key). Forging them costs
 * nobody money, so they sat outside this list for a while; what they buy is the
 * ability to write rows into another workspace's analytics, which is somebody
 * else's dashboard describing traffic they never sent. The rule that catches
 * both is the same one, and it is the rule the guard states: a value a service
 * trusts must come from the platform, and the only way to know it did is to
 * strip whatever the client sent.
 *
 * <p>The internal callers are unaffected: the orchestrator reaches catalog-service
 * directly on {@code orchestrator.catalog.base-url} and never traverses an edge
 * that strips, so only a client coming in through {@code /catalog/v1/**} loses
 * the ability to set them.
 *
 * <p><b>Every edge that fronts {@code /catalog/v1/**} has to strip them, not
 * just the one that was thought of first.</b> The list lives here, in the
 * module both edges already depend on, because the cloud gateway and the CE
 * monolith are two doors into the same endpoint: a set restated in one of them
 * is a set that drifts, and the half that drifts is the half that stops
 * stripping. The DTO fields are additionally {@code @JsonIgnore} so the request
 * BODY cannot carry them either; a header strip alone would close one door of
 * two.
 */
public final class BillingContextHeaders {

    private BillingContextHeaders() {}

    /** Lowercase-insensitive at every comparison site. */
    public static final Set<String> ALL = Set.of(
            // What a call costs.
            "X-Lc-Billing-Scope-Kind",
            "X-Lc-Billing-Scope-Id",
            "X-Lc-Billing-Step-Id",
            "X-Lc-Generation-Model",
            "X-Lc-Generation-Quantity",
            "X-Lc-Generation-Unit",
            // What the call's own choices do to its price. A caller able to set
            // it could send 0 and multiply any generation down to nothing,
            // which is the same hole as a quantity of one for a ten second clip.
            "X-Lc-Generation-Multiplier",
            // Who made it. Read by CatalogV1Controller into the execution request's
            // analytics fields; the shape check there rejects malformed values but has
            // no way to tell a forged well-formed id from a real one.
            "X-Lc-Workflow-Id",
            "X-Lc-Node-Id",
            // How the result is shaped: marks a workflow STEP's output, which lifts the
            // catalog's 4 KB text clip to 1 MB. Only StepNode / FindNode may say so.
            "X-Lc-Step-Output"
    );

    /**
     * The largest price factor any door may act on.
     *
     * <p><b>One number, because three services enforce it and a ceiling that
     * disagrees with itself is worse than none.</b> The descriptor parser
     * refuses a model whose modifiers can reach more than this TOGETHER, the
     * two quote endpoints drop a factor above it rather than showing it, and
     * the charging path refuses one. Written here, in the module all of them
     * already depend on for the header name itself, because the bound belongs
     * to the value that header carries.
     *
     * <p>Restated per service, the failure is silent and expensive: raise it in
     * the parser to admit a legitimate modifier and the quote screens keep
     * dropping the factor, so a reader is shown the published rate and charged
     * the surcharge, with the disagreement visible only on an invoice.
     *
     * <p>It is a typo guard, not a business ceiling: a missing decimal point
     * turns a 1.5x surcharge into 15x on a call already quoted at the lower
     * figure. The clamps on the published price row are the business ceiling
     * and stay the only one that binds a real price.
     */
    public static final java.math.BigDecimal MAX_GENERATION_MULTIPLIER =
            java.math.BigDecimal.valueOf(100);

    /**
     * The factor a door is willing to act on, or {@code null} for "nothing said
     * anything about it", which is how every path behaved before factors existed.
     *
     * <p>Non-positive is dropped rather than honoured: zero would multiply a
     * whole charge away and a negative one is not a price. Above the ceiling is
     * dropped because no descriptor this platform accepts can produce it.
     */
    public static java.math.BigDecimal sanitizeGenerationMultiplier(java.math.BigDecimal raw) {
        if (raw == null || raw.signum() <= 0) {
            return null;
        }
        return raw.compareTo(MAX_GENERATION_MULTIPLIER) > 0 ? null : raw;
    }

    /** True when {@code name} is one of them, whatever its casing. */
    public static boolean contains(String name) {
        if (name == null) {
            return false;
        }
        return ALL.stream().anyMatch(header -> header.equalsIgnoreCase(name));
    }
}
