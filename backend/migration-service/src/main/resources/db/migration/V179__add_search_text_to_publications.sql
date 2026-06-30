-- ============================================================================
-- V179: Add denormalized search_text column to workflow_publications
-- ============================================================================
-- Goal: marketplace search now matches against nested content (interface
-- titles, agent roles, table names, …) in addition to top-level title and
-- description. The text is built at publish time by SearchTextBuilder and
-- stored in this column. Repository queries ILIKE against search_text.
--
-- Backfill seeds existing rows with top-level fields only. Nested content
-- gets indexed on the next re-publish through the new builder.

ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS search_text TEXT NOT NULL DEFAULT '';

UPDATE publication.workflow_publications
SET search_text = LOWER(
    CONCAT(
        COALESCE(title, ''), ' ',
        COALESCE(description, ''), ' ',
        COALESCE(category_name, ''), ' ',
        COALESCE(category_slug, ''), ' ',
        COALESCE(publisher_name, '')
    )
)
WHERE search_text IS NULL OR search_text = '';
