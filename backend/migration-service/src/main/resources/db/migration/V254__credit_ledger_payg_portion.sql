-- ============================================================================
-- V254 - credit_ledger.payg_portion: split-bucket tracking on reservation rows.
--
-- Context: V250 introduced the two-bucket model (sub + payg). tryReserveMarkup
-- atomically debits both buckets via splitBuckets(...), but commit / release /
-- sweeper refund all credited the SUB bucket only - asymmetric to the debit.
--
-- The silent failure mode: a user buys a PAYG top-up, runs a reservation that
-- drains the PAYG bucket, then the reservation expires (auto-release-timeout)
-- or commits with a refund delta. The refund lands on the SUB bucket. At the
-- next renewal (PLAN_RESET), the SUB bucket is overwritten with the plan grant
-- and the "phantom" PAYG dollars are gone - the user's paid money silently
-- evaporates with no ledger trace.
--
-- Fix: store the per-reservation PAYG portion at debit time on the RESERVE row,
-- then refund proportionally at commit/release. Pre-V254 rows default to 0 so
-- the legacy refund-to-sub behaviour is preserved for historical reservations.
-- New reservations inserted post-deploy carry the correct split.
--
-- This column is only read on PLATFORM_MARKUP_RESERVE→commit/release transitions.
-- It is NOT a generated history column - once a reserve commits or releases, the
-- ledger row's source_type changes and payg_portion becomes informational only.
-- ============================================================================

ALTER TABLE auth.credit_ledger
    ADD COLUMN IF NOT EXISTS payg_portion DECIMAL(15, 4) NOT NULL DEFAULT 0;

COMMENT ON COLUMN auth.credit_ledger.payg_portion IS
    'V254: portion of this reservation debit that drained the PAYG bucket (vs the sub bucket). Set on PLATFORM_MARKUP_RESERVE insert, read on commit/release to refund symmetrically. 0 on non-reserve rows and on legacy (pre-V254) reserves - historical reserves still refund-to-sub, which preserves total balance but loses bucket fidelity.';
