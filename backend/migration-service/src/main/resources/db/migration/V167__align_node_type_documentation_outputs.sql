-- ============================================================================
-- V167: Align node_type_documentation.outputs with the 3-Way Alignment rule.
--
-- Context: eleven NodeSpec implementations (B3 fix) corrected output field names
-- so mapper → DB → frontend schema are consistent. The LLM-facing documentation
-- in node_type_documentation.outputs still carried legacy field names from the V11
-- seed and subsequent migrations. This migration updates each affected row so that
-- agents reading the help output see the same field names they will find at runtime.
--
-- All UPDATEs are idempotent (safe to re-apply - they overwrite the outputs JSONB).
-- ============================================================================

SET search_path TO orchestrator;

-- ─────────────────────────────────────────────────────────────────────────────
-- core:compression
-- Old shape (V11): result, format, success, operation, file_url (partial, V27)
-- New shape (NodeSpec / mapper): file_url, file_name, file_size, content_type,
--   format, operation, success, result
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "file_url":     {"type": "string",  "description": "Proxy URL to access the compressed file (compress only; present when upload succeeds)"},
  "file_name":    {"type": "string",  "description": "Filename of the compressed file"},
  "file_size":    {"type": "number",  "description": "File size in bytes"},
  "content_type": {"type": "string",  "description": "MIME type of the compressed file"},
  "format":       {"type": "string",  "description": "Compression format used (gzip, zip, deflate, base64)"},
  "operation":    {"type": "string",  "description": "Operation performed: compress or decompress"},
  "success":      {"type": "boolean", "description": "Whether the operation succeeded"},
  "result":       {"type": "string",  "description": "Compressed or decompressed result (decompress only)"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'compression';

-- ─────────────────────────────────────────────────────────────────────────────
-- core:convert_to_file
-- Old shape (V11 + V27): size, format, result, success, filename, rowCount,
--   file_url (partial V27), content_type (partial V27)
-- New shape (NodeSpec / mapper): file_url, file_name, file_size, content_type,
--   format, row_count, success
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "file_url":     {"type": "string",  "description": "Proxy URL to download the generated file"},
  "file_name":    {"type": "string",  "description": "Generated filename (base name + extension)"},
  "file_size":    {"type": "number",  "description": "File size in bytes"},
  "content_type": {"type": "string",  "description": "MIME type of the file (e.g. text/csv, application/vnd.ms-excel)"},
  "format":       {"type": "string",  "description": "Output file format: csv, xlsx, json, or txt"},
  "row_count":    {"type": "number",  "description": "Number of rows written to the file"},
  "success":      {"type": "boolean", "description": "Whether the conversion succeeded"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'convert_to_file';

-- ─────────────────────────────────────────────────────────────────────────────
-- core:download_file
-- Old shape (V11): file_url, file_name, file_size, source_url, content_type
-- New shape (NodeSpec): file_url, file_name, file_size, content_type, source_url
-- V11 shape was already mostly correct; this re-asserts canonical descriptions.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "file_url":     {"type": "string",  "description": "Proxy URL to access the downloaded file"},
  "file_name":    {"type": "string",  "description": "Name of the downloaded file"},
  "file_size":    {"type": "number",  "description": "File size in bytes"},
  "content_type": {"type": "string",  "description": "MIME type of the downloaded file"},
  "source_url":   {"type": "string",  "description": "Original URL that was downloaded from"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'download_file';

-- ─────────────────────────────────────────────────────────────────────────────
-- trigger:chat
-- Old shape (V11 + V133): message, extracted_message(?), conversation_id(?),
--   attachments, matched(?), match_type, match_value, triggered_at, triggered_by
-- New shape (NodeSpec / mapper): message, extracted_message, conversation_id,
--   attachments (each: file_url, file_name, file_size, content_type),
--   matched, match_type, match_value, triggered_at, triggered_by
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "message":          {"type": "string",   "description": "The chat message that triggered the workflow"},
  "extracted_message":{"type": "string",   "description": "Message after pattern extraction (prefix/suffix trimmed)"},
  "conversation_id":  {"type": "string",   "description": "Conversation identifier"},
  "attachments":      {"type": "array",    "description": "File attachments sent with the message. Each entry has file_url, file_name, file_size, content_type."},
  "matched":          {"type": "boolean",  "description": "Whether the message matched the trigger pattern"},
  "match_type":       {"type": "string",   "description": "Pattern type used: any, starts_with, ends_with, contains, equals, regex"},
  "match_value":      {"type": "string",   "description": "The pattern value matched against"},
  "triggered_at":     {"type": "datetime", "description": "ISO timestamp when the workflow was triggered"},
  "triggered_by":     {"type": "string",   "description": "Display name of the user whose chat message fired the trigger. Empty when unknown. Never tenantId."},
  "trigger_id":       {"type": "string",   "description": "Internal id of the trigger that fired"},
  "item_id":          {"type": "string",   "description": "Item identifier for split-context tracking"},
  "item_index":       {"type": "number",   "description": "Index when iterating over items"},
  "data":             {"type": "array",    "description": "Raw trigger data items emitted by the resolver (one entry per matched message)"},
  "count":            {"type": "number",   "description": "Number of items in the data array (0 when no match, 1 when matched)"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'chat';

-- ─────────────────────────────────────────────────────────────────────────────
-- trigger:form
-- Old shape (V11 + V133 + V161): fields-based parameters + triggered_at/triggered_by appended.
--   Output fields (V11): formData, submissionId, submittedAt, formValues, triggered_at, triggered_by
-- New shape (NodeSpec / mapper): submission_id, submitted_at, form_data,
--   triggered_at, triggered_by + dynamic form field flatten
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "submission_id":  {"type": "string",   "description": "Form submission identifier (UUID)"},
  "submitted_at":   {"type": "datetime", "description": "When the form was submitted"},
  "form_data":      {"type": "object",   "description": "All submitted form fields grouped as an object ({field_name: value})"},
  "triggered_at":   {"type": "datetime", "description": "ISO timestamp when the form was submitted (alias of submitted_at for cross-trigger consistency)"},
  "triggered_by":   {"type": "string",   "description": "Display name of the form submitter (empty when anonymous). Never the raw tenantId."},
  "trigger_id":     {"type": "string",   "description": "Internal id of the trigger that fired"},
  "item_id":        {"type": "string",   "description": "Item identifier for split-context tracking"},
  "item_index":     {"type": "number",   "description": "Index when iterating over items"},
  "{field_name}":   {"type": "any",      "description": "Each form field is also stored at the top level for direct access: {{trigger:<label>.output.my_field}}. Field names vary per form configuration."}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'form';

-- ─────────────────────────────────────────────────────────────────────────────
-- trigger:workflow (parent → child chaining)
-- Old shape (V11 + V133): result, triggeredAt→triggered_at, triggered_by
-- New shape (NodeSpec / mapper): triggered_at, triggered_by, parentWorkflowId,
--   parentRunId, parentStatus, result, parentStatistics + dynamic parent fields
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "triggered_at":      {"type": "datetime", "description": "ISO timestamp when the parent workflow fired this one"},
  "triggered_by":      {"type": "string",   "description": "Display name of the workflow owner. Empty when the parent ran in a system context."},
  "parentWorkflowId":  {"type": "string",   "description": "ID of the parent workflow that triggered this one"},
  "parentRunId":       {"type": "string",   "description": "Run ID of the parent workflow execution"},
  "parentStatus":      {"type": "string",   "description": "Status of the parent workflow run"},
  "result":            {"type": "object",   "description": "Outputs from the parent workflow (also flattened to root level for direct SpEL access)"},
  "parentStatistics":  {"type": "object",   "description": "Execution statistics from the parent run (completedSteps, failedSteps, totalSteps). Present only when the parent recorded statistics."},
  "trigger_id":        {"type": "string",   "description": "Internal id of the trigger that fired"},
  "item_id":           {"type": "string",   "description": "Item identifier for split-context tracking"},
  "item_index":        {"type": "number",   "description": "Index when iterating over items"},
  "{parent_key}":      {"type": "any",      "description": "Each output from the parent workflow''s last completed node is also stored at the top level: {{trigger:<label>.output.my_parent_key}}. Key names vary per parent configuration."}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'workflow';

-- ─────────────────────────────────────────────────────────────────────────────
-- trigger:table (datasource event trigger)
-- Old shape (V95 + V97 + V124 + V133): event_type, datasource_id, row_id, row,
--   previous_row, triggered_at, triggered_by
-- New shape (NodeSpec / mapper): same structured keys + dynamic row columns
-- Re-asserts canonical descriptions; adds dynamic column hint.
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "event_type":    {"type": "string",   "description": "row_created | row_updated | row_deleted"},
  "datasource_id": {"type": "number",   "description": "ID of the datasource that emitted the event"},
  "row_id":        {"type": "number",   "description": "ID of the affected row in the datasource"},
  "row":           {"type": "object",   "description": "Row that triggered the event. Current state for row_created/row_updated; last-known state for row_deleted."},
  "previous_row":  {"type": "object",   "description": "Pre-change row. Populated only for row_updated; null otherwise."},
  "triggered_at":  {"type": "datetime", "description": "ISO-8601 timestamp captured after commit"},
  "triggered_by":  {"type": "string",   "description": "Display name of the workflow owner. ''datasource_event'' when the row-event source is a system process."},
  "trigger_id":    {"type": "string",   "description": "Internal id of the trigger that fired"},
  "item_id":       {"type": "string",   "description": "Item identifier for split-context tracking"},
  "item_index":    {"type": "number",   "description": "Index when iterating over items"},
  "{column}":      {"type": "any",      "description": "Each column of the triggering row is also stored at the top level for direct SpEL access: {{trigger:<label>.output.my_column}}. Column names vary per datasource schema; reserved payload keys are never overwritten."}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'table';

-- ─────────────────────────────────────────────────────────────────────────────
-- core:option
-- Old shape (V11): evaluations, selected_label, selected_choice,
--   skipped_choices, condition_result, condition_expression,
--   selected_choice_index, skipped_choice_labels
-- New shape (NodeSpec / mapper): selected_choice, selected_label,
--   selected_choice_index, selected_branches, skipped_branches, evaluations
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "selected_choice":       {"type": "string",  "description": "ID of the choice that matched"},
  "selected_label":        {"type": "string",  "description": "Label of the selected choice"},
  "selected_choice_index": {"type": "number",  "description": "Index of the selected choice (0-based; -1 when no choice matched)"},
  "selected_branches":     {"type": "array",   "description": "Array containing the selected branch label (empty if no match). Used by edge routing."},
  "skipped_branches":      {"type": "array",   "description": "Labels of branches that were not selected"},
  "evaluations":           {"type": "array",   "description": "Detailed evaluation results per choice: {choice_id, choice_label, expression, resolved_expression, result, error?}"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'option';

-- ─────────────────────────────────────────────────────────────────────────────
-- core:limit
-- Old shape (V21): outputs column was not explicitly updated (parameters only).
--   V11 seed for limit had no outputs block. Current runtime shape (NodeSpec):
--   items, count, original_count, config
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "items":          {"type": "array",  "description": "The limited subset of items"},
  "count":          {"type": "number", "description": "Number of items in the limited result"},
  "original_count": {"type": "number", "description": "Number of items before limiting"},
  "config":         {"type": "object", "description": "Limit configuration: {input, input_count, count, from, offset}"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'limit';

-- ─────────────────────────────────────────────────────────────────────────────
-- core:summarize
-- Old shape (V21): outputs column was not explicitly updated.
--   Current runtime shape (NodeSpec / mapper): groups, total_groups,
--   total_items, aggregation_count + dynamic alias keys (no groupBy case)
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "groups":             {"type": "array",  "description": "List of group results. Each group has group_key, group_count, groupBy field values, and each aggregation alias."},
  "total_groups":       {"type": "number", "description": "Count of distinct groups"},
  "total_items":        {"type": "number", "description": "Total items processed"},
  "aggregation_count":  {"type": "number", "description": "Number of aggregation operations applied"},
  "{alias}":            {"type": "any",    "description": "When no groupBy is configured, each aggregation alias is also stored at the top level (e.g. {{core:summarize.output.total_salary}}). Alias names vary per configuration and cannot be declared statically."}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'summarize';

-- ─────────────────────────────────────────────────────────────────────────────
-- core:sftp
-- Old shape (V11 seed): none or partial.
-- New shape (NodeSpec / mapper): success, operation, remote_path, files,
--   file_count, file_url, file_name, file_size, content_type, new_path, duration_ms
-- ─────────────────────────────────────────────────────────────────────────────
UPDATE node_type_documentation
SET outputs = '{
  "success":      {"type": "boolean", "description": "True if the SFTP operation succeeded"},
  "operation":    {"type": "string",  "description": "The SFTP operation performed (upload, download, list, delete, rename, mkdir)"},
  "remote_path":  {"type": "string",  "description": "The remote file/directory path"},
  "files":        {"type": "array",   "description": "Array of file entries (for list operation): {name, size, is_dir, modified}"},
  "file_count":   {"type": "number",  "description": "Number of file entries returned (for list operation)"},
  "file_url":     {"type": "string",  "description": "Proxy URL to access the downloaded file (for download operation)"},
  "file_name":    {"type": "string",  "description": "Name of the downloaded file (for download operation)"},
  "file_size":    {"type": "number",  "description": "File size in bytes (for upload/download)"},
  "content_type": {"type": "string",  "description": "MIME type of the downloaded file (for download operation)"},
  "new_path":     {"type": "string",  "description": "New file path after rename"},
  "duration_ms":  {"type": "number",  "description": "Total operation time in milliseconds"}
}'::jsonb,
    updated_at = NOW()
WHERE type = 'sftp';
