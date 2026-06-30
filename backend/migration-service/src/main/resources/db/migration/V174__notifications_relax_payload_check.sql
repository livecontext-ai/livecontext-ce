-- V174: Relax `chk_notif_payload_v1` to only require `status`.
--
-- V172 required both `status` AND `endedAt` in the payload. That fits the
-- only V1 producer (RUN_FAILED via WorkflowRunTerminatedEvent) but blocks
-- future categories where `endedAt` is meaningless:
--   - APPROVAL_PENDING: the run is awaiting decision; there is no end time.
--   - QUOTA_EXHAUSTED: the budget rejection happens at decision time, not
--     at a run-end.
--   - CRED_EXPIRED: the credential expiry has its own timestamp; runs are
--     unaffected.
--
-- Relaxing the CHECK to `payload ? 'status'` only is forward-compatible:
-- - Existing rows (RUN_FAILED + WorkflowEpochFailedEvent shipped in V172/P0)
--   already carry both keys, so they trivially satisfy the relaxed
--   constraint.
-- - Future producers MUST still emit `status` (a string discriminator like
--   "failed" / "pending" / "expired") - that's what the bell renders as the
--   severity dot color and what the read-side aggregator buckets on.
--
-- subject_type CHECK (`chk_notif_subject_type_v1`) is NOT relaxed here -
-- we keep it tight at WORKFLOW until P3+ adds APPLICATION / AGENT_TASK and
-- explicitly extends the allow-list with a documented migration.
--
-- Atomicity: Postgres supports transactional DDL, and Flyway runs each
-- migration script in a single transaction by default. If ADD CONSTRAINT
-- fails (it shouldn't - every existing row has `status` per the V172
-- emitter contract), Flyway aborts the script and the DROP is rolled
-- back. No "table without payload check" intermediate state is observable.

ALTER TABLE orchestrator.notifications
    DROP CONSTRAINT IF EXISTS chk_notif_payload_v1;

ALTER TABLE orchestrator.notifications
    ADD CONSTRAINT chk_notif_payload_v1
        CHECK (payload ? 'status');

COMMENT ON CONSTRAINT chk_notif_payload_v1 ON orchestrator.notifications IS
    'V174 relaxed: payload only requires the ''status'' string discriminator. '
    'Category-specific keys (endedAt, runIdPublic, taskId, expiresAt, …) are '
    'documented per-producer in the emitter Javadoc.';
