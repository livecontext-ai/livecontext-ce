-- Update send_email node_type_documentation to reflect credential-based SMTP
-- SMTP credentials now come from Settings > Credentials, not per-node params

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  description = 'Send emails via SMTP. SMTP credentials (host, port, username, password, from_email) are configured once in Settings > Credentials. The node only needs per-email fields: toEmail, subject, body. Supports plain text and HTML, multiple recipients (To, CC, BCC), and dynamic SpEL expressions.',
  parameters = '{
    "toEmail": {"type": "string", "required": true, "description": "Recipient email address(es), comma-separated for multiple."},
    "subject": {"type": "string", "required": true, "description": "Email subject line. Supports SpEL expressions."},
    "body": {"type": "string", "required": true, "description": "Email body content. Supports SpEL expressions."},
    "ccEmail": {"type": "string", "required": false, "description": "CC email address(es), comma-separated."},
    "bccEmail": {"type": "string", "required": false, "description": "BCC email address(es), comma-separated."},
    "fromName": {"type": "string", "required": false, "description": "Override sender display name (from_name in SMTP credential is the default)."},
    "isHtml": {"type": "boolean", "default": false, "required": false, "description": "Whether the body is HTML content."}
  }'::jsonb,
  concepts = '["SMTP credentials (host, port, password) are configured in Settings > Credentials - NOT per-node", "All text fields support SpEL expressions for dynamic content: {{trigger:form.output.email}}", "Multiple recipients can be comma-separated in toEmail, ccEmail, bccEmail", "Set isHtml=true to send HTML-formatted emails", "fromName overrides the sender name from the SMTP credential"]'::jsonb,
  examples = '["workflow_builder(action=''add_node'', type=''send_email'', label=''Send Notification'', params={toEmail: ''{{trigger:form.output.email}}'', subject: ''Order Confirmation'', body: ''Your order #{{core:process.output.order_id}} has been confirmed.''}, connect_after=''Process Order'')", "workflow_builder(action=''add_node'', type=''send_email'', label=''Alert Admin'', params={toEmail: ''admin@company.com'', subject: ''Alert: {{core:check.output.issue}}'', body: ''<h1>Alert</h1><p>{{core:check.output.details}}</p>'', isHtml: true}, connect_after=''Check Status'')"]'::jsonb,
  updated_at = NOW()
WHERE type = 'send_email';
