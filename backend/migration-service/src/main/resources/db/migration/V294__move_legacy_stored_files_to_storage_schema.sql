-- V294 - Align legacy storage-service V1 table with runtime schema.
--
-- V14 intentionally created storage-service V1 tables in public. Later
-- deployments run storage-service with Hibernate default_schema=storage, so a
-- fresh Flyway replay leaves Hibernate validating storage.stored_files while
-- the table exists only as public.stored_files. Existing prod instances that
-- already have storage.stored_files are left untouched.

CREATE SCHEMA IF NOT EXISTS storage;

DO $$
DECLARE
    org_data_type text;
    null_org_rows bigint;
BEGIN
    IF to_regclass('storage.stored_files') IS NULL
       AND to_regclass('public.stored_files') IS NOT NULL THEN
        EXECUTE 'DROP VIEW IF EXISTS public.file_statistics';
        EXECUTE 'ALTER TABLE public.stored_files SET SCHEMA storage';
    END IF;

    IF to_regclass('storage.stored_files') IS NULL THEN
        RETURN;
    END IF;

    SELECT data_type
      INTO org_data_type
      FROM information_schema.columns
     WHERE table_schema = 'storage'
       AND table_name = 'stored_files'
       AND column_name = 'organization_id';

    IF org_data_type IS NULL THEN
        EXECUTE 'ALTER TABLE storage.stored_files ADD COLUMN organization_id VARCHAR(255)';
    ELSIF org_data_type <> 'character varying' THEN
        EXECUTE 'ALTER TABLE storage.stored_files ALTER COLUMN organization_id TYPE VARCHAR(255) USING organization_id::text';
    END IF;

    EXECUTE 'CREATE INDEX IF NOT EXISTS idx_stored_files_org ON storage.stored_files (organization_id) WHERE organization_id IS NOT NULL';

    IF to_regclass('auth.organization_member') IS NOT NULL THEN
        EXECUTE $backfill$
            UPDATE storage.stored_files sf
               SET organization_id = (
                     SELECT om.organization_id::text
                       FROM auth.organization_member om
                      WHERE om.user_id = sf.user_id
                        AND om.is_default = true
                      LIMIT 1)
             WHERE sf.organization_id IS NULL
               AND EXISTS (
                     SELECT 1
                       FROM auth.organization_member om
                      WHERE om.user_id = sf.user_id
                        AND om.is_default = true)
        $backfill$;

        EXECUTE $backfill$
            UPDATE storage.stored_files sf
               SET organization_id = (
                     SELECT om.organization_id::text
                       FROM auth.organization_member om
                      WHERE om.user_id = 1
                        AND om.is_default = true
                      LIMIT 1)
             WHERE sf.organization_id IS NULL
        $backfill$;
    END IF;

    EXECUTE 'SELECT count(*) FROM storage.stored_files WHERE organization_id IS NULL'
      INTO null_org_rows;

    IF null_org_rows = 0 THEN
        EXECUTE 'ALTER TABLE storage.stored_files ALTER COLUMN organization_id SET NOT NULL';
    END IF;
END $$;

DO $$
BEGIN
    IF to_regclass('storage.stored_files') IS NOT NULL THEN
        EXECUTE $view$
            CREATE OR REPLACE VIEW public.file_statistics AS
            SELECT user_id,
                   COUNT(*)        AS file_count,
                   SUM(file_size)  AS total_size,
                   AVG(file_size)  AS average_file_size,
                   MIN(created_at) AS first_file_date,
                   MAX(created_at) AS last_file_date
              FROM storage.stored_files
             GROUP BY user_id
        $view$;
    ELSIF to_regclass('public.stored_files') IS NOT NULL THEN
        EXECUTE $view$
            CREATE OR REPLACE VIEW public.file_statistics AS
            SELECT user_id,
                   COUNT(*)        AS file_count,
                   SUM(file_size)  AS total_size,
                   AVG(file_size)  AS average_file_size,
                   MIN(created_at) AS first_file_date,
                   MAX(created_at) AS last_file_date
              FROM public.stored_files
             GROUP BY user_id
        $view$;
    END IF;
END $$;
