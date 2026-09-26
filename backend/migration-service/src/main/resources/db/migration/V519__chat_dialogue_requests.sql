-- ============================================================================
-- V519: the request row carries a question, not only an approval.
--
-- The table was built for one message type, "may I do this", with two buttons.
-- The same row now carries a question the agent puts to the person when nobody
-- is watching the run: same destination, same deadline, same claim, same sweep,
-- same close. Additive only. Nothing existing changes meaning, and an APPROVAL
-- row written before this migration reads identically after it.
--
-- The table keeps its name. Renaming it would rewrite every reference for no
-- behaviour, and a rename is the kind of change that looks free until a replica
-- is running the previous image.
-- ============================================================================

ALTER TABLE orchestrator.chat_authorization_requests
    -- Which message type this row is. Defaulted so every existing row is an
    -- APPROVAL without a backfill, which is what they all are.
    ADD COLUMN IF NOT EXISTS kind      VARCHAR(16) NOT NULL DEFAULT 'APPROVAL',
    -- One ask_user call asks several questions and gets ONE answer envelope, so
    -- its rows have to be findable together. The tool call id groups them.
    ADD COLUMN IF NOT EXISTS group_key VARCHAR(64),
    -- The question as it was SENT, for the same reason message_text exists for an
    -- approval: what the person was shown is what the record has to say they were
    -- shown, and rebuilding it later rewords it.
    ADD COLUMN IF NOT EXISTS payload   JSONB,
    -- Draft selections while a multi-select is being toggled, then the answer.
    -- In the row rather than in memory because the next press can land on the
    -- other replica, and a toggle nobody remembers is a checkbox that will not
    -- stay checked.
    ADD COLUMN IF NOT EXISTS answer    JSONB;

-- A closed set, so an unreadable kind cannot be stored and then met by a switch
-- that has no branch for it.
ALTER TABLE orchestrator.chat_authorization_requests
    DROP CONSTRAINT IF EXISTS ck_chat_auth_requests_kind;
ALTER TABLE orchestrator.chat_authorization_requests
    ADD CONSTRAINT ck_chat_auth_requests_kind
        CHECK (kind IN ('APPROVAL', 'CHOICE', 'TEXT'));

-- A free-text answer arrives as a Telegram reply, which names the message it
-- replies to and nothing else. This is the only way back from that pair to the
-- row. Partial on SENT because a settled row must never match a late reply.
CREATE INDEX IF NOT EXISTS idx_chat_auth_requests_reply_target
    ON orchestrator.chat_authorization_requests (chat_id, message_id)
    WHERE status = 'SENT';

-- The rows of one ask_user call, read on every answer to find out whether the
-- last question has just been answered.
CREATE INDEX IF NOT EXISTS idx_chat_auth_requests_group
    ON orchestrator.chat_authorization_requests (group_key)
    WHERE group_key IS NOT NULL;

COMMENT ON COLUMN orchestrator.chat_authorization_requests.kind IS
    'APPROVAL (two buttons), CHOICE (one button per option), TEXT (a reply). V519.';
COMMENT ON COLUMN orchestrator.chat_authorization_requests.group_key IS
    'The ask_user tool call id: the questions of one call share it and answer together. V519.';
