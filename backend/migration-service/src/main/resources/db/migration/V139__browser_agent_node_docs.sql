-- ============================================================================
-- V134: Add node_type_documentation row for the new BROWSER_AGENT node.
--
-- Aligned with: BrowserAgentNodeSpec.java (output schema), BrowserAgentModule
-- (param shape), BrowserAgentNode (execution), websearch-service /agent/sessions
-- runner (final-result payload, stop reasons).
--
-- Help text is written from the agent's POV (CLAUDE.md "Write from the Agent's
-- POV"): the agent reads this via the workflow-builder MCP help and acts on it
-- with workflow(action='add_node', type='browser_agent', params={...}). It has
-- no shell, no DB, no UI - only MCP calls.
-- ============================================================================

SET search_path TO orchestrator;

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'browser_agent',
    'Browser Agent',
    'agent',
    'agent',
    'Spawns an LLM-driven browser agent that navigates, clicks, fills forms, and extracts structured data across multiple pages. ~100x slower than a fetch (multi-minute, multi-step) and burns LLM tokens at every step. ONLY use when fetch cannot reach the target - page requires login, JS-driven UI, multi-step navigation, or interactive form filling. Concurrency is capped at 1 session per host: if another browser session is already running the node returns a RATE_LIMITED-style failure asking you to wait. The node terminates when the runner emits one of: COMPLETED, MAX_STEPS, USER_TAKEOVER, LLM_FAILED, SCHEMA_MISMATCH, DOMAIN_BLOCKED, TIMEOUT, CANCELLED, BUDGET_EXHAUSTED - see stop_reason in the output. When emitting via set_plan, wrap the config under ''browserAgent'': { task, start_url, llm, … }.',
    '{
      "task":                   {"type": "string",  "required": true,  "description": "Natural-language goal for the agent. Be specific - vague tasks waste LLM steps. Good: ''Log into example.com using {{credentials}}, open the billing page, and extract the latest invoice amount''. Supports templates."},
      "start_url":              {"type": "string",  "required": false, "description": "Starting URL the agent opens before reasoning. If omitted, the agent picks one from the task. Supports templates."},
      "llm":                    {"type": "object",  "required": true,  "description": "LLM config: { provider, model, credentials_ref, max_steps }. provider/model identify the steering LLM; credentials_ref points at a stored platform credential; max_steps caps the number of browser actions before MAX_STEPS termination (default 25, hard cap 100)."},
      "expected_output_schema": {"type": "object",  "required": false, "description": "Optional JSON Schema. The agent''s extracted_data MUST satisfy this before COMPLETED - on mismatch the session terminates with stop_reason=SCHEMA_MISMATCH and you fall through to error handling."},
      "interaction_mode":       {"type": "string",  "required": false, "default": "autonomous", "description": "One of: ''autonomous'' (runs end-to-end), ''supervised'' (yields after each step for browse_intervene), ''manual'' (starts paused, waits for browse_intervene). Default ''autonomous''."},
      "domain_allowlist":       {"type": "array",   "required": false, "description": "Allowlist of domains the agent may visit. Off-list navigation → stop_reason=DOMAIN_BLOCKED."},
      "domain_denylist":        {"type": "array",   "required": false, "description": "Denylist of domains the agent must avoid."},
      "screenshot_policy":      {"type": "string",  "required": false, "default": "on_change", "description": "One of: ''every_step'', ''on_change'', ''final_only'', ''off''. Controls how often the runner captures screenshots."},
      "session":                {"type": "object",  "required": false, "description": "Session block: { headless: bool, viewport: { width, height }, timeout_seconds: int }. timeout_seconds is the wall-clock cap - exceeding it → stop_reason=TIMEOUT."}
    }'::jsonb,
    '{
      "node_type":      {"type": "string",  "description": "Always ''BROWSER_AGENT''."},
      "final_result":   {"type": "string",  "description": "Natural-language summary of what the agent did. Always present - even on partial / error stops, describes how far the session got."},
      "extracted_data": {"type": "object",  "description": "Structured data the agent collected. Validated against expected_output_schema if one was provided. Null when the task did not request structured extraction."},
      "stop_reason":    {"type": "string",  "description": "Free-form termination reason: COMPLETED, MAX_STEPS, USER_TAKEOVER, LLM_FAILED, SCHEMA_MISMATCH, DOMAIN_BLOCKED, TIMEOUT, CANCELLED, BUDGET_EXHAUSTED. Branch on this - only COMPLETED guarantees the task succeeded."},
      "final_url":      {"type": "string",  "description": "URL the browser was on when the session ended."},
      "pages_visited":  {"type": "array",   "description": "Ordered list of URLs the agent navigated through during the session."},
      "steps":          {"type": "array",   "description": "Per-step trace: { step_index, action, action_args, target, url, eval, memory, next_goal, screenshot_key, tokens_in, tokens_out, duration_ms }. action is the short action kind (e.g. ''go_to_url'', ''click''); action_args is the full parameter dict; target is the URL or selector touched; url is the page URL after the step. Useful for debugging - can be very large, prefer projecting just what you need."},
      "screenshots":    {"type": "array",   "description": "Ordered list of MinIO screenshot keys captured during the session (governed by screenshot_policy). Empty when policy=''off''."},
      "cost":           {"type": "object",  "description": "Cost breakdown: { tokens_in, tokens_out, llm_calls, browser_seconds, cost_usd }."},
      "session_id":     {"type": "string",  "description": "UUID identifying this session. While the session is in flight (i.e. you spawned it via web_search agent_browse and the run hasn''t reached this node''s completion yet), pass this id to web_search(action=''browse_status''/''browse_intervene''/''browse_abort''/''browse_screenshot'') to inspect or steer it."}
    }'::jsonb,
    NULL,
    NULL,
    '[
      "Cost asymmetry: a browser agent run costs LLM tokens AT EVERY STEP plus browser CPU/RAM. A simple 10-step session can be 100x the cost of a fetch. Reach for this node ONLY when fetch genuinely cannot reach the target page (auth-walled, JS-driven UI, interactive form filling).",
      "Concurrency=1 per host. If a browser session is already running, this node fails fast with a RATE_LIMITED-style message - do NOT retry immediately, wait for the in-flight session to complete.",
      "Always branch on stop_reason. COMPLETED is the only success state. MAX_STEPS / SCHEMA_MISMATCH / TIMEOUT / DOMAIN_BLOCKED indicate the agent gave up - final_result and extracted_data may be partial but should not be trusted as authoritative.",
      "domain_allowlist is defense in depth - pair it with a clear task description so the agent does not waste steps trying to leave the allowed surface.",
      "screenshot_policy=''every_step'' produces a complete replay but also costs MinIO storage per step. For long-running sessions prefer ''on_change'' (the runner default).",
      "There is no concept of ''retry'' for this node - a SCHEMA_MISMATCH or LLM_FAILED is a sign the task was ambiguous or the schema was too strict. Tighten the task / loosen the schema before re-adding the node."
    ]'::jsonb,
    '[
      "// Single-shot scrape behind a login wall:",
      "workflow(action=''add_node'', type=''browser_agent'', label=''Pull Invoice'', params={task: ''Log into billing.example.com using {{credentials.example_billing}}, open the latest invoice, extract its amount and due date.'', start_url: ''https://billing.example.com/login'', llm: {provider: ''openai'', model: ''gpt-4o'', max_steps: 25}, expected_output_schema: {type: ''object'', properties: {amount: {type: ''number''}, due_date: {type: ''string''}}, required: [''amount'', ''due_date'']}})",
      "// Then react to the outcome:",
      "workflow(action=''add_node'', type=''decision'', label=''Did it work?'', params={condition: ''{{agent:pull_invoice.output.stop_reason}} == \"COMPLETED\"''}, connect_after=''Pull Invoice'')",
      "// Happy path: act on extracted_data:",
      "workflow(action=''add_node'', type=''send_email'', label=''Notify'', params={subject: ''Invoice for {{agent:pull_invoice.output.extracted_data.amount}} due {{agent:pull_invoice.output.extracted_data.due_date}}''}, connect_after=''Did it work?:if'')",
      "// Sad path: log the failure and exit:",
      "workflow(action=''add_node'', type=''stop_on_error'', label=''Browser failed'', params={errorMessage: ''Browser agent stopped: {{agent:pull_invoice.output.stop_reason}} - {{agent:pull_invoice.output.final_result}}''}, connect_after=''Did it work?:else'')"
    ]'::jsonb,
    '["browser","agent","navigate","scrape","interact","login","form","extract","selenium","playwright","headless","autonomous"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label,
    category = EXCLUDED.category,
    variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description,
    parameters = EXCLUDED.parameters,
    outputs = EXCLUDED.outputs,
    concepts = EXCLUDED.concepts,
    examples = EXCLUDED.examples,
    keywords = EXCLUDED.keywords,
    updated_at = NOW();
