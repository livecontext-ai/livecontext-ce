-- ============================================================================
-- V324: Align email_inbox agent docs with EmailInboxNodeSpec.
-- V323 documented the mandatory runtime metadata key `node_type` in
-- node_type_documentation.outputs, but metadata keys (node_type, item_index,
-- item_id) are by convention NOT part of the documented output schema (see
-- send_email and every other node). NodeOutputSchemaCoherenceContractTest
-- enforces doc keys == NodeSpec keys, so re-set the outputs without node_type.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  outputs = '{
    "messages": {"type": "array", "description": "READ: list of messages. Each item: uid, from, to, cc, replyTo, subject, date, seen, flagged, folder, messageId, references, body, bodyHtml, snippet, hasAttachments, attachments[{filename, contentType, size, file?}]"},
    "count": {"type": "number", "description": "Number of messages returned (READ), or folder count (list_folders)"},
    "folders": {"type": "array", "description": "Mailbox folder names (list_folders action)"},
    "folder": {"type": "string", "description": "The mailbox folder read or acted on"},
    "action": {"type": "string", "description": "The action performed (action mode)"},
    "messageUid": {"type": "number", "description": "IMAP UID of the message acted on (action mode)"},
    "success": {"type": "boolean", "description": "Whether the operation succeeded"}
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'email_inbox';
