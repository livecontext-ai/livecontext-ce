-- V316: 1:1 direct messages (community DM, Phase 2).
--
-- IDENTITY-LEVEL, NOT workspace-scoped: a DM is a relationship between two USER
-- identities, independent of any organization - two users in different workspaces
-- still share ONE canonical thread. (Contrast: conversation.conversations is strictly
-- org-scoped.) So these tables deliberately carry NO organization_id.
--
-- Participants are stored normalised (participant_lo <= participant_hi by text order)
-- so a plain UNIQUE constraint dedups the pair without LEAST/GREATEST in queries.
--
-- Column types match the JPA entities (DmThread/DmMessage): String UUID ids (the
-- entities use @GeneratedValue(UUID) on a String field, like conversation.conversations)
-- so the id/thread_id columns are VARCHAR(36), NOT uuid; Instant timestamps map to
-- TIMESTAMPTZ in Hibernate 6.
CREATE TABLE IF NOT EXISTS conversation.dm_threads (
    id                   VARCHAR(36)  PRIMARY KEY,
    participant_lo       VARCHAR(255) NOT NULL,
    participant_hi       VARCHAR(255) NOT NULL,
    last_message_at      TIMESTAMPTZ,
    last_message_preview VARCHAR(280),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT dm_threads_unique_pair UNIQUE (participant_lo, participant_hi),
    CONSTRAINT dm_threads_ordered     CHECK (participant_lo <= participant_hi),
    CONSTRAINT dm_threads_distinct    CHECK (participant_lo <> participant_hi)
);

CREATE INDEX IF NOT EXISTS idx_dm_threads_lo       ON conversation.dm_threads(participant_lo);
CREATE INDEX IF NOT EXISTS idx_dm_threads_hi       ON conversation.dm_threads(participant_hi);
CREATE INDEX IF NOT EXISTS idx_dm_threads_last_msg ON conversation.dm_threads(last_message_at DESC);

CREATE TABLE IF NOT EXISTS conversation.dm_messages (
    id             VARCHAR(36)  PRIMARY KEY,
    thread_id      VARCHAR(36)  NOT NULL REFERENCES conversation.dm_threads(id) ON DELETE CASCADE,
    sender_user_id VARCHAR(255) NOT NULL,
    content        TEXT         NOT NULL,
    read_at        TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Thread message history (chronological) + unread scan (sender <> me AND read_at IS NULL).
CREATE INDEX IF NOT EXISTS idx_dm_messages_thread ON conversation.dm_messages(thread_id, created_at);
CREATE INDEX IF NOT EXISTS idx_dm_messages_unread ON conversation.dm_messages(thread_id, sender_user_id) WHERE read_at IS NULL;
