package com.apimarketplace.common.recentactivity;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Wrapper response for {@code GET /api/activities/recent} - the top-50
 * {@link RecentActivityItemDto} rows for the user's active workspace plus
 * a cross-scope hint for the empty-state copy.
 *
 * <p>{@code peerScopeCount} = {@code COUNT(*)} (capped at the same 50-row
 * limit) of items the user has in the OTHER scope (personal when in an
 * org workspace, vice-versa). Lets the frontend render the 3-state
 * empty-state:
 *
 * <ul>
 *   <li>{@code items.size() > 0} → render the list as usual</li>
 *   <li>{@code items.isEmpty() && peerScopeCount == 0} → true first-run
 *       CTA ("Create your first workflow")</li>
 *   <li>{@code items.isEmpty() && peerScopeCount > 0} → cross-scope hint
 *       ("Nothing recent in {currentScope}. You have {peerScopeCount} items
 *       in {peerScopeLabel} - [Switch workspace]"). Resolves the v2 audit
 *       gap where a misleading first-run CTA appeared on users with data
 *       in another workspace.</li>
 * </ul>
 *
 * <p>{@code peerScopeLabel} is the friendly name of the OTHER scope - when
 * the user is in an org workspace this is the literal {@code "Personal"}
 * (the frontend may localize via i18n key). When the user is in personal
 * scope, {@code peerScopeCount = 0} and {@code peerScopeLabel = null} -
 * "All workspaces" view is intentionally not aggregated, mirrors the
 * Triggers tab precedent per auditor C v6.
 *
 * <p><b>Why no {@code peerScopeOrgId} / {@code peerScopeRole}</b>: the
 * frontend's {@code [Switch workspace]} CTA only fires when the user is in
 * org workspace AND the peer is Personal. Switching to Personal calls
 * {@code useCurrentOrgStore.clear()} - no orgId/role required.
 * Counter-direction (personal → org) is not supported (auditor C
 * deferred-decision note).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RecentActivityResponseDto(
        List<RecentActivityItemDto> items,
        int peerScopeCount,
        String peerScopeLabel
) {
}
