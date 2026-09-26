package com.apimarketplace.common.plan;

/**
 * What a cloud account may do through ONE CE cloud link (install id), as decided by
 * auth-service and read by every cloud endpoint a linked self-hosted install calls.
 *
 * <p>The rule is "linked AND paid": a self-hosted install may use the cloud relays
 * (LLM, web search, catalog, model and skill bundles) only while the account that
 * owns its link is on a paid plan ({@link PlanTier#isPaid}). A link whose account falls
 * back to FREE is SUSPENDED, never revoked: it answers {@link #PLAN_REQUIRED} until the
 * account pays again and then answers {@link #ACTIVE} with no re-link.
 */
public enum CeLinkAccess {
    /** The caller owns an ACTIVE link to the install and its governing plan is paid. */
    ACTIVE,
    /** No ACTIVE link to that install in the caller's namespace (unknown, foreign, revoked, malformed). */
    NOT_LINKED,
    /** The link is ACTIVE but the governing plan is not paid: suspended until the account pays. */
    PLAN_REQUIRED
}
