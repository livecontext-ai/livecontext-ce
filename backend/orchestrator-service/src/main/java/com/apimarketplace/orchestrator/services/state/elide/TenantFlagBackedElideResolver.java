package com.apimarketplace.orchestrator.services.state.elide;

import com.apimarketplace.orchestrator.services.flag.TenantFlagService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * {@link TenantElideFlagResolver} backed by the real {@link TenantFlagService}
 * (P2.3.3 deliverable). Replaces {@link DefaultTenantElideFlagResolver}
 * via {@link Primary} when both beans are present.
 *
 * <p>Cost: O(1) - delegates to {@link TenantFlagService#getValue(String, String)}
 * which reads from a {@code ConcurrentHashMap}. No I/O on the hot path.
 *
 * <p><strong>Default ON</strong> (since 2026-05-08, P2.3 chain complete): when no
 * tenant_flags row exists for {@code (state-snapshot.elide-running-nodes, tenantId)},
 * elide is treated as enabled. {@code runningNodeIds} should never land in
 * JSONB anymore - the only authoritative source for "this node is running"
 * is Redis ({@code RunningNodeTracker}). Tenants opt out by inserting an
 * explicit {@code value=false} row via
 * {@link TenantFlagService#flip(String, String, boolean, String, String)}.
 *
 * <p>Constant {@link #FLAG_NAME} pins the flag identifier used elsewhere in the
 * codebase: {@code state-snapshot.elide-running-nodes}.
 */
@Component
@Primary
public class TenantFlagBackedElideResolver implements TenantElideFlagResolver {

    public static final String FLAG_NAME = "state-snapshot.elide-running-nodes";

    private final TenantFlagService flagService;

    public TenantFlagBackedElideResolver(TenantFlagService flagService) {
        this.flagService = flagService;
    }

    @Override
    public boolean isElideEnabled(String tenantId) {
        return flagService.getValue(FLAG_NAME, tenantId).orElse(true);
    }
}
