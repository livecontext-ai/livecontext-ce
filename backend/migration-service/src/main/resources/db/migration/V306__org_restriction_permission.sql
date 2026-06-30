-- V306: per-member org resource restrictions gain a permission level.
--
-- Until now a row in auth.org_resource_restrictions meant "fully blocked".
-- We add a `permission` level so a member can be granted READ-ONLY access to a
-- resource (currently used for files): the member can still see / preview /
-- download it but cannot delete / assign / modify it.
--
--   no row        -> full access (unchanged)
--   permission=DENY -> fully blocked (default, legacy behaviour)
--   permission=READ -> read-only (visible + downloadable, write blocked)

ALTER TABLE auth.org_resource_restrictions
    ADD COLUMN IF NOT EXISTS permission VARCHAR(20) NOT NULL DEFAULT 'DENY';

ALTER TABLE auth.org_resource_restrictions
    DROP CONSTRAINT IF EXISTS chk_orr_permission;
ALTER TABLE auth.org_resource_restrictions
    ADD CONSTRAINT chk_orr_permission CHECK (permission IN ('DENY', 'READ'));

COMMENT ON COLUMN auth.org_resource_restrictions.permission IS
    'DENY = fully blocked (default, legacy); READ = read-only (visible + downloadable, not writable). No row = full access.';
