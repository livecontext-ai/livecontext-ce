package com.apimarketplace.orchestrator.services.state.elide;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default implementation of {@link TenantElideFlagResolver} that always returns
 * false - i.e. the elide flag is OFF for every tenant.
 *
 * <p>P2.3 deliverable: ships a working tenant-flag plumbing with a safe
 * default-OFF behavior. The runtime impact is zero - the elide serializer is
 * registered but the runningNodeIds field is always emitted (identical to
 * pre-P2.3 JSONB shape). The real per-tenant flag wiring (P2.3.3) will replace
 * this bean via {@code @ConditionalOnMissingBean} fallback semantics.
 *
 * <p>Cost contract: O(1), no I/O, no allocation. Satisfies the per-write hot-path
 * latency budget (audit B C5).
 *
 * <p>To wire the real per-tenant resolver, create a competing {@code @Component}
 * implementing {@link TenantElideFlagResolver} that consults the orchestrator's
 * {@code kernel.runtime.flags} system; this default bean steps aside via
 * {@code @ConditionalOnMissingBean}.
 */
@Component
@ConditionalOnMissingBean(TenantElideFlagResolver.class)
public class DefaultTenantElideFlagResolver implements TenantElideFlagResolver {

    @Override
    public boolean isElideEnabled(String tenantId) {
        return false;
    }
}
