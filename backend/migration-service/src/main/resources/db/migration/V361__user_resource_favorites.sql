-- V361: per-(user, workspace) favorited native resources (workflow / table / interface / agent).
--
-- The personal counterpart to V359 (user_publication_favorites), but for the
-- user's OWN resources rather than marketplace publications. A user stars a
-- workflow / table / interface / agent and it floats to the top of that
-- resource's list page (and shows a filled star in the breadcrumb).
--
-- Owned by orchestrator-service. Unlike V359 there is intentionally NO foreign
-- key: resource ids span four different service schemas (orchestrator / datasource
-- / interface / agent), so a single FK is impossible. An orphan row (resource
-- later deleted) is harmless: it simply matches no listed resource and stays
-- invisible. resource_id is stored as text because datasource ids are numeric
-- while workflow/interface/agent ids are UUIDs - the column is opaque to this table.
--
-- Scope: keyed (user_id, organization_id, resource_type, resource_id) -
-- workspace-scoped exactly like V359. organization_id is the ACTIVE workspace;
-- the personal workspace is stored as '' (empty string) NOT NULL so the PK stays
-- well-defined (X-Organization-ID is absent in personal scope).
--
-- See:
--   - ResourceFavoriteService (read/write logic)
--   - ResourceFavoriteController (REST: POST/DELETE /api/favorites/{type}/{id}, GET /api/favorites/{type}/ids)
--   - favorite.service.ts (frontend consumer - star toggle + favorites-first sort)
CREATE TABLE IF NOT EXISTS orchestrator.user_resource_favorites (
    user_id         VARCHAR(255) NOT NULL,
    organization_id VARCHAR(255) NOT NULL DEFAULT '',
    resource_type   VARCHAR(32)  NOT NULL,
    resource_id     VARCHAR(255) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, organization_id, resource_type, resource_id)
);

-- Primary access pattern: all favorites of one type for a (user, workspace), newest first.
CREATE INDEX IF NOT EXISTS idx_user_resource_favorites_scope
    ON orchestrator.user_resource_favorites (user_id, organization_id, resource_type, created_at DESC);
