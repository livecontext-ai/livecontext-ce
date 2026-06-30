-- Update extract_from_file node documentation with text mode and chunking support
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  description = 'Import file content in two modes. Structured (default): parse CSV, XLSX, JSON into rows with column keys. Text: extract raw text from PDF, HTML, DOCX, TXT with optional chunking for RAG pipelines. Accepts raw content or a FileRef from upstream nodes (download_file, convert_to_file, compression).',
  parameters = '{
    "value": {"type": "string", "required": true, "description": "File content (string/base64) or template expression resolving to a FileRef (e.g. {{core:download.output.file}})."},
    "format": {"type": "string", "required": false, "description": "File format: csv, xlsx, json (structured mode) or pdf, html, docx, txt (text mode). Default: csv."},
    "mode": {"type": "string", "required": false, "description": "Extraction mode: structured (parse into rows) or text (extract raw text, optionally chunk). Default: structured."},
    "delimiter": {"type": "string", "required": false, "description": "(Structured/CSV only) Column delimiter. Default: comma."},
    "sheetName": {"type": "string", "required": false, "description": "(Structured/XLSX only) Sheet name. Default: first sheet."},
    "hasHeaders": {"type": "string", "required": false, "description": "(Structured/CSV+XLSX) Whether first row is headers: yes or no. Default: yes."},
    "chunking": {"type": "boolean", "required": false, "description": "(Text mode) Enable chunking for RAG. When true, text is split into overlapping chunks. Default: false."},
    "chunkSize": {"type": "integer", "required": false, "description": "(Text mode) Maximum characters per chunk. Default: 500."},
    "overlap": {"type": "integer", "required": false, "description": "(Text mode) Characters of overlap between consecutive chunks. Default: 50."},
    "chunkingStrategy": {"type": "string", "required": false, "description": "(Text mode) Chunking strategy: fixed_size (cut every N chars), recursive (paragraphs then sentences), separator (custom delimiter). Default: fixed_size."},
    "separator": {"type": "string", "required": false, "description": "(Text mode, separator strategy only) Custom delimiter string to split on. Default: double newline."}
  }'::jsonb,
  outputs = '{
    "items": {"type": "array", "description": "Extracted items. Structured: [{col1: val, ...}]. Text: [{content, chunk_index, source, total_chunks}]."},
    "format": {"type": "string", "description": "The format used (csv, xlsx, json, pdf, html, docx, txt)."},
    "mode": {"type": "string", "description": "The mode used (structured or text)."},
    "rowCount": {"type": "integer", "description": "Number of items/chunks extracted."},
    "success": {"type": "boolean", "description": "Whether extraction succeeded."},
    "columns": {"type": "array", "description": "(Structured only) Column names from first row."},
    "columnCount": {"type": "integer", "description": "(Structured only) Number of columns."},
    "total_chunks": {"type": "integer", "description": "(Text mode) Total number of chunks."},
    "text_length": {"type": "integer", "description": "(Text mode) Total character count of extracted text."},
    "chunking_strategy": {"type": "string", "description": "(Text mode, chunking enabled) Strategy used."},
    "chunk_size": {"type": "integer", "description": "(Text mode, chunking enabled) Chunk size used."},
    "overlap": {"type": "integer", "description": "(Text mode, chunking enabled) Overlap used."}
  }'::jsonb,
  concepts = '[
    "Two modes: structured (CSV/XLSX/JSON rows) and text (PDF/HTML/DOCX/TXT raw text extraction)",
    "Text mode supports optional chunking for RAG: split text into overlapping segments",
    "Three chunking strategies: fixed_size (character-based), recursive (paragraphs then sentences), separator (custom delimiter)",
    "Chunking output: items[] = [{content, chunk_index, source, total_chunks}] - ready to feed into embed + vector store",
    "RAG ingestion pattern: DownloadFile → ExtractFromFile(mode=text, chunking=true) → Split → Embed → InsertRow(vector column)",
    "Accepts FileRef from upstream: download_file, convert_to_file, compression nodes",
    "CSV supports quoted fields (RFC 4180), custom delimiters, and optional headers",
    "XLSX reads first sheet by default; specify sheetName for others. Input must be base64-encoded",
    "JSON handles both [{...}] arrays and single {...} objects (wrapped into array)",
    "Connect a Split node after to iterate per-item/per-chunk"
  ]'::jsonb,
  examples = '[
    "Structured CSV: workflow(action=''add_node'', type=''extract_from_file'', label=''Import CSV'', params={format: ''csv'', value: ''{{core:download.output.file}}'', hasHeaders: true}, connect_after=''Download'')",
    "Text mode PDF: workflow(action=''add_node'', type=''extract_from_file'', label=''Extract PDF'', params={format: ''pdf'', mode: ''text'', value: ''{{core:download.output.file}}''}, connect_after=''Download'')",
    "RAG chunking: workflow(action=''add_node'', type=''extract_from_file'', label=''Chunk Doc'', params={format: ''pdf'', mode: ''text'', chunking: true, chunkSize: 500, overlap: 50, chunkingStrategy: ''recursive'', value: ''{{core:download.output.file}}''}, connect_after=''Download'')"
  ]'::jsonb,
  updated_at = NOW()
WHERE type = 'extract_from_file';
