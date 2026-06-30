-- PR21 - promote conversation.* tables' org context to a first-class column.
--
-- Context: PR15 V209 added organization_id + organization_role to
-- orchestrator.workflow_runs, PR18 V204 to storage.storage, PR19 V208 to
-- auth.credentials, PR20 V210 to the 5 agent.* runtime tables. PR21 continues
-- the chain on conversation.conversations - the parent table for every chat
-- in conversation-service.
--
-- Visible bug: a team-workspace user opens the chat sidebar
-- (ConversationSidebar.tsx → GET /api/conversations) and sees their personal-
-- scope chats from before they switched workspaces, or vice-versa. Reason:
-- ConversationRepository.findByUserIdAndActiveTrueOrderByUpdatedAtDesc
-- filters user_id (= tenant_id) only; there is no organization tag on the row,
-- so a fresh team-workspace session is indistinguishable from the account-
-- root personal session.
--
-- Scope decision: only the parent {conversation.conversations} table gets
-- organization_id. Messages, streams, tool_results, and message_attachments
-- inherit scope through their FK to {conversation.conversations} - every read
-- path on those tables already routes through a parent conversation lookup
-- which IS scope-checked. Mirroring org_id onto every child table (PR20's
-- approach) is not necessary here because conversation children are NEVER
-- queried by user_id directly. Streams are scoped by streamId (PK) or
-- conversation_id (FK); messages by conversation_id only. The TTL maintenance
-- scan is admin-only and intentionally cross-tenant.
--
-- This migration:
--   1. ADD COLUMN organization_id VARCHAR(255) NULL.
--   2. NO backfill - pre-PR21 conversations were created without an org tag
--      and belong to personal scope by definition (no orgs existed at the
--      time of creation). Leaving organization_id NULL on legacy rows is the
--      correct strict-isolation semantic: they only appear in personal-scope
--      reads, never in any org workspace listing.
--   3. CREATE INDEX CONCURRENTLY on (organization_id, user_id, updated_at DESC)
--      WHERE organization_id IS NOT NULL - partial index, small and fast for
--      the org-scoped sidebar list. The existing tenant-only indexes
--      (idx_conversations_user_id, idx_conversations_user_active_updated)
--      remain in place for the personal-scope read path.
--
-- Lock posture:
--   - ALTER TABLE … ADD COLUMN NULL → ACCESS EXCLUSIVE but metadata-only on
--     PostgreSQL ≥11 (instant, no row rewrite).
--   - CREATE INDEX CONCURRENTLY → SHARE UPDATE EXCLUSIVE, concurrent reads/
--     writes proceed during build. Requires flyway:executeInTransaction=false.
--
-- Idempotent: ADD COLUMN IF NOT EXISTS + CREATE INDEX CONCURRENTLY IF NOT
-- EXISTS. Safe to re-run.

-- flyway:executeInTransaction=false

ALTER TABLE conversation.conversations
    ADD COLUMN IF NOT EXISTS organization_id VARCHAR(255);

-- Partial index for the hot org-scoped sidebar read path. The existing
-- idx_conversations_user_active_updated continues to serve the personal-scope
-- listing (which adds AND organization_id IS NULL at the JPQL layer).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_conversations_org_user_updated
    ON conversation.conversations (organization_id, user_id, updated_at DESC)
    WHERE organization_id IS NOT NULL AND active = TRUE;

COMMENT ON COLUMN conversation.conversations.organization_id IS
    'Workspace the conversation belongs to. NULL = personal scope. Sourced '
    'from X-Organization-ID at conversation creation time by '
    'ConversationCommandService.persistConversation (PR21). Reads via '
    'ConversationRepository use strict-isolation finders: org workspace sees '
    'organization_id = :orgId only; personal workspace sees '
    'organization_id IS NULL only. NO mixing per the CLAUDE.md strict-'
    'isolation contract. Messages, streams, tool_results and attachments '
    'inherit scope from their parent conversation via the conversation_id '
    'FK - they are never queried by user_id directly, so they do not need '
    'their own organization_id column.';
