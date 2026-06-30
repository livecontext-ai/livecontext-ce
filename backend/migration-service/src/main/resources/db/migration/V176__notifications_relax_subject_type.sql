-- V176: Relax `chk_notif_subject_type_v1` to support cross-service categories.
--
-- V172 created the constraint tight at `subject_type = 'WORKFLOW'` since the
-- only V1 producer was {@code RUN_FAILED} via {@code WorkflowRunTerminatedEvent}.
-- V174 relaxed the payload check; subject_type stayed pinned by design pending
-- the cross-service notification rollout (P3+).
--
-- P3 introduces {@code notification-client}: producers in trigger-service /
-- auth-service / agent-service / catalog-service emit via
-- {@code POST /api/internal/notifications/emit}. Each new producer maps to a
-- subject_type:
--
--   subject_type   |  emitted by                                    | category
--   ---------------+------------------------------------------------+-------------------------
--   WORKFLOW       | NotificationEmitter (P0/P1/P2a, in-process)    | RUN_FAILED, APPROVAL_PENDING
--   APPLICATION    | catalog billing (P7)                            | BRIDGE_LOW_CREDIT
--   AGENT_TASK     | agent-service AgentTaskService (P6)             | AGENT_TASK_ASSIGNED
--   CREDENTIAL     | auth-service OAuth2RefreshScheduler (P5)        | CRED_EXPIRED
--   TRIGGER        | trigger-service TriggerLifecycleManager (P4)    | WEBHOOK_TRIGGER_DISABLED
--
-- Forward-compatibility: subject_type is left as a free-form VARCHAR(20) and
-- the CHECK admits the documented allow-list. Future categories (P8+) need a
-- new migration that extends the IN clause - keeps the boundary explicit so
-- typos like "TRIGGERS" or "Workflow" fail at INSERT instead of silently
-- bucketing under the bell's deleted-workflow placeholder.
--
-- Existing rows: V172 backfill produced only WORKFLOW rows. P0 + P2a continue
-- to produce only WORKFLOW. So no row violates the relaxed CHECK.

ALTER TABLE orchestrator.notifications
    DROP CONSTRAINT IF EXISTS chk_notif_subject_type_v1;

ALTER TABLE orchestrator.notifications
    ADD CONSTRAINT chk_notif_subject_type_v1
        CHECK (subject_type IN (
            'WORKFLOW',
            'APPLICATION',
            'AGENT_TASK',
            'CREDENTIAL',
            'TRIGGER'
        ));

COMMENT ON CONSTRAINT chk_notif_subject_type_v1 ON orchestrator.notifications IS
    'V176 relaxed: admits cross-service subject types for P3+ rollout '
    '(APPLICATION, AGENT_TASK, CREDENTIAL, TRIGGER). New types require a '
    'follow-up migration to keep the boundary explicit.';
