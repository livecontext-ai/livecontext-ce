-- V263 - Phase 6 du plan "organization_id NOT NULL partout"
-- (the project docs).
--
-- Renumber note (round-7 audit, 2026-05-20): originally V261, renumbered to
-- V263 because origin/dev V259/V260 cloud-link migrations already exist
-- (commit e9da2b481). All 4 files in this series (V261..V264) were untracked
-- locally and never applied anywhere.
--
-- Atomic-batch dependency (round-7 audit M3): V263 flips columns to NOT NULL
-- while they are still UUID; V264 demotes them to VARCHAR(255) in the same
-- Flyway batch. Flyway holds an exclusive schema lock across the entire
-- pending-migration batch, so no concurrent service can write between V263
-- and V264 - the JPA layer (String organizationId fields) only resumes after
-- the whole batch commits. Reorder these two ONLY if you understand the
-- entity-binding shape: a hotfix landing V263 alone (without V264) would
-- crash Hibernate INSERTs.
--
-- ⚠️ POINT DE NON-RETOUR. Cette migration impose `NOT NULL` sur toutes les
-- colonnes `organization_id` des tables USER_SCOPED. Si une seule row a
-- encore NULL, la migration plante (ALTER COLUMN SET NOT NULL est strict).
--
-- PRÉREQUIS DE DEPLOY :
--   1. V261 + V262 + Phase 2 listener + Phase 3 async threading DOIVENT être
--      déployés et soak-testés AVANT V263.
--   2. Verify query (Phase 5 du plan) doit retourner zéro NULL :
--
--      SELECT t, COUNT(*) FROM (
--        SELECT 'orchestrator.workflows' AS t FROM orchestrator.workflows WHERE organization_id IS NULL
--        UNION ALL SELECT 'orchestrator.workflow_runs' FROM orchestrator.workflow_runs WHERE organization_id IS NULL
--        UNION ALL SELECT 'orchestrator.workflow_step_data' FROM orchestrator.workflow_step_data WHERE organization_id IS NULL
--        UNION ALL SELECT 'orchestrator.notifications' FROM orchestrator.notifications WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.agents' FROM agent.agents WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.agent_executions' FROM agent.agent_executions WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.agent_execution_iterations' FROM agent.agent_execution_iterations WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.agent_execution_messages' FROM agent.agent_execution_messages WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.agent_execution_tool_calls' FROM agent.agent_execution_tool_calls WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.agent_tasks' FROM agent.agent_tasks WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.agent_task_recurrences' FROM agent.agent_task_recurrences WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.skills' FROM agent.skills WHERE organization_id IS NULL
--        UNION ALL SELECT 'agent.skill_folders' FROM agent.skill_folders WHERE organization_id IS NULL
--        UNION ALL SELECT 'interface.interfaces' FROM interface.interfaces WHERE organization_id IS NULL
--        UNION ALL SELECT 'trigger.scheduled_executions' FROM trigger.scheduled_executions WHERE organization_id IS NULL
--        UNION ALL SELECT 'trigger.standalone_webhooks' FROM trigger.standalone_webhooks WHERE organization_id IS NULL
--        UNION ALL SELECT 'trigger.standalone_form_endpoints' FROM trigger.standalone_form_endpoints WHERE organization_id IS NULL
--        UNION ALL SELECT 'trigger.standalone_chat_endpoints' FROM trigger.standalone_chat_endpoints WHERE organization_id IS NULL
--        UNION ALL SELECT 'trigger.webhook_tokens' FROM trigger.webhook_tokens WHERE organization_id IS NULL
--        UNION ALL SELECT 'publication.publication_receipts' FROM publication.publication_receipts WHERE organization_id IS NULL
--        UNION ALL SELECT 'publication.shared_links' FROM publication.shared_links WHERE organization_id IS NULL
--        UNION ALL SELECT 'storage.storage' FROM storage.storage WHERE organization_id IS NULL
--        UNION ALL SELECT 'conversation.conversations' FROM conversation.conversations WHERE organization_id IS NULL
--        UNION ALL SELECT 'auth.credit_consumption_dead_letter' FROM auth.credit_consumption_dead_letter WHERE organization_id IS NULL
--      ) x GROUP BY t HAVING COUNT(*) > 0;
--
--   3. Si la query retourne des rows, soit (a) DELETE les orphans (tenant_id
--      non-numeric ou user supprimé), soit (b) backfill manuel vers un org
--      tombstone. NE PAS shipper V263 tant que la query ne retourne pas vide.
--
-- TABLES HORS-SCOPE de Phase 6 :
--   * Tables INHERITED_VIA_FK sans colonne propre (messages, tool_results,
--     widget_configs, etc.) - scope héritage runtime, pas de NOT NULL DB
--   * Tables avec colonne mais 0 rows actuellement
--     (workflow_run_status, tenant_flags, ce_cloud_links, datasource_trigger_subscriptions,
--     public.stored_files) - colonne ajoutée par V261 + flippée NOT NULL ici en
--     toute sécurité puisqu'elles sont vides en prod 2026-05-20. La soak est
--     intentionnellement nulle sur ces tables car les Phase 3 writers les
--     stamp désormais via OrgScopedEntityListener (pas de fallback à NULL).
--   * publication.workflow_publications - modèle owner_type+owner_id poly,
--     hors du modèle (tenant_id, organization_id), géré séparément par V246
--   * orchestrator.flag_flip_audit - round-7 audit catch: V262 adds the column
--     and backfills it, but FlagFlipAuditEntity had NO `organizationId` field
--     (FlagFlipAuditWriter.recordFlip never set it). SET NOT NULL here would
--     have crashed every subsequent flag flip until the entity was updated.
--     Round-10 (2026-05-20): the entity now carries the field + listener +
--     explicit threading from TenantFlagService.flip → V265 closes the
--     constraint with type alignment + SET NOT NULL.

-- ============================================================================
-- Section 0 - Preflight diagnostic (audit round-4 - explicit RAISE)
-- ============================================================================
-- Flyway already rolls back atomically if any ALTER COLUMN SET NOT NULL fails
-- mid-flight, but Postgres's bare message ("column "organization_id" contains
-- null values") doesn't say HOW MANY rows or WHICH table - turning a 30-sec
-- diagnostic into a 30-minute scan-every-table investigation. This block runs
-- ONCE before any ALTER, lists every table+count with residual NULLs, and
-- aborts with a single readable message. Same transaction as the ALTERs, so
-- failure here = zero schema mutation.
DO $$
DECLARE
    problems text := '';
    cnt bigint;
    tables text[] := ARRAY[
        'orchestrator.workflows',
        'orchestrator.workflow_runs',
        'orchestrator.workflow_step_data',
        'orchestrator.notifications',
        'orchestrator.projects',
        'agent.agents',
        'agent.agent_executions',
        'agent.agent_execution_iterations',
        'agent.agent_execution_messages',
        'agent.agent_execution_tool_calls',
        'agent.agent_tasks',
        'agent.agent_task_recurrences',
        'agent.skills',
        'agent.skill_folders',
        'interface.interfaces',
        'trigger.scheduled_executions',
        'trigger.standalone_webhooks',
        'trigger.standalone_form_endpoints',
        'trigger.standalone_chat_endpoints',
        'trigger.webhook_tokens',
        'publication.publication_receipts',
        'publication.shared_links',
        'storage.storage',
        'conversation.conversations',
        'auth.credit_consumption_dead_letter',
        'datasource.data_sources',
        -- V261-added, expected empty in prod but check anyway
        'orchestrator.workflow_run_status',
        'orchestrator.tenant_flags',
        'publication.ce_cloud_links',
        'trigger.datasource_trigger_subscriptions'
        -- orchestrator.flag_flip_audit deliberately omitted from preflight:
        -- column stays NULLABLE pending entity update (see header note).
    ];
    fq text;
    parts text[];
BEGIN
    FOREACH fq IN ARRAY tables LOOP
        parts := string_to_array(fq, '.');
        -- Skip if the table doesn't exist in this deployment (e.g. CE/local
        -- variations of storage-service install state). Mirrors the V261/V263
        -- to_regclass guard for storage.stored_files.
        IF to_regclass(fq) IS NULL THEN
            CONTINUE;
        END IF;
        EXECUTE format('SELECT count(*) FROM %I.%I WHERE organization_id IS NULL',
                       parts[1], parts[2]) INTO cnt;
        IF cnt > 0 THEN
            problems := problems || format(E'  %s: %s rows still NULL\n', fq, cnt);
        END IF;
    END LOOP;

    -- public.stored_files - round-6: schema is `public` not `storage` (see V261 comment).
    IF to_regclass('public.stored_files') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM public.stored_files WHERE organization_id IS NULL'
            INTO cnt;
        IF cnt > 0 THEN
            problems := problems || format(E'  public.stored_files: %s rows still NULL\n', cnt);
        END IF;
    END IF;

    IF length(problems) > 0 THEN
        RAISE EXCEPTION E'V263 preflight failed - one or more tables still have NULL organization_id:\n%', problems
            USING HINT = 'Re-run V262 backfill, then DELETE or manually backfill orphan rows before re-applying V263. See the verify query in this file header.';
    END IF;
END $$;

-- ============================================================================
-- Section 1 - Core USER_SCOPED tables (présentes en prod avec data)
-- ============================================================================

ALTER TABLE orchestrator.workflows         ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE orchestrator.workflow_runs     ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE orchestrator.workflow_step_data ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE orchestrator.notifications     ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE orchestrator.projects          ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE agent.agents                          ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.agent_executions                ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.agent_execution_iterations      ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.agent_execution_messages        ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.agent_execution_tool_calls      ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.agent_tasks                     ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.agent_task_recurrences          ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.skills                          ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE agent.skill_folders                   ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE interface.interfaces ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE trigger.scheduled_executions       ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE trigger.standalone_webhooks        ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE trigger.standalone_form_endpoints  ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE trigger.standalone_chat_endpoints  ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE trigger.webhook_tokens             ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE publication.publication_receipts ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE publication.shared_links         ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE storage.storage ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE conversation.conversations ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE auth.credit_consumption_dead_letter ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE datasource.data_sources ALTER COLUMN organization_id SET NOT NULL;

-- ============================================================================
-- Section 2 - Tables avec colonne mais data minimale / V261-added
-- ============================================================================
-- 0 rows actuellement → ALTER instantané + safe.

ALTER TABLE orchestrator.workflow_run_status ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE orchestrator.tenant_flags        ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE publication.ce_cloud_links       ALTER COLUMN organization_id SET NOT NULL;
ALTER TABLE trigger.datasource_trigger_subscriptions ALTER COLUMN organization_id SET NOT NULL;
-- public.stored_files is portable-guarded (V261 only adds the column when
-- the table exists; the SET NOT NULL here is similarly conditional).
-- Round-6: corrected schema from `storage` to `public` - that's where V14
-- actually created the table.
DO $$
BEGIN
    IF to_regclass('public.stored_files') IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.stored_files ALTER COLUMN organization_id SET NOT NULL';
    END IF;
END $$;

-- ============================================================================
-- Section 3 - LOG/audit (V262-added)
-- ============================================================================
-- orchestrator.flag_flip_audit deliberately NOT flipped here: FlagFlipAuditEntity
-- had no organizationId field at the time V263 was written, so SET NOT NULL
-- would have broken every flag flip. Phase-7 follow-up landed in V265 (round-10,
-- 2026-05-20): entity + writer update + V265 type-align + SET NOT NULL.

-- ============================================================================
-- Effet final
-- ============================================================================
-- 29 tables ont organization_id NOT NULL ici. V265 (round-10 follow-up)
-- ferme flag_flip_audit → 30 tables au total post-V265.
-- L'état hybride (NULL = personal vs uuid = org) est FINI : tout user a un org
-- perso depuis V202, donc tout row USER_SCOPED a un UUID concret.
--
-- Code à nettoyer en Phase 7 (ne fait PAS partie de cette migration) :
--   * Predicates `WHERE (tenant_id = :u AND organization_id IS NULL) OR (organization_id = :o)`
--     → simplifient en `WHERE organization_id = :o`
--   * Helpers `ScopeGuard.crossResourceMatches` branche `parentOrg == null` morte
--   * AuthenticationFilter `addAuthHeaders` silent fallback : peut émettre
--     X-Effective-Organization-Id header pour résoudre les workspace fantômes
--   * (CLOSED in round-10 by V265) FlagFlipAuditEntity organizationId field +
--     writer threading + flag_flip_audit.organization_id SET NOT NULL.
