-- V267 - Hotfix for V261 §8: stored_files schema mismatch (round-13 deploy 2026-05-20)
--
-- V261 §8 used `to_regclass('public.stored_files')` because the audit round-6 read
-- V14__create_public_schema_tables.sql which created the table without a schema
-- prefix → Hibernate resolved via search_path → on a local dev DB with default
-- search_path that meant `public.stored_files`. ON PROD, however, storage-service's
-- JDBC URL has `currentSchema=storage` so the same Hibernate `@Table(name = "stored_files")`
-- resolves to `storage.stored_files`. V261's to_regclass on `public.stored_files`
-- returned NULL → the conditional block was skipped → the column never landed →
-- post-deploy 2026-05-20 lc-storage crashed on Hibernate schema-validation:
--   "missing column [organization_id] in table [stored_files]"
--
-- This file is idempotent + handles BOTH layouts:
--   - if storage.stored_files exists and lacks the column → ADD COLUMN + backfill via user_id
--   - if public.stored_files exists and lacks the column → ADD COLUMN there too (defense)
--   - if NEITHER exists → no-op
--
-- After V267 the column is present on whichever schema the running instance uses.

DO $$
DECLARE
    sch text;
    has_org boolean;
BEGIN
    FOREACH sch IN ARRAY ARRAY['storage', 'public'] LOOP
        IF to_regclass(sch || '.stored_files') IS NULL THEN
            CONTINUE;
        END IF;

        SELECT EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_schema = sch
               AND table_name = 'stored_files'
               AND column_name = 'organization_id'
        ) INTO has_org;

        IF NOT has_org THEN
            EXECUTE format('ALTER TABLE %I.stored_files ADD COLUMN organization_id VARCHAR(255)', sch);

            -- Backfill via user_id → default-org (StoredFile.user_id is a bigint FK to auth.users).
            EXECUTE format($f$
                UPDATE %I.stored_files sf
                   SET organization_id = (
                         SELECT om.organization_id::text FROM auth.organization_member om
                          WHERE om.user_id = sf.user_id AND om.is_default = true LIMIT 1)
                 WHERE sf.organization_id IS NULL
                   AND EXISTS (SELECT 1 FROM auth.organization_member om
                                WHERE om.user_id = sf.user_id AND om.is_default = true)
            $f$, sch);

            -- Any residual NULLs (user without default org membership) → assign to platform admin (user 1).
            EXECUTE format($f$
                UPDATE %I.stored_files sf
                   SET organization_id = (
                         SELECT om.organization_id::text FROM auth.organization_member om
                          WHERE om.user_id = 1 AND om.is_default = true LIMIT 1)
                 WHERE sf.organization_id IS NULL
            $f$, sch);

            EXECUTE format('ALTER TABLE %I.stored_files ALTER COLUMN organization_id SET NOT NULL', sch);
        END IF;
    END LOOP;
END $$;
