-- V170: Validate the v3.5 lockstep CHECK constraints declared in V169.
--
-- V169 added the 4 lockstep CHECK constraints as `NOT VALID` so existing rows
-- were not scanned at migration time (zero downtime). V169 also ran a
-- pre-flight UPDATE reconciliation aligning legacy boolean with `state` -
-- so by V170 there should be zero drift rows to fail validation.
--
-- VALIDATE CONSTRAINT performs a one-time existing-row scan and, on success,
-- flips the constraint from `convalidated=false` to `convalidated=true` in
-- pg_constraint. After this migration, ANY consumer of the constraint
-- metadata (Postgres planner optimizations, third-party tooling, drift
-- detectors) sees the constraint as fully enforced. New writes since V169
-- have already been checked - VALIDATE only seals the back-catalogue.
--
-- This migration is idempotent: re-running on an already-validated
-- constraint is a no-op for Postgres ≥ 11. We don't gate via DO blocks
-- because VALIDATE CONSTRAINT does not emit "already validated" errors.
--
-- If validation fails (drift row exists despite V169 reconciliation), the
-- migration aborts with a clear error citing the offending row. Recovery
-- path: investigate the drift, hand-correct the row, re-run V170. The
-- constraint is left in `NOT VALID` state on failure, so writes continue
-- working normally - only the back-catalogue scan is blocked.
--
-- Deferred to a future migration:
--   • CREATE FUNCTION + CREATE TRIGGER enforcing the state-transition
--     allow-list (ACTIVE↔SUSPENDED_*↔ARCHIVED with ARCHIVED terminal). This
--     needs a drift report on real production data first to avoid blocking
--     legitimate writes that happen to violate a presumed-impossible
--     transition. Deferred until that report is feasible.

ALTER TABLE "trigger".scheduled_executions
    VALIDATE CONSTRAINT enabled_state_lockstep;

ALTER TABLE "trigger".standalone_webhooks
    VALIDATE CONSTRAINT is_active_state_lockstep;

ALTER TABLE "trigger".standalone_chat_endpoints
    VALIDATE CONSTRAINT is_active_state_lockstep;

ALTER TABLE "trigger".standalone_form_endpoints
    VALIDATE CONSTRAINT is_active_state_lockstep;
