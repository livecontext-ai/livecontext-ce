-- ============================================================================
-- V131: Error trigger docs - execute now returns status='BOOTSTRAPPED' (not throw).
--
-- Before this migration, V129 told the agent: "call workflow(action='execute',
-- id='<this_workflow_id>') ONCE so a WAITING_TRIGGER run exists." That was true,
-- BUT the call also threw "No agent-fireable trigger found" - the seed run was
-- created as a side effect of the throw. The agent saw the error and assumed
-- bootstrap failed.
--
-- WorkflowBuilderProvider.executeWorkflow now short-circuits when every trigger
-- in the plan is non-agent-fireable (workflow / error). It returns
-- status='BOOTSTRAPPED' with the seed run id, no throw. This migration aligns
-- the agent-facing docs with the new contract.
--
-- Aligned with:
--   - WorkflowBuilderProvider.executeWorkflow (bootstrap-only short-circuit)
--   - AgentWorkflowFireService.hasOnlyBootstrapTriggers
--   - WorkflowHelpProvider.fireableTypes['error (NOT fireable)']
--   - TriggerCreator validation message (parent_workflow_id required)
--   - TriggerStepResponseBuilder.behavior['error']
--   - WorkflowBuilderHelpModule.add_error_trigger example
-- ============================================================================

UPDATE orchestrator.node_type_documentation
SET
    description = 'Fires when a referenced workflow run ends in FAILED or PARTIAL_SUCCESS. Use to build error-handler workflows that react to failures (alert ops, log, retry, fall back). Anti-loop: if the handler itself fails, it does NOT trigger another error handler. BOOTSTRAP: after workflow(action=''finish''), call workflow(action=''execute'', id=''<this_workflow_id>'') ONCE - it returns status=''BOOTSTRAPPED'' with the seed run id (no fire happens; error triggers are system-only). The dispatcher attaches future parent failures to that seed run; without it, failures are silently dropped (logged as ''No active run, skipping dispatch''). To exercise the chain end-to-end, fail the parent workflow and check workflow(action=''runs'', workflow_id=''<this_workflow_id>'') for a new epoch.',
    concepts = '[
        "Fires only on terminal failure states (FAILED or PARTIAL_SUCCESS) of the watched parent workflow.",
        "BOOTSTRAP: workflow(action=''execute'', id=''<handler_id>'') after finish returns status=''BOOTSTRAPPED'' with the seed run id. No fire happens - the trigger is system-only. The dispatcher reuses that seed run on future parent failures.",
        "Anti-loop: an error handler that itself fails does NOT trigger another error handler. The cascade stops at one level.",
        "Multi-DAG: multiple error triggers in one workflow can watch different parents (each becomes its own DAG).",
        "Pinning: in production, fires only when the handler workflow has a pinned version (same rule as schedule/webhook).",
        "There is no manual fire path. To test dispatch end-to-end, fail the watched parent (e.g. add a stop_on_error step then execute it) and verify a new epoch lands on the seed run via workflow(action=''runs'', workflow_id=''<handler_id>'').",
        "Access fields via {{trigger:<label>.output.errorMessage}}, {{trigger:<label>.output.parentRunId}}, etc."
    ]'::jsonb,
    examples = '[
        "// 1. Add the error trigger pointing at the watched workflow:",
        "workflow(action=''add_node'', type=''error'', label=''On Order Failure'', params={parent_workflow_id: ''<order-workflow-uuid>''})",
        "// 2. Chain a handler - e.g. notify ops with the parent error message:",
        "workflow(action=''add_node'', type=''send_email'', label=''Alert Ops'', params={toEmail: ''ops@example.com'', subject: ''Workflow failed'', body: ''Parent run {{trigger:on_order_failure.output.parentRunId}} failed: {{trigger:on_order_failure.output.errorMessage}}''}, connect_after=''On Order Failure'')",
        "// 3. Save:",
        "workflow(action=''finish'')",
        "// 4. Bootstrap the seed run (returns status=''BOOTSTRAPPED'', no fire happens):",
        "workflow(action=''execute'', id=''<this_handler_id>'')",
        "// 5. Verify the seed:",
        "workflow(action=''runs'', workflow_id=''<this_handler_id>'')  // expect one WAITING_TRIGGER run",
        "// 6. Exercise the dispatch chain by failing the parent and looking for a new epoch on the seed run:",
        "workflow(action=''execute'', id=''<order-workflow-uuid>'')  // parent fails → handler dispatcher attaches a new epoch"
    ]'::jsonb,
    updated_at = NOW()
WHERE type = 'error';
