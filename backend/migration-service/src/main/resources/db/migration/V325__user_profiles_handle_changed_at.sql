-- @handle changes now follow the same 1-change-per-week cooldown as the display name
-- (auth.user_onboarding.display_name_changed_at). NULL = never explicitly changed by the
-- user (lazy auto-generation does not start the cooldown), so existing rows stay editable.
ALTER TABLE auth.user_profiles
    ADD COLUMN IF NOT EXISTS handle_changed_at TIMESTAMP NULL;
