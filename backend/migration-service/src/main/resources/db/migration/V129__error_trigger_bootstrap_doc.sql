-- ============================================================================
-- V125: Error trigger docs - make bootstrap requirement and parent_workflow_id
--       parameter explicit so the agent stops creating broken handlers.
--
-- Bug 1: V11 seeded params={ "id": ... } as the parent workflow link, but the
-- agent-facing TriggerCreator accepts parent_workflow_id / workflow_id / id.
-- Document parent_workflow_id (the natural name) as the canonical parameter.
--
-- Bug 2: V11 mentioned "must have an active run (WAITING_TRIGGER)" inside a
-- generic concept bullet, but the agent had to grep for it. Promote the
-- bootstrap step into the description, concepts, AND examples so the agent
-- cannot miss it.
--
-- Bug 3: V11 examples used "workflow_builder(action='add_trigger', ...)" - a
-- non-existent tool. Replace with the real workflow(action='add_node', ...)
-- syntax and add a concrete bootstrap example.
--
-- Aligned with: TriggerCreator validation (parent_workflow_id required),
--               TriggerCreator.buildTriggerSchema branch for 'error',
--               TriggerStepResponseBuilder.behavior for 'error',
--               WorkflowHelpProvider.fireableTypes['error (NOT fireable)'],
--               WorkflowBuilderHelpModule.add_error_trigger example.
-- ============================================================================

UPDATE orchestrator.node_type_documentation
SET
    description = 'Fires when a referenced workflow run ends in FAILED or PARTIAL_SUCCESS. Use to build error-handler workflows that react to failures (alert ops, log, retry, fall back). Anti-loop: if the handler itself fails, it does NOT trigger another error handler. ⚠️ BOOTSTRAP REQUIRED: after saving the handler, call workflow(action=''execute'', id=''<this_workflow_id>'') ONCE so a WAITING_TRIGGER run exists. The dispatcher reuses that run on real parent failures - without it, failures are silently dropped (logged as ''No active run, skipping dispatch'').',
    parameters = '{
        "parent_workflow_id": {"type": "string", "required": true, "description": "UUID of the parent workflow whose FAILED/PARTIAL_SUCCESS runs should fire this handler. Aliases also accepted: workflow_id, id."},
        "label": {"type": "string", "required": false, "description": "Human-readable label for this trigger (e.g., ''On Payment Failure'')."}
    }'::jsonb,
    outputs = '{
        "parentWorkflowId": {"type": "string", "description": "UUID of the parent workflow that failed."},
        "parentRunId": {"type": "string", "description": "Public run id of the parent execution that failed - use to fetch full details via workflow(action=''get_run'', run_id=...)."},
        "status": {"type": "string", "enum": ["FAILED", "PARTIAL_SUCCESS"], "description": "Terminal status of the parent run."},
        "errorMessage": {"type": "string", "description": "Aggregated failure message (or ''Workflow execution failed'' if not surfaced)."},
        "triggeredAt": {"type": "string", "description": "ISO timestamp when this handler fired."},
        "failedSteps": {"type": "number", "description": "Count of failed nodes in the parent run."},
        "completedSteps": {"type": "number", "description": "Count of completed nodes in the parent run."},
        "totalSteps": {"type": "number", "description": "Total node count in the parent plan."},
        "skippedSteps": {"type": "number", "description": "Count of skipped nodes in the parent run."}
    }'::jsonb,
    concepts = '[
        "Fires only on terminal failure states (FAILED or PARTIAL_SUCCESS) of the watched parent workflow.",
        "BOOTSTRAP: The dispatcher reuses an existing non-terminal run of THIS handler - it never creates one. Seed the run yourself with workflow(action=''execute'', id=''<handler_id>'') after finish.",
        "Anti-loop: an error handler that itself fails does NOT trigger another error handler. The cascade stops at one level.",
        "Multi-DAG: multiple error triggers in one workflow can watch different parents (each becomes its own DAG).",
        "Pinning: in production, fires only when the handler workflow has a pinned version (same rule as schedule/webhook).",
        "Cannot be fired manually with workflow(action=''execute'', trigger_id=...) - execute without trigger_id is what creates the bootstrap run.",
        "Access fields via {{trigger:<label>.output.errorMessage}}, {{trigger:<label>.output.parentRunId}}, etc."
    ]'::jsonb,
    examples = '[
        "// 1. Add the error trigger pointing at the watched workflow:",
        "workflow(action=''add_node'', type=''error'', label=''On Order Failure'', params={parent_workflow_id: ''<order-workflow-uuid>''})",
        "// 2. Chain a handler - e.g. notify ops with the parent error message:",
        "workflow(action=''add_node'', type=''send_email'', label=''Alert Ops'', params={toEmail: ''ops@example.com'', subject: ''Workflow failed'', body: ''Parent run {{trigger:on_order_failure.output.parentRunId}} failed: {{trigger:on_order_failure.output.errorMessage}}''}, connect_after=''On Order Failure'')",
        "// 3. Finish + BOOTSTRAP (otherwise dispatcher silently drops the first failure):",
        "workflow(action=''finish'')",
        "workflow(action=''execute'', id=''<this_handler_id>'')",
        "// 4. Verify wiring: cause a failure in the parent workflow and check workflow(action=''runs'', workflow_id=''<this_handler_id>'') for a new epoch."
    ]'::jsonb,
    keywords = '["error","failure","fail","handler","catch","exception","on-error","error-trigger","alert","notify","recovery","fallback","bootstrap","FAILED","PARTIAL_SUCCESS"]'::jsonb,
    updated_at = NOW()
WHERE type = 'error';
