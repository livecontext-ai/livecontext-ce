-- V359: per-(user, workspace) favorited applications.
--
-- A personal, self-service list: a user stars/unstars an APPLICATION publication
-- and the Home row can surface a "Favorites" view (their own picks) alongside the
-- admin-curated "Highlights" row. Owned by publication-service, same schema as
-- publication_highlights (V164), because favorites reference publication ids.
--
-- Scope: keyed (user_id, organization_id, publication_id) - workspace-scoped like
-- conversation.user_chat_defaults (V312). organization_id is the ACTIVE workspace;
-- the personal workspace is stored as '' (empty string) NOT NULL so the PK stays
-- well-defined (X-Organization-ID is absent in personal scope). ON DELETE CASCADE
-- mirrors V164 so deleting a publication cleans up its favorites automatically.
--
-- See:
--   - UserPublicationFavoriteService (read/write logic)
--   - PublicationFavoriteController (REST: POST/DELETE /{id}/favorite, GET /favorites[/ids])
--   - HighlightedApps.tsx (frontend consumer - Highlights/Favorites toggle)
CREATE TABLE IF NOT EXISTS publication.user_publication_favorites (
    user_id         VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL DEFAULT '',
    publication_id  UUID NOT NULL REFERENCES publication.workflow_publications(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, organization_id, publication_id)
);

-- Primary access pattern: all favorites for a (user, workspace), newest first.
CREATE INDEX IF NOT EXISTS idx_user_pub_favorites_scope
    ON publication.user_publication_favorites (user_id, organization_id, created_at DESC);
