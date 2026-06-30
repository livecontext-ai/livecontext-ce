-- V137: Unified trigger state model (PR2 of the unified trigger redesign)
--
-- Adds the new state machine on the 5 trigger config tables. The boolean flags
-- (enabled/is_active) remain in place for PR2 - they are kept in sync with the new
-- `state` column via triggers + application-side writes - and will be dropped in PR5
-- once all dispatch and admin paths read `state` exclusively.
--
-- Design ref: the project docs (round 7, audit 9.22/10)
--
-- New columns per trigger config table:
--   state                  VARCHAR(20)  ENUM-like {ACTIVE, SUSPENDED_NO_RUN,
--                                                  SUSPENDED_UNPINNED, ARCHIVED}
--   last_disabled_reason   VARCHAR(40)  free-text + a small enum (USER, EXPIRED,
--                                                  MAX_EXEC, PIN_REMOVED, ...)
--   last_disabled_at       TIMESTAMPTZ
--
-- Round-7 audit consensus: auto_disable_enabled flag is NOT added (dispatch cannot
-- mutate state by design, see the project docs).
-- pin_armed_at column is NOT added (zero readers; pin time captured in metric + log).

-- =============================================================================
-- 1. scheduled_executions
-- =============================================================================
ALTER TABLE "trigger".scheduled_executions
    ADD COLUMN IF NOT EXISTS state                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS last_disabled_reason   VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS last_disabled_at       TIMESTAMPTZ NULL;

-- Backfill: rows with enabled=false get state=ARCHIVED with reason=LEGACY_DISABLED.
-- Rationale: PR1 already removed dispatcher-side auto-disable, so any row still flagged
-- enabled=false post-PR1 was disabled by user/admin/legacy logic. Marking ARCHIVED
-- preserves the disabled state in the new model without losing the user intent.
UPDATE "trigger".scheduled_executions
   SET state                = 'ARCHIVED',
       last_disabled_reason = 'LEGACY_DISABLED',
       last_disabled_at     = COALESCE(updated_at, now())
 WHERE enabled = FALSE
   AND state   = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_scheduled_executions_state
    ON "trigger".scheduled_executions (state)
 WHERE state <> 'ARCHIVED';

-- =============================================================================
-- 2. standalone_webhooks
-- =============================================================================
ALTER TABLE "trigger".standalone_webhooks
    ADD COLUMN IF NOT EXISTS state                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS last_disabled_reason   VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS last_disabled_at       TIMESTAMPTZ NULL;

UPDATE "trigger".standalone_webhooks
   SET state                = 'ARCHIVED',
       last_disabled_reason = 'LEGACY_DISABLED',
       last_disabled_at     = COALESCE(updated_at, now())
 WHERE is_active = FALSE
   AND state     = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_standalone_webhooks_state
    ON "trigger".standalone_webhooks (state)
 WHERE state <> 'ARCHIVED';

-- =============================================================================
-- 3. standalone_chat_endpoints
-- =============================================================================
ALTER TABLE "trigger".standalone_chat_endpoints
    ADD COLUMN IF NOT EXISTS state                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS last_disabled_reason   VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS last_disabled_at       TIMESTAMPTZ NULL;

UPDATE "trigger".standalone_chat_endpoints
   SET state                = 'ARCHIVED',
       last_disabled_reason = 'LEGACY_DISABLED',
       last_disabled_at     = COALESCE(updated_at, now())
 WHERE is_active = FALSE
   AND state     = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_standalone_chat_endpoints_state
    ON "trigger".standalone_chat_endpoints (state)
 WHERE state <> 'ARCHIVED';

-- =============================================================================
-- 4. standalone_form_endpoints
-- =============================================================================
ALTER TABLE "trigger".standalone_form_endpoints
    ADD COLUMN IF NOT EXISTS state                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS last_disabled_reason   VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS last_disabled_at       TIMESTAMPTZ NULL;

UPDATE "trigger".standalone_form_endpoints
   SET state                = 'ARCHIVED',
       last_disabled_reason = 'LEGACY_DISABLED',
       last_disabled_at     = COALESCE(updated_at, now())
 WHERE is_active = FALSE
   AND state     = 'ACTIVE';

CREATE INDEX IF NOT EXISTS idx_standalone_form_endpoints_state
    ON "trigger".standalone_form_endpoints (state)
 WHERE state <> 'ARCHIVED';

-- =============================================================================
-- 5. webhook_tokens - no enabled/is_active column today, only state added
-- =============================================================================
ALTER TABLE "trigger".webhook_tokens
    ADD COLUMN IF NOT EXISTS state                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS last_disabled_reason   VARCHAR(40) NULL,
    ADD COLUMN IF NOT EXISTS last_disabled_at       TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_webhook_tokens_state
    ON "trigger".webhook_tokens (state)
 WHERE state <> 'ARCHIVED';

-- =============================================================================
-- 6. CHECK constraints - enforce the enum values
-- =============================================================================
ALTER TABLE "trigger".scheduled_executions
    DROP CONSTRAINT IF EXISTS scheduled_executions_state_check;
ALTER TABLE "trigger".scheduled_executions
    ADD CONSTRAINT scheduled_executions_state_check
    CHECK (state IN ('ACTIVE', 'SUSPENDED_NO_RUN', 'SUSPENDED_UNPINNED', 'ARCHIVED'));

ALTER TABLE "trigger".standalone_webhooks
    DROP CONSTRAINT IF EXISTS standalone_webhooks_state_check;
ALTER TABLE "trigger".standalone_webhooks
    ADD CONSTRAINT standalone_webhooks_state_check
    CHECK (state IN ('ACTIVE', 'SUSPENDED_NO_RUN', 'SUSPENDED_UNPINNED', 'ARCHIVED'));

ALTER TABLE "trigger".standalone_chat_endpoints
    DROP CONSTRAINT IF EXISTS standalone_chat_endpoints_state_check;
ALTER TABLE "trigger".standalone_chat_endpoints
    ADD CONSTRAINT standalone_chat_endpoints_state_check
    CHECK (state IN ('ACTIVE', 'SUSPENDED_NO_RUN', 'SUSPENDED_UNPINNED', 'ARCHIVED'));

ALTER TABLE "trigger".standalone_form_endpoints
    DROP CONSTRAINT IF EXISTS standalone_form_endpoints_state_check;
ALTER TABLE "trigger".standalone_form_endpoints
    ADD CONSTRAINT standalone_form_endpoints_state_check
    CHECK (state IN ('ACTIVE', 'SUSPENDED_NO_RUN', 'SUSPENDED_UNPINNED', 'ARCHIVED'));

ALTER TABLE "trigger".webhook_tokens
    DROP CONSTRAINT IF EXISTS webhook_tokens_state_check;
ALTER TABLE "trigger".webhook_tokens
    ADD CONSTRAINT webhook_tokens_state_check
    CHECK (state IN ('ACTIVE', 'SUSPENDED_NO_RUN', 'SUSPENDED_UNPINNED', 'ARCHIVED'));
