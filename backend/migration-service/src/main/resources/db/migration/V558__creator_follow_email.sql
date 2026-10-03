-- Creator follows by email (follow-up to V551):
--   * FOLLOWING: "a creator you follow published a new app", emailed at once.
--   * AUDIENCE: "someone subscribed to you", in the daily summary.
-- Both are new notification topics a person can switch off, and the "someone subscribed"
-- bell row is about a person, hence the USER subject type.

ALTER TABLE orchestrator.notification_preferences
    DROP CONSTRAINT IF EXISTS chk_notif_pref_topic;

ALTER TABLE orchestrator.notification_preferences
    ADD CONSTRAINT chk_notif_pref_topic
        CHECK (topic IN ('FAILURES', 'CREDITS', 'ACCOUNT', 'TASKS', 'FOLLOWING', 'AUDIENCE')) NOT VALID;

ALTER TABLE orchestrator.notification_preferences
    VALIDATE CONSTRAINT chk_notif_pref_topic;

ALTER TABLE orchestrator.notifications
    DROP CONSTRAINT IF EXISTS chk_notif_subject_type_v1;

-- NOT VALID then VALIDATE, as V518, V528 and V551 did: every existing row already satisfies
-- the narrower V551 check, so the validation is a plain scan.
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
            'AGENT',
            'BILLING',
            'PUBLICATION',
            'USER'
        )) NOT VALID;

ALTER TABLE orchestrator.notifications
    VALIDATE CONSTRAINT chk_notif_subject_type_v1;

COMMENT ON CONSTRAINT chk_notif_subject_type_v1 ON orchestrator.notifications IS
    'V558 relaxed: admits USER, for a new subscriber of a creator.';
