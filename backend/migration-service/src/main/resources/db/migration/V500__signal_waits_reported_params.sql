-- A node that PARKS on a signal could not report what it ran with.
--
-- An interface, an approval and a wait above its inline threshold all resolve their
-- configuration and then yield AWAITING_SIGNAL. The engine does not persist a step row on
-- that path (UnifiedExecutionEngine only emits an event), and when the signal resolves,
-- SignalResumeService writes the one row this node will ever have with
-- `input_data = {signal_type, signal_config, item_id, trigger_id, epoch}` - signal
-- bookkeeping, not the node's own parameters.
--
-- So the Params column showed, for every paused node in the product: while parked, the
-- CONFIGURED expressions (the fallback for a node with no row); after resuming, the signal
-- row's own fields under keys the plan does not use (`signal_config.durationMs` where the
-- plan says `duration`). The values the node actually resolved were never shown, on any
-- path, at any moment. An interface's variable_mapping - the wiring between the workflow's
-- data and an empty-looking page - was the most costly of those.
--
-- This column carries them from the yield to the resume. Nullable and additive: a signal
-- written before this migration simply has none, and the resume path falls back to what it
-- did before. It is written through ReportedParams, so it holds no credential and nothing
-- unbounded, exactly like the step row it ends up in.

SET search_path TO orchestrator;

ALTER TABLE workflow_signal_waits
    ADD COLUMN IF NOT EXISTS reported_params jsonb;

COMMENT ON COLUMN workflow_signal_waits.reported_params IS
    'The parking node''s resolved_params, captured at yield so the row written when the signal resolves can report what the node ran with rather than only what the signal held. Nullable: signals registered before V500 have none.';
