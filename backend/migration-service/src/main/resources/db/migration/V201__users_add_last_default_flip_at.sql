-- PR11d-b - rate-limit setDefaultOrganization flips.
--
-- Audit B 2026-05-12 MUST-FIX #2: a member belonging to TEAM-A (cap=100)
-- and TEAM-B (cap=1000) can flip default to B mid-consume to bypass A's
-- cap. While each org's cap is per-org-membership by design, RAPID flips
-- (2 API calls in <100ms) let an attacker juggle wallets across multiple
-- TEAM orgs they're invited to. The fix is a cooldown between flips -
-- not a hard block (that would break legitimate workspace-switching) but
-- enough to make scripted-bypass non-viable.
--
-- Stored as a per-user timestamp because the rate limit applies to the
-- USER, not to a specific membership. NULL = never flipped (or pre-V201).
-- Default cooldown is configured at the service layer via env
-- AUTH_ORG_SETDEFAULT_COOLDOWN_SECONDS (default 60 seconds - generous
-- enough for legitimate UI clicks, tight enough to defeat scripted abuse).

ALTER TABLE auth.users
    ADD COLUMN last_default_flip_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN auth.users.last_default_flip_at IS
    'PR11d-b - timestamp of the most-recent setDefaultOrganization flip '
    'by this user. NULL for users who never switched workspaces. Used by '
    'OrganizationController to enforce a per-user cooldown (default 60s, '
    'overridable via AUTH_ORG_SETDEFAULT_COOLDOWN_SECONDS) on rapid '
    'workspace flips, preventing scripted bypass of per-member quota caps '
    'that are keyed on the default workspace at consume time.';
