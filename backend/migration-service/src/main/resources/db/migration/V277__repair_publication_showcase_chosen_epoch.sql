-- V277: Repair showcase_chosen_epoch for databases where V273 is recorded
-- in Flyway history but the column is missing.
--
-- Some long-lived local/staging databases can have V273 marked successful
-- after a checksum repair while the original ALTER did not run. Publication
-- service reads this column through Hibernate, so the missing column breaks
-- every publication query before application code can handle the request.

ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS showcase_chosen_epoch INTEGER NULL;
