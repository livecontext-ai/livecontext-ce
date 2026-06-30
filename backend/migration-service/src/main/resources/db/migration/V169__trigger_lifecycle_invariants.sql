-- V169: Trigger lifecycle invariants - design v3.5 follow-up to V137.
--
-- Adds two pieces of the v3.5 architecture (see project memory:
-- project_trigger_lifecycle_v3_5_design.md):
--
--   1. trigger_state_audit_log table - append-only forensic record of every
--      lifecycle transition. Answers "why did my trigger stop?" / "who
--      suspended this row?" with one query, without scanning logs.
--   2. enabled_state_lockstep CHECK constraints (NOT VALID) - declares the
--      invariant {(legacy_bool = TRUE) ≡ (state = 'ACTIVE')} on the four
--      trigger tables that still carry the legacy boolean during the dual-
--      write window. The constraint is declared but NOT yet validated against
--      existing rows; v3.5 design deliberately defers VALIDATE CONSTRAINT to
--      V170 (after a soak period) so that operators can run a drift report
--      and reconcile any pre-V137 anomalies before flipping the enforcement
--      gate. New writes (post-V169) are checked by the CHECK regardless of
--      VALIDATE state - Postgres always enforces NOT VALID constraints on
--      INSERT and UPDATE; VALIDATE only adds a one-time scan over existing
--      rows to seal the back-catalogue.
--
-- Design refs:
--   • the project docs (round 7)
--   • project_trigger_lifecycle_v3_5_design.md (audit converged 9.06 → 9.36)
--
-- Deferred to V170 (next release after soak):
--   • VALIDATE CONSTRAINT on the four lockstep CHECKs
--   • REPLACE FUNCTION enforcing state-transition allow-list (DB CHECK trigger)
--
-- Deferred to V171 (rollback artifact, pre-baked alongside V170):
--   • Reverts V170's enforcement to no-op so a buggy enforcer can be flipped
--     off via Flyway in seconds rather than direct DB intervention.

-- =============================================================================
-- 1. Audit log table - append-only, sequence-backed seq column
-- =============================================================================
-- The sequence backs the `seq` column for monotonic ordering across
-- transactions (id alone is acceptable but seq makes the contract explicit
-- and tolerant of UUID PK changes if ever needed). Postgres sequences allow
-- gaps under concurrent commits + rollbacks - that's intentional and
-- documented; tests assert strict monotonicity, not gaplessness.
CREATE SEQUENCE IF NOT EXISTS "trigger".trigger_state_audit_log_seq;

CREATE TABLE IF NOT EXISTS "trigger".trigger_state_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    seq             BIGINT      NOT NULL DEFAULT nextval('"trigger".trigger_state_audit_log_seq'),
    -- Polymorphic reference: the trigger may be a schedule (UUID),
    -- webhook (UUID), chat/form endpoint (UUID), or webhook token (BIGINT).
    -- Stored as TEXT so all five types fit one column without a per-type
    -- table. Read-only forensic surface - not joined back to the trigger
    -- tables for FK semantics, just for queryable lineage.
    trigger_id      TEXT        NOT NULL,
    trigger_type    VARCHAR(40) NOT NULL,  -- schedule | webhook | chat | form | token
    from_state      VARCHAR(20) NULL,       -- NULL on first INSERT (no prior state)
    to_state        VARCHAR(20) NOT NULL,
    reason          VARCHAR(40) NULL,       -- one of TriggerLifecycleManager.Reason; NULL on arm
    source          VARCHAR(20) NOT NULL,   -- one of TriggerLifecycleManager.Source
    actor           TEXT        NULL,       -- user id or system identifier (free-form)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Index for "show me the history of trigger X" - the most common query shape.
CREATE INDEX IF NOT EXISTS idx_trigger_audit_log_trigger
    ON "trigger".trigger_state_audit_log (trigger_id, trigger_type, seq DESC);

-- Index for "what transitioned in the last N hours" - operational dashboard.
CREATE INDEX IF NOT EXISTS idx_trigger_audit_log_created_at
    ON "trigger".trigger_state_audit_log (created_at DESC);

COMMENT ON TABLE  "trigger".trigger_state_audit_log IS
    'Append-only audit of trigger lifecycle transitions (v3.5). Retention 30d default; bump when T&Cs land.';
COMMENT ON COLUMN "trigger".trigger_state_audit_log.seq IS
    'Sequence-backed monotonic ordering. Gaps OK under concurrent commits + rollbacks; strict monotonic only.';
COMMENT ON COLUMN "trigger".trigger_state_audit_log.trigger_id IS
    'Polymorphic id (UUID for schedule/webhook/chat/form, BIGINT-as-text for webhook tokens).';

-- =============================================================================
-- 2. enabled_state_lockstep CHECK constraints - NOT VALID, declared only
-- =============================================================================
-- Each of the four tables that still carry the legacy boolean alongside the
-- new state column gets a CHECK declaring "boolean and state agree". V137
-- backfilled the boolean → state mapping; new writes since V137 keep them
-- in sync via TriggerLifecycleManager. NOT VALID means existing rows are
-- not scanned at migration time (zero downtime).
--
-- IMPORTANT: NOT VALID CHECK constraints in Postgres are still enforced on
-- every UPDATE - including UPDATEs of unrelated columns on a drift row. So
-- any pre-existing drift between `state` and the legacy boolean would make
-- the row write-locked once the constraint lands (e.g. a reaper updating
-- `next_execution_at` would hit ConstraintViolation). To prevent this, the
-- migration first reconciles any drift by trusting `state` as the source of
-- truth (it's what TriggerLifecycleManager has been writing since V137).
-- The reconciliation is idempotent: zero rows touched if no drift exists,
-- correct rows touched if any pre-V137 anomaly slipped through.
--
-- VALIDATE CONSTRAINT (one-time existing-row scan) is still deferred to
-- V170 so ops can confirm zero drift remains before sealing the
-- back-catalogue.

-- 2.0 Pre-flight drift reconciliation. `state` is the source of truth - the
-- legacy boolean follows. We do NOT touch rows where they already agree.
UPDATE "trigger".scheduled_executions
   SET enabled = (state = 'ACTIVE')
 WHERE (enabled = TRUE) <> (state = 'ACTIVE');

UPDATE "trigger".standalone_webhooks
   SET is_active = (state = 'ACTIVE')
 WHERE (is_active = TRUE) <> (state = 'ACTIVE');

UPDATE "trigger".standalone_chat_endpoints
   SET is_active = (state = 'ACTIVE')
 WHERE (is_active = TRUE) <> (state = 'ACTIVE');

UPDATE "trigger".standalone_form_endpoints
   SET is_active = (state = 'ACTIVE')
 WHERE (is_active = TRUE) <> (state = 'ACTIVE');

-- Postgres lacks `ADD CONSTRAINT IF NOT EXISTS` for ALTER TABLE; the DO
-- blocks below provide idempotency by checking pg_constraint first. Each
-- block is independent so a re-run that hits a partially-applied state
-- still completes the remaining tables.

-- 2a. scheduled_executions  (legacy boolean: `enabled`)
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_namespace n ON n.oid = c.connamespace
        WHERE c.conname = 'enabled_state_lockstep'
          AND n.nspname = 'trigger'
    ) THEN
        ALTER TABLE "trigger".scheduled_executions
            ADD CONSTRAINT enabled_state_lockstep
            CHECK ((enabled = TRUE) = (state = 'ACTIVE'))
            NOT VALID;
    END IF;
END $$;

-- 2b. standalone_webhooks  (legacy boolean: `is_active`)
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t   ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = c.connamespace
        WHERE c.conname = 'is_active_state_lockstep'
          AND t.relname = 'standalone_webhooks'
          AND n.nspname = 'trigger'
    ) THEN
        ALTER TABLE "trigger".standalone_webhooks
            ADD CONSTRAINT is_active_state_lockstep
            CHECK ((is_active = TRUE) = (state = 'ACTIVE'))
            NOT VALID;
    END IF;
END $$;

-- 2c. standalone_chat_endpoints  (legacy boolean: `is_active`)
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t   ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = c.connamespace
        WHERE c.conname = 'is_active_state_lockstep'
          AND t.relname = 'standalone_chat_endpoints'
          AND n.nspname = 'trigger'
    ) THEN
        ALTER TABLE "trigger".standalone_chat_endpoints
            ADD CONSTRAINT is_active_state_lockstep
            CHECK ((is_active = TRUE) = (state = 'ACTIVE'))
            NOT VALID;
    END IF;
END $$;

-- 2d. standalone_form_endpoints  (legacy boolean: `is_active`)
DO $$ BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint c
        JOIN pg_class t   ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = c.connamespace
        WHERE c.conname = 'is_active_state_lockstep'
          AND t.relname = 'standalone_form_endpoints'
          AND n.nspname = 'trigger'
    ) THEN
        ALTER TABLE "trigger".standalone_form_endpoints
            ADD CONSTRAINT is_active_state_lockstep
            CHECK ((is_active = TRUE) = (state = 'ACTIVE'))
            NOT VALID;
    END IF;
END $$;

-- webhook_tokens has no legacy boolean (per V137 - state is sole source of
-- truth). No lockstep CHECK needed there.
