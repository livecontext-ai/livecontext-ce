-- V298: Persisted user authorization of sensitive tool actions, per conversation.
--
-- Stores { "always": [...], "once": [...] } of rule keys ("tool:action", e.g.
-- "application:acquire"). See ToolAuthorizationApprovalService.
--   always - "Toujours autoriser dans cette conversation": skips the authorization
--            card for every subsequent turn.
--   once   - single-shot "Autoriser": injected into the next turn's credentials, then
--            cleared (per-call default).
--
-- Sibling of approved_services (credential approvals) but kept distinct: tool-action
-- rules are gated by ToolAuthorizationGuard, not by credential existence.

ALTER TABLE conversation.conversations
    ADD COLUMN IF NOT EXISTS approved_tool_actions jsonb NOT NULL DEFAULT '{}'::jsonb;

COMMENT ON COLUMN conversation.conversations.approved_tool_actions IS
    'User-authorized sensitive tool actions. {"always":[...] persisted, "once":[...] single-shot consumed on the next turn}. Rule keys are "tool:action" (e.g. application:acquire).';
