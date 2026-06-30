-- ============================================================================
-- V221: Drop legacy flat fields (file_url, file_name, file_size, content_type)
-- from the 4 file-producer node docs. Clean break from V219's dual-emit doc.
--
-- Context: V219 documented BOTH the canonical `file` FileRef AND the legacy flat
-- fields as DEPRECATED back-compat. PR2 removes legacy emission entirely from
-- the runtime (DownloadFileNode, SftpNode, ConvertToFileNode, CompressionNode)
-- and from the NodeSpec.outputs() served to the frontend Inspector. The DB doc
-- (`node_type_documentation.outputs`, what the LLM agent sees) must match.
--
-- Idempotent UPDATE - re-applying writes the same canonical-only outputs JSONB.
-- ============================================================================

SET search_path TO orchestrator;

-- ─── download_file ──────────────────────────────────────────────────────────

-- Note: `node_type`, `item_index`, `itemIndex`, `item_id`, `resolved_params` are
-- ENGINE_ENVELOPE_KEYS stripped by GenericOutputSchemaMapper.customTransform
-- before persistence - never document them as outputs the LLM can reference.
UPDATE node_type_documentation
SET outputs = '{
      "file":         {"type": "object",  "description": "Canonical FileRef ({_type:''file'', path, name, mimeType, size}). Reference via `{{core:label.output.file}}` to render images in interfaces (frontend injects an auth token; marketplace + share previews work for anonymous visitors via HMAC-signed URLs)."},
      "source_url":   {"type": "string",  "description": "The HTTP URL the file was downloaded from"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'download_file';

-- ─── sftp ───────────────────────────────────────────────────────────────────

UPDATE node_type_documentation
SET outputs = '{
      "success":      {"type": "boolean", "description": "Whether the operation succeeded"},
      "operation":    {"type": "string",  "description": "The operation that was executed"},
      "remote_path":  {"type": "string",  "description": "The remote path that was targeted"},
      "files":        {"type": "array",   "description": "Array of file entries for list operation ({name, size, is_dir, modified})"},
      "file_count":   {"type": "number",  "description": "Number of files listed (for list operation)"},
      "file":         {"type": "object",  "description": "Canonical FileRef ({_type:''file'', path, name, mimeType, size}) for the download operation. Reference via `{{core:label.output.file}}` to render in interfaces. Marketplace + share preview only work through `file`."},
      "uploaded_size":{"type": "number",  "description": "Bytes written to the remote server (upload operation)"},
      "new_path":     {"type": "string",  "description": "New file path after rename"},
      "duration_ms":  {"type": "number",  "description": "Total execution time in milliseconds"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'sftp';

-- ─── convert_to_file ────────────────────────────────────────────────────────

-- Note: ConvertToFileNode does not currently emit `duration_ms` - kept out of the
-- doc so the LLM never references a non-existent runtime field.
UPDATE node_type_documentation
SET outputs = '{
      "file":         {"type": "object",  "description": "Canonical FileRef ({_type:''file'', path, name, mimeType, size}). Reference via `{{core:label.output.file}}` to render in interfaces. Marketplace + share preview only work through `file`."},
      "format":       {"type": "string",  "description": "The chosen output format (csv, xlsx, json, txt, …)"},
      "row_count":    {"type": "number",  "description": "Number of rows written (when applicable)"},
      "result":       {"type": "string",  "description": "Inline file contents (xlsx: base64; csv/json/txt: plain text)"},
      "success":      {"type": "boolean", "description": "True when the conversion succeeded"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'convert_to_file';

-- ─── compression ────────────────────────────────────────────────────────────

-- Note: CompressionNode does not currently emit `duration_ms` - kept out of the
-- doc so the LLM never references a non-existent runtime field.
UPDATE node_type_documentation
SET outputs = '{
      "operation":    {"type": "string",  "description": "''compress'' or ''decompress''"},
      "format":       {"type": "string",  "description": "''zip'', ''gzip'', ''deflate'' or ''base64''"},
      "file":         {"type": "object",  "description": "Canonical FileRef ({_type:''file'', path, name, mimeType, size}) for compress operation (null on decompress and on failure). Reference via `{{core:label.output.file}}`. Marketplace + share preview only work through `file`."},
      "result":       {"type": "string",  "description": "Operation payload - decompress: plain string; compress: base64-encoded archive bytes (also available via `file` for marketplace/share rendering)"},
      "success":      {"type": "boolean", "description": "True when the operation succeeded"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'compression';
