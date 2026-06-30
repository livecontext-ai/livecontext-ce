-- V318: drop the unused website/social-link columns from auth.user_profiles.
-- The profile feature was reduced to bio + visibility - profiles are viewable IN-APP only
-- (keyed by user id; the real first/last name is never exposed, only the chosen display name).
-- V315 created these columns and is ALREADY APPLIED in production, so they are removed here in a
-- forward migration rather than by amending V315 (which would fail Flyway checksum validation).
ALTER TABLE auth.user_profiles DROP COLUMN IF EXISTS website_url;
ALTER TABLE auth.user_profiles DROP COLUMN IF EXISTS social_links;
