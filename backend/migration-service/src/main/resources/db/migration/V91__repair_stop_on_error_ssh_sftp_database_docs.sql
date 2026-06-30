-- ============================================================================
-- V91: Repair migration - re-assert the 4 rows from V83.
--
-- Context: in some databases, flyway_schema_history_orchestrator shows V83 as
-- success=true but the 4 INSERTed rows (stop_on_error, ssh, sftp, database) are
-- absent from node_type_documentation. Consequence: workflow(action='help',
-- topics=['ssh'|'sftp'|'database'|'stop_on_error']) returns not_found even
-- though each NodeSpec exists in code and add_node accepts them.
--
-- This migration is idempotent (ON CONFLICT DO UPDATE) - a no-op on healthy
-- databases, self-healing on affected ones.
-- ============================================================================

SET search_path TO orchestrator;

-- 1. Stop on Error
INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'stop_on_error',
    'Stop on Error',
    'core',
    'core',
    'Immediately fails the workflow with a custom error message. All branches are terminated and the workflow status transitions to FAILED. Unlike the Stop node (which completes gracefully), Stop on Error signals an error condition. When emitting via set_plan, wrap the config under ''stopOnError'': { errorMessage, errorCode }.',
    '{
      "errorMessage": {"type": "string", "required": false, "default": "Workflow stopped due to error", "description": "Error message to display. Supports templates e.g. {{core:check.output.reason}}"},
      "errorCode":    {"type": "string", "required": false, "description": "Optional error code for programmatic handling (e.g. ERR_VALIDATION_001)"}
    }'::jsonb,
    '{
      "node_type":     {"type": "string",   "description": "Always ''STOP_ON_ERROR''"},
      "error_message": {"type": "string",   "description": "The resolved error message"},
      "error_code":    {"type": "string",   "description": "The error code if provided"},
      "stopped_at":    {"type": "datetime", "description": "ISO timestamp when the error was triggered"},
      "status":        {"type": "string",   "description": "Always ''failed''"}
    }'::jsonb,
    NULL,
    NULL,
    '[]'::jsonb,
    '[]'::jsonb,
    '["stop", "error", "fail", "abort", "terminate", "halt"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label, category = EXCLUDED.category, variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description, parameters = EXCLUDED.parameters, outputs = EXCLUDED.outputs,
    keywords = EXCLUDED.keywords, updated_at = NOW();

-- 2. SSH
INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'ssh',
    'SSH',
    'core',
    'core',
    'Execute commands on a remote server via SSH. Supports password and private key authentication. Captures stdout, stderr, and exit code. When emitting via set_plan, wrap the config under ''ssh'': { host, port, username, authMethod, password, privateKey, command, timeout }.',
    '{
      "host":       {"type": "string",  "required": true,  "description": "Remote server hostname or IP. Supports templates."},
      "port":       {"type": "number",  "required": false, "default": 22, "description": "SSH port number"},
      "username":   {"type": "string",  "required": false, "description": "SSH username. Supports templates."},
      "authMethod": {"type": "string",  "required": false, "default": "password", "description": "One of: password, privateKey"},
      "password":   {"type": "string",  "required": false, "description": "SSH password (when authMethod=password). Supports templates."},
      "privateKey": {"type": "string",  "required": false, "description": "PEM-encoded private key (when authMethod=privateKey). Supports templates."},
      "command":    {"type": "string",  "required": true,  "description": "Shell command to execute. Supports templates."},
      "timeout":    {"type": "number",  "required": false, "default": 30000, "description": "Connection and execution timeout in milliseconds"}
    }'::jsonb,
    '{
      "node_type":   {"type": "string",  "description": "Always ''SSH''"},
      "success":     {"type": "boolean", "description": "true if exit code is 0"},
      "exit_code":   {"type": "number",  "description": "Command exit code (0 = success)"},
      "stdout":      {"type": "string",  "description": "Standard output from the command"},
      "stderr":      {"type": "string",  "description": "Standard error from the command"},
      "host":        {"type": "string",  "description": "Host that was connected to"},
      "command":     {"type": "string",  "description": "Command that was executed"},
      "duration_ms": {"type": "number",  "description": "Total execution time in milliseconds"}
    }'::jsonb,
    NULL,
    NULL,
    '[]'::jsonb,
    '[]'::jsonb,
    '["ssh", "remote", "command", "shell", "server", "execute", "terminal"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label, category = EXCLUDED.category, variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description, parameters = EXCLUDED.parameters, outputs = EXCLUDED.outputs,
    keywords = EXCLUDED.keywords, updated_at = NOW();

-- 3. SFTP
INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'sftp',
    'SFTP',
    'core',
    'core',
    'Perform file operations on a remote server via SFTP. Supports upload, download, list, delete, rename, and mkdir. When emitting via set_plan, wrap the config under ''sftp'': { host, port, username, authMethod, password, privateKey, operation, remotePath, localContent, newPath, timeout }.',
    '{
      "host":         {"type": "string",  "required": true,  "description": "Remote server hostname or IP. Supports templates."},
      "port":         {"type": "number",  "required": false, "default": 22, "description": "SFTP port number"},
      "username":     {"type": "string",  "required": false, "description": "SFTP username. Supports templates."},
      "authMethod":   {"type": "string",  "required": false, "default": "password", "description": "One of: password, privateKey"},
      "password":     {"type": "string",  "required": false, "description": "SFTP password. Supports templates."},
      "privateKey":   {"type": "string",  "required": false, "description": "PEM-encoded private key. Supports templates."},
      "operation":    {"type": "string",  "required": true,  "description": "One of: upload, download, list, delete, rename, mkdir"},
      "remotePath":   {"type": "string",  "required": true,  "description": "Remote file/directory path. Supports templates."},
      "localContent": {"type": "string",  "required": false, "description": "Content to upload (for upload operation). Supports templates."},
      "newPath":      {"type": "string",  "required": false, "description": "New file path (for rename operation). Supports templates."},
      "timeout":      {"type": "number",  "required": false, "default": 30000, "description": "Connection timeout in milliseconds"}
    }'::jsonb,
    '{
      "node_type":     {"type": "string",  "description": "Always ''SFTP''"},
      "success":       {"type": "boolean", "description": "Whether the operation succeeded"},
      "operation":     {"type": "string",  "description": "The operation that was executed"},
      "remote_path":   {"type": "string",  "description": "The remote path that was targeted"},
      "files":         {"type": "array",   "description": "Array of file entries for list operation ({name, size, is_dir, modified})"},
      "file_content":  {"type": "string",  "description": "Base64-encoded file content for download"},
      "file_size":     {"type": "number",  "description": "File size in bytes"},
      "file_count":    {"type": "number",  "description": "Number of files listed"},
      "duration_ms":   {"type": "number",  "description": "Total execution time in milliseconds"}
    }'::jsonb,
    NULL,
    NULL,
    '[]'::jsonb,
    '[]'::jsonb,
    '["sftp", "file", "transfer", "upload", "download", "remote", "server"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label, category = EXCLUDED.category, variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description, parameters = EXCLUDED.parameters, outputs = EXCLUDED.outputs,
    keywords = EXCLUDED.keywords, updated_at = NOW();

-- 4. Database
INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'database',
    'Database',
    'core',
    'core',
    'Execute SQL queries against PostgreSQL, MySQL, or MSSQL databases. ALWAYS uses parameterized queries for security. When emitting via set_plan, wrap the config under ''database'': { dbType, host, port, databaseName, username, password, sslEnabled, query, queryParams, operation, timeout }.',
    '{
      "dbType":       {"type": "string",  "required": false, "default": "postgresql", "description": "One of: postgresql, mysql, mssql"},
      "host":         {"type": "string",  "required": true,  "description": "Database server hostname or IP. Supports templates."},
      "port":         {"type": "number",  "required": false, "default": 5432, "description": "Database port (auto-set by dbType: postgresql=5432, mysql=3306, mssql=1433)"},
      "databaseName": {"type": "string",  "required": true,  "description": "Database name. Supports templates."},
      "username":     {"type": "string",  "required": false, "description": "Database username. Supports templates."},
      "password":     {"type": "string",  "required": false, "description": "Database password. Supports templates."},
      "sslEnabled":   {"type": "boolean", "required": false, "default": false, "description": "Enable SSL for the connection"},
      "query":        {"type": "string",  "required": true,  "description": "SQL query. Use $1, $2 placeholders for parameters. Supports templates for non-parameterized parts."},
      "queryParams":  {"type": "array",   "required": false, "description": "Ordered list of parameter values matching $1, $2, etc. Supports templates."},
      "operation":    {"type": "string",  "required": false, "default": "select", "description": "One of: select, insert, update, delete, execute"},
      "timeout":      {"type": "number",  "required": false, "default": 30000, "description": "Query timeout in milliseconds"}
    }'::jsonb,
    '{
      "node_type":      {"type": "string",  "description": "Always ''DATABASE''"},
      "success":        {"type": "boolean", "description": "Whether the query executed successfully"},
      "operation":      {"type": "string",  "description": "The operation that was executed"},
      "rows":           {"type": "array",   "description": "Array of row objects for select/execute (each row is a key-value map)"},
      "columns":        {"type": "array",   "description": "Array of column names for select/execute"},
      "row_count":      {"type": "number",  "description": "Number of rows returned for select/execute"},
      "affected_rows":  {"type": "number",  "description": "Number of rows affected for insert/update/delete"},
      "duration_ms":    {"type": "number",  "description": "Total execution time in milliseconds"}
    }'::jsonb,
    NULL,
    NULL,
    '[]'::jsonb,
    '[]'::jsonb,
    '["database", "sql", "query", "postgres", "postgresql", "mysql", "mssql", "select", "insert", "update", "delete"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label, category = EXCLUDED.category, variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description, parameters = EXCLUDED.parameters, outputs = EXCLUDED.outputs,
    keywords = EXCLUDED.keywords, updated_at = NOW();
