-- V262 - Phase 3-4 du plan "organization_id NOT NULL partout"
-- (the project docs).
--
-- Renumber note (round-7 audit, 2026-05-20): originally V260, renumbered to
-- V262 because origin/dev V259/V260 cloud-link migrations already exist.
--
-- Deux objectifs :
--   1. Ajouter `organization_id UUID NULL` sur les 4 tables d'audit/log qui
--      ont déjà `tenant_id` mais pas `organization_id`. Cohérence "partout
--      où tenant, on a org".
--   2. Backfill EXHAUSTIF de tous les rows `organization_id IS NULL` sur les
--      28 tables USER_SCOPED. Source du backfill : l'org default de l'owner
--      (via `auth.organization_member WHERE is_default = true`) ou héritage
--      du parent FK (`workflow_step_data` → `workflow_run`).
--
-- Mirrors le pattern V218/V248 (idempotent, defensive EXISTS predicate,
-- regex sur tenant_id pour exclure les '_publications' marker et fixtures).
--
-- ⚠️  V262 NE pose PAS la contrainte NOT NULL. C'est V263 (suivante) qui le fait
-- après la soak window de confirmation zéro-NULL.
--
-- Tables explicitement HORS-SCOPE de V262 (décision documentée) :
--   * auth.platform_credentials  - admin-managed OAuth apps platform-wide,
--                                  scope par admin tenant pas par org user
--   * auth.tenant_resource_counters - PK (tenant_id, resource_type), changer
--                                     la sémantique du counter par scope est hors-périmètre
--   * storage.tenant_storage_breakdown / _usage_history / _quota - design
--                                  parallèle avec organization_storage_* tables,
--                                  consolidation séparée si besoin
--   * agent.catalog_import_errors, model_catalog_sync_log - admin-only audit

-- ============================================================================
-- Section 1 - ADD COLUMN organization_id sur les tables avec tenant_id
-- ============================================================================
--
-- Vérification schema 2026-05-19 : sur les 4 tables LOG initialement
-- envisagées, seule `orchestrator.flag_flip_audit` a un `tenant_id` column.
-- Les 3 autres (trigger.trigger_state_audit_log, conversation.admin_search_audit,
-- auth.credit_reconciliation_log) sont des audit logs SANS tenant_id explicite
-- (scope inherits from the action context, pas de own column). Skip - la règle
-- "partout où il y a tenant, on a org" ne s'applique pas à elles.

ALTER TABLE orchestrator.flag_flip_audit
    ADD COLUMN IF NOT EXISTS organization_id UUID;

CREATE INDEX IF NOT EXISTS idx_flag_flip_audit_org
    ON orchestrator.flag_flip_audit (organization_id)
    WHERE organization_id IS NOT NULL;

-- ============================================================================
-- Section 2 - BACKFILL via default org (V218/V248 pattern)
-- ============================================================================
-- Pour chaque table user-scoped : route le NULL vers la default org du tenant.
-- Predicate identique à V218 + V248 : (a) tenant_id numérique, (b) le user a
-- une membership is_default=true. Sinon row laissé tel quel (orphan).

-- 2.1 - orchestrator.workflows + workflow_runs (post-V248 résidus)
UPDATE orchestrator.workflows w
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = w.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE w.organization_id IS NULL
   AND w.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = w.tenant_id::bigint AND om.is_default = true);

UPDATE orchestrator.workflow_runs r
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = r.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE r.organization_id IS NULL
   AND r.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = r.tenant_id::bigint AND om.is_default = true);

-- 2.2 - workflow_step_data (135K rows) : héritage du parent run
-- workflow_runs.organization_id is VARCHAR(255) (V209); workflow_step_data
-- column is UUID after V261 (then VARCHAR(255) after V264). The explicit
-- ::uuid cast turns the VARCHAR source value into a UUID literal so the
-- assignment is well-typed against the V261 UUID column. After V264
-- demotes the destination to VARCHAR(255), re-running this UPDATE would
-- still parse VARCHAR→UUID→VARCHAR cleanly via implicit cast - but V262
-- runs strictly before V264 in the Flyway batch, so we never hit that path.
UPDATE orchestrator.workflow_step_data sd
   SET organization_id = wr.organization_id::uuid
  FROM orchestrator.workflow_runs wr
 WHERE sd.workflow_run_id = wr.id
   AND sd.organization_id IS NULL
   AND wr.organization_id IS NOT NULL;

-- 2.3 - orchestrator.notifications (7 NULL)
UPDATE orchestrator.notifications n
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = n.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE n.organization_id IS NULL
   AND n.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = n.tenant_id::bigint AND om.is_default = true);

-- 2.4 - agent.agents + agent_executions + agent_tasks + agent_task_recurrences + skills + skill_folders
UPDATE agent.agents a
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = a.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE a.organization_id IS NULL
   AND a.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = a.tenant_id::bigint AND om.is_default = true);

UPDATE agent.agent_executions e
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = e.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE e.organization_id IS NULL
   AND e.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = e.tenant_id::bigint AND om.is_default = true);

-- agent_execution children - heritent du parent execution (PR20 contract)
UPDATE agent.agent_execution_iterations c
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE c.execution_id = e.id AND c.organization_id IS NULL AND e.organization_id IS NOT NULL;

UPDATE agent.agent_execution_messages c
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE c.execution_id = e.id AND c.organization_id IS NULL AND e.organization_id IS NOT NULL;

UPDATE agent.agent_execution_tool_calls c
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE c.execution_id = e.id AND c.organization_id IS NULL AND e.organization_id IS NOT NULL;

UPDATE agent.agent_tasks t
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = t.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE t.organization_id IS NULL
   AND t.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = t.tenant_id::bigint AND om.is_default = true);

UPDATE agent.agent_task_recurrences r
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = r.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE r.organization_id IS NULL
   AND r.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = r.tenant_id::bigint AND om.is_default = true);

UPDATE agent.skills s
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE s.organization_id IS NULL
   AND s.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true);

UPDATE agent.skill_folders f
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = f.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE f.organization_id IS NULL
   AND f.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = f.tenant_id::bigint AND om.is_default = true);

-- 2.5 - interface.interfaces (9 NULL)
UPDATE interface.interfaces i
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = i.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE i.organization_id IS NULL
   AND i.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = i.tenant_id::bigint AND om.is_default = true);

-- 2.6 - trigger.* (5 user-scoped tables)
UPDATE trigger.scheduled_executions s
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE s.organization_id IS NULL
   AND s.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true);

UPDATE trigger.standalone_webhooks w
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = w.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE w.organization_id IS NULL
   AND w.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = w.tenant_id::bigint AND om.is_default = true);

UPDATE trigger.standalone_form_endpoints f
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = f.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE f.organization_id IS NULL
   AND f.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = f.tenant_id::bigint AND om.is_default = true);

UPDATE trigger.standalone_chat_endpoints c
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = c.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE c.organization_id IS NULL
   AND c.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = c.tenant_id::bigint AND om.is_default = true);

UPDATE trigger.webhook_tokens t
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = t.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE t.organization_id IS NULL
   AND t.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = t.tenant_id::bigint AND om.is_default = true);

-- 2.7 - publication.publication_receipts (3 NULL)
UPDATE publication.publication_receipts p
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = p.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE p.organization_id IS NULL
   AND p.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = p.tenant_id::bigint AND om.is_default = true);

-- publication.shared_links (V261 ajout, 12 rows à backfill)
-- Target column is UUID (V261-added); drop ::text cast - om.organization_id is
-- already UUID-native, casting to text would re-trigger VARCHAR→UUID rejection.
UPDATE publication.shared_links l
   SET organization_id = (
         SELECT om.organization_id FROM auth.organization_member om
          WHERE om.user_id = l.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE l.organization_id IS NULL
   AND l.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = l.tenant_id::bigint AND om.is_default = true);

-- 2.8 - storage.storage (4382 NULL - la plus grosse table)
-- storage.storage.tenant_id est text. Cast vers bigint pour matcher
-- auth.organization_member.user_id (bigint).
UPDATE storage.storage s
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE s.organization_id IS NULL
   AND s.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true);

-- 2.9 - conversation.conversations (3 NULL, pas de tenant_id direct, via user_id)
-- ============================================================================
-- 2.9-pre - Round-10 hotfix (2026-05-20): defuse uq_conversations_primary_agent_per_user_workspace
--
-- V212 created a partial unique index on
--   (agent_id, user_id, COALESCE(organization_id, ''))
--   WHERE agent_id IS NOT NULL AND parent_conversation_id IS NULL
--     AND memory_enabled IS TRUE AND active IS TRUE
-- which buckets NULL-org rows distinctly from any non-NULL-org rows.
--
-- Data shape that triggers the collision: a user has a primary memory-enabled
-- conversation with agent X created PRE-V202 (org=NULL) AND another one with
-- the same agent created POST-V202 in personal scope (org=<personal-org-uuid>).
-- Both are valid under the partial index today. The §2.9 backfill would move
-- the NULL-org row into the same bucket as the personal-org row → duplicate
-- key violation, V262 rolls back entirely.
--
-- Fix without data loss: for each colliding pair, demote the NULL-org "loser"
-- out of the partial unique index by flipping memory_enabled=false. The row
-- stays active and queryable (history is preserved); it just stops competing
-- for the primary-memory slot. The default-org row carries forward as the
-- canonical primary.
UPDATE conversation.conversations c
   SET memory_enabled = false
 WHERE c.organization_id IS NULL
   AND c.user_id ~ '^[0-9]+$'
   AND c.agent_id IS NOT NULL
   AND c.parent_conversation_id IS NULL
   AND c.memory_enabled IS TRUE
   AND c.active IS TRUE
   AND EXISTS (
         SELECT 1 FROM conversation.conversations c2
          WHERE c2.id <> c.id
            AND c2.agent_id = c.agent_id
            AND c2.user_id = c.user_id
            AND c2.organization_id::text = (
                  SELECT om.organization_id::text FROM auth.organization_member om
                   WHERE om.user_id = c.user_id::bigint AND om.is_default = true LIMIT 1)
            AND c2.parent_conversation_id IS NULL
            AND c2.memory_enabled IS TRUE
            AND c2.active IS TRUE);

-- 2.9 - Actual backfill (now collision-free post §2.9-pre)
UPDATE conversation.conversations c
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = c.user_id::bigint AND om.is_default = true LIMIT 1)
 WHERE c.organization_id IS NULL
   AND c.user_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = c.user_id::bigint AND om.is_default = true);

-- 2.10 - auth.credit_consumption_dead_letter (16 rows, ajouté V261)
-- Target column is UUID (V261-added); drop ::text cast.
UPDATE auth.credit_consumption_dead_letter d
   SET organization_id = (
         SELECT om.organization_id FROM auth.organization_member om
          WHERE om.user_id = d.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE d.organization_id IS NULL
   AND d.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = d.tenant_id::bigint AND om.is_default = true);

-- 2.11 - orchestrator.flag_flip_audit (seule LOG table avec tenant_id)
-- Note: round-7 audit catch - column stays NULLABLE (no entity field on
-- FlagFlipAuditEntity), so this backfill is best-effort only. EXISTS guard
-- matches the rest of the file: rows whose tenant has no default org stay
-- NULL rather than getting silently wiped by a sub-select NULL result.
UPDATE orchestrator.flag_flip_audit l
   SET organization_id = (
         SELECT om.organization_id FROM auth.organization_member om
          WHERE om.user_id = l.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE l.organization_id IS NULL
   AND l.tenant_id IS NOT NULL
   AND l.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = l.tenant_id::bigint AND om.is_default = true);

-- 2.12bis - Defensive backfill pour les 3 tables V261-added qui ont tenant_id
-- (audit C4 - 0 rows aujourd'hui mais peuvent gagner des rows entre deploy
-- de V261 et V262). Pattern identique au reste. Idempotent.

-- All three targets are V261-added UUID columns - drop ::text casts.
UPDATE orchestrator.workflow_run_status s
   SET organization_id = (
         SELECT om.organization_id FROM auth.organization_member om
          WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE s.organization_id IS NULL
   AND s.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true);

UPDATE orchestrator.tenant_flags f
   SET organization_id = (
         SELECT om.organization_id FROM auth.organization_member om
          WHERE om.user_id = f.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE f.organization_id IS NULL
   AND f.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = f.tenant_id::bigint AND om.is_default = true);

UPDATE trigger.datasource_trigger_subscriptions d
   SET organization_id = (
         SELECT om.organization_id FROM auth.organization_member om
          WHERE om.user_id = d.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE d.organization_id IS NULL
   AND d.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = d.tenant_id::bigint AND om.is_default = true);

-- publication.ce_cloud_links.tenant_id est Long (bigint), pas regex needed
UPDATE publication.ce_cloud_links c
   SET organization_id = (
         SELECT om.organization_id FROM auth.organization_member om
          WHERE om.user_id = c.tenant_id AND om.is_default = true LIMIT 1)
 WHERE c.organization_id IS NULL
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = c.tenant_id AND om.is_default = true);

-- storage.stored_files n'a ni tenant_id ni org_id source - skip backfill,
-- table 0 rows et l'INSERT path doit stamp explicitement (Phase 3 caller fix).

-- 2.13 - datasource.data_sources (audit C6 - manquait du plan initial)
-- 25 rows total, 4 NULL en prod 2026-05-19. Même pattern V218/V248.
UPDATE datasource.data_sources ds
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = ds.tenant_id::bigint AND om.is_default = true LIMIT 1)
 WHERE ds.organization_id IS NULL
   AND ds.tenant_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = ds.tenant_id::bigint AND om.is_default = true);

-- 2.14 - orchestrator.projects (audit round-6 - V202 deliberately skipped this
-- because the table uses owner_id (varchar 50) not tenant_id. V263 preflight
-- enumerates orchestrator.projects, so we MUST backfill or the migration aborts.
-- Pattern mirrors conversation.conversations (§2.9) which has the same shape.
UPDATE orchestrator.projects p
   SET organization_id = (
         SELECT om.organization_id::text FROM auth.organization_member om
          WHERE om.user_id = p.owner_id::bigint AND om.is_default = true LIMIT 1)
 WHERE p.organization_id IS NULL
   AND p.owner_id ~ '^[0-9]+$'
   AND EXISTS (SELECT 1 FROM auth.organization_member om
                WHERE om.user_id = p.owner_id::bigint AND om.is_default = true);

-- ============================================================================
-- Section 3 - Verify counts (informational, ne fail pas la migration)
-- ============================================================================
-- Phase 5 du plan : verify zero NULL avant Phase 6 NOT NULL.
-- À exécuter post-deploy :
--
-- SELECT 'orchestrator.workflows' AS t, COUNT(*) FILTER (WHERE organization_id IS NULL) AS nulls FROM orchestrator.workflows
-- UNION ALL SELECT 'orchestrator.workflow_runs', COUNT(*) FILTER (WHERE organization_id IS NULL) FROM orchestrator.workflow_runs
-- ... etc.
--
-- Si non-zéro post-V262 : investiguer le tenant_id concerné (probablement
-- non-numérique ou sans default org). Soit assigner manuellement à un
-- org tombstone, soit DELETE la row orpheline avant Phase 6 (V263).
