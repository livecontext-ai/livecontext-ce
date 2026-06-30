-- Phase A2 (archi-refoundation 2026-05-04) - persist last_event_seq per run.
--
-- Purpose: cross-restart seed for WsEventSequencer. Without this column, a pod
-- restart resets the in-memory AtomicLong (or invalidates the Redis seq key on
-- TTL expiry), and the frontend `lastKnownSeq` rejects all events post-restart
-- because new seq values start at 0 (< whatever the FE last saw).
--
-- The column is bumped by a @Scheduled flusher every 5 seconds (batched
-- `UPDATE workflow_runs ... FROM (VALUES (?, ?), ...)` to avoid 200 RPS DB
-- writes at 1000 concurrent runs). Worst-case drop window post-restart: 5s of
-- events. Frontend recovers via WS-reconnect → REST `/state` → seq alignment
-- in `applyTrackingFromApi`.
--
-- DEFAULT 0 + NOT NULL is metadata-only on PostgreSQL ≥ 11 (instant rewrite).
-- Existing rows on prod (~10M) take milliseconds.
--
-- Rollback: irreversible without a new migration (Flyway one-way). The column
-- is harmless to leave behind if the application code is rolled back - old
-- code never reads or writes it.
ALTER TABLE workflow_runs
    ADD COLUMN last_event_seq BIGINT NOT NULL DEFAULT 0;

-- Index NOT created on this column: it is only used as a lookup target via the
-- PK (runId), and the flusher writes by batched WHERE r.run_id_public IN (...).
-- The PK index already covers that path.
