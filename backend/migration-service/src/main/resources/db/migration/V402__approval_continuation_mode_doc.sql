-- User Approval node: continuationMode build param (split-context continuation).
--
-- WHY: the approval node now accepts `continuationMode` ('all_items' | 'per_item',
-- default 'all_items'). Inside a split (one approval per item), 'all_items' keeps the
-- current behavior (downstream steps start once, after every item's approval is
-- decided) while 'per_item' lets each approved/rejected item continue its own
-- downstream chain immediately; the first cross-item consumer (merge, aggregate,
-- loop, fork, nested split) still waits for all items. Outside a split the setting
-- has no effect. The agent-facing node library must document it so agents can build
-- per-item approval pipelines.
--
-- Same pattern as V400 (interface video params): jsonb_set with create=true is
-- idempotent (overwrites/creates the key on every run). Schema-qualified because
-- the search_path is reset between migrations.
SET search_path TO orchestrator;

UPDATE orchestrator.node_type_documentation
SET parameters = jsonb_set(
        parameters,
        '{continuationMode}',
        '{
            "type": "string",
            "enum": ["all_items", "per_item"],
            "default": "all_items",
            "required": false,
            "description": "Split-context continuation. Only matters when this approval runs inside a split (one approval per item). ''all_items'' (default): downstream steps start once, after every item''s approval is decided. ''per_item'': each approved/rejected item continues its own downstream chain immediately (use it to process each approved item without waiting for the rest of the batch); the first cross-item node (merge, aggregate, loop, fork, nested split) still waits for all items. Outside a split the setting has no effect. Unknown values fall back to ''all_items''."
        }'::jsonb,
        true
    ),
    updated_at = NOW()
WHERE type = 'approval';
