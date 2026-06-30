-- V135: Widen workflow_pending_signals.signal_type to fit BROWSER_USER_TAKEOVER (21 chars).
--
-- The original V1 schema declared signal_type VARCHAR(20). The new
-- SignalType.BROWSER_USER_TAKEOVER value is 21 characters and would fail
-- to insert. The sibling table workflow_signal_waits.signal_type was
-- already VARCHAR(30) (V1) and needs no change.

ALTER TABLE orchestrator.workflow_pending_signals
    ALTER COLUMN signal_type TYPE VARCHAR(30);
