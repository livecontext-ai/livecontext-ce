-- ============================================================================
-- V518: the bell needs AGENT as a subject type.
--
-- An agent armed with require_tool_authorization and running unattended can
-- reach a sensitive action with no chat destination to ask on. The action does
-- not run, and until now nobody was told: the card was painted into a stream
-- nobody reads, and the agent got a refusal with no reason in it. That silence
-- is the failure this row exists to end, and the bell is where the person
-- actually looks.
--
-- AGENT rather than AGENT_TASK: the subject is the agent itself, not a task.
-- The bell links to the agent's own settings, which is where the setting that
-- caused the block is changed, and a task id would send the reader somewhere
-- that has nothing to do with it.
-- ============================================================================

ALTER TABLE orchestrator.notifications
    DROP CONSTRAINT IF EXISTS chk_notif_subject_type_v1;

ALTER TABLE orchestrator.notifications
    ADD CONSTRAINT chk_notif_subject_type_v1
        CHECK (subject_type IN (
            'WORKFLOW',
            'APPLICATION',
            'AGENT_TASK',
            'CREDENTIAL',
            'TRIGGER',
            'ORG_INVITATION',
            'BADGE',
            'AGENT'
        ));

COMMENT ON CONSTRAINT chk_notif_subject_type_v1 ON orchestrator.notifications IS
    'V518 relaxed: admits AGENT, for an agent blocked with nowhere to ask.';
