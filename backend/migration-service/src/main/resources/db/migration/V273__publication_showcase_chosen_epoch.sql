-- V273: Add showcase_chosen_epoch column to workflow_publications.
--
-- Workstream E (single previewable epoch). Pre-fix the marketplace
-- preview showed every epoch the publisher's source run had reached at
-- publish time - visitors paginated through them via "newer/older"
-- chevrons in the iframe. That was confusing ("which one is the canonical
-- demo?") and forced the snapshot JSONB to carry N copies of the rendered
-- items[] even when the publisher only ever wanted to showcase one.
--
-- The new nullable column lets the publisher pick exactly ONE epoch in
-- the publish wizard. The marketplace reader filters items[] /
-- aggregatedSteps to that single epoch when set; when NULL (legacy pubs
-- and updates that haven't re-published), the reader keeps the
-- multi-epoch behavior so nothing breaks for existing publications.
--
-- The capture path (ShowcaseSnapshotBuilder) is NOT changed yet - it
-- continues to write every epoch to the JSONB. Trimming the snapshot
-- write to a single epoch is a separate optimization (Wave E follow-up)
-- that requires a SCHEMA_VERSION bump on the snapshot record and a
-- backfill plan. Reader-side filtering ships now because it's the
-- user-visible win.

ALTER TABLE publication.workflow_publications
    ADD COLUMN showcase_chosen_epoch INTEGER NULL;

-- No backfill: existing rows keep the legacy multi-epoch view until the
-- publisher re-saves through the wizard and explicitly picks one.
