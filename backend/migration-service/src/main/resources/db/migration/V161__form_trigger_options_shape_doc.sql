-- ---------------------------------------------------------------------------
-- V161: Form trigger - clarify the `options` shape for select-like fields.
--
-- Problem in prod (caught 2026-05-01): an LLM agent calling
-- workflow(action='add_node', type='form', ...) had no schema for the
-- `options` array on select/multiselect/radio/checkboxGroup fields. The DB
-- documentation (V11 seed → row id=22, type='form') only said:
--
--    "fields": "[{name, type, label, required, options}]. Types: text, ..."
--
-- with no nested-object spec for `options` and no example using a `select`
-- field. The agent reasonably guessed the simplest shape - a string array
-- (`options: ["a","b"]`) - which the backend accepted as-is, but the
-- inspector `OptionsEditor` and the runtime preview `TriggerPanel` both
-- key on `option.id / option.label / option.value` (object form) → the
-- builder UI rendered empty inputs and the user couldn't see / edit the
-- options they thought they had.
--
-- Fix (this migration): rewrite the `parameters.fields` description so the
-- LLM is told explicitly that `options` is `[{label, value}]`, and add an
-- example row that includes a `select` field with options. The backend
-- coercion in TriggerCreator.coerceFieldOptions / frontend importer
-- TriggerNodeCreator.normalizeFieldOptions still accept the string-array
-- shorthand for legacy plans, but the doc no longer encourages it.
--
-- Idempotent (UPDATE on a single row).
-- ---------------------------------------------------------------------------

UPDATE orchestrator.node_type_documentation
SET parameters = jsonb_build_object(
        'title', jsonb_build_object(
            'type', 'string',
            'required', false,
            'description', 'Form title displayed to users'
        ),
        'fields', jsonb_build_object(
            'type', 'array',
            'required', false,
            'description', 'Array of {name, type, label, required, options?, defaultValue?}. ' ||
                'Types: text, email, textarea, select, checkbox, number, date, datetime, time, ' ||
                'file, phone, url, multiselect, checkboxGroup, radio, password, hidden. ' ||
                'For select/multiselect/radio/checkboxGroup, the options array MUST be ' ||
                '[{label, value}] objects (NOT plain strings). Example: ' ||
                '[{label: "Free", value: "free"}, {label: "Pro", value: "pro"}]. ' ||
                'String shorthand ["a","b"] is also accepted (auto-coerced to ' ||
                '[{label:"a",value:"a"}, ...]) but the explicit object form is preferred for ' ||
                'human-readable labels distinct from machine values.'
        ),
        'submit_button_text', jsonb_build_object(
            'type', 'string',
            'default', 'Submit',
            'required', false
        )
    ),
    examples = '[
        "workflow(action=''add_node'', type=''form'', label=''Contact Form'', params={title: ''Contact Us'', fields: [{name: ''email'', type: ''email'', label: ''Email'', required: true}, {name: ''subscribe'', type: ''checkbox'', label: ''Subscribe to newsletter''}]})",
        "workflow(action=''add_node'', type=''form'', label=''Image Prompt'', params={title: ''Generate Image'', fields: [{name: ''prompt'', type: ''textarea'', label: ''Image Prompt'', required: true}, {name: ''model'', type: ''select'', label: ''Model'', required: true, options: [{label: ''GPT Image 2'', value: ''gpt-image-2''}, {label: ''GPT Image 1.5'', value: ''gpt-image-1.5''}]}]})"
    ]'::jsonb,
    updated_at = NOW()
WHERE type = 'form' AND category = 'trigger';
