ALTER TABLE auth.platform_credentials
    ADD COLUMN IF NOT EXISTS show_unverified_app_warning BOOLEAN NOT NULL DEFAULT TRUE;
