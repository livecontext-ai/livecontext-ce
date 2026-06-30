-- V150: CONCURRENTLY partial index on credit_ledger.expires_at for the reserve sweeper.
--
-- The PlatformMarkupReserveSweeper queries:
--   SELECT * FROM credit_ledger
--   WHERE source_type = 'PLATFORM_MARKUP_RESERVE' AND expires_at < now()
-- every 5 minutes. Without this partial index, that's a seq scan over the full
-- credit_ledger every 5 minutes - production-killer on a 50M-row table.
--
-- Partial predicate keeps the index tiny: only un-committed reservations are
-- indexed. As soon as commit/release flips source_type to PLATFORM_MARKUP /
-- PLATFORM_MARKUP_RELEASED*, the row drops out of the index.
--
-- See V149 for tx-wrapper rationale.

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_cl_expires_pending
    ON auth.credit_ledger (expires_at)
    WHERE source_type = 'PLATFORM_MARKUP_RESERVE' AND expires_at IS NOT NULL;
