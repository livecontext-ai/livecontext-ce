package com.apimarketplace.common.recentactivity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Per-downstream response shape for the recent-activity fan-out.
 * Bundles the active-scope items + the peer-scope count in ONE round-trip
 * (versus a naive design with 8 RPCs - 4 list + 4 count).
 *
 * <p>{@code peerScopeCount} semantics (mirrors how the aggregator uses it):
 * <ul>
 *   <li>active = org workspace ({@code orgId != null}) → peer = personal of
 *       the caller. {@code peerScopeCount = COUNT(*)} of personal rows for
 *       this user in the downstream's table, capped at 50.</li>
 *   <li>active = personal workspace ({@code orgId == null}) → peer = "any
 *       org you're in" is intentionally not aggregated. The frontend's
 *       empty-state shows the true first-run CTA in this case, never a
 *       cross-org hint. Downstream emits {@code peerScopeCount = 0}.</li>
 * </ul>
 *
 * <p>Why cap at 50? The number is shown to the user as "You have N items
 * in Personal" - past a few dozen, "many" is more useful than "127". The
 * cap also bounds the COUNT(*) query cost on the peer-scope partial index.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecentActivityScopeResultDto(
        List<RecentActivityItemDto> items,
        int peerScopeCount
) {
}
