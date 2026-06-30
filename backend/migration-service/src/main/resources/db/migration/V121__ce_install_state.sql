-- CE (Community Edition) install state - singleton row tracking whether a
-- self-hosted deployment has completed its first-run wizard (/ce-setup).
--
-- Why
-- ---
-- The wizard used to track completion in localStorage only
-- (lc_ce_setup_done), which meant:
--   * admins who cleared their browser re-saw the wizard on a
--     fully-configured install;
--   * admins logging in from a second device were prompted again;
--   * there was no server-side record that the install was ever bootstrapped.
--
-- A DB-backed flag makes the state survive browsers and devices. Singleton
-- pattern (id = 1 always) because CE is single-install, single-realm -
-- there is no tenancy to multiplex over.
--
-- GET /api/ce/status     (public, gateway-allowlisted) reads this row.
-- POST /api/ce/complete  (admin-only) flips bootstrapped = true.
--
-- Frontend guard (CeFirstLoginGuard) additionally self-migrates existing
-- installs: when DB says false but localStorage flag is set, it POSTs
-- /api/ce/complete in the background so no admin is re-prompted.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO auth;

CREATE TABLE IF NOT EXISTS ce_install_state (
    id                    SMALLINT     PRIMARY KEY DEFAULT 1,
    bootstrapped          BOOLEAN      NOT NULL    DEFAULT FALSE,
    bootstrapped_at       TIMESTAMPTZ,
    bootstrap_admin_id    BIGINT,
    version               VARCHAR(32)  NOT NULL    DEFAULT 'v1',
    updated_at            TIMESTAMPTZ  NOT NULL    DEFAULT NOW(),
    CONSTRAINT ce_install_state_singleton CHECK (id = 1)
);

INSERT INTO ce_install_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

COMMENT ON TABLE ce_install_state IS
    'Singleton row (id=1) recording whether a CE install completed its first-run wizard. Public GET /api/ce/status reads it; POST /api/ce/complete writes it. Singleton is enforced via CHECK (id = 1).';
COMMENT ON COLUMN ce_install_state.bootstrapped IS
    'True once an admin has clicked Finish on /ce-setup. Used by CeFirstLoginGuard to decide whether to redirect.';
COMMENT ON COLUMN ce_install_state.bootstrap_admin_id IS
    'auth.users.id of the admin who first completed the wizard. Not a declared FK (users row could be soft-deleted later) - audit only.';
