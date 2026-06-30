-- V61: Enrich classify and guardrail node_type_documentation with internal behavior details.
-- The AI workflow builder MUST know that:
--   1. A system prompt is injected automatically (the user prompt is NOT the only prompt)
--   2. The categories/rules provided in params are formatted and injected into the LLM prompt
--   3. The LLM is instructed to respond exclusively in JSON (the user should NOT add format instructions)
--   4. The user's 'prompt' param is injected as supplementary instruction, not as the main prompt

-- ── Classify ────────────────────────────────────────────────────────────────

UPDATE node_type_documentation
SET concepts = '[
  "CRITICAL - DATA FLOW: The prompt parameter MUST include data from a previous node using {{type:label.output.field}} syntax. Writing just ''Classify the message'' gives the node NO data to work with.",
  "The prompt contains BOTH the instruction AND the data reference. Example: ''Classify this ticket by type: {{trigger:form.output.description}}''",
  "INTERNAL SYSTEM PROMPT - INJECTED AUTOMATICALLY: A system prompt is injected behind the scenes instructing the LLM to act as a classification assistant and respond with EXCLUSIVELY a raw JSON object (no markdown, no preamble, no explanation - pure JSON only). The enforced JSON response schema is: {\"selected_category\": \"<one of the category labels>\", \"confidence\": 0.0-1.0, \"reasoning\": \"<explanation>\"}. Do NOT include JSON format instructions or output schema in the prompt parameter - they are already handled by the injected system prompt.",
  "CATEGORY INJECTION: The categories array provided in params (each with label and description) is automatically formatted and injected into the LLM prompt as a structured list. The LLM sees each category label and its description. The selected_category in the response MUST match one of the provided category labels exactly (case-sensitive).",
  "PROMPT ROLE: The prompt parameter is injected as a ''Classification Instruction'' section in the user prompt, alongside the categories and the content. Use it for domain-specific guidance (e.g. ''Prioritize billing if the message mentions money''), NOT for output format instructions.",
  "SEMANTIC ROUTING: AI understands meaning, not just values",
  "vs Decision: Decision = explicit conditions (status==''active''), Classify = AI understands content",
  "vs Switch: Switch = exact value match, Classify = semantic understanding",
  "ONE category selected (exclusive). Confidence indicates certainty.",
  "Variable syntax: {{trigger:label.output.field}}, {{mcp:label.output.field}}, {{agent:label.output.response}}, {{core:label.output.field}}"
]'::jsonb,
    updated_at = NOW()
WHERE type = 'classify';

-- ── Guardrail ───────────────────────────────────────────────────────────────

UPDATE node_type_documentation
SET concepts = '[
  "CRITICAL - DATA FLOW: The input parameter MUST reference data from a previous node using {{type:label.output.field}} syntax. Writing just ''Check the content'' gives the node NO data to validate.",
  "INTERNAL SYSTEM PROMPT - INJECTED AUTOMATICALLY: A system prompt is injected behind the scenes instructing the LLM to act as a content moderation assistant and respond with EXCLUSIVELY a raw JSON object (no markdown, no preamble, no explanation - pure JSON only). The enforced JSON response schema is: {\"passed\": true/false, \"violations\": [\"rule_id\", ...], \"details\": {\"rule_id\": {\"violated\": bool, \"severity\": \"low|medium|high|critical\", \"explanation\": \"...\", \"matched_content\": \"...\"}}, \"sanitized\": \"...\"}. Do NOT include JSON format instructions or output schema in the prompt parameter - they are already handled by the injected system prompt.",
  "RULES INJECTION: The rules array provided in params (each entry has an id and a description) is automatically formatted and injected into the LLM prompt as a structured checklist. The LLM evaluates ALL rules and returns violation details per rule id. Rule IDs in the response match the ids you provided.",
  "ACTION MODE INJECTION: The action parameter (flag/block/redact) is automatically translated into an instruction in the LLM prompt. flag=mark but allow through, block=prevent continuation, redact=return sanitized content with violations replaced by [REDACTED].",
  "PROMPT ROLE: The prompt parameter (optional) is injected as an ''Additional Instructions'' section in the user prompt. Use it for domain-specific guidance (e.g. ''Be strict about medical claims''), NOT for output format instructions.",
  "Variable syntax: {{trigger:label.output.field}}, {{mcp:label.output.field}}, {{agent:label.output.response}}, {{core:label.output.field}}",
  "Typically placed after an agent node to validate its output before proceeding"
]'::jsonb,
    updated_at = NOW()
WHERE type = 'guardrail';
