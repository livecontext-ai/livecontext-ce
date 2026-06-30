-- User Approval node: configurable approval context.
-- The node's contextTemplate (literal + {{...}}) is resolved at yield time and the rendered
-- display string is persisted here, then surfaced to the human approver as `approvalContext`
-- in the signals payload (distinct from `split_item_data`/itemContext, which is the auto split item).
-- Plain display text (no restoration keys) -> a text column, not JSONB. Nullable: approval nodes
-- without a template, and all non-approval signals, leave it NULL.
ALTER TABLE orchestrator.workflow_signal_waits
    ADD COLUMN IF NOT EXISTS approval_context text;
