-- V148: Unify pricing pin table for workflow + chat scopes (Phase 1a - additive only).
--
-- This migration runs in the standard Flyway transaction (one statement per script,
-- one tx per script per Flyway default). NO CREATE INDEX CONCURRENTLY here - those
-- live in V149/V150 with `executeInTransaction=false` headers.
--
-- The batched UPDATE for `scope_id` backfill lives in V151 (BaseJavaMigration) so
-- it can chunk + COMMIT per batch without breaking Flyway's tx contract. The
-- ALTER COLUMN scope_id SET NOT NULL is applied at the end of V151.
--
-- VALIDATE CONSTRAINT for the new credit_ledger.pin_id FK is deferred to V152
-- (separate migration, low-traffic deploy window - 3-8 min seq scan on 50M rows).
--
-- Rollback (per OPERATOR_RUNBOOK §v148-deploy):
--   ALTER TABLE auth.workflow_run_pricing_pin
--       DROP COLUMN scope_kind, DROP COLUMN scope_id, DROP COLUMN last_used_at;
--   ALTER TABLE auth.credit_ledger
--       DROP CONSTRAINT IF EXISTS fk_cl_pin, DROP COLUMN pin_id, DROP COLUMN expires_at;
--   ALTER TABLE auth.subscription DROP COLUMN delinquent;
--   DROP TABLE IF EXISTS auth.shedlock;
--   ALTER TABLE auth.workflow_run_pricing_pin ALTER COLUMN run_id TYPE VARCHAR(64);
--   -- restore old UNIQUE if dropped:
--   ALTER TABLE auth.workflow_run_pricing_pin
--       ADD CONSTRAINT workflow_run_pricing_pin_run_id_platform_credential_id_key
--       UNIQUE (run_id, platform_credential_id);

SET search_path TO auth;
SET lock_timeout = '10s';
SET statement_timeout = '60s';

-- 1. Widen run_id 64 → 128 to fit STREAM scope_ids like "<conv-uuid>:<turn-uuid>" (~73 chars).
--    Metadata-only in PG14+ (varlena under the hood; both are TEXT-backed); near-instant on 50M rows.
ALTER TABLE auth.workflow_run_pricing_pin
    ALTER COLUMN run_id TYPE VARCHAR(128);

-- 2. Add scope discriminator + scope_id mirror column. scope_id is NULL initially;
--    V151 backfills it (UPDATE SET scope_id = run_id) in 50K-row chunks, then sets NOT NULL.
ALTER TABLE auth.workflow_run_pricing_pin
    ADD COLUMN IF NOT EXISTS scope_kind TEXT NOT NULL DEFAULT 'RUN'
        CHECK (scope_kind IN ('RUN', 'STREAM'));

ALTER TABLE auth.workflow_run_pricing_pin
    ADD COLUMN IF NOT EXISTS scope_id VARCHAR(128) NULL;

-- 3. last_used_at - single TTL column. Sweeper checks `last_used_at < now() - 30d`
--    for STREAM-scoped pins; RUN pins are cancelled at run-terminal regardless.
ALTER TABLE auth.workflow_run_pricing_pin
    ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 4. Drop old (run_id, platform_credential_id) UNIQUE; replace with scope-aware partial UNIQUE.
--    Partial predicate `cancelled = FALSE` lets a new run with the same scope_id replace a
--    cancelled pin without conflict. ON CONFLICT TARGET in pinForScope must repeat the
--    WHERE predicate verbatim.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'workflow_run_pricing_pin_run_id_platform_credential_id_key') THEN
        ALTER TABLE auth.workflow_run_pricing_pin
            DROP CONSTRAINT workflow_run_pricing_pin_run_id_platform_credential_id_key;
    END IF;
END $$;

-- Partial UNIQUE - created standard (not CONCURRENTLY) so it ships in V148's tx.
-- On 50M rows this takes seconds (b-tree build), table-locked but brief.
CREATE UNIQUE INDEX IF NOT EXISTS uq_pcpp_scope_credential_live
    ON auth.workflow_run_pricing_pin (scope_kind, scope_id, platform_credential_id)
    WHERE cancelled = FALSE;

-- 5. credit_ledger: widen source_type for new lifecycle values.
--    Existing length=30 fits 'IMAGE_GENERATION_BYOK' (21) and 'PLATFORM_MARKUP_RESERVE' (23)
--    but NOT 'PLATFORM_MARKUP_RELEASED_TIMEOUT' (32). Widen to 64 for headroom.
--    Metadata-only on PG14+ (varlena). Safe on 50M rows.
ALTER TABLE auth.credit_ledger
    ALTER COLUMN source_type TYPE VARCHAR(64);

-- 6. credit_ledger: pin_id link + expires_at TTL.
--    pin_id allows the sweeper to skip rows referenced by an active reservation
--    even if last_used_at says it's stale. expires_at is set per-call by the caller
--    (10/15/60/1440 min depending on scope kind, see SourceIdBuilder + CatalogToolBillingService).
ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS pin_id BIGINT NULL;

ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ NULL;

-- 7. FK on pin_id - added NOT VALID (instant), validation deferred to V152.
--    NOT VALID accepts pre-existing NULL values without scanning; new INSERTs are
--    still constraint-checked. ON DELETE SET NULL so a swept pin doesn't cascade
--    into ledger row deletion (audit must survive).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_cl_pin') THEN
        ALTER TABLE auth.credit_ledger
            ADD CONSTRAINT fk_cl_pin FOREIGN KEY (pin_id)
            REFERENCES auth.workflow_run_pricing_pin(id)
            ON DELETE SET NULL
            NOT VALID;
    END IF;
END $$;

-- 8. Subscription delinquent flag - gates new reservations when balance is in deficit
--    after a partial-charge or floored commit. PG11+ fast-path: ADD COLUMN with
--    constant DEFAULT is metadata-only (no row rewrite); near-instant on 1M rows.
--    Cleared by clearDelinquentIfPositive (helper called after any positive balance
--    transition: grantCredits, releaseReservation, refundPlatformMarkup).
--    Invariant maintained by code: `delinquent = TRUE ⇒ remaining_credits ≤ 0`.
ALTER TABLE auth.subscription
    ADD COLUMN IF NOT EXISTS delinquent BOOLEAN NOT NULL DEFAULT FALSE;

-- 9. ShedLock table for distributed locks. Used by:
--      - PlatformMarkupReserveSweeper (5-min cadence)
--      - PlatformCredentialMarkupBootstrapValidator (15-min cadence)
--      - StreamPinCleanupScheduler (hourly cadence)
--    Idempotent guard - auth-service may already have it from a prior shedlock setup.
CREATE TABLE IF NOT EXISTS auth.shedlock (
    name VARCHAR(64) PRIMARY KEY,
    lock_until TIMESTAMP NOT NULL,
    locked_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

-- 10. Indexes for sweeper + analytics. Standard CREATE INDEX (not CONCURRENTLY)
--    on workflow_run_pricing_pin since the table is small (typical: <1M rows).
--    The credit_ledger indexes - bigger table - go in V149/V150 with CONCURRENTLY.
CREATE INDEX IF NOT EXISTS idx_pcpp_last_used_stream_sweep
    ON auth.workflow_run_pricing_pin (last_used_at)
    WHERE scope_kind = 'STREAM' AND cancelled = FALSE;
