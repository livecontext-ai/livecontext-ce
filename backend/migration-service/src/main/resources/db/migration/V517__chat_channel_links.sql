-- ============================================================================
-- V517: Linked chat channels - one place that answers "where do I reach this
-- person outside the app".
--
-- WHY THIS EXISTS. The delegated-approval feature (V393, approval_channel_
-- deliveries) already pushes a pending approval to Telegram with inline
-- approve/reject buttons, but it has nowhere to READ the destination from: the
-- workflow approval node carries its own chatId typed by hand into every node,
-- and anything outside a workflow (an agent asking to run a sensitive action
-- from a scheduled task) has no destination at all. These two tables are that
-- missing address book, resolved once per workspace and reused by every
-- surface that needs to reach a person who is not watching the screen.
--
-- WHY TWO TABLES, not one. A Telegram bot has exactly ONE webhook URL, so
-- "is the webhook set" is a property of the BOT (the credential), while "which
-- chat do I write to" is a property of the DESTINATION, and one bot serves
-- several destinations (a private chat and a team group). Folding both into a
-- single row makes the webhook columns lie the moment a second destination is
-- added on the same bot: one row would say "set" and its sibling "not set",
-- with both statements true of the same webhook. The split keeps every column
-- true of exactly one thing.
--
-- SCOPING. organization_id is the isolation boundary (post-V263 every user-
-- scoped table is NOT NULL on it, and a personal workspace is just the org
-- with is_personal = true). tenant_id is the connecting user, kept for
-- attribution only, never for isolation.
--
-- NO CHECK CONSTRAINT ON `channel`, deliberately. ApprovalChannelNotifier-
-- Registry is open by construction - its class comment says adding a channel
-- is adding one bean with a new channelId - so a CHECK here would mean a
-- migration is needed to ship a bean, which is exactly the coupling the
-- registry was built to avoid. The value is validated in Java against the
-- registry, which is the only place that knows what can actually deliver.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- The bot: one connected credential per (workspace, channel), plus the state
-- that belongs to the bot itself rather than to any one destination.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orchestrator.chat_channel_bots (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255) NOT NULL,

    channel          VARCHAR(30)  NOT NULL,

    -- The credential that OWNS the bot token. NOT NULL on purpose, where the
    -- approval node's delegation block allows a blank "use my default": a link
    -- must know which bot sends, because the webhook it points at is that
    -- bot's and nobody else's. Resolution of "the default one" happens once,
    -- at connect time, and the resolved id is what is stored.
    credential_id    BIGINT       NOT NULL,

    -- Identity as the provider reports it (Telegram get_me), so the UI can say
    -- "@my_ops_bot" instead of a credential id, and so a token swapped for a
    -- different bot behind the same credential is visible rather than silent.
    bot_identity     VARCHAR(120),
    bot_username     VARCHAR(120),

    -- The webhook this bot is pointed at, as WE set it. Kept verbatim so a
    -- later base-url change is detectable: a row whose webhook_url no longer
    -- matches the current public URL needs re-pointing, and that is a question
    -- only the stored value can answer.
    webhook_url      TEXT,
    webhook_set_at   TIMESTAMPTZ,

    -- Last successful round trip with the provider (get_me). Distinct from
    -- webhook_set_at: a bot can answer perfectly while its webhook points
    -- somewhere else entirely, which is precisely the failure that makes
    -- buttons do nothing with no error anywhere.
    verified_at      TIMESTAMPTZ,
    last_error       TEXT,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_channel_bots_scope
    ON orchestrator.chat_channel_bots (organization_id, channel, credential_id);

-- ----------------------------------------------------------------------------
-- The destination: where a message actually lands.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orchestrator.chat_channel_links (
    id               UUID         PRIMARY KEY,
    tenant_id        VARCHAR(255) NOT NULL,
    organization_id  VARCHAR(255) NOT NULL,

    bot_id           UUID         NOT NULL,

    -- Provider-side chat identifier, as text: Telegram's is a signed 64-bit
    -- integer for a private chat but a channel is addressable as "@name", and
    -- other channels use opaque strings. Text holds every shape and is what
    -- the delegation block already carries.
    chat_id          VARCHAR(120) NOT NULL,
    chat_title       VARCHAR(255),
    chat_type        VARCHAR(40),

    -- Optional allow-list of provider user ids permitted to press a button,
    -- same semantics as the approval node's allowedUserIds. Empty = anyone in
    -- the chat decides, which is the right default for a private chat and a
    -- deliberate choice for a group.
    allowed_user_ids JSONB        NOT NULL DEFAULT '[]'::jsonb,

    -- The destination used when a caller asks for "this workspace's channel"
    -- without naming one. At most one per workspace, ACROSS channels rather
    -- than per channel: the callers that need it (an agent asking permission
    -- from an unattended run) want one answer, not one answer per channel.
    is_default       BOOLEAN      NOT NULL DEFAULT FALSE,

    active           BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Last time a message was successfully delivered HERE. The bot answering
    -- get_me proves the token; only a delivered message proves the chat id,
    -- and those fail separately (a valid bot that was never started by the
    -- user cannot write to them - Telegram refuses with 403).
    verified_at      TIMESTAMPTZ,
    last_error       TEXT,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_chat_channel_links_bot
        FOREIGN KEY (bot_id) REFERENCES orchestrator.chat_channel_bots (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_channel_links_bot_chat
    ON orchestrator.chat_channel_links (bot_id, chat_id);

-- One default per workspace. Partial, so the constraint says nothing at all
-- about the non-default rows instead of forcing them to differ from each other.
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_channel_links_default
    ON orchestrator.chat_channel_links (organization_id)
    WHERE is_default;

-- The read every delivery does: "the active destinations of this workspace,
-- default first". Small tables (a workspace has a handful), so this exists to
-- keep the default lookup a single index hit rather than for volume.
CREATE INDEX IF NOT EXISTS idx_chat_channel_links_scope
    ON orchestrator.chat_channel_links (organization_id, active, is_default);

-- ----------------------------------------------------------------------------
-- A permission an agent asked for, delivered to a destination.
--
-- WHY IT IS NOT approval_channel_deliveries (V393). That table's key is a
-- workflow signal (signal_wait_id NOT NULL) and everything on it is denormalised
-- run state: run, node, epoch, item. An agent asking to run a sensitive action
-- has none of those. It has a conversation and a parked tool call, and it is
-- answered by releasing that call rather than by resolving a signal. Same
-- transport, different subject.
--
-- THE PARTIAL UNIQUE INDEX IS THE ANTI-PILE-UP RULE, and it is here rather than
-- in Java on purpose. A scheduled agent that asks every night and is answered on
-- the fourth day would otherwise have sent four identical messages, and the
-- person approving one of them could not tell which run it belongs to. A
-- check-then-insert in the service would race two replicas into exactly that.
-- Only LIVE rows take part, so the same request may legitimately be asked again
-- once the previous one has been decided or has expired.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS orchestrator.chat_authorization_requests (
    id                   UUID         PRIMARY KEY,
    tenant_id            VARCHAR(255) NOT NULL,
    organization_id      VARCHAR(255) NOT NULL,

    link_id              UUID         NOT NULL,
    channel              VARCHAR(30)  NOT NULL,
    credential_id        BIGINT       NOT NULL,
    chat_id              VARCHAR(120) NOT NULL,

    -- The sole capability carried by the button, and the only thing that comes
    -- back from the provider: 128-bit, unguessable, scoped to this one request.
    callback_token       VARCHAR(64)  NOT NULL,

    -- What answering it releases: the parked tool call, addressed exactly as the
    -- in-app card addresses it.
    conversation_id      VARCHAR(100) NOT NULL,
    gate_key             VARCHAR(200) NOT NULL,
    rule                 VARCHAR(120) NOT NULL,
    agent_id             UUID,
    agent_name           VARCHAR(255),

    -- Identifies "the same request asked again": the rule plus a digest of the
    -- call's own arguments. Two different publishes are two requests; the same
    -- publish retried by tomorrow's run is one.
    fingerprint          VARCHAR(80)  NOT NULL,

    message_id           VARCHAR(40),

    -- The body AS SENT, so the closing edit can keep it instead of rebuilding a
    -- shorter one. Without this the edit after a press replaced the message with
    -- the agent name and the rule alone, deleting the description of WHAT was
    -- approved from the only audit trail a phone user has. Stored already capped
    -- to what the connector will accept, so appending the verdict line cannot
    -- push the edit over the provider's limit and truncate the verdict itself.
    message_text         TEXT,

    status               VARCHAR(20)  NOT NULL,
    decision             VARCHAR(20),
    decided_by           VARCHAR(120),
    decided_at           TIMESTAMPTZ,
    error                TEXT,

    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMPTZ  NOT NULL,

    -- No PENDING and no FAILED: a row exists only once a message really reached
    -- the chat. A failed send writes nothing, because a row is what the duplicate
    -- rule reads and one left by an undelivered send would block the next ask.
    CONSTRAINT ck_chat_auth_requests_status
        CHECK (status IN ('SENT', 'RESOLVED', 'EXPIRED')),
    CONSTRAINT fk_chat_auth_requests_link
        FOREIGN KEY (link_id) REFERENCES orchestrator.chat_channel_links (id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_auth_requests_token
    ON orchestrator.chat_authorization_requests (callback_token);

-- One live request per conversation per distinct ask. See the block comment.
CREATE UNIQUE INDEX IF NOT EXISTS uq_chat_auth_requests_live
    ON orchestrator.chat_authorization_requests (conversation_id, fingerprint)
    WHERE status = 'SENT';

-- The sweep that expires them, and the "what is still pending here" read.
CREATE INDEX IF NOT EXISTS idx_chat_auth_requests_live
    ON orchestrator.chat_authorization_requests (status, expires_at);

COMMENT ON TABLE orchestrator.chat_authorization_requests IS
    'A permission an agent asked for from an unattended run, delivered to a chat destination. Answering it releases the parked tool call in its conversation.';
COMMENT ON COLUMN orchestrator.chat_authorization_requests.fingerprint IS
    'Rule + digest of the call arguments. With the partial unique index on live rows, this is what stops a nightly agent sending the same question every night.';
COMMENT ON COLUMN orchestrator.chat_authorization_requests.gate_key IS
    'The parked tool call this answer releases - the same id the in-app authorization card uses.';

COMMENT ON TABLE orchestrator.chat_channel_bots IS
    'A connected outbound chat bot (v1: Telegram) per workspace + credential. Holds what belongs to the BOT: its provider identity and the single webhook URL it is pointed at.';
COMMENT ON TABLE orchestrator.chat_channel_links IS
    'A destination reachable through a connected bot. Resolved by any surface that must reach a person who is not watching the app: delegated workflow approvals and agent tool-authorization requests.';
COMMENT ON COLUMN orchestrator.chat_channel_bots.webhook_url IS
    'The webhook URL as we set it. A value that no longer matches the current public base URL means inbound button clicks are landing nowhere and the bot needs re-pointing.';
COMMENT ON COLUMN orchestrator.chat_channel_links.verified_at IS
    'Last message successfully delivered to THIS chat. The bot verifying (get_me) proves the token, not the destination: a bot the user never started cannot write to them.';
COMMENT ON COLUMN orchestrator.chat_channel_links.tenant_id IS
    'Connecting user. Attribution only - isolation is organization_id.';
