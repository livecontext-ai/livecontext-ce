-- V260 - Cloud-Link Phase 1 onboarding (cloud side).
--
-- Creates the server-side mirror of the CE install registry on cloud:
--   - auth.ce_link            (one row per install, PK on install_id)
--   - auth.ce_link_heartbeat  (hot row separated from cold ce_link)
--   - auth.ce_link_audit      (append-only via BEFORE UPDATE/DELETE/TRUNCATE triggers)
--
-- See the project docs for the full design rationale.

-- ===========================================================================
-- 1) auth.ce_link - server-side install registry
-- ===========================================================================

CREATE TABLE IF NOT EXISTS auth.ce_link (
    install_id          UUID PRIMARY KEY,                                                    -- generated CE-side
    user_id             BIGINT NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    label               VARCHAR(128),
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                        CHECK (status IN ('ACTIVE','REVOKED')),
    scopes              VARCHAR(256) NOT NULL DEFAULT 'catalog,marketplace',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    revoked_at          TIMESTAMPTZ,
    revoked_by_user_id  BIGINT REFERENCES auth.users(id),
    revoke_reason       VARCHAR(32)
                        CHECK (revoke_reason IS NULL OR revoke_reason IN
                              ('USER','ADMIN','RESET_SIGNAL','SQUAT_RECOVERY','SYSTEM','DEACTIVATED_USER')),

    CONSTRAINT chk_ce_link_revoked_consistency CHECK (
        (status='ACTIVE'  AND revoked_at IS NULL     AND revoked_by_user_id IS NULL AND revoke_reason IS NULL)
        OR
        (status='REVOKED' AND revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_ce_link_user_active
    ON auth.ce_link (user_id)
    WHERE status = 'ACTIVE';

-- ===========================================================================
-- 2) auth.ce_link_heartbeat - hot table, HOT-update friendly
--    Separate from auth.ce_link so the per-request write doesn't bloat the
--    cold registry row.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS auth.ce_link_heartbeat (
    install_id                  UUID PRIMARY KEY REFERENCES auth.ce_link(install_id) ON DELETE CASCADE,
    last_seen_at                TIMESTAMPTZ NOT NULL,
    last_seen_ip_hash           VARCHAR(64) NOT NULL,                                        -- HMAC-SHA256(server_secret_key_v{N}, install_id || ':' || ip)
    key_version                 INTEGER NOT NULL DEFAULT 1,
    last_seen_ce_version        VARCHAR(32),
    last_audited_at             TIMESTAMPTZ,
    heartbeat_count_since_audit BIGINT NOT NULL DEFAULT 0                                    -- audit row at IP change OR every 24h OR every 1000th call
);

CREATE INDEX IF NOT EXISTS idx_ce_link_heartbeat_last_seen
    ON auth.ce_link_heartbeat (last_seen_at DESC);

-- ===========================================================================
-- 3) auth.ce_link_audit - append-only.
--    Triggers raise on any UPDATE/DELETE/TRUNCATE attempt; INSERT is allowed.
--    Rows are also shipped to Loki via auth.audit.AuditLogger (the second
--    authoritative trail in case of DBA-with-DDL tampering).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS auth.ce_link_audit (
    id                BIGSERIAL PRIMARY KEY,
    install_id        UUID NOT NULL,                                                          -- no FK: we want audit rows to survive ce_link deletion
    actor_user_id     BIGINT,                                                                 -- NULL = SYSTEM
    actor_role        VARCHAR(16) NOT NULL CHECK (actor_role IN ('OWNER','ADMIN','SYSTEM')),
    event             VARCHAR(32) NOT NULL
                      CHECK (event IN ('REGISTER','REVOKE','RESET','SCOPE_GRANT','HEARTBEAT','NETWORK_CHANGE','SUSPECTED_CROSS_USER_RESET')),
    scope_before      VARCHAR(256),
    scope_after       VARCHAR(256),
    ip_hash           VARCHAR(64),
    key_version       INTEGER NOT NULL DEFAULT 1,
    user_agent        VARCHAR(256),
    metadata          JSONB,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ce_link_audit_install_chrono
    ON auth.ce_link_audit (install_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_ce_link_audit_actor_chrono
    ON auth.ce_link_audit (actor_user_id, created_at DESC)
    WHERE actor_user_id IS NOT NULL;

-- Immutability triggers.
CREATE OR REPLACE FUNCTION auth.ce_link_audit_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION USING MESSAGE = 'auth.ce_link_audit is append-only (' || TG_OP || ' blocked)';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS ce_link_audit_no_update   ON auth.ce_link_audit;
DROP TRIGGER IF EXISTS ce_link_audit_no_delete   ON auth.ce_link_audit;
DROP TRIGGER IF EXISTS ce_link_audit_no_truncate ON auth.ce_link_audit;

CREATE TRIGGER ce_link_audit_no_update
    BEFORE UPDATE ON auth.ce_link_audit
    FOR EACH ROW EXECUTE FUNCTION auth.ce_link_audit_immutable();

CREATE TRIGGER ce_link_audit_no_delete
    BEFORE DELETE ON auth.ce_link_audit
    FOR EACH ROW EXECUTE FUNCTION auth.ce_link_audit_immutable();

CREATE TRIGGER ce_link_audit_no_truncate
    BEFORE TRUNCATE ON auth.ce_link_audit
    FOR EACH STATEMENT EXECUTE FUNCTION auth.ce_link_audit_immutable();
