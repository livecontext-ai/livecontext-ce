-- ============================================================================
-- V53: Add node_type_documentation rows for two new core nodes
--   - core:set          - Set / Edit Fields (n8n-style)
--   - core:html_extract - HTML Extract (CSS selectors via jsoup)
-- ============================================================================

SET search_path TO orchestrator;

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'set',
    'Set',
    'core',
    'core',
    'Assign or transform fields on the input data. Equivalent of n8n''s Set / Edit Fields node - the most-used no-code field assignment node. Each assignment has a name (output key), a value (template string resolved via SpEL), and an optional type (string|number|boolean|json|auto). When keepOnlySet=false the assignments are merged onto the upstream input data; when true only the assigned fields are returned. When emitting via set_plan, wrap the config under ''set'': { assignments, keepOnlySet, input }.',
    '{
      "input": {"type": "string", "required": false, "description": "Optional template/SpEL expression resolving to an upstream object that the assignments are merged onto (e.g. {{mcp:fetch.output.body}}). Ignored when keepOnlySet=true."},
      "assignments": {"type": "array", "required": true, "description": "List of {name, value, type} assignments. value is a template string. type is one of ''string'' | ''number'' | ''boolean'' | ''json'' | ''auto'' (default)."},
      "keepOnlySet": {"type": "boolean", "default": false, "required": false, "description": "If true, only the assigned fields are returned; otherwise they are merged onto the input data."}
    }'::jsonb,
    '{
      "fields":  {"type": "object", "description": "DO NOT REFERENCE downstream - use ''output'' instead. This is a debug-only mirror of ''output'' kept for introspection. Writing {{core:<label>.output.fields.<name>}} or {{core:<label>.fields.<name>}} will return null."},
      "output":  {"type": "object", "description": "Final merged object. Reference assignments downstream as {{core:<label>.output.<assignment_name>}}. When keepOnlySet=false, also includes upstream input fields."},
      "keep_only_set": {"type": "boolean", "description": "Echoes the keepOnlySet flag"},
      "count":   {"type": "number", "description": "Number of assigned fields"}
    }'::jsonb,
    NULL,
    NULL,
    '[]'::jsonb,
    '[]'::jsonb,
    '["set", "edit", "assign", "field", "transform", "n8n"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label,
    category = EXCLUDED.category,
    variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description,
    parameters = EXCLUDED.parameters,
    outputs = EXCLUDED.outputs,
    keywords = EXCLUDED.keywords,
    updated_at = NOW();

INSERT INTO node_type_documentation (
    type, label, category, variable_prefix, description,
    parameters, outputs, global_variables, edge_ports, concepts, examples, keywords,
    enabled, created_at, updated_at
) VALUES (
    'html_extract',
    'HTML Extract',
    'core',
    'core',
    'Parses HTML using CSS selectors and extracts fields into structured items. Powered by jsoup. Modes: "single" extracts one item from the document, "multiple" loops over elements matching rootSelector and extracts one item per match. Each field has a CSS selector, an attribute (text|html|<attrName>), an optional transform (none|trim|lowercase|uppercase|number), and required/default flags. When emitting via set_plan, wrap the config under ''htmlExtract'': { sourceHtml, extractionMode, rootSelector, fields, cleanWhitespace }.',
    '{
      "sourceHtml":     {"type": "string", "required": true, "description": "Template/SpEL expression resolving to the HTML to parse, e.g. {{mcp:fetch.output.body}}"},
      "extractionMode": {"type": "string", "required": false, "default": "single", "description": "single | multiple"},
      "rootSelector":   {"type": "string", "required": false, "description": "CSS selector that scopes items in multiple mode"},
      "fields":         {"type": "array",  "required": true, "description": "Array of {name, selector, attribute, transform, required, default}"},
      "cleanWhitespace": {"type": "boolean", "default": true, "description": "Trim whitespace on text extractions"}
    }'::jsonb,
    '{
      "items":        {"type": "array",  "description": "Array of {<field_name>: extracted_value} objects. ALWAYS an array - single mode returns a 1-element array, multiple mode returns one per matched root element. Reference: {{core:<label>.output.items}} or {{core:<label>.output.items[0].<field_name>}} for single mode."},
      "count":        {"type": "number", "description": "Number of items extracted"},
      "matched_root": {"type": "number", "description": "Number of root elements matched"},
      "errors":       {"type": "array",  "description": "Human-readable errors for required fields that were missing"}
    }'::jsonb,
    NULL,
    NULL,
    '[]'::jsonb,
    '[]'::jsonb,
    '["html", "extract", "scrape", "parse", "css", "selector", "jsoup"]'::jsonb,
    true, NOW(), NOW()
)
ON CONFLICT (type) DO UPDATE SET
    label = EXCLUDED.label,
    category = EXCLUDED.category,
    variable_prefix = EXCLUDED.variable_prefix,
    description = EXCLUDED.description,
    parameters = EXCLUDED.parameters,
    outputs = EXCLUDED.outputs,
    keywords = EXCLUDED.keywords,
    updated_at = NOW();
