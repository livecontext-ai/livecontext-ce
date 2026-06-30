-- Bridge CLI access control: admin-governed ACL for the shared CLI sessions
-- exposed by the local bridge (Claude Code, Codex, Gemini CLI, Mistral Vibe).
--
-- CLI bridges use a SHARED OS-level session on the bridge host (an admin's
-- Claude Pro / ChatGPT Plus account). Without gating, any CE user can
-- exhaust the subscription's rate limits and break it for everyone. Opt-in
-- by default: every bridge seeded as 'disabled' so an upgraded CE never
-- exposes bridge models until the admin explicitly approves.
--
-- Four access modes:
--   disabled    - no one (even admin) can dispatch through this bridge.
--                 Bridge models remain hidden from pickers.
--   admin_only  - only users with the ADMIN role.
--   allowlist   - users explicitly listed in bridge_access_allowlist.
--   all_users   - every user of the CE instance (usually paired with quota).

SET lock_timeout = '10s';
SET statement_timeout = '60s';

SET search_path TO auth;

CREATE TABLE IF NOT EXISTS bridge_access_policy (
    id                              BIGSERIAL PRIMARY KEY,
    bridge_provider                 VARCHAR(64)  NOT NULL UNIQUE,
    access_mode                     VARCHAR(16)  NOT NULL DEFAULT 'disabled',
    max_requests_per_user_per_day   INT,
    max_concurrent_per_user         INT          NOT NULL DEFAULT 2,
    updated_at                      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by                      VARCHAR(128),
    CONSTRAINT bridge_access_policy_mode_check
        CHECK (access_mode IN ('disabled', 'admin_only', 'allowlist', 'all_users')),
    CONSTRAINT bridge_access_policy_daily_quota_positive
        CHECK (max_requests_per_user_per_day IS NULL OR max_requests_per_user_per_day > 0),
    CONSTRAINT bridge_access_policy_concurrent_positive
        CHECK (max_concurrent_per_user > 0)
);

CREATE TABLE IF NOT EXISTS bridge_access_allowlist (
    policy_id   BIGINT       NOT NULL REFERENCES bridge_access_policy(id) ON DELETE CASCADE,
    user_id     VARCHAR(128) NOT NULL,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    granted_by  VARCHAR(128),
    PRIMARY KEY (policy_id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_bridge_access_allowlist_user
    ON bridge_access_allowlist(user_id);

-- Daily per-user usage counter (one row per (user, bridge, UTC date)).
-- Counter reset is logical - a new calendar day starts a fresh row.
CREATE TABLE IF NOT EXISTS bridge_usage_log (
    user_id           VARCHAR(128) NOT NULL,
    bridge_provider   VARCHAR(64)  NOT NULL,
    usage_date        DATE         NOT NULL DEFAULT (NOW() AT TIME ZONE 'UTC')::date,
    requests_count    INT          NOT NULL DEFAULT 0,
    last_request_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, bridge_provider, usage_date)
);
CREATE INDEX IF NOT EXISTS idx_bridge_usage_by_provider_date
    ON bridge_usage_log(bridge_provider, usage_date DESC);

-- Seed the 4 bridges advertised by BridgeAvailabilityFilter. Opt-in: all
-- start disabled. Existing CE installs must re-grant explicitly after upgrade.
INSERT INTO bridge_access_policy (bridge_provider, access_mode, updated_by)
VALUES ('claude-code', 'disabled', 'system:v118'),
       ('codex',        'disabled', 'system:v118'),
       ('gemini-cli',   'disabled', 'system:v118'),
       ('mistral-vibe', 'disabled', 'system:v118')
ON CONFLICT (bridge_provider) DO NOTHING;

COMMENT ON TABLE bridge_access_policy IS
    'Per-CLI-bridge access policy. Opt-in by default (disabled) to prevent abuse of the admin''s shared Claude Pro / ChatGPT Plus subscription.';
COMMENT ON TABLE bridge_access_allowlist IS
    'Explicit user grants when policy.access_mode = allowlist.';
COMMENT ON TABLE bridge_usage_log IS
    'Daily per-user request count for quota enforcement. One row per (user, bridge, UTC date).';
