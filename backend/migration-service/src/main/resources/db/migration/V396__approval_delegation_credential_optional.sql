-- Reword the approval node's `delegation` param docs: credentialId is OPTIONAL.
--
-- WHY: the first live agent-built delegated approval shipped without a credentialId
-- (the builder LLM rarely knows the numeric credential id), the old docs said the id
-- was needed, validation warned, and the run silently sent nothing. The notifier now
-- falls back to the user's own Telegram credential when the id is absent (the same
-- resolution a telegram step gets with no explicit credential), so the agent-facing
-- docs must stop presenting credentialId as required.
--
-- Runs under the orchestrator search_path (see beforeEachMigrate.sql); same pattern as
-- V394. Idempotent: overwrites only the delegation key when present.
UPDATE node_type_documentation
SET parameters = parameters || jsonb_build_object(
        'delegation',
        jsonb_build_object(
            'type', 'object',
            'required', false,
            'description', 'Optional external-channel delegation. Object with: channel (only ''telegram''), chatId (destination chat id, {{...}} templates allowed), credentialId (optional numeric id to pin a specific Telegram bot credential; omit it and the send uses the user''s own Telegram credential automatically), messageTemplate (optional message body, {{...}} allowed; defaults to the resolved contextTemplate), allowedUserIds (optional array of Telegram user ids allowed to decide; empty = anyone in the chat). The channel message shows Approve/Reject buttons; a button tap resolves this approval exactly like an in-app decision.'
        )
    ),
    updated_at = NOW()
WHERE type = 'approval'
  AND parameters ? 'delegation';

-- Workspace scoping for the default-credential fallback and post-resolution edits.
-- The delegation send runs on an async listener thread with NO bound HTTP request,
-- so the org context must be carried explicitly: it is captured from the run at
-- delivery creation and re-bound (TenantResolver.runWithOrgScope) around every
-- catalog call, otherwise an org-workspace run would resolve only PERSONAL
-- credentials and miss org-shared ones. Null = personal scope, matching
-- workflow_runs.org_id.
ALTER TABLE orchestrator.approval_channel_deliveries
    ADD COLUMN IF NOT EXISTS org_id VARCHAR(100);

COMMENT ON COLUMN orchestrator.approval_channel_deliveries.org_id IS
    'Workspace (organization) id of the run at delivery creation; re-bound around '
    'catalog calls on async threads so org-shared credentials resolve. Null = personal scope.';
