-- Extend the approval node's `delegation` param docs with the custom inline-button
-- labels (approveLabel / rejectLabel) and the previously-undocumented image field.
--
-- WHY: the delegation now accepts optional custom Telegram button text
-- (approveLabel / rejectLabel); blank = the defaults "Approve" / "Reject". Only the
-- displayed text changes, the approve/reject outcome is unaffected. The agent-facing
-- node docs must list these so the builder LLM can set them. The image field was
-- already emitted by the exporter/parser but never made it into this doc string
-- (it stayed out of sync with WorkflowBuilderPrompts); document it here too.
--
-- Runs under the orchestrator search_path (see beforeEachMigrate.sql); same pattern as
-- V394 / V396. Idempotent: overwrites only the delegation key when present.
UPDATE node_type_documentation
SET parameters = parameters || jsonb_build_object(
        'delegation',
        jsonb_build_object(
            'type', 'object',
            'required', false,
            'description', 'Optional external-channel delegation. Object with: channel (only ''telegram''), chatId (destination chat id, {{...}} templates allowed), credentialId (optional numeric id to pin a specific Telegram bot credential; omit it and the send uses the user''s own Telegram credential automatically), messageTemplate (optional message body, {{...}} allowed; defaults to the resolved contextTemplate), image (optional image, {{...}} allowed; a file output from another node such as an interface screenshot, or an image URL; non-blank = the message is sent as a photo with the text as caption), allowedUserIds (optional array of Telegram user ids allowed to decide; empty = anyone in the chat), approveLabel (optional custom approve-button text, {{...}} allowed; default ''✅ Approve''), rejectLabel (optional custom reject-button text, {{...}} allowed; default ''❌ Reject''). approveLabel/rejectLabel change only the button text, not the approve/reject outcome. The channel message shows Approve/Reject buttons; a button tap resolves this approval exactly like an in-app decision.'
        )
    ),
    updated_at = NOW()
WHERE type = 'approval'
  AND parameters ? 'delegation';
