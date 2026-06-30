-- V258 - add tenant_id to trigger.webhook_tokens.
--
-- Renumbered from V253 on 2026-05-19 to resolve a Flyway collision: the merge
-- c4fe1630b of branch `worktree-org-scope-consolidation` brought this file in
-- while V253__align_table_node_outputs.sql was already present (and applied to
-- existing dev DBs via flyway_schema_history). align_table_node_outputs kept
-- V253 to preserve the history record; this migration moved to V258 (next
-- free slot after V256 + V257 from the PAYG/ENT pricing refresh).
--
-- Original intent (2026-05-18):
-- Closes the schema gap that forced WebhookTokenService.deleteTokensForWorkflowScoped
-- to be @TolerantScope("schema gap"): the table had only organization_id, so a
-- caller in any workspace could delete legacy NULL-org tokens by knowing the
-- workflow UUID. Backfill from the parent workflow's tenant_id; future tokens
-- get the column populated on insert (handled in WebhookTokenService).
--
-- Additive migration - no breaking change. NOT NULL deferred to a follow-up
-- once orphan legacy rows (workflow deleted but token left) are reconciled.

ALTER TABLE trigger.webhook_tokens
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(255);

-- Backfill from the parent workflow's tenant_id. Cross-schema lookup is one-shot
-- (this migration is the only place that touches orchestrator.workflows from
-- trigger.*); subsequent reads/writes stay schema-local.
UPDATE trigger.webhook_tokens t
SET tenant_id = w.tenant_id
FROM orchestrator.workflows w
WHERE t.workflow_id = w.id
  AND t.tenant_id IS NULL;

-- Partial index - only on populated rows, supports the strict-isolation
-- WHERE clause in WebhookTokenService.deleteTokensForWorkflowScoped without
-- bloating with legacy orphans.
CREATE INDEX IF NOT EXISTS idx_webhook_tokens_tenant
    ON trigger.webhook_tokens (tenant_id)
    WHERE tenant_id IS NOT NULL;
