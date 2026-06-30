-- V184: Clamp negative storage usage and pin invariant at the DB level.
--
-- Symptom: "Usage Trend" chart in /settings/storage rendered areas below the
-- x-axis (notably EXECUTION_DATA, occasionally INTERFACES / CONFIGURATION).
--
-- Root cause: TenantStorageBreakdownRepository.incrementUsage did not clamp the
-- ON CONFLICT branch, so trackDelete deltas larger than the running trackSave
-- total drove used_bytes / item_count below zero. The 02:30 history snapshot
-- captured the drift before reconciliation at 03:00 could overwrite it.
--
-- The Java/SQL clamp ships in the same commit; this migration repairs the
-- already-corrupted rows and adds a CHECK constraint so the invariant cannot
-- regress at the DB level.
--
-- Idempotent: GREATEST(x, 0) is a no-op on rows already at or above zero.
--
-- Deploy window safety: migration-service deploys before the app services per
-- CLAUDE.md. During the 5-10 min window where V184 is applied but old service
-- jars still run the unclamped incrementUsage SQL, a trackDelete whose
-- cumulative would drive used_bytes < 0 will be rejected by the new CHECK
-- constraint. The application path is wrapped in REQUIRES_NEW + try/catch
-- (StorageBreakdownService.increment), so the exception logs WARN and never
-- bubbles to the user-facing transaction. The next daily reconciliation at
-- 02:00 re-establishes the absolute truth. Brief under-counting during the
-- window is the acceptable trade-off for the permanent DB invariant.
--
-- Known upstream producers not addressed here (clamp surfaces them via WARN log
-- in StorageBreakdownService.logIfWouldUnderflow - separate follow-up):
--   * StateSnapshotService.saveSnapshotFullRewrite - emits a trackSizeChange
--     using json.getBytes(UTF_8).length deltas, but reconciliation uses
--     pg_column_size (TOAST-compressed). The two metrics drift apart, which
--     is why EXECUTION_DATA accumulated -10.7 MB over 15 days for tenant 1.
--   * InterfaceService update paths - compute oldSize AFTER mutating the
--     entity, so the size delta is ~0 instead of the real change.

-- 1) Backfill: clamp every negative used_bytes / item_count to zero.
UPDATE storage.tenant_storage_breakdown
SET used_bytes = GREATEST(used_bytes, 0),
    item_count = GREATEST(item_count, 0)
WHERE used_bytes < 0 OR item_count < 0;

UPDATE storage.storage_usage_history
SET used_bytes = GREATEST(used_bytes, 0),
    item_count = GREATEST(item_count, 0)
WHERE used_bytes < 0 OR item_count < 0;

-- 2) DB-level invariant: future writes cannot produce negative values, even if
--    a new application code path is introduced that bypasses incrementUsage.
ALTER TABLE storage.tenant_storage_breakdown
    ADD CONSTRAINT tsb_used_bytes_nonneg CHECK (used_bytes >= 0),
    ADD CONSTRAINT tsb_item_count_nonneg CHECK (item_count >= 0);

ALTER TABLE storage.storage_usage_history
    ADD CONSTRAINT suh_used_bytes_nonneg CHECK (used_bytes >= 0),
    ADD CONSTRAINT suh_item_count_nonneg CHECK (item_count >= 0);
