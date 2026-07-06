-- ============================================================================
-- V392: Second pass aligning node_type_documentation with backend behavior
-- ============================================================================
-- Completes the node-doc audit (all 61 node types now cross-checked against
-- their creators). Each fix makes the doc match the backend, except limit and
-- respond_to_webhook whose backend creators were fixed in the same change to
-- FORWARD a documented+supported param the builder was dropping.
--
--  1. limit          examples omitted the REQUIRED input param (LimitNode fails
--                    without it). Now that the creator forwards input, show it.
--  2. date_time      every example wrapped config in a nested dateTime:{...} the
--                    creator never reads (it reads flat) -> unwrapped examples.
--  3. option         param 'fallback' is read nowhere (fallback = a choice with
--                    expression:'true') -> removed from the doc.
--  4. compression    concept referenced a nonexistent output field file_url.
--  5. convert_to_file concepts referenced nonexistent fields filename/rowCount/
--                    file_url (real outputs: file, row_count).
--  6. response       example set an ignored 'format' param -> removed.
-- ============================================================================

SET search_path TO orchestrator;

-- 1. limit: show the required input in every example --------------------------
UPDATE node_type_documentation
SET examples = replace(replace(replace(examples::text,
        '{count: 5, from: ''first''}',
        '{count: 5, from: ''first'', input: ''{{core:sort_results.output.items}}''}'),
        '{count: 3, from: ''last''}',
        '{count: 3, from: ''last'', input: ''{{core:fetch_data.output.items}}''}'),
        '{count: 10, from: ''first'', offset: 10}',
        '{count: 10, from: ''first'', offset: 10, input: ''{{core:get_all_items.output.items}}''}')::jsonb,
    updated_at = NOW()
WHERE type = 'limit'
  AND examples::text LIKE '%{count: 5, from: ''first''}%';

-- 2. date_time: flat examples (the creator reads params flat, not under dateTime) --
UPDATE node_type_documentation
SET examples = '[
    "workflow(action=''add_node'', type=''date_time'', label=''Format Date'', params={operation: ''format'', value: ''{{trigger:start.output.created_at}}'', outputFormat: ''dd MMM yyyy''}, connect_after=''Start'')",
    "workflow(action=''add_node'', type=''date_time'', label=''Add 7 Days'', params={operation: ''add'', value: ''{{trigger:start.output.due_date}}'', durationUnit: ''days'', durationAmount: 7, outputFormat: ''yyyy-MM-dd''}, connect_after=''Get Task'')",
    "workflow(action=''add_node'', type=''date_time'', label=''Convert To Local'', params={operation: ''convertTimezone'', value: ''{{trigger:start.output.timestamp}}'', timezone: ''UTC'', targetTimezone: ''Europe/Paris'', outputFormat: ''yyyy-MM-dd HH:mm:ss''}, connect_after=''Fetch Event'')",
    "workflow(action=''add_node'', type=''date_time'', label=''Get Current Time'', params={operation: ''now'', timezone: ''UTC'', outputFormat: ''yyyy-MM-dd HH:mm:ss''}, connect_after=''Start'')",
    "workflow(action=''add_node'', type=''date_time'', label=''Days Until Deadline'', params={operation: ''difference'', value: ''{{core:get_current_time.output.result}}'', secondValue: ''{{trigger:start.output.deadline}}'', durationUnit: ''days''}, connect_after=''Get Current Time'')"
  ]'::jsonb,
    updated_at = NOW()
WHERE type = 'date_time'
  AND examples::text LIKE '%dateTime: {%';

-- 3. option: fallback is not a read param (it is a choice with expression:'true') --
UPDATE node_type_documentation
SET parameters = parameters - 'fallback',
    updated_at = NOW()
WHERE type = 'option'
  AND parameters ? 'fallback';

-- 3b. option edge_ports: getOptionPorts() emits only choice_0..choice_N; there is
--     no 'fallback' port. Drop the nonexistent-port claim (same class as 3).
UPDATE node_type_documentation
SET edge_ports = replace(replace(edge_ports::text,
        'Creates 1 port per choice + optional fallback.', 'Creates 1 port per choice.'),
        'choice_2, fallback', 'choice_2')::jsonb,
    updated_at = NOW()
WHERE type = 'option'
  AND edge_ports::text LIKE '%fallback%';

-- 4. compression: the FileRef output field is `file`, not `file_url` -----------
UPDATE node_type_documentation
SET concepts = replace(concepts::text,
        'Access file URL: {{core:label.output.file_url}}',
        'Access the file: {{core:label.output.file}}')::jsonb,
    updated_at = NOW()
WHERE type = 'compression'
  AND concepts::text LIKE '%output.file_url%';

-- 5. convert_to_file: real output fields are file and row_count ----------------
UPDATE node_type_documentation
SET concepts = replace(replace(replace(concepts::text,
        '"Access filename: {{core:label.output.filename}}", ', ''),
        '{{core:label.output.rowCount}}', '{{core:label.output.row_count}}'),
        'Access the file URL: {{core:label.output.file_url}}',
        'Access the file: {{core:label.output.file}}')::jsonb,
    updated_at = NOW()
WHERE type = 'convert_to_file'
  AND concepts::text LIKE '%output.filename%';

-- 6. response: the response node stores only `message`; format is ignored ------
UPDATE node_type_documentation
SET examples = replace(examples::text, ', format: ''markdown''', '')::jsonb,
    updated_at = NOW()
WHERE type = 'response'
  AND examples::text LIKE '%format: ''markdown''%';
