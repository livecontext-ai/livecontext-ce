-- V232: Allow CE organization invitations in the notification bell.
--
-- Auth-service emits ORG_INVITATION_PENDING in CE embedded-auth mode instead
-- of sending SMTP mail. The row is personal-scope (tenant_id = invitee user id,
-- organization_id NULL) because the invitee is not yet a member of the target
-- organization when the notification is created.

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
            'ORG_INVITATION'
        ));

COMMENT ON CONSTRAINT chk_notif_subject_type_v1 ON orchestrator.notifications IS
    'V232 relaxed: admits ORG_INVITATION for CE embedded-auth team invites.';
