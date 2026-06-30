-- ============================================================================
-- V79: Update Code node documentation with clear $input access patterns
-- ============================================================================
-- The $input variable in code nodes contains all predecessor step outputs
-- keyed by their alias (label) AND full node ID. Each step's output fields
-- are directly accessible without the .output wrapper used in templates.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET
  concepts = '[
    "Supported languages: javascript, python, typescript, bash",
    "In JS/TS: upstream data available as $input, set $output for results",
    "In Python: upstream data available as _input, set _output for results",
    "In Bash: upstream data available as $INPUT env var, write to $OUTPUT file",
    "Code runs in a sandboxed Piston container with configurable timeout",
    "ACCESS PATTERN - $input contains all predecessor outputs keyed by node alias (normalized label). Access fields directly: $input.alias.field. Example: if table:vector_search outputs {items: [...]}, use $input.vector_search.items",
    "MAPPING from template syntax to code: {{table:label.output.field}} becomes $input.label.field (or $input[\"table:label\"].field with full node ID)",
    "Available keys in $input: each predecessor has TWO keys - the alias (e.g. vector_search) and the full ID (e.g. table:vector_search). Both point to the same data.",
    "Common patterns: $input.trigger_label.field (trigger data), $input.step_label.field (step output), $input.step_label.items (table find results)",
    "Access results downstream: {{core:label.output.result.field_name}}"
  ]'::jsonb,
  examples = E'[
    "workflow_builder(action=''add_node'', type=''code'', label=''Transform Data'', params={language: ''javascript'', code: ''const items = $input.find_users.items; $output = { count: items.length, names: items.map(i => i.name) }''}, connect_after=''Find Users'')",
    "workflow_builder(action=''add_node'', type=''code'', label=''Build Context'', params={language: ''javascript'', code: ''const items = $input.vector_search.items || []; const ctx = items.map(function(item, i) { return i + \\\": \\\" + item.content; }).join(\\\"\\\\n\\\"); $output = { context: ctx, count: items.length }''}, connect_after=''Vector Search'')",
    "workflow_builder(action=''add_node'', type=''code'', label=''Process CSV'', params={language: ''python'', code: ''rows = _input[\\\"fetch_data\\\"][\\\"items\\\"]; _output = {\\\"count\\\": len(rows), \\\"total\\\": sum(r[\\\"amount\\\"] for r in rows)}''}, connect_after=''Fetch Data'')",
    "workflow_builder(action=''add_node'', type=''code'', label=''Merge Results'', params={language: ''javascript'', code: ''const a = $input.step_a.response; const b = $input.step_b.items; $output = { combined: a + \\\" | \\\" + JSON.stringify(b) }''}, connect_after=''Step B'')"
  ]'::jsonb,
  description = 'Execute custom code in JavaScript, Python, TypeScript, or Bash via a sandboxed Piston runtime. $input (JS/TS) / _input (Python) / INPUT (Bash) contains all predecessor outputs keyed by node alias. Access fields directly: $input.my_step.field - NO .output wrapper needed. Set $output/_output/OUTPUT to pass structured results downstream.',
  updated_at = NOW()
WHERE type = 'code';
