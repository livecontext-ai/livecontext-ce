-- PR-4b MVP: organization-scoped audit log.
--
-- Persists every sensitive ORG_* action (member invited / removed / left /
-- role changed / ownership transferred / org deleted) so OWNER + ADMIN can
-- review who did what and when. Critical for SOC2 / GDPR Art.30 baseline.
--
-- This MVP intentionally OMITS:
--   - HMAC chain per row (PR-4b.1)
--   - ShedLock retention purge cron (PR-4b.1)
--   - WORM mirror to Loki / S3 Object Lock (PR-4b.1)
--   - Append-only DB role separation (PR-4b.1)
-- so the basic feature can ship and be observed in prod before we add the
-- tamper-evident layer.

CREATE TABLE auth.organization_audit_event (
    id              BIGSERIAL    PRIMARY KEY,
    org_id          UUID         NOT NULL,
    actor_user_id   BIGINT       NULL,           -- nullable for system-triggered events
    event_type      VARCHAR(64)  NOT NULL,
    event_data      JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_audit_event_org
        FOREIGN KEY (org_id)
        REFERENCES auth.organization(id)
        ON DELETE CASCADE
);

-- Hot path: OWNER/ADMIN listing audit log for one org, newest first.
CREATE INDEX idx_audit_event_org_created
    ON auth.organization_audit_event(org_id, created_at DESC);

-- For per-type analytics and security forensics (e.g. all ORG_OWNERSHIP_TRANSFERRED).
CREATE INDEX idx_audit_event_type_created
    ON auth.organization_audit_event(event_type, created_at DESC);

COMMENT ON TABLE auth.organization_audit_event IS
    'PR-4b MVP - ORG_* event log. HMAC chain + retention purge land in PR-4b.1.';
COMMENT ON COLUMN auth.organization_audit_event.event_type IS
    'Canonical event key. Current vocabulary: ORG_MEMBER_INVITED, '
    'ORG_INVITE_ACCEPTED, ORG_INVITE_CANCELLED, ORG_MEMBER_REMOVED, '
    'ORG_MEMBER_LEFT, ORG_ROLE_CHANGED, ORG_OWNERSHIP_TRANSFERRED, ORG_DELETED.';
