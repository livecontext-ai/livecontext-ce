-- V264 - Phase 6 follow-up of the "organization_id NOT NULL partout" plan.
-- (the project docs, audit round-6 fix 2026-05-20)
--
-- Renumber note (round-7 audit, 2026-05-20): originally V262, renumbered to
-- V264 because origin/dev V259/V260 cloud-link migrations already exist.
--
-- V261 added `organization_id UUID` to 8 USER_SCOPED tables to match the
-- `auth.organization_member.organization_id` UUID PK. The 8 JPA entities,
-- however, declare `String organizationId` fields with no `@JdbcTypeCode`
-- adapter - Hibernate would bind VARCHAR and PostgreSQL would reject the
-- VARCHAR→UUID assignment at every INSERT, the moment V263 makes the column
-- NOT NULL (round-5 audit catch).
--
-- The minimum-blast-radius fix is to align the V261-added columns to
-- VARCHAR(255), which is the type every OTHER org_id column already uses
-- (V204/V208/V209/V210/V211/V213/V215/V217 + V222 + V242). This file does
-- exactly that, idempotently:
--   * On fresh deploys (V261 just ran with UUID type) it converts the
--     column to VARCHAR(255).
--   * On local DBs that already applied V261 *before* this audit round
--     (same UUID type), it converts the same way.
--   * If a future deploy already has VARCHAR (the column is `character
--     varying`), the WHERE predicate skips it - pure no-op.
--
-- USING organization_id::text is the canonical UUID-to-text cast; the
-- column data (UUID values) becomes the same UUID-string-form that the
-- entity layer was always going to write.
--
-- orchestrator.flag_flip_audit is intentionally OMITTED from the targets
-- below: its column stays UUID + NULLABLE pending FlagFlipAuditEntity
-- update (see V263 header note). No entity writes happen against it yet,
-- so the type alignment is not load-bearing.

DO $$
DECLARE
    tbl text;
    sch text;
    tab text;
    parts text[];
    targets text[] := ARRAY[
        'orchestrator.workflow_step_data',
        'orchestrator.tenant_flags',
        'orchestrator.workflow_run_status',
        'auth.credit_consumption_dead_letter',
        'publication.shared_links',
        'publication.ce_cloud_links',
        'trigger.datasource_trigger_subscriptions'
    ];
BEGIN
    FOREACH tbl IN ARRAY targets LOOP
        parts := string_to_array(tbl, '.');
        sch := parts[1];
        tab := parts[2];
        IF to_regclass(tbl) IS NULL THEN
            CONTINUE;
        END IF;
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_schema = sch
               AND table_name = tab
               AND column_name = 'organization_id'
               AND data_type = 'uuid'
        ) THEN
            EXECUTE format(
                'ALTER TABLE %I.%I ALTER COLUMN organization_id TYPE VARCHAR(255) USING organization_id::text',
                sch, tab);
        END IF;
    END LOOP;

    -- public.stored_files - portable guard mirrors V261 Section 8.
    IF to_regclass('public.stored_files') IS NOT NULL THEN
        IF EXISTS (
            SELECT 1 FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name = 'stored_files'
               AND column_name = 'organization_id'
               AND data_type = 'uuid'
        ) THEN
            EXECUTE 'ALTER TABLE public.stored_files ALTER COLUMN organization_id TYPE VARCHAR(255) USING organization_id::text';
        END IF;
    END IF;
END $$;
