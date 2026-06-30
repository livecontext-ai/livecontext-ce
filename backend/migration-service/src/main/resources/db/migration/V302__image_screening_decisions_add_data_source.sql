-- V302: allow image_source = 'DATA' on publication.image_screening_decisions.
--
-- Wave 2b - the pre-publish image screening now also surfaces images that
-- live in the resolved interface render data (items[].data), not just the
-- static HTML/CSS/JS templates. These are tagged with a fourth source,
-- 'DATA', covering both:
--   * raw third-party URLs interpolated from per-item data (e.g. a scraped
--     CDN image such as scontent-*.cdninstagram.com), and
--   * downloaded/re-hosted FileRef objects (download_file / image_generation
--     outputs that the publisher re-serves from their own storage).
--
-- V274/V278 created the column with CHECK (image_source IN ('HTML','CSS','JS')).
-- Widen the constraint. Idempotent: drop the old constraint if present, then
-- (re)create the widened one only when no constraint already covers 'DATA'.

ALTER TABLE publication.image_screening_decisions
    DROP CONSTRAINT IF EXISTS image_screening_decisions_image_source_check;

ALTER TABLE publication.image_screening_decisions
    ADD CONSTRAINT image_screening_decisions_image_source_check
    CHECK (image_source IN ('HTML','CSS','JS','DATA'));
