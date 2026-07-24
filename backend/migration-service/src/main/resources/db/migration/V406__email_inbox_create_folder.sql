-- ============================================================================
-- V406: email_inbox gains create_folder + move.createTargetIfMissing.
-- Updates the agent-facing node_type_documentation (the LLM's source of truth).
--
-- Why: a workflow that files mail into folders could not create the folders it
-- files into, so move failed with "Destination folder not found" until a human
-- created them by hand out-of-band. The docs also never said that folder names
-- are SERVER PATHS: on a namespaced server (Dovecot/cPanel) the folder is
-- 'INBOX.Clients', not 'Clients', which made every guessed targetFolder fail.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  description = 'Read messages from a mailbox and act on them via IMAP. IMAP credentials (host, port, username, password) are configured once in Settings > Credentials (separate from the SMTP credential used by send_email). Modes via ''action'': action=''none'' (default) READS messages from ''folder'' with optional filters (unreadOnly, flaggedOnly, sinceDays/beforeDays, fromContains/subjectContains/bodyContains), capped at ''limit''; each message exposes its stable IMAP ''uid'', body/bodyHtml, cc/replyTo and attachments. action=''list_folders'' lists the mailbox folders. action=''create_folder'' creates ''targetFolder'' (idempotent). Any other action (mark_read/mark_unread/flag/unflag/move/delete) acts on the single message ''messageUid''. IMAP only reads/acts, it never SENDS: to SEND or REPLY use the send_email node (set its inReplyTo to a message''s messageId to thread the reply). When emitting via set_plan, wrap the config under ''emailInbox''.',
  parameters = '{
    "folder": {"type": "string", "required": false, "default": "INBOX", "description": "Mailbox folder to read or act on."},
    "action": {"type": "string", "required": false, "default": "none", "description": "none | list_folders | create_folder | mark_read | mark_unread | flag | unflag | move | delete. ''none'' reads; ''list_folders'' lists folders; ''create_folder'' creates targetFolder; the rest act on messageUid."},
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
    "targetFolder": {"type": "string", "required": false, "description": "Destination folder for move; the folder to create for create_folder. A SERVER PATH exactly as list_folders returns it (e.g. ''INBOX.Clients'' on a server that namespaces under the inbox), never a display label."},
    "createTargetIfMissing": {"type": "boolean", "required": false, "default": false, "description": "move only: create targetFolder when it does not exist instead of failing. Use it to let a filing workflow bootstrap its own folders."}
  }'::jsonb,
  outputs = '{
    "messages": {"type": "array", "description": "READ: list of messages. Each item: uid, from, to, cc, replyTo, subject, date, seen, flagged, folder, messageId, references, body, bodyHtml, snippet, hasAttachments, attachments[{filename, contentType, size, file?}]"},
    "count": {"type": "number", "description": "Number of messages returned (READ), or folder count (list_folders)"},
    "folders": {"type": "array", "description": "Mailbox folder names as server paths (list_folders and create_folder actions)"},
    "folder": {"type": "string", "description": "The mailbox folder read, acted on, or created"},
    "created": {"type": "boolean", "description": "create_folder: true when the folder was created, false when it already existed"},
    "action": {"type": "string", "description": "The action performed (action mode)"},
    "messageUid": {"type": "number", "description": "IMAP UID of the message acted on (action mode)"},
    "success": {"type": "boolean", "description": "Whether the operation succeeded"}
  }'::jsonb,
  -- NOTE: node_type is deliberately absent. V323 documented it, V324 removed it: metadata keys
  -- (node_type, item_index, item_id) are by convention NOT part of the documented output schema,
  -- and NodeOutputSchemaCoherenceContractTest enforces doc keys == NodeSpec keys.
  concepts = '["IMAP credentials configured in Settings > Credentials, separate from the SMTP credential used by send_email", "ALWAYS run action=list_folders FIRST and build targetFolder from a name in output.folders: create_folder and createTargetIfMissing create targetFolder EXACTLY AS WRITTEN and never correct it, so a guessed name silently creates a second, wrong folder and files mail into it instead of failing loudly", "IMAP reads/acts; it never SENDS: use send_email (SMTP) to send or reply", "READ mode (action=none): filter with unreadOnly/flaggedOnly/sinceDays/beforeDays/fromContains/subjectContains/bodyContains; iterate messages with a Split node", "Each message has a stable uid; carry messages[i].uid into a downstream email_inbox to mark_read/flag/move/delete it", "Attachments: each message exposes attachments[{filename, contentType, size}]; set downloadAttachments=true to also save each to file storage (attachments[].file is a FileRef)", "Folder names are SERVER PATHS, not display labels: a server that namespaces under the inbox expects ''INBOX.Clients'' with ''.'' as separator, another expects ''Clients'' or ''A/B''. Run action=list_folders and copy a name from output.folders rather than guessing, or move fails with ''Destination folder not found''", "action=create_folder creates targetFolder and returns output.created (false when it already existed, so it is safe to run on every tick)", "A filing workflow can bootstrap its own folders: either create_folder once up front, or set createTargetIfMissing=true on each move", "To REPLY in-thread: send_email with inReplyTo set to the original messages[i].messageId (and toEmail set to messages[i].from)", "All single-message actions require messageUid (none/list_folders/create_folder do not); move also requires targetFolder, and create_folder requires targetFolder"]'::jsonb,
  examples = '["workflow_builder(action=''add_node'', type=''email_inbox'', label=''Find Invoices'', params={subjectContains: ''invoice'', unreadOnly: true, downloadAttachments: true, limit: 50}, connect_after=''Every Morning'')", "workflow_builder(action=''add_node'', type=''email_inbox'', label=''List Folders'', params={action: ''list_folders''}, connect_after=''Start'')", "workflow_builder(action=''add_node'', type=''email_inbox'', label=''Create Clients Folder'', params={action: ''create_folder'', targetFolder: ''INBOX.Clients''}, connect_after=''Start'')", "workflow_builder(action=''add_node'', type=''email_inbox'', label=''File As Client'', params={action: ''move'', targetFolder: ''INBOX.Clients'', createTargetIfMissing: true, messageUid: ''{{core:snapshot.output.uid}}''}, connect_after=''Classify'')"]'::jsonb,
  updated_at = NOW()
WHERE type = 'email_inbox';

-- ---- send_email: replyTo + fromEmail --------------------------------------
-- fromEmail was already declared on the config record and documented as the sender
-- address, but the node never read it: a node that set it silently sent from the
-- credential address instead. replyTo closes the asymmetry with email_inbox, which
-- reads a message's replyTo but could not set one when sending.
UPDATE node_type_documentation
SET
  parameters = parameters || '{
    "replyTo": {"type": "string", "required": false, "description": "Reply-To header: where replies should go when that is not the sender address. Comma-separated for multiple."},
    "fromEmail": {"type": "string", "required": false, "description": "Sender address, overriding the SMTP credential''s from_email. Leave empty to send as the credential''s address (the usual case): most SMTP servers reject a from address they do not own."},
    "references": {"type": "string", "required": false, "description": "Reply threading: space-separated References header chain. Optional, defaults to inReplyTo when omitted."}
  }'::jsonb,
  outputs = outputs || '{
    "sent": {"type": "boolean", "description": "True when the mail was handed to the SMTP server; false on the failure path"},
    "success": {"type": "boolean", "description": "Whether the operation succeeded. Present and false when sending failed"}
  }'::jsonb,
  -- The em-dash is banned product-wide, and V18 left one in this array. Rewrite it in place
  -- rather than replacing the array, so concepts added by other migrations survive.
  concepts = replace((COALESCE(concepts, '[]'::jsonb))::text, ' - ', ', ')::jsonb
             || '["Set replyTo when answers should reach a different address than the sender (a shared or monitored mailbox)", "On failure the node returns success=false and sent=false, so {{core:<label>.output.success}} is a reliable guard", "fromEmail overrides the credential sender: leave it out unless the SMTP server is known to accept that address, or the relay rejects the mail", "Transport security is on by default: an SMTP credential with no use_tls value is treated as use_tls=true, and the server certificate is always validated. If sending fails with a TLS or certificate error against a relay that genuinely has no TLS, set use_tls=false on the credential; there is no way to skip certificate validation."]'::jsonb,
  updated_at = NOW()
WHERE type = 'send_email';
