-- V319: a public @handle for the in-app profile.
-- Lets a profile be addressed by a chosen, URL-safe handle (/app/u/{handle}) and shown as
-- @handle - instead of the numeric tenant/user id (sensitive) and without ever exposing the
-- real first/last name. Derived from the display name by default, editable, unique.
-- Generated lazily by the app (so it can dedupe + slugify); the column is therefore nullable,
-- with a PARTIAL unique index so the many not-yet-generated rows can coexist as NULL.
ALTER TABLE auth.user_profiles ADD COLUMN IF NOT EXISTS handle VARCHAR(32);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_profiles_handle
    ON auth.user_profiles (handle) WHERE handle IS NOT NULL;
