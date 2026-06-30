-- V144: Audit log for admin message search.
--
-- Every call to /api/admin/conversations/messages/search inserts a row.
-- Lets us answer "who searched what on whom?" during incident response.
-- Paired with the admin endpoint added in PR 2 (search service).

CREATE TABLE IF NOT EXISTS conversation.admin_search_audit (
    id              BIGSERIAL PRIMARY KEY,
    admin_user_id   VARCHAR(255) NOT NULL,
    target_user_id  VARCHAR(255) NOT NULL,
    query           TEXT NOT NULL,
    filters         JSONB,
    result_count    INTEGER,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Lookup by admin (e.g. "what did admin X search recently?")
CREATE INDEX IF NOT EXISTS idx_admin_search_audit_admin
    ON conversation.admin_search_audit (admin_user_id, occurred_at DESC);

-- Lookup by target (e.g. "who has been searching tenant Y?")
CREATE INDEX IF NOT EXISTS idx_admin_search_audit_target
    ON conversation.admin_search_audit (target_user_id, occurred_at DESC);
