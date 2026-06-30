package com.apimarketplace.common.web;

/**
 * Shared plan-limit constants for user-creatable resources (webhooks,
 * schedules, forms, chat endpoints, shared links, …).
 *
 * <p>{@link #UNLIMITED} is the sentinel value returned when quota enforcement
 * is disabled (CE mode, or future Enterprise-Unlimited cloud tiers). It is
 * kept at {@code 9999} rather than {@link Integer#MAX_VALUE} for two reasons:
 * <ul>
 *   <li>The frontend renders this as "Unlimited" via a sentinel comparison
 *       contract that predates this module (legacy compat).</li>
 *   <li>It is a sanity cap - the spec says "effectively unlimited but with a
 *       hard ceiling so a stuck client cannot create infinite resources".</li>
 * </ul>
 *
 * <p>Edition detection is the responsibility of {@link AppEditionProvider};
 * this class is intentionally edition-agnostic so non-edition runtime
 * predicates (e.g. {@code plan-limits.enabled} kill-switch, future paid-tier
 * unlimited SKUs) can reuse the sentinel without going through the edition.
 */
public final class PlanLimits {

    /**
     * Sentinel returned when quota enforcement is disabled. Frontend renders
     * this as "Unlimited". Do not change without updating the rendering
     * contract in {@code frontend/.../storage/page.tsx} and friends.
     */
    public static final int UNLIMITED = 9999;

    private PlanLimits() {
        // No instances - constants holder only.
    }
}
