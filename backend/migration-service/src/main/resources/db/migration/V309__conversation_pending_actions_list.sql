-- V309: Multiple parallel pending actions per conversation.
--
-- The agent now raises approval/authorization cards asynchronously (without pausing the
-- run), so a single turn can leave SEVERAL cards waiting at once (e.g. one credential
-- approval + one tool authorization). The legacy single `pending_action` jsonb holds at
-- most one; this adds a `pending_actions` jsonb ARRAY that is the source of truth for the
-- chat's parallel cards. Each element has the SAME shape as `pending_action`
-- (waiting_for + service_approval/tool_authorization fields + created_at/expires_at).
--
-- `pending_action` is kept in sync with the FIRST element for backward compatibility with
-- single-action readers (Conversation.getWaitingFor, credential-config resume). An empty
-- list clears both. See PendingActionService.setPendingActions.

ALTER TABLE conversation.conversations
    ADD COLUMN IF NOT EXISTS pending_actions jsonb NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN conversation.conversations.pending_actions IS
    'List of parallel pending actions (approval/authorization cards) awaiting the user. Each element mirrors the legacy pending_action shape. pending_action stays in sync with element[0] for backward compatibility.';
