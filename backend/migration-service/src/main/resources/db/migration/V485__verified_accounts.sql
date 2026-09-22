-- Verified accounts (the blue check next to a public name).
--
-- Two independent sources feed the flag, resolved in VerifiedAccountService:
--   1. the platform ADMIN role - always verified, nothing stored here;
--   2. this column - the manual grant an admin makes for anyone else.
-- No backfill is needed for (1): the role IS the source, so today's admins and
-- tomorrow's are both covered without a row.
--
-- The badge is a MANAGED-CLOUD feature. A self-hosted install makes its first
-- user an admin (FirstAdminBootstrap), so deriving a badge from the role there
-- would decorate every solo install owner with a meaningless seal. The column
-- still ships to CE so the schema stays identical across editions; the service
-- gate is what keeps it dark.

ALTER TABLE auth.user_profiles
    ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE auth.user_profiles
    ADD COLUMN IF NOT EXISTS verified_at TIMESTAMP;

-- Who granted it, for audit correlation. Deliberately NOT a foreign key: an
-- admin account may be deleted long after the grant, and losing the trail
-- would be worse than pointing at an id that no longer resolves.
ALTER TABLE auth.user_profiles
    ADD COLUMN IF NOT EXISTS verified_by BIGINT;

COMMENT ON COLUMN auth.user_profiles.verified IS
    'Manually granted verified badge. The ADMIN role grants it implicitly and is not stored here.';
