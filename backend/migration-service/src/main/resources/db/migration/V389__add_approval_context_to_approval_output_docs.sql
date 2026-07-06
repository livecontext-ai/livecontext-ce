-- Expose the resolved `approval_context` in the User Approval node OUTPUT docs.
--
-- WHY: the approval node's contextTemplate is rendered at yield and, until now, the
-- resolved text lived ONLY on the pending signal (the awaiting-state banner). At
-- resolution SignalResumeService.buildSignalResolutionOutput writes a NEW COMPLETED
-- step whose output never carried it, so the resolved context vanished from the node's
-- params after approval and could not be referenced downstream. The output builder now
-- emits `approval_context`; keep node_type_documentation (agent-facing) in sync so the
-- variable picker / agent docs advertise {{core:<label>.output.approval_context}}.
--
-- Runs under the orchestrator search_path (see beforeEachMigrate.sql); node_type_documentation
-- lives in the orchestrator schema like the V11 seed. Idempotent: only adds the key when absent.
UPDATE node_type_documentation
SET outputs = outputs || jsonb_build_object(
        'approval_context',
        jsonb_build_object(
            'type', 'string',
            'description', 'Resolved approval context: the node''s contextTemplate rendered at pause time. Present only when a context template was configured and resolved to non-blank text.'
        )
    ),
    updated_at = NOW()
WHERE type = 'approval'
  AND NOT (outputs ? 'approval_context');
