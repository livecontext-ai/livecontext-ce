-- V261 - Phase 1 du plan "organization_id NOT NULL partout" (the project docs).
--
-- Inventaire Phase 0 (2026-05-19) a identifié 8 tables USER_SCOPED qui n'ont pas
-- la colonne organization_id. Cette migration l'ajoute, NULLABLE pour l'instant.
-- Phase 4 backfille les rows existantes (mostly empty tables, sauf workflow_step_data
-- 135K rows qui héritent du parent workflow_run.organization_id).
-- Phase 6 ajoute la contrainte NOT NULL une fois Phase 4 + Phase 5 verify OK.
--
-- Renumber note (round-7 audit, 2026-05-20): originally numbered V259..V262 but
-- collided with origin/dev's V259__ce_cloud_links_install_id_and_state.sql and
-- V260__cloud_ce_link.sql (commit e9da2b481). Renumbered to V261..V264 before
-- any deploy - all 4 files were untracked locally and never applied anywhere.
--
-- Type note (round-6 audit, 2026-05-19): columns are added as UUID here, then
-- V264 alters them to VARCHAR(255) to line up with every other org_id column
-- in the schema (V204/V208/V209/V210/V211/V213/V215/V217 all VARCHAR(255)) and
-- with the String field type on the 8 mapped entities. The two-step (UUID
-- here, ALTER TYPE in V264) keeps this file purely additive and lets the
-- type-alignment land in a separate, idempotent script.
--
-- Tables hors-scope (intentionnellement laissées en tenant-only) :
--   * storage.storage_usage_history / tenant_storage_breakdown / tenant_storage_quota
--     - parallel `organization_storage_*` tables existent déjà, la décision
--     de consolider est séparée.
--   * publication.workflow_publications - pattern poly owner_type+owner_id depuis
--     V223/V246, ne fit pas le modèle (tenant_id, organization_id).
--
-- Additif uniquement, zéro risque. Soak window 0.

-- ============================================================================
-- 1. orchestrator.workflow_step_data (135 745 rows en prod, hérite du run parent)
-- ============================================================================
ALTER TABLE orchestrator.workflow_step_data
    ADD COLUMN IF NOT EXISTS organization_id UUID;

CREATE INDEX IF NOT EXISTS idx_workflow_step_data_org
    ON orchestrator.workflow_step_data (organization_id)
    WHERE organization_id IS NOT NULL;

-- ============================================================================
-- 2. orchestrator.tenant_flags (0 rows, scope-aware feature flags)
-- ============================================================================
ALTER TABLE orchestrator.tenant_flags
    ADD COLUMN IF NOT EXISTS organization_id UUID;

CREATE INDEX IF NOT EXISTS idx_tenant_flags_org
    ON orchestrator.tenant_flags (organization_id)
    WHERE organization_id IS NOT NULL;

-- ============================================================================
-- 3. orchestrator.workflow_run_status (0 rows, status snapshot par run)
-- ============================================================================
ALTER TABLE orchestrator.workflow_run_status
    ADD COLUMN IF NOT EXISTS organization_id UUID;

CREATE INDEX IF NOT EXISTS idx_workflow_run_status_org
    ON orchestrator.workflow_run_status (organization_id)
    WHERE organization_id IS NOT NULL;

-- ============================================================================
-- 4. auth.credit_consumption_dead_letter (16 rows, audit log async retry)
-- ============================================================================
ALTER TABLE auth.credit_consumption_dead_letter
    ADD COLUMN IF NOT EXISTS organization_id UUID;

CREATE INDEX IF NOT EXISTS idx_credit_dead_letter_org
    ON auth.credit_consumption_dead_letter (organization_id)
    WHERE organization_id IS NOT NULL;

-- ============================================================================
-- 5. publication.shared_links (12 rows, lien public résolution)
-- ============================================================================
ALTER TABLE publication.shared_links
    ADD COLUMN IF NOT EXISTS organization_id UUID;

CREATE INDEX IF NOT EXISTS idx_shared_links_org
    ON publication.shared_links (organization_id)
    WHERE organization_id IS NOT NULL;

-- ============================================================================
-- 6. publication.ce_cloud_links (0 rows, CE→cloud account binding)
-- ============================================================================
ALTER TABLE publication.ce_cloud_links
    ADD COLUMN IF NOT EXISTS organization_id UUID;

CREATE INDEX IF NOT EXISTS idx_ce_cloud_links_org
    ON publication.ce_cloud_links (organization_id)
    WHERE organization_id IS NOT NULL;

-- ============================================================================
-- 7. trigger.datasource_trigger_subscriptions (0 rows, datasource event sub)
-- ============================================================================
ALTER TABLE trigger.datasource_trigger_subscriptions
    ADD COLUMN IF NOT EXISTS organization_id UUID;

CREATE INDEX IF NOT EXISTS idx_datasource_trigger_sub_org
    ON trigger.datasource_trigger_subscriptions (organization_id)
    WHERE organization_id IS NOT NULL;

-- ============================================================================
-- 8. public.stored_files (0 rows, file-ref table)
-- Audit round-6 (2026-05-19): the table lives in the DEFAULT `public` schema
-- (created by V14__create_public_schema_tables - no `storage.` prefix in the
-- DDL, no `currentSchema=` JDBC param in storage-service application.yml).
-- StoredFile JPA entity uses `@Table(name = "stored_files")` with no schema,
-- so Hibernate resolves via search_path → `public.stored_files`. V261/V263
-- targeting `storage.stored_files` would have been a silent no-op (`to_regclass`
-- returns NULL → block skipped → entity continues writing to `public` table
-- with no organization_id column). Round-6 fix: schema-qualify to `public`.
-- Robust guard kept in case a future CE install never activates storage-service.
-- ============================================================================
DO $$
BEGIN
    IF to_regclass('public.stored_files') IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.stored_files ADD COLUMN IF NOT EXISTS organization_id UUID';
        EXECUTE 'CREATE INDEX IF NOT EXISTS idx_stored_files_org '
             || 'ON public.stored_files (organization_id) '
             || 'WHERE organization_id IS NOT NULL';
    END IF;
END $$;
