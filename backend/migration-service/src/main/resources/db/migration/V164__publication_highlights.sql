-- V164: admin-curated marketplace highlights per display_mode.
--
-- Cloud-only feature. Admins (Keycloak ADMIN role) pick which publications
-- appear in the homepage "Highlights" rows and set their order. The table
-- has a per-(display_mode, rank) UNIQUE constraint so no two highlights
-- share a slot, and DEFERRABLE INITIALLY DEFERRED so a single transaction
-- can re-rank multiple rows without hitting transient violations.
--
-- See:
--   - PublicationHighlightAdminController (admin write)
--   - PublicationHighlightPublicController (public read)
--   - HighlightedApps.tsx (frontend consumer)
CREATE TABLE IF NOT EXISTS publication.publication_highlights (
    display_mode VARCHAR(50) NOT NULL,
    publication_id UUID NOT NULL REFERENCES publication.workflow_publications(id) ON DELETE CASCADE,
    rank INT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (display_mode, publication_id),
    CONSTRAINT pub_highlights_rank_unique UNIQUE (display_mode, rank)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT pub_highlights_displaymode_check CHECK (display_mode IN
        ('WORKFLOW','INTERFACE','APPLICATION','EXPERIENCE','AGENT','TABLE','SKILL'))
);

CREATE INDEX IF NOT EXISTS idx_pub_highlights_mode_rank
    ON publication.publication_highlights (display_mode, rank);
