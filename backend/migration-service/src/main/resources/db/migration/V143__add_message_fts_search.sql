-- V143: Full-text search on conversation messages.
--
-- Adds a generated tsvector column on conversation.messages and the indexes
-- needed to power keyword search with ranking, excerpts, and filters.
--
-- Tokenizer: 'simple' (no stemming) - content is multi-language and
-- multi-tenant; stemming for one language would bias results for others.
-- See the project docs.
--
-- The column is GENERATED ALWAYS AS ... STORED - PostgreSQL maintains it
-- automatically on every INSERT/UPDATE, including the backfill that happens
-- when this migration runs. No application code is needed.
--
-- Online migration: PG runs the backfill without locking out reads. Writes
-- are briefly blocked while the column is being added; on a million-row
-- table this is on the order of seconds.

-- 1. Add the search_vector column.
ALTER TABLE conversation.messages
    ADD COLUMN IF NOT EXISTS search_vector tsvector
    GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(content, ''))
    ) STORED;

-- 2. GIN index on the tsvector - the only index that supports @@ matching
-- on tsvector at scale.
CREATE INDEX IF NOT EXISTS idx_messages_search_vector
    ON conversation.messages
    USING GIN(search_vector);

-- 3. Composite index for filters by role + recency within a conversation.
-- Existing idx_messages_conversation_created already covers
-- (conversation_id, created_at); we add role for queries like
-- "all assistant messages of conv X sorted by recency".
CREATE INDEX IF NOT EXISTS idx_messages_conv_role_created
    ON conversation.messages (conversation_id, role, created_at DESC);

-- 4. Partial index on tool_name - only ~1/3 of messages have a tool_name
-- (TOOL role messages); a partial index keeps it small and selective.
CREATE INDEX IF NOT EXISTS idx_messages_tool_name
    ON conversation.messages (tool_name)
    WHERE tool_name IS NOT NULL;
