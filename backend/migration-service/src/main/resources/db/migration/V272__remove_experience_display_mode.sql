-- V272: Remove EXPERIENCE display mode
--
-- EXPERIENCE was a publication mode where visitors interacted with the
-- publisher's running workflow under the publisher's tenant (cross-tenant
-- execution, free participation with daily-limit gate). Pre-flight prod
-- query confirmed 0 rows with display_mode='EXPERIENCE' at the time of
-- this migration, so a data flip is not needed - only the CHECK constraint
-- on publication_highlights must be relaxed.
--
-- The publication.experience_participations table (V33) is intentionally
-- retained as historical audit data - see WORKSTREAM-B rollback note.
-- Code-side cleanup (DisplayMode enum, ExperienceController, gateway
-- route, frontend literals) lands in the same release as this migration.

ALTER TABLE publication.publication_highlights
    DROP CONSTRAINT IF EXISTS pub_highlights_displaymode_check;

ALTER TABLE publication.publication_highlights
    ADD CONSTRAINT pub_highlights_displaymode_check CHECK (display_mode IN
        ('WORKFLOW','INTERFACE','APPLICATION','AGENT','TABLE','SKILL'));

-- Defense in depth: coerce any straggler EXPERIENCE row on workflow_publications
-- (should be 0 per pre-flight; statement is a no-op if so) to APPLICATION so
-- @PostLoad enum mapping does not blow up post-deploy. workflow_publications.
-- display_mode is VARCHAR (no DB enum since V33), so no ALTER TYPE needed.
UPDATE publication.workflow_publications
   SET display_mode = 'APPLICATION'
 WHERE display_mode = 'EXPERIENCE';
