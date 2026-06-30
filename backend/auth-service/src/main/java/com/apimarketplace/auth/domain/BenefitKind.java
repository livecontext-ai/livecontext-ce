package com.apimarketplace.auth.domain;

/**
 * What a redeemer receives.
 *
 * <ul>
 *   <li>{@code FREE_NODE_COUNTER} a time-boxed, per-account counter of free
 *       workflow-node executions (the legacy promo benefit, preserved verbatim).</li>
 *   <li>{@code CREDIT_GRANT} a one-shot grant of PAYG credits via
 *       {@code CreditService.grantCredits} (referral and partner).</li>
 * </ul>
 */
public enum BenefitKind {
    FREE_NODE_COUNTER,
    CREDIT_GRANT
}
