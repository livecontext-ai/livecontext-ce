package com.apimarketplace.common.plan;

/**
 * A {@link CeLinkAccess} verdict plus the governing plan code it was decided on.
 *
 * @param access   never null
 * @param planCode the plan that governed the decision; null when the install is not linked
 *                 (no plan was consulted)
 */
public record CeLinkAccessResult(CeLinkAccess access, String planCode) {

    public CeLinkAccessResult {
        if (access == null) {
            access = CeLinkAccess.NOT_LINKED;
        }
    }

    public static CeLinkAccessResult active(String planCode) {
        return new CeLinkAccessResult(CeLinkAccess.ACTIVE, planCode);
    }

    public static CeLinkAccessResult notLinked() {
        return new CeLinkAccessResult(CeLinkAccess.NOT_LINKED, null);
    }

    public static CeLinkAccessResult planRequired(String planCode) {
        return new CeLinkAccessResult(CeLinkAccess.PLAN_REQUIRED, planCode);
    }

    /** Linked AND paid: the only state in which a link-gated call may proceed. */
    public boolean isActive() {
        return access == CeLinkAccess.ACTIVE;
    }
}
