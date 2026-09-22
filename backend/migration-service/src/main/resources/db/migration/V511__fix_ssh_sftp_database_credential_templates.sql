-- ======================================================================
-- V511: credential templates for the ssh, sftp and database nodes
--
-- DURABILITY CAVEAT (same as V295, read its header): Flyway runs this once, and
-- a periodic API re-import does TRUNCATE catalog.credentials CASCADE, which takes
-- these three rows with it. Until the native templates are seeded from the
-- importer post-truncate path, recovering them means copying this file to a fresh
-- version; the UPSERT below makes that safe to re-run.
--
-- SshNode, SftpNode and DatabaseNode have shipped for a while and each one can
-- resolve its connection from a credential, keyed "ssh" / "sftp" / "database".
-- "imap" and "smtp" have a row in catalog.credentials and these three never
-- did, so Settings > Credentials could not offer the form and no such
-- credential could be created. Production bears it out: 2 imap credentials,
-- 1 smtp, and zero ssh / sftp / database across every tenant. The three nodes
-- were therefore reachable only through their inline-config fallback, which
-- puts the host, the username and the PASSWORD in the workflow plan, that is,
-- in the one place the credential system exists to keep them out of.
--
-- Seeding the templates makes the secure path AVAILABLE; it does not make it
-- automatic, and the difference matters. Unlike email_inbox / send_email, these
-- three enter the credential branch only under
--   if (credentialId != null && credentialClient != null)
-- so getDefaultCredential is reached only as a FALLBACK, once that id is set but
-- resolves to nothing. The user therefore still selects the credential on the
-- node, and an existing node left on inline config keeps working exactly as
-- before. That is deliberate: changing the resolution order would silently
-- repoint live workflows at a different server.
-- (The condition is quoted rather than given as a line number, because a line
-- number is wrong the first time anything above it moves.)
--
-- Field names below are not a convention, they are what each node reads out of
-- getCredentialData(), so a rename here silently breaks the node:
--   ssh, sftp -> host, port, username, auth_method, password, private_key
--   database  -> db_type, host, port, database_name, username, password,
--                ssl_enabled
-- Defaults match the node defaults (ssh/sftp port 22, database port 5432,
-- auth_method "password", db_type "postgresql").
--
-- db_type offers postgresql and mysql only. DatabaseNode.buildJdbcUrl also
-- accepts "mssql", but no SQL Server driver is on the orchestrator classpath,
-- so offering it here would hand the user a choice that fails at connect time
-- with "No suitable driver". Add the mssql-jdbc dependency first, then the
-- option.
-- ============================================================================

SET search_path TO catalog;

-- UPSERT, not insert-if-absent, and that is the whole point of this migration.
--
-- V88 already inserted rows under these three names, and its 'database' template
-- names its fields "database" and "use_ssl" while DatabaseNode reads
-- "database_name" and "ssl_enabled". A user filling in that form gets
-- databaseName = null (the JDBC url becomes .../null) and ssl_enabled silently
-- stuck at false whatever they picked. An insert-if-absent would read "a row with
-- this name exists" as "already correct" and skip, leaving every fresh install on
-- the broken definition forever. Production happens to hold NO such rows today, so
-- both paths matter: insert where they are missing, repair where V88 put them.
--
-- ON CONFLICT targets (credential_name, variant), which is the real unique key;
-- ON CONFLICT (credential_name) alone is rejected, and that is why V88 could not
-- use this form. The id is NOT updated: V88's rows keep the ids they have, and
-- nothing keys off them.

-- 1. SSH ---------------------------------------------------------------------
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, variant, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-000000000006'::uuid,
    'ssh',
    'SSH',
    'SSH server credentials for running commands on a remote host from the SSH workflow node. Authenticate with a password or a private key.',
    'ssh',
    'custom',
    'custom',  -- MUST match auth_type: V103 backfilled variant=auth_type, so V88's rows are
               -- ('ssh','custom'). Defaulting to 'primary' (as omitting this column does)
               -- makes ON CONFLICT miss them, inserts a DUPLICATE, and leaves V88's broken
               -- row canonical, since the listing picks the lowest variant and 'custom'<'primary'.
    NULL,
    NULL,
    '/icons/services/ssh.svg',
    'ssh',
    '[
      {"name": "host", "displayName": "Host", "type": "string", "required": true, "placeholder": "server.example.com", "description": "SSH server hostname or IP address"},
      {"name": "port", "displayName": "Port", "type": "string", "required": false, "default": "22", "placeholder": "22", "description": "SSH port (22 by default)"},
      {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "deploy", "description": "SSH login"},
      {"name": "auth_method", "displayName": "Authentication", "type": "options", "required": false, "default": "password", "options": [{"name": "Password", "value": "password"}, {"name": "Private key", "value": "private_key"}], "description": "How to authenticate. Fill the matching field below."},
      {"name": "password", "displayName": "Password", "type": "string", "required": false, "placeholder": "Required when Authentication is Password", "typeOptions": {"password": true}, "description": "SSH password (encrypted at rest). Leave empty when using a private key."},
      {"name": "private_key", "displayName": "Private Key", "type": "string", "required": false, "placeholder": "-----BEGIN OPENSSH PRIVATE KEY-----", "typeOptions": {"password": true, "rows": 6}, "description": "PEM private key (encrypted at rest). Leave empty when using a password."}
    ]'::jsonb,
    '{}'::jsonb,
    '{"provider": "ssh", "category": "infrastructure"}'::jsonb,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
)
ON CONFLICT (credential_name, variant) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description  = EXCLUDED.description,
    icon_url     = EXCLUDED.icon_url,
    icon_slug    = EXCLUDED.icon_slug,
    properties   = EXCLUDED.properties,
    metadata     = EXCLUDED.metadata,
    updated_at   = EXCLUDED.updated_at;

-- 2. SFTP --------------------------------------------------------------------
-- Separate from ssh on purpose: the two nodes read separate integrations, and a
-- file-transfer account is routinely not the account that may run commands.
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, variant, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-000000000007'::uuid,
    'sftp',
    'SFTP',
    'SFTP server credentials for listing, uploading and downloading files from the SFTP workflow node. Separate from the SSH credential, so a file-transfer account does not have to be one that can run commands.',
    'sftp',
    'custom',
    'custom',  -- MUST match auth_type: V103 backfilled variant=auth_type, so V88's rows are
               -- ('ssh','custom'). Defaulting to 'primary' (as omitting this column does)
               -- makes ON CONFLICT miss them, inserts a DUPLICATE, and leaves V88's broken
               -- row canonical, since the listing picks the lowest variant and 'custom'<'primary'.
    NULL,
    NULL,
    '/icons/services/sftp.svg',
    'sftp',
    '[
      {"name": "host", "displayName": "Host", "type": "string", "required": true, "placeholder": "sftp.example.com", "description": "SFTP server hostname or IP address"},
      {"name": "port", "displayName": "Port", "type": "string", "required": false, "default": "22", "placeholder": "22", "description": "SFTP port (22 by default)"},
      {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "transfer", "description": "SFTP login"},
      {"name": "auth_method", "displayName": "Authentication", "type": "options", "required": false, "default": "password", "options": [{"name": "Password", "value": "password"}, {"name": "Private key", "value": "private_key"}], "description": "How to authenticate. Fill the matching field below."},
      {"name": "password", "displayName": "Password", "type": "string", "required": false, "placeholder": "Required when Authentication is Password", "typeOptions": {"password": true}, "description": "SFTP password (encrypted at rest). Leave empty when using a private key."},
      {"name": "private_key", "displayName": "Private Key", "type": "string", "required": false, "placeholder": "-----BEGIN OPENSSH PRIVATE KEY-----", "typeOptions": {"password": true, "rows": 6}, "description": "PEM private key (encrypted at rest). Leave empty when using a password."}
    ]'::jsonb,
    '{}'::jsonb,
    '{"provider": "sftp", "category": "file_transfer"}'::jsonb,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
)
ON CONFLICT (credential_name, variant) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description  = EXCLUDED.description,
    icon_url     = EXCLUDED.icon_url,
    icon_slug    = EXCLUDED.icon_slug,
    properties   = EXCLUDED.properties,
    metadata     = EXCLUDED.metadata,
    updated_at   = EXCLUDED.updated_at;

-- 3. Database ----------------------------------------------------------------
INSERT INTO credentials (id, credential_name, display_name, description, credential_type, auth_type, variant, test_endpoint, documentation_url, icon_url, icon_slug, properties, extends_, metadata, created_at, updated_at)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-000000000008'::uuid,
    'database',
    'Database (SQL)',
    'SQL database connection used by the Database workflow node to run queries against an external PostgreSQL or MySQL server. This is an OUTSIDE database; the tables built inside the product need no credential.',
    'database',
    'custom',
    'custom',  -- MUST match auth_type: V103 backfilled variant=auth_type, so V88's rows are
               -- ('ssh','custom'). Defaulting to 'primary' (as omitting this column does)
               -- makes ON CONFLICT miss them, inserts a DUPLICATE, and leaves V88's broken
               -- row canonical, since the listing picks the lowest variant and 'custom'<'primary'.
    NULL,
    NULL,
    '/icons/services/database.svg',
    'database',
    '[
      {"name": "db_type", "displayName": "Engine", "type": "options", "required": true, "default": "postgresql", "options": [{"name": "PostgreSQL", "value": "postgresql"}, {"name": "MySQL", "value": "mysql"}], "description": "Database engine"},
      {"name": "host", "displayName": "Host", "type": "string", "required": true, "placeholder": "db.example.com", "description": "Database server hostname or IP address"},
      {"name": "port", "displayName": "Port", "type": "string", "required": false, "default": "5432", "placeholder": "5432", "description": "Database port (5432 for PostgreSQL, 3306 for MySQL)"},
      {"name": "database_name", "displayName": "Database", "type": "string", "required": true, "placeholder": "appdb", "description": "Name of the database to connect to"},
      {"name": "username", "displayName": "Username", "type": "string", "required": true, "placeholder": "readonly", "description": "Database user"},
      {"name": "password", "displayName": "Password", "type": "string", "required": true, "placeholder": "Database password", "typeOptions": {"password": true}, "description": "Database password (encrypted at rest)"},
      {"name": "ssl_enabled", "displayName": "Use SSL", "type": "options", "required": false, "default": "false", "options": [{"name": "Yes", "value": "true"}, {"name": "No", "value": "false"}], "description": "Connect over SSL. Required by most managed database providers."}
    ]'::jsonb,
    '{}'::jsonb,
    '{"provider": "database", "category": "database"}'::jsonb,
    EXTRACT(EPOCH FROM NOW()) * 1000,
    EXTRACT(EPOCH FROM NOW()) * 1000
)
ON CONFLICT (credential_name, variant) DO UPDATE SET
    display_name = EXCLUDED.display_name,
    description  = EXCLUDED.description,
    icon_url     = EXCLUDED.icon_url,
    icon_slug    = EXCLUDED.icon_slug,
    properties   = EXCLUDED.properties,
    metadata     = EXCLUDED.metadata,
    updated_at   = EXCLUDED.updated_at;
