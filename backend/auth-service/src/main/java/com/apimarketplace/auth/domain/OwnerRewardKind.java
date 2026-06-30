package com.apimarketplace.auth.domain;

/**
 * What the code owner earns when a redeemer converts.
 *
 * <ul>
 *   <li>{@code NONE} no owner reward (PROMO).</li>
 *   <li>{@code CREDIT_GRANT} PAYG credits to the owner (REFERRAL).</li>
 *   <li>{@code PARTNER_PAYOUT} a revenue-share payout recorded in v1, paid out in
 *       v2 via the partner payout seam.</li>
 * </ul>
 */
public enum OwnerRewardKind {
    NONE,
    CREDIT_GRANT,
    PARTNER_PAYOUT
}
