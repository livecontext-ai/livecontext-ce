package com.apimarketplace.common.plan;

/**
 * What a cloud account may do through ONE CE cloud link (install id), as decided by
 * auth-service and read by every cloud endpoint a linked self-hosted install calls.
 *
 * <p>Any plan, FREE included, may LINK an install: register, heartbeat and the model and
 * skill bundles only need a link ({@link CeLinkAccessResult#isLinked}). What spends cloud
 * money (the LLM, web-search and catalog relays) additionally needs a paid plan
 * ({@link PlanTier#isPaid}): a linked account that is not paid answers {@link #PLAN_REQUIRED}
 * there, keeps its link, and answers {@link #ACTIVE} as soon as it pays, with no re-link.
 */
public enum CeLinkAccess {
    /** The caller owns an ACTIVE link to the install and its governing plan is paid. */
    ACTIVE,
    /** No ACTIVE link to that install in the caller's namespace (unknown, foreign, revoked, malformed). */
    NOT_LINKED,
    /** The link is ACTIVE but the governing plan is not paid: linked, but no paid relay. */
    PLAN_REQUIRED
}
