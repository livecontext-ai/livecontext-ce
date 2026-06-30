-- V132__manual_trigger_doc_triggered_by_display_name.sql
-- ============================================================================
-- Manual Trigger node_type_documentation - clarify triggered_by semantics
--
-- Background: V11 (initial seed) described triggered_by as "User ID who
-- triggered the workflow". The runtime ManualTriggerResolver now looks up
-- the user's display name via AuthClient.getDisplayName(tenantId) instead of
-- exposing the raw tenantId. Update the doc the LLM reads so it generates
-- variable_mapping references ({{trigger:start.output.triggered_by}}) with
-- the correct semantic expectation ("display name", not "user id").
--
-- Also keeps the description and output keys in snake_case so interface
-- templates that reference triggered_at / triggered_by resolve correctly.

UPDATE orchestrator.node_type_documentation
SET
    outputs = jsonb_build_object(
        'triggered_at', jsonb_build_object(
            'type', 'string',
            'description', 'ISO timestamp when the workflow was triggered'
        ),
        'triggered_by', jsonb_build_object(
            'type', 'string',
            'description', 'Display name of the user who triggered the workflow (empty string when no display name is configured). Never the raw tenantId.'
        )
    ),
    concepts = '["Simplest trigger for testing or on-demand execution", "triggered_at and triggered_by are snake_case - use them as-is in variable_mapping", "triggered_by resolves to the user display name via AuthClient, not tenantId"]'::jsonb,
    updated_at = NOW()
WHERE type = 'manual';
