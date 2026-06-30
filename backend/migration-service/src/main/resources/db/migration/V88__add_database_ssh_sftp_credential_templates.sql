-- Add credential templates for workflow-native nodes: Database, SSH, SFTP
-- These have no matching catalog.apis entry, so CredentialSection fetches with includeInactive=true

SET search_path TO catalog;

-- Database credential template
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-000000000002',
    'database',
    'Database',
    'Database connection credentials for executing SQL queries from workflow nodes. Supports PostgreSQL, MySQL, and MSSQL.',
    'database',
    'custom',
    NULL,
    NULL,
    '/icons/services/database.svg',
    'database',
    '[
      {"name": "db_type", "displayName": "Database Type", "type": "options", "required": true, "default": "postgresql", "options": [{"name": "PostgreSQL", "value": "postgresql"}, {"name": "MySQL", "value": "mysql"}, {"name": "MSSQL", "value": "mssql"}], "description": "Type of database server"},
      {"name": "host", "displayName": "Host", "type": "string", "required": true, "placeholder": "db.example.com", "description": "Database server hostname or IP address"},
      {"name": "port", "displayName": "Port", "type": "string", "required": true, "default": "5432", "placeholder": "5432", "description": "Database port (5432 for PostgreSQL, 3306 for MySQL, 1433 for MSSQL)"},
      {"name": "database", "displayName": "Database Name", "type": "string", "required": true, "placeholder": "mydb", "description": "Name of the database to connect to"},
      {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "db_user", "description": "Database login username"},
      {"name": "password", "displayName": "Password", "type": "string", "required": true, "placeholder": "Your database password", "typeOptions": {"password": true}, "description": "Database password (encrypted at rest)"},
      {"name": "use_ssl", "displayName": "Use SSL", "type": "options", "required": false, "default": "false", "options": [{"name": "Yes", "value": "true"}, {"name": "No", "value": "false"}], "description": "Enable SSL/TLS encryption for the connection"}
    ]'::jsonb,
    '{}'::jsonb,
    '{"provider": "database", "category": "infrastructure"}'::jsonb,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
)
ON CONFLICT (credential_name) DO NOTHING;

-- SSH credential template
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-000000000003',
    'ssh',
    'SSH Server',
    'SSH connection credentials for executing remote commands from workflow nodes. Supports password and private key authentication.',
    'ssh',
    'custom',
    NULL,
    NULL,
    '/icons/services/ssh.svg',
    'ssh',
    '[
      {"name": "host", "displayName": "Host", "type": "string", "required": true, "placeholder": "server.example.com", "description": "SSH server hostname or IP address"},
      {"name": "port", "displayName": "Port", "type": "string", "required": true, "default": "22", "placeholder": "22", "description": "SSH port (default: 22)"},
      {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "root", "description": "SSH login username"},
      {"name": "auth_method", "displayName": "Authentication Method", "type": "options", "required": true, "default": "password", "options": [{"name": "Password", "value": "password"}, {"name": "Private Key", "value": "private_key"}], "description": "How to authenticate with the SSH server"},
      {"name": "password", "displayName": "Password", "type": "string", "required": false, "placeholder": "Your SSH password", "typeOptions": {"password": true}, "description": "SSH password (required if auth method is Password)"},
      {"name": "private_key", "displayName": "Private Key", "type": "string", "required": false, "placeholder": "-----BEGIN OPENSSH PRIVATE KEY-----", "typeOptions": {"password": true, "rows": 6}, "description": "SSH private key in PEM format (required if auth method is Private Key)"},
      {"name": "passphrase", "displayName": "Key Passphrase", "type": "string", "required": false, "placeholder": "Optional passphrase for the private key", "typeOptions": {"password": true}, "description": "Passphrase to decrypt the private key (if encrypted)"}
    ]'::jsonb,
    '{}'::jsonb,
    '{"provider": "ssh", "category": "infrastructure"}'::jsonb,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
)
ON CONFLICT (credential_name) DO NOTHING;

-- SFTP credential template
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-000000000004',
    'sftp',
    'SFTP Server',
    'SFTP connection credentials for file transfer operations from workflow nodes. Supports password and private key authentication.',
    'sftp',
    'custom',
    NULL,
    NULL,
    '/icons/services/sftp.svg',
    'sftp',
    '[
      {"name": "host", "displayName": "Host", "type": "string", "required": true, "placeholder": "sftp.example.com", "description": "SFTP server hostname or IP address"},
      {"name": "port", "displayName": "Port", "type": "string", "required": true, "default": "22", "placeholder": "22", "description": "SFTP port (default: 22)"},
      {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "sftp_user", "description": "SFTP login username"},
      {"name": "auth_method", "displayName": "Authentication Method", "type": "options", "required": true, "default": "password", "options": [{"name": "Password", "value": "password"}, {"name": "Private Key", "value": "private_key"}], "description": "How to authenticate with the SFTP server"},
      {"name": "password", "displayName": "Password", "type": "string", "required": false, "placeholder": "Your SFTP password", "typeOptions": {"password": true}, "description": "SFTP password (required if auth method is Password)"},
      {"name": "private_key", "displayName": "Private Key", "type": "string", "required": false, "placeholder": "-----BEGIN OPENSSH PRIVATE KEY-----", "typeOptions": {"password": true, "rows": 6}, "description": "SSH private key in PEM format (required if auth method is Private Key)"},
      {"name": "passphrase", "displayName": "Key Passphrase", "type": "string", "required": false, "placeholder": "Optional passphrase for the private key", "typeOptions": {"password": true}, "description": "Passphrase to decrypt the private key (if encrypted)"}
    ]'::jsonb,
    '{}'::jsonb,
    '{"provider": "sftp", "category": "infrastructure"}'::jsonb,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
)
ON CONFLICT (credential_name) DO NOTHING;
