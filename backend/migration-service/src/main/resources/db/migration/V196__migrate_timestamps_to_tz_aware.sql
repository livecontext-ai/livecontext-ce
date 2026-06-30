-- ============================================================================
-- V196: migrate TIMESTAMP WITHOUT TIME ZONE columns to TIMESTAMPTZ
--
-- WHY: legacy entities (auth, conversation, catalog, storage) declared their
-- `created_at` / `updated_at` columns as bare `TIMESTAMP WITHOUT TIME ZONE`.
-- Newer entities (orchestrator, agent, publication, trigger, interface,
-- datasource, V2 storage) use `TIMESTAMPTZ`. The mismatch made the frontend
-- unable to tell which API response fields were UTC and which were local,
-- and forced every API consumer to remember per-field semantics.
--
-- DATA SAFETY: this is a NO-OP REINTERPRETATION on this deployment.
--
--   Verified 2026-05-11:
--     - app-host (JVM hosts):   OS timezone = Etc/UTC
--     - db-host (PostgreSQL): OS timezone = Etc/UTC, session TimeZone = Etc/UTC
--
--   Since the JVM has always run with `user.timezone=UTC` (inherited from
--   the OS) and PostgreSQL has always run with `TimeZone=UTC`, every
--   `LocalDateTime.now()` write produced a wall-clock UTC value, and every
--   write to a `TIMESTAMP WITHOUT TIME ZONE` column stored that UTC
--   wall-clock literally. Reinterpreting those bare values with
--   `AT TIME ZONE 'UTC'` does NOT shift any instant - it just promotes the
--   column type so the API/frontend can treat it as TZ-aware.
--
-- HOW IT IS SAFE:
--  - Idempotent: for every column we check `information_schema.columns.data_type`
--    and skip if it's already `timestamp with time zone`. Re-running the
--    migration on a partially-converted DB is a no-op.
--  - Reversible interpretation: `ALTER TABLE ... USING (col AT TIME ZONE
--    :source_tz)` reads the bare timestamp as a clock-time in :source_tz
--    and produces the corresponding UTC instant. With source_tz='UTC' that
--    is a passthrough (12:34:56 bare → 12:34:56Z instant).
--  - Fail-fast: refuses to run unless the operator explicitly sets the
--    `lc.migration.source_timezone` session GUC before invoking Flyway.
--    For THIS deployment the correct value is 'UTC' (see Data Safety above).
--    Future deployments restored from a non-UTC backup would need to set
--    the source TZ to whatever the original host actually used.
--
-- HOW TO RUN:
--   psql ... <<SQL
--     SET lc.migration.source_timezone = 'UTC';   -- verified for app-host + db-host
--     <run flyway migrate, which will pick up this V196>
--   SQL
--
-- Alternatively pass it via JDBC URL: `?options=-c%20lc.migration.source_timezone=UTC`
-- or, for `migration-service` started via systemd, prepend the GUC in the
-- service's `EnvironmentFile`:
--   PGOPTIONS=-c lc.migration.source_timezone=UTC
-- and ensure the JDBC URL forwards PGOPTIONS (libpq does this automatically).
--
-- WHAT IT TOUCHES (audit 2026-05-11):
--  auth.users (5 cols), auth.user_onboarding.display_name_changed_at,
--  auth.refresh_tokens (V26), auth.pending_credit_upgrade (V188),
--  agent.agents.budget_last_reset (V25),
--  publication.ce_cloud_links (V29),
--  conversation.{conversations,messages,streams,tool_results,message_attachments} (V10),
--  catalog.{tool_responses, mapping_definitions, mapping_versions} (V12+V23),
--  public.{stored_files,file_metadata,user_storage_stats} (V14).
--
-- Not touched (already TIMESTAMPTZ):
--  orchestrator.*, agent.* (except budget_last_reset), trigger.*, interface.*,
--  publication.* (except ce_cloud_links), datasource.*, storage.* (V2 schema),
--  auth.subscription/organization/credit_ledger/billing_* (already TZ).
-- ============================================================================

DO $migration$
DECLARE
    src_tz TEXT;
    target RECORD;
    is_tz   TEXT;
    sql_stmt TEXT;
BEGIN
    BEGIN
        src_tz := current_setting('lc.migration.source_timezone');
    EXCEPTION WHEN OTHERS THEN
        src_tz := NULL;
    END;

    IF src_tz IS NULL OR src_tz = '' THEN
        RAISE EXCEPTION
            'V196: lc.migration.source_timezone is not set. See migration header for instructions. '
            'Hetzner DE prod historically defaulted to Europe/Berlin - set explicitly before running.';
    END IF;

    -- Sanity-check that the value is a valid TZ name PostgreSQL understands.
    PERFORM now() AT TIME ZONE src_tz;

    RAISE NOTICE 'V196: reinterpreting legacy TIMESTAMP columns as %', src_tz;

    -- Drop views that reference any of the columns we're about to ALTER.
    -- PostgreSQL refuses `ALTER COLUMN ... TYPE` when a view/rule depends
    -- on the column ("cannot alter type of a column used by a view or rule"),
    -- and the dependency check is conservative - it triggers even when the
    -- view's projected expression would survive the type change. We DROP
    -- the dependent views before the loop and CREATE them back after,
    -- which is idempotent across V196 replays (CREATE OR REPLACE + DROP
    -- IF EXISTS handle every state).
    --
    -- Audit (`pg_depend` join on the columns in the loop's tuple list,
    -- 2026-05-12 local DB) returned exactly ONE such view:
    --   public.file_statistics  →  public.stored_files.created_at
    -- Other column targets (file_metadata, user_storage_stats, auth.*,
    -- conversation.*, catalog.*, etc.) have no dependent views.
    --
    -- After re-creation the view's `first_file_date` / `last_file_date`
    -- expressions will be TIMESTAMPTZ instead of TIMESTAMP - desirable,
    -- matches the rest of the migration's intent.
    EXECUTE 'DROP VIEW IF EXISTS public.file_statistics';

    FOR target IN
        SELECT * FROM (VALUES
            -- (schema, table, column)
            ('auth',         'users',                   'api_key_created_at'),
            ('auth',         'users',                   'age'),
            ('auth',         'users',                   'created_at'),
            ('auth',         'users',                   'updated_at'),
            ('auth',         'users',                   'last_login_at'),
            ('auth',         'user_onboarding',         'display_name_changed_at'),
            ('auth',         'refresh_tokens',          'created_at'),
            ('auth',         'refresh_tokens',          'expires_at'),
            ('auth',         'refresh_tokens',          'revoked_at'),
            ('auth',         'pending_credit_upgrade',  'created_at'),
            ('auth',         'pending_credit_upgrade',  'updated_at'),
            ('auth',         'pending_credit_upgrade',  'completed_at'),
            -- V25: budget_last_reset lives on agent.agents (NOT auth.agents).
            ('agent',        'agents',                  'budget_last_reset'),
            -- V29: ce_cloud_links is in publication schema.
            ('publication',  'ce_cloud_links',          'token_expires_at'),
            ('publication',  'ce_cloud_links',          'linked_at'),
            ('publication',  'ce_cloud_links',          'last_used_at'),
            -- conversation schema (V10)
            ('conversation', 'conversations',           'created_at'),
            ('conversation', 'conversations',           'updated_at'),
            ('conversation', 'messages',                'created_at'),
            ('conversation', 'streams',                 'created_at'),
            ('conversation', 'streams',                 'updated_at'),
            ('conversation', 'streams',                 'completed_at'),
            ('conversation', 'tool_results',            'created_at'),
            ('conversation', 'message_attachments',     'created_at'),
            -- catalog schema (V12 + V23)
            ('catalog',      'tool_responses',          'created_at'),
            ('catalog',      'tool_responses',          'updated_at'),
            ('catalog',      'mapping_definitions',     'created_at'),
            ('catalog',      'mapping_definitions',     'updated_at'),
            ('catalog',      'mapping_versions',        'created_at'),
            -- public schema legacy (V14) - pre-V2 storage tables
            ('public',       'stored_files',            'created_at'),
            ('public',       'stored_files',            'updated_at'),
            ('public',       'stored_files',            'last_accessed_at'),
            ('public',       'file_metadata',           'created_at'),
            ('public',       'user_storage_stats',      'last_updated')
        ) AS t(schema_name, table_name, column_name)
    LOOP
        -- Skip silently if the table doesn't exist (CE-only setups may not
        -- have provisioned every cloud table; safer than failing the run).
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = target.schema_name AND table_name = target.table_name
        ) THEN
            CONTINUE;
        END IF;

        SELECT data_type INTO is_tz
        FROM information_schema.columns
        WHERE table_schema = target.schema_name
          AND table_name   = target.table_name
          AND column_name  = target.column_name;

        IF is_tz IS NULL THEN
            -- Column missing - likely already dropped by a later migration. Skip.
            CONTINUE;
        END IF;

        IF is_tz = 'timestamp with time zone' THEN
            -- Already converted, idempotent skip.
            CONTINUE;
        END IF;

        sql_stmt := format(
            'ALTER TABLE %I.%I ALTER COLUMN %I TYPE TIMESTAMP WITH TIME ZONE '
            'USING (%I AT TIME ZONE %L)',
            target.schema_name, target.table_name, target.column_name,
            target.column_name, src_tz
        );
        RAISE NOTICE 'V196: %', sql_stmt;
        EXECUTE sql_stmt;
    END LOOP;

    -- Re-create the view dropped above. Skip if the underlying table is
    -- absent (CE-only setup that never had public.stored_files).
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'stored_files'
    ) THEN
        EXECUTE $view$
            CREATE OR REPLACE VIEW public.file_statistics AS
            SELECT user_id,
                   COUNT(*)         AS file_count,
                   SUM(file_size)   AS total_size,
                   AVG(file_size)   AS average_file_size,
                   MIN(created_at)  AS first_file_date,
                   MAX(created_at)  AS last_file_date
            FROM public.stored_files
            GROUP BY user_id
        $view$;
    END IF;

    RAISE NOTICE 'V196: legacy TIMESTAMP -> TIMESTAMPTZ migration complete.';
END
$migration$;
