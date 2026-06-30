-- V173: flag_flip_audit table for tenant-flag forensics.
--
-- Introduced by P2.1 of the execution-kernel scaling roadmap. Provides an audit
-- trail for every per-tenant flag flip - initially scoped to
-- `state-snapshot.elide-running-nodes` but the schema is generic and supports
-- any future kernel-runtime flag that requires forensics.
--
-- Write contract (enforced at the service layer in FlagFlipAuditWriter):
--   • Sync, same-TX as the flag-mutation method. NO REQUIRES_NEW. NO async.
--     NO @TransactionalEventListener(AFTER_COMMIT) - that would race with
--     crash-loss on a JVM kill between commit and listener.
--   • If the audit insert throws, the entire TX rolls back AND the flag is
--     NOT flipped (fail-the-flip - preserves "no flip without audit row").
--
-- Indexes (per execution-kernel roadmap rev12 §7.6 / audit C D4):
--   • Primary  : (flag_name, tenant_id, created_at DESC) for "last N flips
--     for flag X / tenant Y" - the dominant ops query.
--   • Secondary: (actor, created_at DESC) for actor-search forensics
--     ("who flipped what?") which the primary index cannot serve.
--
-- Retention: 90 days, purged hourly by FlagFlipAuditPurgeService (modeled on
-- the trigger-service TriggerStateAuditLogPurgeService).

CREATE TABLE IF NOT EXISTS orchestrator.flag_flip_audit (
    id          BIGSERIAL PRIMARY KEY,
    flag_name   TEXT        NOT NULL,
    tenant_id   TEXT,
    old_value   TEXT,
    new_value   TEXT,
    actor       TEXT,
    reason      TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_flag_flip_audit_flag_tenant_created
    ON orchestrator.flag_flip_audit (flag_name, tenant_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_flag_flip_audit_actor_created
    ON orchestrator.flag_flip_audit (actor, created_at DESC);

-- Retention-purge support: index the cutoff column independently for the
-- bulk-DELETE bounded by created_at. The composite indexes above don't help
-- the WHERE created_at < cutoff scan because flag_name leads.
CREATE INDEX IF NOT EXISTS idx_flag_flip_audit_created_at
    ON orchestrator.flag_flip_audit (created_at);

COMMENT ON TABLE orchestrator.flag_flip_audit IS
    'Per-tenant flag flip audit trail. Sync same-TX writes from FlagFlipAuditWriter. 90-day retention via FlagFlipAuditPurgeService.';
