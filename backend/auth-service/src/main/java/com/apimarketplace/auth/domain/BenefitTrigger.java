package com.apimarketplace.auth.domain;

/**
 * When a benefit/reward is delivered.
 *
 * <ul>
 *   <li>{@code REDEEM_TIME} granted immediately when the code is redeemed
 *       (the promo free-node window).</li>
 *   <li>{@code PAID_CONVERSION} granted only after the redeemer's first paid
 *       subscription is captured (referral and partner), subject to the hold.</li>
 * </ul>
 */
public enum BenefitTrigger {
    REDEEM_TIME,
    PAID_CONVERSION
}
