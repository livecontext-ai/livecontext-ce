-- ============================================================================
-- V153: Align SFTP node outputs with the canonical file-producer shape.
--
-- Context: SFTP download already uploads bytes through FileStorageService
-- (SftpNode v3.0), but its node_type_documentation.outputs (set by V91) still
-- declared file_content (legacy base64 fallback) instead of the canonical
-- {file_url, file_name, file_size, content_type} shape used by download_file,
-- convert_to_file and compression. The SftpNodeSpec.customTransform() flattens
-- the FileRef into that canonical shape; this migration updates the LLM-facing
-- documentation so agents see the same field names that the persisted output
-- carries (the 3-Way Alignment rule from CLAUDE.md).
--
-- Idempotent - re-asserts the row via ON CONFLICT DO UPDATE.
-- ============================================================================

SET search_path TO orchestrator;

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'sftp',
    'SFTP',
    'core',
    'core',
    'Perform file operations on a remote server via SFTP. Supports upload, download, list, delete, rename, and mkdir. Download uploads the bytes to internal storage and returns a canonical file reference (file_url, file_name, file_size, content_type) - same shape as download_file. When emitting via set_plan, wrap the config under ''sftp'': { host, port, username, authMethod, password, privateKey, operation, remotePath, localContent, newPath, timeout }.',
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
      "node_type":    {"type": "string",  "description": "Always ''SFTP''"},
      "success":      {"type": "boolean", "description": "Whether the operation succeeded"},
      "operation":    {"type": "string",  "description": "The operation that was executed"},
      "remote_path":  {"type": "string",  "description": "The remote path that was targeted"},
      "files":        {"type": "array",   "description": "Array of file entries for list operation ({name, size, is_dir, modified})"},
      "file_count":   {"type": "number",  "description": "Number of files listed (for list operation)"},
      "file_url":     {"type": "string",  "description": "Proxy URL to access the downloaded file (for download operation)"},
      "file_name":    {"type": "string",  "description": "Name of the downloaded file (for download operation)"},
      "file_size":    {"type": "number",  "description": "File size in bytes (for upload/download)"},
      "content_type": {"type": "string",  "description": "MIME type of the downloaded file (for download operation)"},
      "new_path":     {"type": "string",  "description": "New file path after rename"},
      "duration_ms":  {"type": "number",  "description": "Total execution time in milliseconds"}
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
