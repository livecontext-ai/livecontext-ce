-- V27: Update file node documentation to include FileRef/S3 output fields
-- ConvertToFile: add file_url, content_type to outputs
-- Compression: add file_url to outputs
-- ExtractFromFile: update description + value param to mention FileRef input support

SET search_path TO orchestrator;

-- ConvertToFile: add file_url and content_type to outputs
UPDATE node_type_documentation
SET
  outputs = outputs || '{
    "file_url": {"type": "string", "description": "Proxy URL to download the generated file (present when S3 upload succeeds)"},
    "content_type": {"type": "string", "description": "MIME type of the generated file (e.g. text/csv, application/json)"}
  }'::jsonb,
  concepts = concepts || '{
    "file_ref": "ConvertToFile now uploads to S3 and produces a FileRef. Access the file URL: {{core:label.output.file_url}}. Downstream nodes (ExtractFromFile) can accept the FileRef directly."
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'convert_to_file';

-- Compression: add file_url to outputs (only file_url - not file_name/file_size/content_type
-- to avoid frontend isFileRef() false positive that would hijack the entire output rendering)
UPDATE node_type_documentation
SET
  outputs = outputs || '{
    "file_url": {"type": "string", "description": "Proxy URL for compressed file download (compress only, present when S3 upload succeeds)"}
  }'::jsonb,
  concepts = concepts || '{
    "file_ref": "Compression (compress only) now uploads to S3 and produces a FileRef. Access file URL: {{core:label.output.file_url}}. Decompress does not produce a file."
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'compression';

-- ExtractFromFile: update description and value parameter to mention FileRef support
UPDATE node_type_documentation
SET
  description = 'Imports CSV, XLSX, and JSON file content into structured JSON items. Accepts raw file content (string/base64) or a FileRef from upstream file-producing nodes (DownloadFile, ConvertToFile, Compression). When a FileRef is provided, the file is automatically downloaded from S3 before parsing.',
  parameters = jsonb_set(
    parameters,
    '{value,description}',
    '"The input data to parse. Accepts raw text (CSV/JSON), Base64 (XLSX), or a FileRef from upstream nodes like DownloadFile/ConvertToFile ({{core:convert.output.file}}). FileRef is auto-detected and downloaded from S3."'::jsonb
  ),
  concepts = concepts || '{
    "file_ref_input": "ExtractFromFile auto-detects FileRef objects from upstream nodes. Chain: ConvertToFile → ExtractFromFile or DownloadFile → ExtractFromFile. Use {{core:upstream.output.file}} as the value expression."
  }'::jsonb,
  updated_at = NOW()
WHERE type = 'extract_from_file';
