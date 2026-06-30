package com.apimarketplace.agent.loop;

/**
 * Guard checked before each agent loop iteration.
 *
 * <p>Replaces the legacy {@code canProceed()} boolean API with a richer
 * {@link GuardResult} so that guards can communicate <em>why</em> they stopped
 * the run (budget, loop, timeout, custom). Multiple guards can be combined
 * with {@link #chain(PreIterationGuard...)}; the first deny wins.</p>
 *
 * <p>Implementations are stateless or own their own state - the {@code IterationContext}
 * carries only running execution data. Implementations must be safe to call from a
 * single loop thread (no concurrent invocation).</p>
 */
@FunctionalInterface
public interface PreIterationGuard {

    /**
     * Decide whether the upcoming iteration may execute.
     *
     * @param context snapshot of the loop state at this point in time
     * @return {@link GuardResult#allow()} to continue, or a {@code deny(...)} result to stop
     */
    GuardResult check(IterationContext context);

    /** Default guard that always allows execution. */
    PreIterationGuard ALWAYS_PROCEED = ctx -> GuardResult.allow();

    /**
     * Compose any number of guards into one. Guards are evaluated in order;
     * the first guard that denies short-circuits the chain. Null entries are
     * ignored, and an empty/all-null array yields {@link #ALWAYS_PROCEED}.
     *
     * <p>Use this to layer concerns, e.g. {@code chain(tenantGuard, agentGuard, loopGuard)}.</p>
     */
    static PreIterationGuard chain(PreIterationGuard... guards) {
        if (guards == null || guards.length == 0) {
            return ALWAYS_PROCEED;
        }
        // Filter nulls once at construction time.
        int nonNull = 0;
        for (PreIterationGuard g : guards) {
            if (g != null) nonNull++;
        }
        if (nonNull == 0) return ALWAYS_PROCEED;
        if (nonNull == 1) {
            for (PreIterationGuard g : guards) {
                if (g != null) return g;
            }
        }
        final PreIterationGuard[] chain = new PreIterationGuard[nonNull];
        int i = 0;
        for (PreIterationGuard g : guards) {
            if (g != null) chain[i++] = g;
        }
        return ctx -> {
            for (PreIterationGuard g : chain) {
                GuardResult r = g.check(ctx);
                if (r != null && !r.proceed()) {
                    return r;
                }
            }
            return GuardResult.allow();
        };
    }
}
