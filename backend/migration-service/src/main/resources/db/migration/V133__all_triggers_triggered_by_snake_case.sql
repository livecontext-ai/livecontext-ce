-- V133__all_triggers_triggered_by_snake_case.sql
-- ============================================================================
-- Unify trigger output schema across ALL trigger types:
--   • triggered_at - ISO timestamp (snake_case)
--   • triggered_by - user display name, never tenantId (empty string when unknown)
--
-- Before: only the `manual` trigger exposed both fields snake_case. Other types
-- advertised `triggeredAt` camelCase and none surfaced `triggered_by`. The
-- frontend schema (shared/contracts/node-contracts.schema.json) is now aligned
-- to snake_case too, and TriggerNode.execute() injects `triggered_at` /
-- `triggered_by` uniformly at persistence time so interface variable_mapping
-- references like {{trigger:<label>.output.triggered_by}} resolve the same way
-- regardless of trigger type.
--
-- This migration updates node_type_documentation (which drives the LLM help
-- for `workflow(action='help', topics=['<trigger>'])`) so the agent sees the
-- same names it has to write in variable_mapping.

-- ---- chat ----
UPDATE orchestrator.node_type_documentation
SET outputs = outputs
    - 'triggeredAt'
    || jsonb_build_object(
        'triggered_at', jsonb_build_object('type', 'string', 'description', 'ISO timestamp when the workflow was triggered'),
        'triggered_by', jsonb_build_object('type', 'string', 'description', 'Display name of the user whose chat message fired the trigger. Empty when unknown. Never tenantId.')
       ),
    updated_at = NOW()
WHERE type = 'chat';

-- ---- webhook ----
UPDATE orchestrator.node_type_documentation
SET outputs = outputs
    - 'triggeredAt'
    || jsonb_build_object(
        'triggered_at', jsonb_build_object('type', 'string', 'description', 'ISO timestamp when the webhook fired'),
        'triggered_by', jsonb_build_object('type', 'string', 'description', 'Display name of the workflow owner. Empty when the webhook is unauthenticated.')
       ),
    updated_at = NOW()
WHERE type = 'webhook';

-- ---- schedule ----
UPDATE orchestrator.node_type_documentation
SET outputs = outputs
    - 'triggeredAt'
    || jsonb_build_object(
        'triggered_at', jsonb_build_object('type', 'string', 'description', 'ISO timestamp when the scheduler fired this workflow'),
        'triggered_by', jsonb_build_object('type', 'string', 'description', 'Display name of the workflow owner (schedule fires autonomously - still carries the owner identity for variable_mapping).')
       ),
    updated_at = NOW()
WHERE type = 'schedule';

-- ---- form ----
UPDATE orchestrator.node_type_documentation
SET outputs = outputs
    || jsonb_build_object(
        'triggered_at', jsonb_build_object('type', 'string', 'description', 'ISO timestamp when the form was submitted (alias of submitted_at for cross-trigger consistency)'),
        'triggered_by', jsonb_build_object('type', 'string', 'description', 'Display name of the form submitter (empty when anonymous). Never the raw tenantId.')
       ),
    updated_at = NOW()
WHERE type = 'form';

-- ---- workflow (parent → child chaining) ----
UPDATE orchestrator.node_type_documentation
SET outputs = outputs
    - 'triggeredAt'
    || jsonb_build_object(
        'triggered_at', jsonb_build_object('type', 'string', 'description', 'ISO timestamp when the parent workflow fired this one'),
        'triggered_by', jsonb_build_object('type', 'string', 'description', 'Display name of the workflow owner. Empty when the parent ran in a system context.')
       ),
    updated_at = NOW()
WHERE type = 'workflow';

-- ---- table (datasource event) ----
UPDATE orchestrator.node_type_documentation
SET outputs = outputs
    || jsonb_build_object(
        'triggered_by', jsonb_build_object('type', 'string', 'description', 'Display name of the workflow owner. Empty when the row-event source is a system process.')
       ),
    updated_at = NOW()
WHERE type = 'table';

-- ---- error ----
UPDATE orchestrator.node_type_documentation
SET outputs = outputs
    - 'triggeredAt'
    || jsonb_build_object(
        'triggered_at', jsonb_build_object('type', 'string', 'description', 'ISO timestamp when the parent failure was dispatched to this error handler'),
        'triggered_by', jsonb_build_object('type', 'string', 'description', 'Display name of the owner of the failed parent workflow. Empty when it ran in a system context.')
       ),
    updated_at = NOW()
WHERE type = 'error';

-- ---- datasource (scheduled batch scan path) ----
UPDATE orchestrator.node_type_documentation
SET outputs = outputs
    || jsonb_build_object(
        'triggered_by', jsonb_build_object('type', 'string', 'description', 'Display name of the workflow owner.')
       ),
    updated_at = NOW()
WHERE type = 'datasource';
