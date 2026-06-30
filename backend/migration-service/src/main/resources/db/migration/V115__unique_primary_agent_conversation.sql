-- V115 - Enforce "one primary conversation per (agent_id, user_id)".
--
-- The app-level GET-then-POST in findOrCreateAgentConversation can race,
-- yielding duplicate primary conversations for the same agent+user. The
-- DB becomes the serialisation point via a partial unique index that
-- excludes the three legitimate N:1 cases:
--   - sub-agent conversations  (parent_conversation_id IS NOT NULL)
--   - memory-off webhooks/schedules  (memory_enabled IS FALSE)
--   - soft-deleted conversations  (active IS FALSE)

-- 1. Supporting index for the GET /api/conversations/agent/{agentId}
--    lookup, currently unindexed (full scan on every agent turn).
CREATE INDEX IF NOT EXISTS idx_conversations_agent_user
  ON conversation.conversations (agent_id, user_id)
  WHERE agent_id IS NOT NULL;

-- 2. Dedup: keep the oldest primary row per (agent_id, user_id),
--    soft-delete the rest. Non-destructive - messages are preserved,
--    the losing rows remain readable via historical queries, they just
--    stop occupying the "primary" slot.
WITH ranked AS (
  SELECT id,
         ROW_NUMBER() OVER (
           PARTITION BY agent_id, user_id
           ORDER BY created_at ASC, id ASC
         ) AS rn
  FROM conversation.conversations
  WHERE agent_id IS NOT NULL
    AND parent_conversation_id IS NULL
    AND memory_enabled IS TRUE
    AND active IS TRUE
)
UPDATE conversation.conversations c
   SET active = FALSE,
       updated_at = CURRENT_TIMESTAMP
  FROM ranked r
 WHERE c.id = r.id
   AND r.rn > 1;

-- 3. Enforce one primary conversation per (agent_id, user_id) going forward.
--    Concurrent inserts that both pass the app-level findFirst check will
--    now collide with pg error 23505; the server-side creator catches it
--    and re-fetches the winning row.
CREATE UNIQUE INDEX uq_conversations_primary_agent_per_user
  ON conversation.conversations (agent_id, user_id)
  WHERE agent_id IS NOT NULL
    AND parent_conversation_id IS NULL
    AND memory_enabled IS TRUE
    AND active IS TRUE;
