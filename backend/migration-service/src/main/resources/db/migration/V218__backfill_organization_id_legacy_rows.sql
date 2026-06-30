-- V218 - backfill organization_id on legacy rows missed by V210/V211/V213/V215/V217
--
-- Context: V210-V217 (PR23/PR23-conv/PR22/PR22c/PR27.2) added organization_id
-- columns to 11 tables across 4 schemas. Each migration explicitly opted OUT
-- of backfill, with the design rationale "pre-PR21 rows have no org tag and
-- belong to personal scope" - meaning callers in personal scope query
-- WHERE organization_id IS NULL.
--
-- That contract holds for the BACKEND, but the FRONTEND always sends
-- X-Active-Organization-ID equal to the user's default-org id (every user
-- has a personal-org auto-created at signup, set as default). The gateway
-- forwards this header to every read; the backend then filters strict
-- `WHERE organization_id = orgId` and returns ZERO rows for legacy NULL data.
--
-- Visible symptom: a user who created conversations / triggers / agent runs
-- before V210-V217 deploy (2026-05-14) opens the corresponding UI panel and
-- sees an empty list, even though their data is intact in the DB.
--
-- This migration applies the V202 backfill pattern to the 11 tables: for
-- each row with organization_id IS NULL and a numeric tenant_id (or user_id)
-- whose owner has a default-org membership, set organization_id to that
-- default-org's id. Non-numeric tenants (legacy/UUID/special markers like
-- '_publications') stay NULL - same predicate V202 used.
--
-- For the agent_execution_* child tables (iterations, messages, tool_calls),
-- propagate from the parent agent_executions row to preserve the (execution,
-- *_step) ownership chain - same pattern V210's intra-tx backfill block uses
-- when running through Flyway (and would have used here if V210 hadn't been
-- applied with the prod-deadlock workaround that strips CONCURRENTLY and
-- skips the inline backfill block).
--
-- Idempotent: every UPDATE is bounded by WHERE organization_id IS NULL, so
-- re-running this migration is a no-op once applied. Safe to re-run.

-- ── conversation.conversations ─────────────────────────────────────────
UPDATE conversation.conversations c
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = c.user_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE c.organization_id IS NULL
   AND c.user_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = c.user_id::bigint AND om.is_default = true);

-- ── agent.agent_executions ─────────────────────────────────────────────
UPDATE agent.agent_executions a
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = a.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE a.organization_id IS NULL
   AND a.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = a.tenant_id::bigint AND om.is_default = true);

-- ── agent.agent_execution_iterations (propagate from parent) ───────────
UPDATE agent.agent_execution_iterations i
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE i.execution_id = e.id
   AND i.organization_id IS NULL
   AND e.organization_id IS NOT NULL;

-- ── agent.agent_execution_messages (propagate from parent) ─────────────
UPDATE agent.agent_execution_messages m
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE m.execution_id = e.id
   AND m.organization_id IS NULL
   AND e.organization_id IS NOT NULL;

-- ── agent.agent_execution_tool_calls (propagate from parent) ───────────
UPDATE agent.agent_execution_tool_calls t
   SET organization_id = e.organization_id
  FROM agent.agent_executions e
 WHERE t.execution_id = e.id
   AND t.organization_id IS NULL
   AND e.organization_id IS NOT NULL;

-- ── agent.agent_tasks ──────────────────────────────────────────────────
UPDATE agent.agent_tasks t
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = t.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE t.organization_id IS NULL
   AND t.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = t.tenant_id::bigint AND om.is_default = true);

-- ── trigger.scheduled_executions ───────────────────────────────────────
UPDATE trigger.scheduled_executions s
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE s.organization_id IS NULL
   AND s.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true);

-- ── trigger.standalone_webhooks ────────────────────────────────────────
UPDATE trigger.standalone_webhooks w
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = w.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE w.organization_id IS NULL
   AND w.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = w.tenant_id::bigint AND om.is_default = true);

-- ── trigger.webhook_tokens (V215 - only if tenant_id col exists) ───────
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema='trigger' AND table_name='webhook_tokens' AND column_name='tenant_id'
  ) THEN
    UPDATE trigger.webhook_tokens t
       SET organization_id = (
         SELECT om.organization_id::text
           FROM auth.organization_member om
          WHERE om.user_id = t.tenant_id::bigint AND om.is_default = true
          LIMIT 1)
     WHERE t.organization_id IS NULL
       AND t.tenant_id ~ '^[0-9]+$'
       AND EXISTS (
         SELECT 1 FROM auth.organization_member om
          WHERE om.user_id = t.tenant_id::bigint AND om.is_default = true);
  END IF;
END $$;

-- ── agent.skills (V217) ────────────────────────────────────────────────
UPDATE agent.skills s
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE s.organization_id IS NULL
   AND s.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = s.tenant_id::bigint AND om.is_default = true);

-- ── agent.skill_folders (V217) ─────────────────────────────────────────
UPDATE agent.skill_folders f
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = f.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE f.organization_id IS NULL
   AND f.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = f.tenant_id::bigint AND om.is_default = true);

-- ── agent.agent_task_recurrences (V217) ────────────────────────────────
UPDATE agent.agent_task_recurrences r
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = r.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE r.organization_id IS NULL
   AND r.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = r.tenant_id::bigint AND om.is_default = true);

-- ── storage.storage (V204 column was already there, but post-V204 NULL
-- rows from rows owned by users who got default-org later) ─────────────
UPDATE storage.storage st
   SET organization_id = (
     SELECT om.organization_id::text
       FROM auth.organization_member om
      WHERE om.user_id = st.tenant_id::bigint AND om.is_default = true
      LIMIT 1)
 WHERE st.organization_id IS NULL
   AND st.tenant_id ~ '^[0-9]+$'
   AND EXISTS (
     SELECT 1 FROM auth.organization_member om
      WHERE om.user_id = st.tenant_id::bigint AND om.is_default = true);
