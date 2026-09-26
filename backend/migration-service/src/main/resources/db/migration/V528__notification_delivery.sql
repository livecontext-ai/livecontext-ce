-- ============================================================================
-- V528: notifications leave the app (email and the workspace chat channel).
--
-- Until now every notification was a bell row and nothing else, so a workflow
-- that failed at 3am was invisible until someone opened the app. This adds the
-- delivery side, and the four tables exist to keep it from turning into spam:
--
--   notification_preferences  per person AND per workspace: for each topic,
--                             OFF / EMAIL / CHANNEL / BOTH. No row = the
--                             topic's default, so nothing is backfilled.
--   notification_incidents    one OPEN row per (person, workspace, workflow)
--                             while that workflow keeps failing. The first
--                             failure opens it and is sent; every later one
--                             only increments failure_count. A success closes
--                             it and sends "recovered". A reminder goes out at
--                             most once a day while it stays open. A failure
--                             within 24 h of an announced recovery reopens the
--                             same row and sends ONE "failing again".
--   notification_deliveries   what was actually sent, where, and how it went.
--                             Also the source of the daily cap (a DEFERRED row
--                             lands in the next digest) and of the digest
--                             cursor (the last SENT DIGEST row).
--   auth.credit_alert_sent    one row per subscription, credit cycle and level
--                             (LOW, EXHAUSTED), so the credit scanner alerts
--                             once per threshold per cycle.
--
-- Plus the BILLING subject type for the two credit categories, and the plan
-- gate that keeps email for the paid plans on shared cloud (credit alerts are
-- exempt in code: they are the one email a Free account must get).
-- ============================================================================

CREATE TABLE IF NOT EXISTS orchestrator.notification_preferences (
    tenant_id        VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255) NOT NULL,
    topic            VARCHAR(32)  NOT NULL,
    delivery         VARCHAR(16)  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, organization_id, topic),
    CONSTRAINT chk_notif_pref_topic CHECK (topic IN ('FAILURES', 'CREDITS', 'ACCOUNT', 'TASKS')),
    CONSTRAINT chk_notif_pref_delivery CHECK (delivery IN ('OFF', 'EMAIL', 'CHANNEL', 'BOTH'))
);

CREATE INDEX IF NOT EXISTS idx_notif_pref_org
    ON orchestrator.notification_preferences (organization_id);

CREATE TABLE IF NOT EXISTS orchestrator.notification_incidents (
    id                      BIGSERIAL    PRIMARY KEY,
    tenant_id               VARCHAR(255) NOT NULL,
    organization_id         VARCHAR(255) NOT NULL,
    workflow_id             UUID         NOT NULL,
    opened_at               TIMESTAMPTZ  NOT NULL,
    last_failure_at         TIMESTAMPTZ  NOT NULL,
    failure_count           INTEGER      NOT NULL DEFAULT 1,
    last_notified_at        TIMESTAMPTZ  NOT NULL,
    notified_failure_count  INTEGER      NOT NULL DEFAULT 1,
    resolved_at             TIMESTAMPTZ,
    -- When "recovered" was last announced. Within 24 h of it a failure REOPENS the incident
    -- (one "failing again", see reopen_announced_at), the recovery after that is announced, and
    -- every later flip is silent: at most failed, recovered, failing again, recovered.
    recovered_notified_at   TIMESTAMPTZ,
    -- When "failing again" was last announced: once per announced recovery, so a workflow that
    -- recovers and breaks again corrects the "recovered" message exactly once.
    reopen_announced_at     TIMESTAMPTZ
);

-- At most ONE open incident per person, workspace and workflow. This index is
-- what makes "only the first failure is sent" hold across replicas: the losing
-- INSERT hits ON CONFLICT and increments instead.
CREATE UNIQUE INDEX IF NOT EXISTS uq_notif_incident_open
    ON orchestrator.notification_incidents (tenant_id, organization_id, workflow_id)
    WHERE resolved_at IS NULL;

-- Every successful production epoch asks "is there an open incident for this
-- workflow?", so that lookup must stay an index probe.
CREATE INDEX IF NOT EXISTS idx_notif_incident_open_workflow
    ON orchestrator.notification_incidents (workflow_id)
    WHERE resolved_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_notif_incident_org
    ON orchestrator.notification_incidents (organization_id);

-- Seed, once: a production workflow that is STILL failing when delivery ships (failed in the
-- last 24 h AND its production run's latest outcome is a failure) gets an open incident now, as
-- already reported. One that failed and has since recovered is NOT seeded: its first success
-- would otherwise announce "recovered" for a failure nobody was told about. Its next failures JOIN it and the daily reminder reports
-- it, instead of the first deploy sending one alert per already-broken workflow at once. Done
-- here rather than guessed at runtime ("was there an earlier failure row?"), because at runtime
-- that question cannot tell "failing before delivery existed" from "the previous failure, whose
-- delivery has not run yet", and answering it wrong silently eats a real first alert.
INSERT INTO orchestrator.notification_incidents
    (tenant_id, organization_id, workflow_id, opened_at, last_failure_at, failure_count,
     last_notified_at, notified_failure_count)
SELECT n.tenant_id, n.organization_id, n.subject_id, min(n.occurred_at), max(n.occurred_at), count(*),
       max(n.occurred_at), count(*)
  FROM orchestrator.notifications n
  JOIN orchestrator.workflows w ON w.id = n.subject_id
  JOIN orchestrator.workflow_runs r ON r.id = w.production_run_id
 WHERE n.category = 'RUN_FAILED'
   AND n.subject_type = 'WORKFLOW'
   AND n.subject_id IS NOT NULL
   AND n.organization_id IS NOT NULL
   AND n.occurred_at > now() - interval '24 hours'
   -- The latest production outcome: a reusable-trigger run records it per cycle in metadata,
   -- a terminal run in its status.
   AND lower(COALESCE(r.metadata ->> 'lastCycleResult', r.status)) IN ('failed', 'cancelled', 'timeout')
 GROUP BY n.tenant_id, n.organization_id, n.subject_id
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS orchestrator.notification_deliveries (
    id               BIGSERIAL    PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255) NOT NULL,
    notification_id  BIGINT,
    kind             VARCHAR(16)  NOT NULL,
    medium           VARCHAR(16)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    detail           TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_notif_delivery_kind CHECK (kind IN ('ALERT', 'REMINDER', 'RECOVERED', 'DIGEST')),
    CONSTRAINT chk_notif_delivery_medium CHECK (medium IN ('EMAIL', 'CHANNEL')),
    CONSTRAINT chk_notif_delivery_status CHECK (status IN ('SENT', 'FAILED', 'DEFERRED'))
);

CREATE INDEX IF NOT EXISTS idx_notif_delivery_recipient
    ON orchestrator.notification_deliveries (tenant_id, organization_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notif_delivery_org
    ON orchestrator.notification_deliveries (organization_id);

-- The two credit categories are about the account's wallet, not about a
-- workflow, a credential or a task.
ALTER TABLE orchestrator.notifications
    DROP CONSTRAINT IF EXISTS chk_notif_subject_type_v1;

-- NOT VALID then VALIDATE, as V518 did. Flyway runs this file in ONE transaction, so the
-- DROP CONSTRAINT's lock is held until commit anyway: this does not shorten the lock, it only
-- keeps the validation a plain scan (every row already satisfies the narrower V518 check).
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
            'BILLING'
        )) NOT VALID;

ALTER TABLE orchestrator.notifications
    VALIDATE CONSTRAINT chk_notif_subject_type_v1;

COMMENT ON CONSTRAINT chk_notif_subject_type_v1 ON orchestrator.notifications IS
    'V528 relaxed: admits BILLING, for credits running low or exhausted.';

CREATE TABLE IF NOT EXISTS auth.credit_alert_sent (
    subscription_id  BIGINT       NOT NULL REFERENCES auth.subscription (id) ON DELETE CASCADE,
    cycle_key        VARCHAR(64)  NOT NULL,
    level            VARCHAR(16)  NOT NULL,
    sent_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (subscription_id, cycle_key, level),
    CONSTRAINT chk_credit_alert_level CHECK (level IN ('LOW', 'EXHAUSTED'))
);

-- Email alerts are a paid capability on shared cloud. Setting this row back to
-- FREE in the admin screen deletes it and gives email to every plan.
INSERT INTO auth.plan_feature_requirement (feature_key, min_plan, label, updated_by)
VALUES ('feature:notification_email', 'STARTER',
        'Email alerts (failures, account, tasks); credit alerts are always emailed', 'seed:V528')
ON CONFLICT (feature_key) DO NOTHING;
