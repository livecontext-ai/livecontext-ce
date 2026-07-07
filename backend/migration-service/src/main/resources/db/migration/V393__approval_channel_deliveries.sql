-- V393: Delegated-approval delivery ledger (external-channel approval resolution).
--
-- A User Approval node can now DELEGATE the approve/reject decision to an external
-- channel (v1: Telegram inline buttons). One row per (signal_wait, channel) outbound
-- notification. The row carries:
--   * callback_token: the opaque 128-bit capability embedded in the channel's button
--     callback (Telegram callback_data is capped at 64 bytes, so only this token +
--     a verdict flag travel over the wire; everything else resolves from this row).
--   * denormalized run_id/node_id/item_id/epoch/tenant_id so the inbound click
--     handler can call the approval-resolution primitive with a single indexed lookup.
--   * chat_id/message_id/message_text so post-resolution edits (append verdict,
--     strip buttons) work from any replica without re-reading the signal.
--
-- Lifecycle: PENDING (row inserted, send in flight) -> SENT (message delivered,
-- message_id captured) | FAILED (send error, error column set) -> RESOLVED (approval
-- decided, message edited) | CANCELLED (signal cancelled, message edited).
--
-- FK ON DELETE CASCADE: purging a signal wait purges its deliveries.
-- UNIQUE (signal_wait_id, channel) is the idempotency guard for event replays
-- (same INSERT ... ON CONFLICT DO NOTHING pattern as orchestrator.notifications).

CREATE TABLE IF NOT EXISTS orchestrator.approval_channel_deliveries (
    id               BIGSERIAL PRIMARY KEY,
    signal_wait_id   BIGINT       NOT NULL
        REFERENCES orchestrator.workflow_signal_waits(id) ON DELETE CASCADE,
    channel          VARCHAR(30)  NOT NULL,
    callback_token   VARCHAR(64)  NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    tenant_id        VARCHAR(255) NOT NULL,
    run_id           VARCHAR(100) NOT NULL,
    node_id          VARCHAR(200) NOT NULL,
    item_id          VARCHAR(50)  NOT NULL DEFAULT '0',
    epoch            INT          NOT NULL DEFAULT 0,
    credential_id    BIGINT,
    chat_id          VARCHAR(100),
    message_id       VARCHAR(50),
    message_text     TEXT,
    allowed_user_ids JSONB,
    error            TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    sent_at          TIMESTAMPTZ,
    resolved_at      TIMESTAMPTZ,
    CONSTRAINT uq_acd_token UNIQUE (callback_token),
    CONSTRAINT uq_acd_signal_channel UNIQUE (signal_wait_id, channel),
    CONSTRAINT chk_acd_status_v1
        CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'RESOLVED', 'CANCELLED'))
);

COMMENT ON TABLE orchestrator.approval_channel_deliveries IS
    'Delegated-approval delivery ledger: one row per (signal_wait, channel) external '
    'notification (v1: Telegram inline-button message). callback_token is the opaque '
    'capability the channel button click presents to resolve the approval.';
COMMENT ON COLUMN orchestrator.approval_channel_deliveries.callback_token IS
    'Base64url 128-bit SecureRandom token. Single-use capability: resolving the '
    'delivery flips status, and the signal claim-before-process makes double clicks no-ops.';
COMMENT ON COLUMN orchestrator.approval_channel_deliveries.run_id IS
    'PUBLIC run id (matches workflow_signal_waits.run_id), not the UUID primary key.';
COMMENT ON COLUMN orchestrator.approval_channel_deliveries.allowed_user_ids IS
    'Optional JSON array of channel user ids (Telegram from.id as strings) allowed to '
    'decide. Empty/null: anyone in the chat may decide.';

CREATE INDEX IF NOT EXISTS idx_acd_signal_wait
    ON orchestrator.approval_channel_deliveries (signal_wait_id);
