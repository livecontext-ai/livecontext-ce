package com.apimarketplace.auth.client.dto;

/**
 * Subscription entitlements of the cloud account owning a CE link, as returned
 * by {@code GET /api/internal/auth/ce-link/{installId}/entitlements}.
 *
 * <p>Consumed by the cloud-side CE catalog relay to gate relayed tool
 * executions on an ACTIVE PAID subscription. {@link #hasSubscription} is the
 * server-computed verdict; {@link #planCode} is informational (surfaced to the
 * CE for upsell copy). {@code "__NONE__"} mirrors the auth-service
 * no-subscription sentinel.
 *
 * @param planCode        the linked account's governing plan code, or
 *                        {@code "__NONE__"} when none.
 * @param hasSubscription true only when the linked account has an active paid
 *                        (non-FREE) subscription.
 */
public record CeLinkEntitlementsResult(String planCode, boolean hasSubscription) {

    /** Auth-service sentinel for "no active subscription". */
    public static final String NO_SUBSCRIPTION = "__NONE__";

    /** Fail-closed shape used on transport failure, non-2xx, or malformed input. */
    public static CeLinkEntitlementsResult none() {
        return new CeLinkEntitlementsResult(NO_SUBSCRIPTION, false);
    }
}
