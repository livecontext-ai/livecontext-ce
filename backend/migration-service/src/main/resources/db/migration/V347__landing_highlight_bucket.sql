-- V347: add the LANDING highlight bucket.
--
-- The public landing page showcase (frontend MarketplacePreview) is now admin-
-- curated through the existing publication_highlights mechanism, under a new
-- display_mode bucket key 'LANDING'. Unlike the other buckets, LANDING is NOT a
-- publication type - it holds APPLICATION-type publications (validation maps
-- LANDING -> APPLICATION in PublicationHighlightService.requiredPublicationMode).
-- It is a separate bucket from 'APPLICATION' (which drives the chat highlights
-- row), so the two surfaces can be curated independently.
--
-- Extends the CURRENT check constraint (the V272 list, which already dropped
-- EXPERIENCE) with 'LANDING'. Drop-then-add because Postgres has no
-- "ALTER CONSTRAINT ... CHECK". Idempotent via IF EXISTS so a re-run is safe.
-- NB: EXPERIENCE is deliberately NOT in this list - V272 removed it and this
-- migration must not resurrect it.
ALTER TABLE publication.publication_highlights
    DROP CONSTRAINT IF EXISTS pub_highlights_displaymode_check;

ALTER TABLE publication.publication_highlights
    ADD CONSTRAINT pub_highlights_displaymode_check CHECK (display_mode IN
        ('WORKFLOW','INTERFACE','APPLICATION','AGENT','TABLE','SKILL','LANDING'));
