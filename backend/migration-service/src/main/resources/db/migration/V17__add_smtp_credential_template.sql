-- Add SMTP credential template to catalog.credentials
-- Users configure SMTP once in Settings > Credentials, then all Send Email nodes use it automatically

SET search_path TO catalog;

INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-000000000001',
    'smtp',
    'SMTP Email',
    'SMTP server credentials for sending emails from workflow nodes. Supports any SMTP provider (Brevo, Gmail, SendGrid, Mailgun, etc.)',
    'smtp',
    'custom',
    NULL,
    NULL,
    '/icons/services/smtp.svg',
    'smtp',
    '[
      {"name": "host", "displayName": "SMTP Host", "type": "string", "required": true, "placeholder": "smtp-relay.brevo.com", "description": "SMTP server hostname"},
      {"name": "port", "displayName": "Port", "type": "string", "required": true, "default": "587", "placeholder": "587", "description": "SMTP port (587 for STARTTLS, 465 for SSL)"},
      {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "user@smtp-provider.com", "description": "SMTP login / username"},
      {"name": "password", "displayName": "Password / API Key", "type": "string", "required": true, "placeholder": "Your SMTP password or API key", "typeOptions": {"password": true}, "description": "SMTP password or API key (encrypted at rest)"},
      {"name": "from_email", "displayName": "From Email", "type": "string", "required": true, "placeholder": "noreply@yourdomain.com", "description": "Sender email address (must be verified with your SMTP provider)"},
      {"name": "from_name", "displayName": "From Name", "type": "string", "required": false, "placeholder": "My App", "description": "Sender display name"},
      {"name": "use_tls", "displayName": "Use TLS", "type": "options", "required": false, "default": "true", "options": [{"name": "Yes", "value": "true"}, {"name": "No", "value": "false"}], "description": "Enable TLS/STARTTLS encryption"}
    ]'::jsonb,
    '{}'::jsonb,
    '{"provider": "smtp", "category": "email"}'::jsonb,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
)
ON CONFLICT (credential_name) DO NOTHING;
