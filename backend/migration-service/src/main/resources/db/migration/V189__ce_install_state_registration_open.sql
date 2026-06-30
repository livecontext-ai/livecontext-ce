-- CE registration close-after-bootstrap door.
--
-- Why
-- ---
-- Once an admin has completed the /ce-setup wizard, /api/auth/register stays
-- permanently public - any visitor who reaches the box can still sign up
-- (gets USER role only; FirstAdminBootstrap closes the admin-promotion path
-- in d6eb8556b). For a self-hosted box, that attack surface should not stay
-- open by default. The wizard now also closes registration on completion;
-- the admin can re-open via PUT /api/ce/registration.
--
-- Upgrade-safety
-- --------------
-- Default TRUE makes fresh installs work (registration is open until wizard
-- runs). But existing already-bootstrapped CE deploys MUST not silently
-- re-open their door for one upgrade window - so we backfill closed for any
-- row where bootstrapped=true. Atomic in a single Flyway script: the UPDATE
-- runs in the same transaction as the ALTER, so there is no race window
-- where a bootstrapped install briefly accepts new signups.

SET lock_timeout = '10s';
SET statement_timeout = '60s';

ALTER TABLE auth.ce_install_state
    ADD COLUMN IF NOT EXISTS registration_open BOOLEAN NOT NULL DEFAULT TRUE;

UPDATE auth.ce_install_state
   SET registration_open = NOT bootstrapped
 WHERE id = 1;

COMMENT ON COLUMN auth.ce_install_state.registration_open IS
    'Controls /api/auth/register acceptance in embedded auth mode. Flips false when an admin POSTs /api/ce/complete (wizard finish). Admins can re-open via PUT /api/ce/registration. Cloud deployments ignore this column.';
