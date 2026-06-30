package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.RewardRedemption;

/**
 * v2 seam for partner/influencer payouts (revenue-share money-out).
 *
 * <p>In v1 there is NO implementation bean, so a PARTNER conversion is RECORDED
 * (QUALIFIED then RELEASED with the owner snapshot) but no money leaves the
 * platform: the release scheduler logs the deferred payout instead. A future v2
 * provides a Cloud bean (for example Stripe Connect) that reads the v1-recorded
 * conversion history and the {@code payout_*} columns, so no backfill is needed.
 */
public interface PartnerPayoutSpi {

    /** Record/settle the owner payout for a converted PARTNER redemption. */
    void recordPayout(RewardRedemption redemption);
}
