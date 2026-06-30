-- ============================================================================
-- V219: Align all file-producer nodes on the canonical FileRef output.
--
-- Context (prod 2026-05-14 Instagram Profile Scraper marketplace preview):
-- the showcase rewriter (`ShowcaseFileRefRewriter`, publication-service) only
-- recognises FileRef objects ({_type:"file", path, name, mimeType, size}) when
-- it walks items[*].data to mint HMAC-signed URLs for anonymous visitors. The
-- 4 file-producer nodes (download_file, sftp, convert_to_file, compression)
-- were flattening their FileRef into plain `file_url` strings, so marketplace
-- + share preview displayed broken images for every workflow using them.
--
-- Fix shipped in PR1: each producer now emits BOTH the canonical FileRef
-- object under `file` (recommended; recognised by frontend file-proxy token
-- injection AND showcase HMAC rewriter) AND keeps the legacy flattened fields
-- (file_url, file_name, file_size, content_type) as deprecated back-compat so
-- existing workflows that reference `{{node.output.file_url}}` keep working.
--
-- This migration aligns `node_type_documentation.outputs` so the LLM agent
-- sees the same canonical shape that the runtime emits (the 3-Way Alignment
-- rule from CLAUDE.md: mapper → DB doc → frontend schema).
--
-- Idempotent UPDATE - relies on V11 (download_file, convert_to_file, compression)
-- and V83 (sftp) having already seeded the 4 rows. Flyway ordering is guaranteed
-- (Vxxx ascending) so the rows always exist when V219 runs. Re-applying V219 a
-- second time is a no-op (UPDATE matches the same rows and writes the same data).
-- ============================================================================

SET search_path TO orchestrator;

-- ─── download_file ──────────────────────────────────────────────────────────

UPDATE node_type_documentation
SET outputs = '{
      "file":         {"type": "object",  "description": "Canonical FileRef ({_type:''file'', path, name, mimeType, size}). RECOMMENDED for new workflows - reference via `{{core:label.output.file}}` to render images in interfaces (frontend injects an auth token; marketplace + share previews work for anonymous visitors via HMAC-signed URLs). Recognised by the showcase rewriter."},
      "file_url":     {"type": "string",  "description": "DEPRECATED. Proxy URL `/api/files/proxy?key=...` to the stored file. Kept for back-compat with existing workflows; new workflows should use `file` instead - `file_url` is invisible to the marketplace/share preview HMAC rewriter and the image will not display for anonymous visitors."},
      "file_name":    {"type": "string",  "description": "DEPRECATED - use `file.name` instead."},
      "file_size":    {"type": "number",  "description": "DEPRECATED - use `file.size` instead."},
      "content_type": {"type": "string",  "description": "DEPRECATED - use `file.mimeType` instead."},
      "source_url":   {"type": "string",  "description": "The HTTP URL the file was downloaded from"},
      "node_type":    {"type": "string",  "description": "Always ''DOWNLOAD_FILE''"},
      "item_index":   {"type": "number",  "description": "Index within a split (when applicable)"},
      "item_id":      {"type": "string",  "description": "Item identifier within a split (when applicable)"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'download_file';

-- ─── sftp ───────────────────────────────────────────────────────────────────

UPDATE node_type_documentation
SET outputs = '{
      "node_type":    {"type": "string",  "description": "Always ''SFTP''"},
      "success":      {"type": "boolean", "description": "Whether the operation succeeded"},
      "operation":    {"type": "string",  "description": "The operation that was executed"},
      "remote_path":  {"type": "string",  "description": "The remote path that was targeted"},
      "files":        {"type": "array",   "description": "Array of file entries for list operation ({name, size, is_dir, modified})"},
      "file_count":   {"type": "number",  "description": "Number of files listed (for list operation)"},
      "file":         {"type": "object",  "description": "Canonical FileRef ({_type:''file'', path, name, mimeType, size}) for the download operation. RECOMMENDED - reference via `{{core:label.output.file}}` to render in interfaces. Marketplace + share preview only work through `file`."},
      "file_url":     {"type": "string",  "description": "DEPRECATED - use `file` instead. Proxy URL for the downloaded file."},
      "file_name":    {"type": "string",  "description": "DEPRECATED - use `file.name` instead."},
      "file_size":    {"type": "number",  "description": "DEPRECATED - use `file.size` instead."},
      "content_type": {"type": "string",  "description": "DEPRECATED - use `file.mimeType` instead."},
      "new_path":     {"type": "string",  "description": "New file path after rename"},
      "duration_ms":  {"type": "number",  "description": "Total execution time in milliseconds"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'sftp';

-- ─── convert_to_file ────────────────────────────────────────────────────────

UPDATE node_type_documentation
SET outputs = '{
      "file":         {"type": "object",  "description": "Canonical FileRef ({_type:''file'', path, name, mimeType, size}). RECOMMENDED - reference via `{{core:label.output.file}}` to render in interfaces. Marketplace + share preview only work through `file`."},
      "file_url":     {"type": "string",  "description": "DEPRECATED - use `file` instead."},
      "file_name":    {"type": "string",  "description": "DEPRECATED - use `file.name` instead."},
      "file_size":    {"type": "number",  "description": "DEPRECATED - use `file.size` instead."},
      "content_type": {"type": "string",  "description": "DEPRECATED - use `file.mimeType` instead."},
      "format":       {"type": "string",  "description": "The chosen output format (csv, xlsx, json, txt, …)"},
      "row_count":    {"type": "number",  "description": "Number of rows written (when applicable)"},
      "node_type":    {"type": "string",  "description": "Always ''CONVERT_TO_FILE''"},
      "duration_ms":  {"type": "number",  "description": "Total execution time in milliseconds"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'convert_to_file';

-- ─── compression ────────────────────────────────────────────────────────────

UPDATE node_type_documentation
SET outputs = '{
      "operation":    {"type": "string",  "description": "''compress'' or ''decompress''"},
      "format":       {"type": "string",  "description": "''zip'' or ''gzip''"},
      "file":         {"type": "object",  "description": "Canonical FileRef ({_type:''file'', path, name, mimeType, size}) for compress operation. RECOMMENDED - reference via `{{core:label.output.file}}`. Marketplace + share preview only work through `file`."},
      "file_url":     {"type": "string",  "description": "DEPRECATED - use `file` instead."},
      "file_name":    {"type": "string",  "description": "DEPRECATED - use `file.name` instead."},
      "file_size":    {"type": "number",  "description": "DEPRECATED - use `file.size` instead."},
      "content_type": {"type": "string",  "description": "DEPRECATED - use `file.mimeType` instead."},
      "output":       {"type": "string",  "description": "Base64-encoded output payload (compress: archive bytes; decompress: file content)"},
      "node_type":    {"type": "string",  "description": "Always ''COMPRESSION''"},
      "duration_ms":  {"type": "number",  "description": "Total execution time in milliseconds"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'compression';
