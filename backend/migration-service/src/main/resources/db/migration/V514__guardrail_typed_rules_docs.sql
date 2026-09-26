-- Guardrail rules are now ENFORCED by type. Until this change every rule was reduced to
-- {id, description} and judged by one LLM call, so a keyword list, a regex, a length bound or a
-- PII type selected in the builder was never checked. keyword_filter, regex_pattern,
-- length_check, pii_detection (email, phone, ssn, credit_card), custom and competitor_mention
-- are now decided without a model; toxic_language, prompt_injection, topic_restriction and a
-- PII address stay with the model. The agent-facing documentation below says so, and accepts the
-- typed array form of `rules` beside the {ruleId: description} object.

UPDATE node_type_documentation
SET parameters = jsonb_set(
        jsonb_set(parameters, '{rules}', '{
          "type": "object|array",
          "required": true,
          "description": "Either an object {ruleId: description} (each rule judged by the model), or an array of typed rules [{type, action, config, id?}]. Types checked WITHOUT a model: keyword_filter (config.keywordsExpression: comma-separated list, a list or a {{...}} reference; config.mode: block = fail when one appears, allow = fail when none appears), regex_pattern (config.pattern; config.mode: require = the content must match (default), block = the content must not contain a match), length_check (config.minLength / config.maxLength in characters), pii_detection (config.piiTypes among email, phone, ssn, credit_card, address; address is judged by the model; no piiTypes = email, phone, ssn and credit_card; a phone needs a leading + or a separator, a bare run of digits is not one), custom (config.expression: boolean expression, #input is the content, true = valid, e.g. #length(#input) > 10), competitor_mention (config.topicsExpression: names that must not appear). Types judged by the model: toxic_language, prompt_injection, topic_restriction (config.topicsExpression: the restricted topics). Any violated rule routes to fail (passed=false), whatever its action. action per rule: block (default) and flag only list the violation, sanitize also replaces the matched text by [REDACTED] in sanitized. A rule whose config is left empty is judged by the model from its description, and so is an older imported rule whose list field holds its description or whose length bounds (1 to 10000) or PII types (email, phone, credit_card) are the import defaults next to a description. A rule whose config is invalid (keywords that resolve to nothing, a regex that does not compile) fails the node with the rule named."
        }'::jsonb),
        '{action}', '{
          "enum": ["flag", "block", "redact"],
          "type": "string",
          "default": "flag",
          "required": false,
          "description": "How the MODEL treats the rules it judges when they carry no action of their own: flag = report, block = report as blocking, redact = also return sanitized content. A typed rule with its own action (block/flag/sanitize) follows that action instead."
        }'::jsonb),
    outputs = jsonb_set(
        jsonb_set(outputs, '{violations}', '{
          "type": "array",
          "description": "Ids of the rules the content violated (strings), including flagged and sanitized ones"
        }'::jsonb),
        '{details}', '{
          "type": "object",
          "description": "Per rule id: {violated, severity, explanation, matched_content, type, action}"
        }'::jsonb),
    concepts = '[
  "DATA FLOW: the input parameter MUST reference data from a previous node with {{type:label.output.field}}. Writing plain text gives the node nothing to validate.",
  "TWO KINDS OF RULES: typed rules keyword_filter, regex_pattern, length_check, pii_detection (email, phone, ssn, credit_card), custom and competitor_mention are checked exactly, without a model and without tokens. toxic_language, prompt_injection, topic_restriction, a pii_detection address and every {ruleId: description} rule are judged by the model. When every rule is typed and checked without a model, the node makes no LLM call at all.",
  "CONFIG VALUES ACCEPT REFERENCES: keywordsExpression, pattern, topicsExpression, expression, minLength and maxLength can hold {{...}} references, resolved against the run before the check.",
  "PASS OR FAIL: passed=false (fail port) as soon as ANY rule is violated, whatever its action. Every violation is listed in violations and details. sanitized holds the content with every sanitize match replaced by [REDACTED].",
  "INVALID RULES FAIL THE NODE: keywords or names that resolve to nothing, a regex that does not compile or runs too long, a non-numeric length bound, or a custom expression that does not return true/false fails the node with the rule named. A rule whose config is left EMPTY is judged by the model instead. Fix the rule with workflow(action=''modify'', ...).",
  "PROMPT ROLE: the optional prompt parameter is added as additional instructions for the model-judged rules. Do not put output-format instructions in it; the response format is enforced automatically.",
  "Variable syntax: {{trigger:label.output.field}}, {{mcp:label.output.field}}, {{agent:label.output.response}}, {{core:label.output.field}}",
  "Typically placed after an agent node to validate its output before proceeding"
]'::jsonb,
    examples = '[
  "workflow(action=''add_node'', type=''guardrail'', label=''Check PII'', params={input: ''{{agent:writer.output.response}}'', rules: {pii: ''Block emails and phones'', toxicity: ''Block offensive content''}, action: ''redact''}, connect_after=''Writer'')",
  "workflow(action=''add_node'', type=''guardrail'', label=''Screen Reply'', params={input: ''{{agent:writer.output.response}}'', rules: [{type: ''keyword_filter'', action: ''block'', config: {keywordsExpression: ''refund, chargeback''}}, {type: ''pii_detection'', action: ''sanitize'', config: {piiTypes: [''email'', ''phone'']}}, {type: ''length_check'', action: ''block'', config: {maxLength: 2000}}]}, connect_after=''Writer'')"
]'::jsonb,
    updated_at = NOW()
WHERE type = 'guardrail';
