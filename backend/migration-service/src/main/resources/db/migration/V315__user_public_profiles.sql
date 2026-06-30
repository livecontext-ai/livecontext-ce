-- V315: public user profiles for the public-profile (Instagram-style) feature.
-- 1:1 with auth.users (shared primary key). Kept SEPARATE from user_onboarding so
-- editing a public profile (bio / website / social links / visibility) never
-- mutates the onboarding lifecycle (completed/skipped/step + display-name
-- uniqueness checks). Created lazily the first time a user edits their profile.
CREATE TABLE IF NOT EXISTS auth.user_profiles (
    user_id            BIGINT       PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    bio                VARCHAR(500),
    website_url        VARCHAR(255),
    social_links       JSONB        NOT NULL DEFAULT '[]'::jsonb,
    profile_visibility VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT NOW()
);
