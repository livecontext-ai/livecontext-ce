-- ============================================================================
-- V323: Extend email_inbox (search filters, attachments, list_folders, richer
--       outputs) and send_email (reply threading inReplyTo/references) docs.
-- Updates the agent-facing node_type_documentation (the LLM's source of truth).
-- ============================================================================

SET search_path TO orchestrator;

-- ---- email_inbox ------------------------------------------------------------
UPDATE node_type_documentation
SET
  description = 'Read messages from a mailbox and act on them via IMAP. IMAP credentials (host, port, username, password) are configured once in Settings > Credentials (separate from the SMTP credential used by send_email). Modes via ''action'': action=''none'' (default) READS messages from ''folder'' with optional filters (unreadOnly, flaggedOnly, sinceDays/beforeDays, fromContains/subjectContains/bodyContains), capped at ''limit''; each message exposes its stable IMAP ''uid'', body/bodyHtml, cc/replyTo and attachments. action=''list_folders'' lists the mailbox folders. Any other action (mark_read/mark_unread/flag/unflag/move/delete) acts on the single message ''messageUid''. IMAP only reads/acts - to SEND or REPLY use the send_email node (set its inReplyTo to a message''s messageId to thread the reply). When emitting via set_plan, wrap the config under ''emailInbox''.',
  parameters = '{
    "folder": {"type": "string", "required": false, "default": "INBOX", "description": "Mailbox folder to read or act on."},
    "action": {"type": "string", "required": false, "default": "none", "description": "none | list_folders | mark_read | mark_unread | flag | unflag | move | delete. ''none'' reads; ''list_folders'' lists folders; the rest act on messageUid."},
    "unreadOnly": {"type": "boolean", "required": false, "default": false, "description": "READ filter: only unseen messages."},
    "flaggedOnly": {"type": "boolean", "required": false, "default": false, "description": "READ filter: only flagged messages."},
    "limit": {"type": "number", "required": false, "default": 10, "description": "READ: max messages (1-100), most recent first."},
    "markSeen": {"type": "boolean", "required": false, "default": false, "description": "READ: mark fetched messages as seen."},
    "sinceDays": {"type": "number", "required": false, "default": 0, "description": "READ filter: only messages received within the last N days (0 = no limit)."},
    "beforeDays": {"type": "number", "required": false, "default": 0, "description": "READ filter: only messages received more than N days ago (0 = no limit). Combine with sinceDays for a window."},
    "fromContains": {"type": "string", "required": false, "description": "READ filter: sender contains this text (IMAP FROM search)."},
    "subjectContains": {"type": "string", "required": false, "description": "READ filter: subject contains this text."},
    "bodyContains": {"type": "string", "required": false, "description": "READ filter: body contains this text."},
    "downloadAttachments": {"type": "boolean", "required": false, "default": false, "description": "READ: upload each attachment to file storage and return a FileRef under messages[].attachments[].file."},
    "messageUid": {"type": "string", "required": false, "description": "IMAP UID of the message to act on (required for mark_read/mark_unread/flag/unflag/move/delete). Supports templates e.g. {{core:read.output.messages[0].uid}}."},
    "targetFolder": {"type": "string", "required": false, "description": "Destination folder for the move action."}
  }'::jsonb,
  outputs = '{
    "messages": {"type": "array", "description": "READ: list of messages. Each item: uid, from, to, cc, replyTo, subject, date, seen, flagged, folder, messageId, references, body, bodyHtml, snippet, hasAttachments, attachments[{filename, contentType, size, file?}]"},
    "count": {"type": "number", "description": "Number of messages returned (READ), or folder count (list_folders)"},
    "folders": {"type": "array", "description": "Mailbox folder names (list_folders action)"},
    "folder": {"type": "string", "description": "The mailbox folder read or acted on"},
    "action": {"type": "string", "description": "The action performed (action mode)"},
    "messageUid": {"type": "number", "description": "IMAP UID of the message acted on (action mode)"},
    "success": {"type": "boolean", "description": "Whether the operation succeeded"},
    "node_type": {"type": "string", "description": "Always ''EMAIL_INBOX''"}
  }'::jsonb,
  concepts = '["IMAP credentials configured in Settings > Credentials - separate from the SMTP credential used by send_email", "IMAP reads/acts; it never SENDS - use send_email (SMTP) to send or reply", "READ mode (action=none): filter with unreadOnly/flaggedOnly/sinceDays/beforeDays/fromContains/subjectContains/bodyContains; iterate messages with a Split node", "Each message has a stable uid; carry messages[i].uid into a downstream email_inbox to mark_read/flag/move/delete it", "Attachments: each message exposes attachments[{filename, contentType, size}]; set downloadAttachments=true to also save each to file storage (attachments[].file is a FileRef)", "action=list_folders returns output.folders (use it to discover valid targetFolder values for move)", "To REPLY in-thread: send_email with inReplyTo set to the original messages[i].messageId (and toEmail set to messages[i].from)", "All single-message actions except none/list_folders require messageUid; move also requires targetFolder"]'::jsonb,
  examples = '["workflow_builder(action=''add_node'', type=''email_inbox'', label=''Find Invoices'', params={subjectContains: ''invoice'', unreadOnly: true, downloadAttachments: true, limit: 50}, connect_after=''Every Morning'')", "workflow_builder(action=''add_node'', type=''email_inbox'', label=''List Folders'', params={action: ''list_folders''}, connect_after=''Start'')", "workflow_builder(action=''add_node'', type=''email_inbox'', label=''Archive'', params={action: ''move'', targetFolder: ''Archive'', messageUid: ''{{split:item.output.uid}}''}, connect_after=''Read Inbox'')"]'::jsonb,
  updated_at = NOW()
WHERE type = 'email_inbox';

-- ---- send_email (reply threading) -------------------------------------------
UPDATE node_type_documentation
SET
  parameters = parameters || '{
    "inReplyTo": {"type": "string", "required": false, "description": "Reply threading: set the In-Reply-To header to the original message Message-ID (from email_inbox messages[].messageId) so the sent mail threads under that conversation. Also seeds References when references is empty."},
    "references": {"type": "string", "required": false, "description": "Reply threading: space-separated References header chain. Optional - defaults to inReplyTo when omitted."}
  }'::jsonb,
  concepts = (COALESCE(concepts, '[]'::jsonb)) || '["Reply in-thread: set inReplyTo to the original message''s messageId (from email_inbox output) and toEmail to its from address"]'::jsonb,
  updated_at = NOW()
WHERE type = 'send_email';
