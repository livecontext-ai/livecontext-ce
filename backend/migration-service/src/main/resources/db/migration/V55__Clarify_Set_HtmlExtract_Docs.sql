-- ============================================================================
-- V55: Hotfix for V53 - clarify Set and HtmlExtract node_type_documentation.
-- V53 already ran on existing databases, so this migration re-applies the
-- clarified descriptions, parameters and outputs JSON to the persisted rows.
-- Source-of-truth V53 SQL has also been updated; this file only ensures DBs
-- that already applied V53 end up with the same content.
-- ============================================================================

SET search_path TO orchestrator;

UPDATE node_type_documentation
SET description = 'Assign or transform fields on the input data. Equivalent of n8n''s Set / Edit Fields node - the most-used no-code field assignment node. Each assignment has a name (output key), a value (template string resolved via SpEL), and an optional type (string|number|boolean|json|auto). When keepOnlySet=false the assignments are merged onto the upstream input data; when true only the assigned fields are returned. When emitting via set_plan, wrap the config under ''set'': { assignments, keepOnlySet, input }.',
    parameters = '{
      "input": {"type": "string", "required": false, "description": "Optional template/SpEL expression resolving to an upstream object that the assignments are merged onto (e.g. {{mcp:fetch.output.body}}). Ignored when keepOnlySet=true."},
      "assignments": {"type": "array", "required": true, "description": "List of {name, value, type} assignments. value is a template string. type is one of ''string'' | ''number'' | ''boolean'' | ''json'' | ''auto'' (default)."},
      "keepOnlySet": {"type": "boolean", "default": false, "required": false, "description": "If true, only the assigned fields are returned; otherwise they are merged onto the input data."}
    }'::jsonb,
    outputs = '{
      "fields":  {"type": "object", "description": "DO NOT REFERENCE downstream - use ''output'' instead. This is a debug-only mirror of ''output'' kept for introspection. Writing {{core:<label>.output.fields.<name>}} or {{core:<label>.fields.<name>}} will return null."},
      "output":  {"type": "object", "description": "Final merged object. Reference assignments downstream as {{core:<label>.output.<assignment_name>}}. When keepOnlySet=false, also includes upstream input fields."},
      "keep_only_set": {"type": "boolean", "description": "Echoes the keepOnlySet flag"},
      "count":   {"type": "number", "description": "Number of assigned fields"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'set';

UPDATE node_type_documentation
SET description = 'Parses HTML using CSS selectors and extracts fields into structured items. Powered by jsoup. Modes: "single" extracts one item from the document, "multiple" loops over elements matching rootSelector and extracts one item per match. Each field has a CSS selector, an attribute (text|html|<attrName>), an optional transform (none|trim|lowercase|uppercase|number), and required/default flags. When emitting via set_plan, wrap the config under ''htmlExtract'': { sourceHtml, extractionMode, rootSelector, fields, cleanWhitespace }.',
    outputs = '{
      "items":        {"type": "array",  "description": "Array of {<field_name>: extracted_value} objects. ALWAYS an array - single mode returns a 1-element array, multiple mode returns one per matched root element. Reference: {{core:<label>.output.items}} or {{core:<label>.output.items[0].<field_name>}} for single mode."},
      "count":        {"type": "number", "description": "Number of items extracted"},
      "matched_root": {"type": "number", "description": "Number of root elements matched"},
      "errors":       {"type": "array",  "description": "Human-readable errors for required fields that were missing"}
    }'::jsonb,
    updated_at = NOW()
WHERE type = 'html_extract';
