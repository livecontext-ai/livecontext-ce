-- V308: per-plan workspace cap (shared-wallet model).
--
-- A user may OWN up to max_workspaces organizations (counting their personal one).
-- All owned workspaces draw from the owner's single credit wallet (owner-pays / ADR-009),
-- so this is purely an organizational cap, not a billing multiplier. NULL = unlimited.
--
--   FREE / STARTER : 1   (personal only - cannot create extra workspaces)
--   PRO            : 3
--   TEAM           : 10
--   ENTERPRISE_*   : NULL (unlimited)

ALTER TABLE auth.plan
    ADD COLUMN IF NOT EXISTS max_workspaces INTEGER;

UPDATE auth.plan SET max_workspaces = 1    WHERE code IN ('FREE', 'STARTER');
UPDATE auth.plan SET max_workspaces = 3    WHERE code = 'PRO';
UPDATE auth.plan SET max_workspaces = 10   WHERE code = 'TEAM';
UPDATE auth.plan SET max_workspaces = NULL WHERE code LIKE 'ENTERPRISE%';

COMMENT ON COLUMN auth.plan.max_workspaces IS
    'Max organizations a user may own (incl. their personal one). NULL = unlimited. Shared-wallet model - workspaces are organizational containers, billing stays owner-level.';
