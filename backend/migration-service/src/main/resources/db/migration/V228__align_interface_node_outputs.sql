-- V228: Align node_type_documentation.outputs for INTERFACE with InterfaceNodeSpec.
--
-- V11 seeded INTERFACE outputs without action_mapping / is_entry_interface (both
-- emitted by InterfaceNode.execute() and persisted by GenericOutputSchemaMapper
-- via InterfaceNodeSpec.definition().outputs()). Agents that read this row to
-- build SpEL references therefore couldn't see those two fields.
--
-- The dynamic per-action data is kept as a placeholder pattern (output.{action_name}
-- and output.{action_name}.fired_at) - the runtime shape merged at signal resolve
-- time. Agents need both the literal NodeSpec outputs AND the dynamic pattern.
--
-- resolved_params is intentionally NOT documented: it lives in ENGINE_ENVELOPE_KEYS
-- and is stripped before persistence.
--
-- Idempotent UPDATE (safe to re-apply - overwrites the outputs JSONB).
SET search_path TO orchestrator;

UPDATE node_type_documentation
SET outputs = '{
    "interface_id": {
        "type": "string",
        "description": "UUID of the rendered interface entity (matches the interface_id parameter)."
    },
    "action_mapping": {
        "type": "object",
        "description": "Maps CSS selectors to trigger/navigation tokens. Same shape as the action_mapping parameter, surfaced as output for downstream introspection."
    },
    "is_entry_interface": {
        "type": "boolean",
        "description": "True if this interface is the entry tab shown first in Application Mode."
    },
    "output.{action_name}": {
        "type": "object",
        "description": "DYNAMIC: when a user fires an action bound via action_mapping (e.g. trigger:submit:submit → key ''submit''), the submitted form fields are merged under output.{action_name}. The {action_name} segment is the normalized trigger label, NOT a literal field. Access via {{interface:label.output.action_name.field_name}}, e.g. {{interface:my_form.output.submit.email}}."
    },
    "output.{action_name}.fired_at": {
        "type": "string",
        "description": "ISO-8601 timestamp captured when the action was fired by the user. Always present alongside the action''s form fields under output.{action_name}."
    },
    "spel_pattern": {
        "type": "string",
        "description": "Variable reference shape: {{interface:label.output.action_name.field_name}} - e.g. {{interface:my_form.output.submit.email}}."
    }
}'::jsonb,
    updated_at = NOW()
WHERE type = 'interface';
