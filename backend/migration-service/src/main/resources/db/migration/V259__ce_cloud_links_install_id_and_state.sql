-- V259 - Cloud-Link Phase 1 onboarding (CE side).
--
-- Extends publication.ce_cloud_links (V29) with the install identity layer
-- + DB-backed PKCE state needed by the new wizard onboarding flow.
-- NOTE the plaintext `cached_access_token` DROP is in V261 (PR7-co-shipped),
-- NOT here. V259 only ADDs new columns + indexes + the reset-retry queue
-- table. This avoids a "column does not exist" runtime error in the gap
-- between V259 apply and PR7 Java entity removal.
--
-- See the project docs for the full rationale.
--
-- Migration-service runs Flyway exclusively with application services
-- stopped (CLAUDE.md "migration-service is the ONLY service that runs Flyway").

-- NOTE: V196 already converted publication.ce_cloud_links.{token_expires_at,
-- linked_at, last_used_at} from TIMESTAMP to TIMESTAMPTZ (V196 lines 128-130).
-- V259 does NOT touch those columns - re-running ALTER COLUMN TYPE on an
-- already-TIMESTAMPTZ column with `AT TIME ZONE 'UTC'` would silently shift
-- values when the session timezone is not UTC (audit C1 r1 PR1).

-- ===========================================================================
-- 1) Extend publication.ce_cloud_links
-- ===========================================================================
--
-- install_id is added with DEFAULT gen_random_uuid() so Postgres populates
-- existing rows AND any INSERT from a (still-running) older publication-service
-- JAR that doesn't yet know about the column. After backfill we promote it
-- to NOT NULL UNIQUE. The DEFAULT remains so unupgraded JARs keep inserting
-- valid rows during the deploy gap (audit H1 r1 PR1 - backward compat).

ALTER TABLE publication.ce_cloud_links
    ADD COLUMN IF NOT EXISTS install_id                 UUID DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS pending_state_hash         VARCHAR(64),
    ADD COLUMN IF NOT EXISTS pending_state_expires_at   TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pending_state_authcode_enc VARCHAR(4096),
    ADD COLUMN IF NOT EXISTS registered_at              TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS label                      VARCHAR(128);

-- Defensive: if a prior partial-run left install_id NULL on some rows
-- (DEFAULT only applies when the column is added), this UPDATE is the
-- safety net. No-op when DEFAULT did its job.
UPDATE publication.ce_cloud_links
   SET install_id = gen_random_uuid()
 WHERE install_id IS NULL;

ALTER TABLE publication.ce_cloud_links
    ALTER COLUMN install_id SET NOT NULL;

-- Idempotent UNIQUE constraint per V169/V223 pattern (avoid re-run failure
-- if constraint was added in a prior partial migration).
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'uq_ce_cloud_links_install_id'
           AND conrelid = 'publication.ce_cloud_links'::regclass
    ) THEN
        ALTER TABLE publication.ce_cloud_links
            ADD CONSTRAINT uq_ce_cloud_links_install_id UNIQUE (install_id);
    END IF;
END $$;

-- Single-flight pending state per tenant: only one OAuth flow in progress
-- per tenant. UI surfaces "Resume or Cancel" when a 2nd flow is attempted.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ce_cloud_links_one_pending_state
    ON publication.ce_cloud_links (tenant_id)
    WHERE pending_state_hash IS NOT NULL;

-- ===========================================================================
-- 2) Reset retry queue (publication.ce_cloud_link_pending_delete)
--    Tracks failed cloud DELETE calls during /api/cloud-link/reset; retried
--    by CloudLinkResetRetryScheduler in publication-service (PR7).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS publication.ce_cloud_link_pending_delete (
    install_id        UUID PRIMARY KEY,
    tenant_id         BIGINT NOT NULL,
    refresh_token_enc VARCHAR(4096) NOT NULL,                                              -- AES-GCM, same key as ce_cloud_links.encrypted_refresh_token
    attempts          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ NOT NULL,
    last_error        VARCHAR(512),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    give_up_at        TIMESTAMPTZ NOT NULL                                                 -- created_at + 7 days; scheduler stops retrying past this
);

CREATE INDEX IF NOT EXISTS idx_ce_pending_delete_due
    ON publication.ce_cloud_link_pending_delete (next_attempt_at);
