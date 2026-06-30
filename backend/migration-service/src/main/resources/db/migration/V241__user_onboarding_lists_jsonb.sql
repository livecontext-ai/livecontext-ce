-- Align user_onboarding list columns with the Hibernate List<String> mapping.
-- Older schemas created these columns as text[], while Hibernate writes List
-- values as jsonb. Convert existing arrays without losing values.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'user_onboarding'
          AND column_name = 'interests'
          AND udt_name = '_text'
    ) THEN
        ALTER TABLE auth.user_onboarding
            ALTER COLUMN interests DROP DEFAULT;

        ALTER TABLE auth.user_onboarding
            ALTER COLUMN interests TYPE jsonb
            USING to_jsonb(COALESCE(interests, ARRAY[]::text[]));

        ALTER TABLE auth.user_onboarding
            ALTER COLUMN interests SET DEFAULT '[]'::jsonb;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'auth'
          AND table_name = 'user_onboarding'
          AND column_name = 'use_cases'
          AND udt_name = '_text'
    ) THEN
        ALTER TABLE auth.user_onboarding
            ALTER COLUMN use_cases DROP DEFAULT;

        ALTER TABLE auth.user_onboarding
            ALTER COLUMN use_cases TYPE jsonb
            USING to_jsonb(COALESCE(use_cases, ARRAY[]::text[]));

        ALTER TABLE auth.user_onboarding
            ALTER COLUMN use_cases SET DEFAULT '[]'::jsonb;
    END IF;
END $$;
