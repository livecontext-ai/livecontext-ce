-- Fix SMTP credential template: generic host placeholder, port as placeholder not default value
SET search_path TO catalog;

UPDATE credentials
SET properties = '[
  {"name": "host", "displayName": "SMTP Host", "type": "string", "required": true, "placeholder": "smtp.example.com", "description": "SMTP server hostname"},
  {"name": "port", "displayName": "Port", "type": "string", "required": true, "placeholder": "587", "description": "SMTP port (587 for STARTTLS, 465 for SSL)"},
  {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "user@example.com", "description": "SMTP login / username"},
  {"name": "password", "displayName": "Password / API Key", "type": "string", "required": true, "placeholder": "Your SMTP password or API key", "typeOptions": {"password": true}, "description": "SMTP password or API key (encrypted at rest)"},
  {"name": "from_email", "displayName": "From Email", "type": "string", "required": true, "placeholder": "noreply@yourdomain.com", "description": "Sender email address (must be verified with your SMTP provider)"},
  {"name": "from_name", "displayName": "From Name", "type": "string", "required": false, "placeholder": "My App", "description": "Sender display name"},
  {"name": "use_tls", "displayName": "Use TLS", "type": "options", "required": false, "default": "true", "options": [{"name": "Yes", "value": "true"}, {"name": "No", "value": "false"}], "description": "Enable TLS/STARTTLS encryption"}
]'::jsonb,
updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
WHERE credential_name = 'smtp';
