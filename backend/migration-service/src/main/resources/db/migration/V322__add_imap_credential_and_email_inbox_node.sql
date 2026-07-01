-- ============================================================================
-- V322: Email Inbox (IMAP) node
--   1. Seed the 'imap' credential template (catalog.credentials) so users can
--      configure IMAP once in Settings > Credentials, distinct from 'smtp'.
--   2. Seed node_type_documentation for the email_inbox core node (orchestrator).
--
-- IMAP reads/acts on a mailbox; it never SENDS mail (sending stays on send_email
-- over SMTP) - like a mail client, one credential for incoming (IMAP) and one for
-- outgoing (SMTP).
-- ============================================================================

-- 1. IMAP credential template ------------------------------------------------
SET search_path TO catalog;

-- NOTE: catalog.credentials has UNIQUE (credential_name, variant), NOT credential_name alone,
-- so use INSERT ... SELECT ... WHERE NOT EXISTS (mirrors V295) - ON CONFLICT (credential_name) fails.
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
SELECT
    'a1b2c3d4-e5f6-7890-abcd-000000000005'::uuid,
    'imap',
    'IMAP Email',
    'IMAP server credentials for reading and acting on a mailbox from workflow nodes. Supports any IMAP provider (Gmail, Outlook, Yahoo, Fastmail, etc.). Used by the Email Inbox node; sending email uses the separate SMTP credential.',
    'imap',
    'custom',
    NULL,
    NULL,
    '/icons/services/imap.svg',
    'imap',
    '[
      {"name": "host", "displayName": "IMAP Host", "type": "string", "required": true, "placeholder": "imap.gmail.com", "description": "IMAP server hostname"},
      {"name": "port", "displayName": "Port", "type": "string", "required": true, "default": "993", "placeholder": "993", "description": "IMAP port (993 for SSL, 143 for STARTTLS)"},
      {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "user@example.com", "description": "IMAP login / username"},
      {"name": "password", "displayName": "Password / App Password", "type": "string", "required": true, "placeholder": "Your IMAP password or app password", "typeOptions": {"password": true}, "description": "IMAP password or app-specific password (encrypted at rest)"},
      {"name": "use_ssl", "displayName": "Use SSL", "type": "options", "required": false, "default": "true", "options": [{"name": "Yes", "value": "true"}, {"name": "No", "value": "false"}], "description": "Connect over SSL (imaps, port 993). Disable to use STARTTLS (port 143)."}
    ]'::jsonb,
    '{}'::jsonb,
    '{"provider": "imap", "category": "email"}'::jsonb,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
WHERE NOT EXISTS (SELECT 1 FROM credentials WHERE credential_name = 'imap');

-- 2. email_inbox node documentation ------------------------------------------
SET search_path TO orchestrator;

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'email_inbox',
    'Email Inbox',
    'core',
    'core',
    'Read messages from a mailbox and act on them via IMAP. IMAP credentials (host, port, username, password) are configured once in Settings > Credentials (an IMAP credential, separate from the SMTP credential used by send_email). Two modes selected by ''action'': action=''none'' (default) READS messages from ''folder'' (optionally unreadOnly, capped at ''limit'', optionally within ''sinceDays''); each message exposes its stable IMAP ''uid''. Any other action acts on the single message identified by ''messageUid''. IMAP only reads/acts - to SEND mail use the send_email node. When emitting via set_plan, wrap the config under ''emailInbox'': { folder, unreadOnly, limit, action, messageUid, targetFolder }.',
    '{
      "folder":      {"type": "string",  "required": false, "default": "INBOX", "description": "Mailbox folder to read or act on."},
      "unreadOnly":  {"type": "boolean", "required": false, "default": false, "description": "READ mode: only return unseen messages."},
      "limit":       {"type": "number",  "required": false, "default": 10, "description": "READ mode: max messages to return (1-100), most recent first."},
      "markSeen":    {"type": "boolean", "required": false, "default": false, "description": "READ mode: mark fetched messages as seen."},
      "sinceDays":   {"type": "number",  "required": false, "default": 0, "description": "READ mode: only messages received within the last N days (0 = no limit)."},
      "action":      {"type": "string",  "required": false, "default": "none", "description": "none | mark_read | mark_unread | flag | unflag | move | delete. ''none'' reads; any other acts on messageUid."},
      "messageUid":  {"type": "string",  "required": false, "description": "IMAP UID of the message to act on (required when action != none). Supports templates e.g. {{core:read.output.messages[0].uid}}."},
      "targetFolder":{"type": "string",  "required": false, "description": "Destination folder for the move action."}
    }'::jsonb,
    '{
      "messages":   {"type": "array",   "description": "READ mode: list of messages. Each item: uid, from, to, subject, date, seen, flagged, folder, messageId, body, snippet"},
      "count":      {"type": "number",  "description": "READ mode: number of messages returned"},
      "folder":     {"type": "string",  "description": "The mailbox folder read or acted on"},
      "action":     {"type": "string",  "description": "ACTION mode: the action performed"},
      "messageUid": {"type": "number",  "description": "ACTION mode: IMAP UID of the message acted on"},
      "success":    {"type": "boolean", "description": "Whether the operation succeeded"},
      "node_type":  {"type": "string",  "description": "Always ''EMAIL_INBOX''"}
    }'::jsonb,
    NULL,
    NULL,
    '["IMAP credentials (host, port, password) are configured in Settings > Credentials - separate from the SMTP credential used by send_email", "IMAP reads/acts on a mailbox; it never SENDS mail - use send_email (SMTP) to send", "READ mode (action=none): iterate core:read.output.messages with a Split node; each message has a stable uid", "Carry messages[i].uid into a downstream email_inbox node to mark_read / flag / move / delete that exact message", "All actions except none require messageUid; move also requires targetFolder", "messageUid supports SpEL: {{core:read.output.messages[0].uid}}"]'::jsonb,
    '["workflow_builder(action=''add_node'', type=''email_inbox'', label=''Read Inbox'', params={folder: ''INBOX'', unreadOnly: true, limit: 20}, connect_after=''Every Morning'')", "workflow_builder(action=''add_node'', type=''email_inbox'', label=''Flag Urgent'', params={action: ''flag'', messageUid: ''{{core:read.output.messages[0].uid}}''}, connect_after=''Classify'')", "workflow_builder(action=''add_node'', type=''email_inbox'', label=''Archive'', params={action: ''move'', targetFolder: ''Archive'', messageUid: ''{{split:item.output.uid}}''}, connect_after=''Read Inbox'')"]'::jsonb,
    '["email", "inbox", "read", "imap", "mailbox", "receive", "fetch", "mark read", "flag", "move", "delete"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label,
    category = EXCLUDED.category,
    variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description,
    parameters = EXCLUDED.parameters,
    outputs = EXCLUDED.outputs,
    concepts = EXCLUDED.concepts,
    examples = EXCLUDED.examples,
    keywords = EXCLUDED.keywords,
    enabled = EXCLUDED.enabled,
    updated_at = NOW();
