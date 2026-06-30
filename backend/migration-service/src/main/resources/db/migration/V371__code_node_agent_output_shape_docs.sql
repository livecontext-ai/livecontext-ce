-- ============================================================================
-- V323: Document how to read an AGENT's reply from inside a Code node
-- ============================================================================
-- Field-tested finding (Support Inbox Triage, 2026-06-26): an agent node exposes
-- its text under DIFFERENT keys depending on its execution path.
--   * Inline / synchronous agent  -> output carries `response` AND `content`.
--   * Asynchronous completion      -> output is the raw worker payload:
--       `content` + `finalResponse` (+ thinkingSections, conversationHistory,
--       provider, model, toolResults, agent_config_snapshot) and NO `response`.
-- An agent runs async whenever it is INSIDE A SPLIT, or whenever it awaited a
-- signal. The orchestrator's enrichAgentFields aliases content<-response only
-- forward, never response<-content, so `response` stays absent on the async path.
-- => `content` is the ONLY text key present on BOTH paths.
-- The async output also embeds agent_config_snapshot.agentConfigId (a UUID): a
-- naive "first non-empty string" deepFind returns that UUID, not the reply.
-- This migration appends those two rules to the code node documentation so future
-- agents read the right field the first time instead of re-discovering the trap.
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
    "READING AN AGENT REPLY - the text key DIFFERS by execution path. ALWAYS prefer $input.<agent>.content: it is present on BOTH paths. `response` exists ONLY when the agent ran inline/synchronously; `finalResponse` exists ONLY when it ran asynchronously (agent INSIDE A SPLIT, or any agent that awaited a signal). Robust one-liner: var a = $input.assistant || {}; var text = a.content || a.finalResponse || a.response || \"\";",
    "DO NOT blind-recurse $input for the first non-empty string to find an agent reply - the async agent output embeds agent_config_snapshot.agentConfigId (a UUID), so a naive deepFind returns that UUID instead of the text. Read the known text keys (content -> finalResponse -> response) explicitly.",
    "Access results downstream: {{core:label.output.result.field_name}}"
  ]'::jsonb,
  examples = E'[
    "workflow_builder(action=''add_node'', type=''code'', label=''Transform Data'', params={language: ''javascript'', code: ''const items = $input.find_users.items; $output = { count: items.length, names: items.map(i => i.name) }''}, connect_after=''Find Users'')",
    "workflow_builder(action=''add_node'', type=''code'', label=''Build Context'', params={language: ''javascript'', code: ''const items = $input.vector_search.items || []; const ctx = items.map(function(item, i) { return i + \\\": \\\" + item.content; }).join(\\\"\\\\n\\\"); $output = { context: ctx, count: items.length }''}, connect_after=''Vector Search'')",
    "workflow_builder(action=''add_node'', type=''code'', label=''Process CSV'', params={language: ''python'', code: ''rows = _input[\\\"fetch_data\\\"][\\\"items\\\"]; _output = {\\\"count\\\": len(rows), \\\"total\\\": sum(r[\\\"amount\\\"] for r in rows)}''}, connect_after=''Fetch Data'')",
    "workflow_builder(action=''add_node'', type=''code'', label=''Prepare Reply'', params={language: ''javascript'', code: ''const a = $input.assistant || {}; const text = a.content || a.finalResponse || a.response || \\\"\\\"; $output = { text: text.trim() || \\\"No reply produced\\\" }''}, connect_after=''Assistant'')"
  ]'::jsonb,
  description = 'Execute custom code in JavaScript, Python, TypeScript, or Bash via a sandboxed Piston runtime. $input (JS/TS) / _input (Python) / INPUT (Bash) contains all predecessor outputs keyed by node alias. Access fields directly: $input.my_step.field - NO .output wrapper needed. Reading an agent reply: prefer $input.<agent>.content (present on every execution path); `response` is sync-only and `finalResponse` is async/split-only. Set $output/_output/OUTPUT to pass structured results downstream.',
  updated_at = NOW()
WHERE type = 'code';
