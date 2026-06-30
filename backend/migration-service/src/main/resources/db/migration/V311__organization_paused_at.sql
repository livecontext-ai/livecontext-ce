-- V311: explicit "paused" state for over-cap workspaces after a plan downgrade.
--
-- When a user downgrades to a plan whose max_workspaces (V308) is SMALLER than
-- the number of workspaces they OWN, the excess workspaces are PAUSED (not
-- deleted): the owner keeps their personal workspace + the (cap-1) OLDEST
-- non-personal ones active, and loses access to the rest until they re-upgrade
-- (reconciliation un-pauses up to the new cap on every plan change). Data is
-- fully retained.
--
-- This is OWNER-facing and orthogonal to the existing COMPUTED "dormant team"
-- state (a non-owner member whose owner is no longer team-capable). The two are
-- OR-ed in PlanResolutionService.resolvePausedOrgIds / canMemberActInOrg, which
-- the /me list, the gateway resolve, and the setDefault gate all funnel through.
--
-- NULL = active. Non-null = the instant the workspace was paused (audit). The
-- personal workspace is NEVER paused. Reconciliation in SubscriptionService /
-- AdminPlanService maintains this column on every plan change.

ALTER TABLE auth.organization
    ADD COLUMN IF NOT EXISTS paused_at TIMESTAMP;

COMMENT ON COLUMN auth.organization.paused_at IS
    'Non-null = workspace paused (over the plan workspace cap after a downgrade); the owner regains access on re-upgrade via reconciliation. NULL = active. Personal workspaces are never paused.';
