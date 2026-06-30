-- V48: Add content_preview column to tool_results for hot/cold loading pattern
-- content_preview stores first 3000 chars (used by ConversationHistoryConverter)
-- content_full remains for on-demand access via get_tool_result tool

ALTER TABLE conversation.tool_results
    ADD COLUMN IF NOT EXISTS content_preview TEXT;

-- Backfill existing rows: preview = first 3000 chars of content_full
UPDATE conversation.tool_results
SET content_preview = CASE
    WHEN content_full IS NOT NULL AND length(content_full) > 3000
        THEN left(content_full, 3000) || '...[truncated, use get_tool_result(tool_call_id="' || COALESCE(tool_call_id, id::text) || '") for full content]'
    ELSE content_full
END
WHERE content_preview IS NULL AND content_full IS NOT NULL;
