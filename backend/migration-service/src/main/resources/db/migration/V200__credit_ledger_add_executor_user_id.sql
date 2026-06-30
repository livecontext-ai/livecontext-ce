-- PR11 prerequisite (was PR8b §7.5 - brought forward as PR11 dependency).
--
-- Adds `credit_ledger.executor_user_id` so per-member quota enforcement
-- (V199 `auth.org_member_quota_limit`) can sum a member's consumption in
-- O(index) time. Pre-PR8, `user_id` IS the executor. Post-PR8 with Q1=b
-- redirect ON (`Q1B_CREDIT_REDIRECT_ENABLED=true`), `user_id` carries the
-- PAYER (org owner) - losing the executor identity to a description-string
-- breadcrumb (LIKE-scan only, not indexable).
--
-- This migration:
--   1) Adds the column NULLABLE - every pre-V200 row stays valid.
--   2) Creates a partial covering index for the per-period sum-by-executor
--      query used by MemberQuotaService.checkCreditsCap.
--   3) Does NOT backfill `executor_user_id = user_id` for pre-V200 rows
--      (audit C 2026-05-12 - unbounded UPDATE on credit_ledger can lock
--      writes for tens of minutes on a multi-million-row prod table).
--
-- Backfill strategy (operator runbook):
--
--   The cap enforcement query `WHERE executor_user_id = ?` silently
--   excludes pre-V200 rows (NULL doesn't match). This means quota caps
--   under-count consumption from rows written BEFORE V200 deploy. This
--   is acceptable because:
--     - Q1B_CREDIT_REDIRECT_ENABLED defaults FALSE until PR11d ships.
--     - No org_member_quota_limit rows exist on day-one (admins must
--       configure them via PR11c REST API which isn't shipped yet).
--     - The first billing cycle post-deploy is the first time caps can
--       be hit; by then, all NEW consumes carry executor_user_id.
--
--   If/when the under-count needs closing (e.g. before flipping Q1=b ON
--   with active caps), run the batched backfill OUT OF BAND from psql:
--
--     DO $$
--     DECLARE
--       batch_size INT := 50000;
--       updated INT := -1;
--     BEGIN
--       WHILE updated <> 0 LOOP
--         UPDATE auth.credit_ledger cl
--         SET executor_user_id = cl.user_id
--         WHERE cl.id IN (
--           SELECT id FROM auth.credit_ledger
--           WHERE executor_user_id IS NULL
--           ORDER BY id LIMIT batch_size
--           FOR UPDATE SKIP LOCKED
--         );
--         GET DIAGNOSTICS updated = ROW_COUNT;
--         COMMIT;  -- requires SET autocommit ON or psql \\\\set AUTOCOMMIT on
--         RAISE NOTICE 'Backfilled batch: %', updated;
--       END LOOP;
--     END $$;
--
--   For the index-coverage assertion below: this index intentionally
--   matches the JPQL filter EXACTLY (`amount < 0`). Rejection-audit rows
--   (source_type ending in `_REJECTED`) are zero-amount by construction
--   (see CreditService.deductCredits line ~830), so they're excluded by
--   the `amount < 0` predicate without needing a source_type filter in
--   the index. Defence-in-depth: if a future audit refactor lands non-
--   zero rejection amounts, the JPQL keeps a `NOT LIKE '%_REJECTED'`
--   guard but the index would then no longer fully cover - surface as
--   a performance regression in EXPLAIN, not a correctness bug.

ALTER TABLE auth.credit_ledger
    ADD COLUMN executor_user_id BIGINT NULL;

-- Covering partial index for MemberQuotaService.sumDebitedByExecutorSince.
-- Matches JPQL `WHERE executor_user_id = ? AND created_at >= ? AND amount < 0`.
CREATE INDEX idx_cl_executor_date
    ON auth.credit_ledger(executor_user_id, created_at DESC)
    WHERE amount < 0;

COMMENT ON COLUMN auth.credit_ledger.executor_user_id IS
    'Identity of the user that fired the consume (BEFORE Q1=b payer '
    'redirect). NULL for legacy rows pre-V200 (treated as user_id by '
    'quota enforcement, but ONLY for the same user_id - the cap-sum '
    'query filters on executor_user_id = ? so NULL rows are skipped, '
    'causing a documented one-billing-cycle under-count). Indexed via '
    'idx_cl_executor_date. Set on every CreditService.deductCredits '
    'write path from PR11 onward.';
