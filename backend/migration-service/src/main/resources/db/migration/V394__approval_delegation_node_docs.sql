-- Expose the delegated-approval surface in the User Approval node agent-facing docs.
--
-- WHY: V393 introduced external-channel delegation on the approval node (v1: Telegram
-- inline buttons). Two doc surfaces must advertise it so agents build and reference it
-- correctly: the `delegation` PARAM block (channel/credentialId/chatId/messageTemplate/
-- allowedUserIds) and the `delegated_channel` OUTPUT field (mirrored into the resolved
-- output by SignalResumeService, matching ApprovalNodeSpec and the node contract).
--
-- Runs under the orchestrator search_path (see beforeEachMigrate.sql); same pattern as
-- V389 (approval_context). Idempotent: only adds keys when absent.
UPDATE node_type_documentation
SET outputs = outputs || jsonb_build_object(
        'delegated_channel',
        jsonb_build_object(
            'type', 'string',
            'description', 'External channel the approval was delegated to (e.g. telegram). Present only when the node''s delegation is configured.'
        )
    ),
    updated_at = NOW()
WHERE type = 'approval'
  AND NOT (outputs ? 'delegated_channel');

UPDATE node_type_documentation
SET parameters = parameters || jsonb_build_object(
        'delegation',
        jsonb_build_object(
            'type', 'object',
            'required', false,
            'description', 'Optional external-channel delegation. Object with: channel (only ''telegram''), credentialId (numeric id of the user''s Telegram bot credential), chatId (destination chat id, {{...}} templates allowed), messageTemplate (optional message body, {{...}} allowed; defaults to the resolved contextTemplate), allowedUserIds (optional array of Telegram user ids allowed to decide; empty = anyone in the chat). The channel message shows Approve/Reject buttons; a button tap resolves this approval exactly like an in-app decision.'
        )
    ),
    updated_at = NOW()
WHERE type = 'approval'
  AND NOT (parameters ? 'delegation');
