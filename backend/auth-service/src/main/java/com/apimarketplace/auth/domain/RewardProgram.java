package com.apimarketplace.auth.domain;

/**
 * The kind of reward-code program. One {@link RewardCode} row is exactly one of
 * these, and the program drives which benefit/reward policy columns apply.
 *
 * <ul>
 *   <li>{@code PROMO} open marketing code, no owner, immediate redeemer benefit.</li>
 *   <li>{@code REFERRAL} personal code, owner is the user themself, both parties
 *       rewarded on the redeemer's paid conversion.</li>
 *   <li>{@code PARTNER} campaign code owned by an influencer/partner; owner payout
 *       is recorded in v1 and paid out in v2.</li>
 * </ul>
 */
public enum RewardProgram {
    PROMO,
    REFERRAL,
    PARTNER
}
