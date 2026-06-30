package com.apimarketplace.common.plan;

import java.util.Optional;

/**
 * Lets the entitlement layer (auth-service {@code PlanLimitService}) ask whether a CE
 * (self-hosted) install's plan is governed by a bound cloud account rather than its own
 * local subscription - the consultation half of the CE↔Cloud pricing delegation.
 *
 * <p>Mirrors the {@code CloudLlmRuntimeAccess} pattern: the only implementation is a
 * CE-side adapter (publication-service, {@code marketplace.mode=remote}) that resolves the
 * cloud link and fetches the cloud account's plan. In the cloud deployment no bean exists,
 * so {@code PlanLimitService} sees {@code null} and keeps its current local-plan behaviour
 * unchanged - this interface is inert on the cloud side by construction.
 */
public interface CloudPlanAccess {

    /**
     * @param tenantId the CE user/tenant id (the cloud link is keyed by it)
     * @return the cloud account's plan code that should govern this install, present ONLY
     *         when the install is CLOUD-sourced, registered, and the cloud returns a usable
     *         plan; empty otherwise (BYOK, unlinked, unregistered, or cloud unreachable) so
     *         the caller fails safe to the local plan rather than stripping entitlements
     */
    Optional<String> governingPlanCode(Long tenantId);
}
