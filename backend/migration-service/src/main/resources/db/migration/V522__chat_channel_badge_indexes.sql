-- V522: tenant indexes for the chat-channel trophies.
--
-- BadgeStatsCollector.collectChannels reads three tables by the user who owns
-- the rows (tenant_id), and none of them is indexed that way:
--   orchestrator.chat_channel_links          indexed by organization (V517)
--   orchestrator.chat_authorization_requests indexed by token, conversation,
--                                            live status and group (V517, V519)
--   orchestrator.approval_channel_deliveries indexed by signal wait and token
--                                            hash (V393, V497)
--
-- The statement runs for every user who still has a locked channel trophy, on
-- every badge evaluation (the periodic sweep and each settings visit). The top
-- "remote decisions" tier is 2500, so in practice that is nearly every user,
-- and approval_channel_deliveries grows with every approval sent to a chat.
-- Without these, each evaluation reads all three tables.
--
-- Partial where the statement filters on a fixed condition, so each index holds
-- only the rows that are ever counted:
--   links     only verified destinations (verified_at IS NOT NULL)
--   requests  only requests decided from a chat (RESOLVED with decided_by)
-- Deliveries are joined to their signal wait and filtered on the wait's
-- resolved_by, which cannot be expressed as a partial predicate here, so that
-- index is plain.
--
-- Lock posture: CONCURRENTLY, so reads and writes continue during the build
-- (same convention as V509). On crash PG marks the index INVALID: drop it and
-- re-run V522 (flyway:repair if needed).

-- flyway:executeInTransaction=false

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_channel_links_tenant_verified
    ON orchestrator.chat_channel_links (tenant_id)
    WHERE verified_at IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_chat_auth_requests_tenant_decided
    ON orchestrator.chat_authorization_requests (tenant_id)
    WHERE status = 'RESOLVED' AND decided_by IS NOT NULL;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_acd_tenant
    ON orchestrator.approval_channel_deliveries (tenant_id);
