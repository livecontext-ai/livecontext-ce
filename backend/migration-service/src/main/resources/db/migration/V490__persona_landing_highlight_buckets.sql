-- V490: one landing highlight bucket per persona.
--
-- The six /for/<persona> pages all read the same curated row ('LANDING'), so they showed
-- the same apps. Each persona gets its own bucket key, on the same mechanism: the bucket
-- is NOT a publication type (no publication is ever of type LANDING_*), it holds
-- APPLICATION-type publications, and PublicationHighlightService.requiredPublicationMode
-- maps every LANDING* bucket to APPLICATION for validation, exactly as V347 did for
-- LANDING.
--
-- Extends the CURRENT check constraint (the V347 list) with the six keys. Drop-then-add
-- because Postgres has no "ALTER CONSTRAINT ... CHECK"; IF EXISTS keeps a re-run safe.
-- EXPERIENCE stays out: V272 removed it and this must not resurrect it.
ALTER TABLE publication.publication_highlights
    DROP CONSTRAINT IF EXISTS pub_highlights_displaymode_check;

ALTER TABLE publication.publication_highlights
    ADD CONSTRAINT pub_highlights_displaymode_check CHECK (display_mode IN
        ('WORKFLOW','INTERFACE','APPLICATION','AGENT','TABLE','SKILL','LANDING',
         'LANDING_OPS','LANDING_CREATOR','LANDING_SUPPORT','LANDING_SALES',
         'LANDING_MARKETING','LANDING_RECRUITING'));

-- Seed each persona bucket with what the landing already shows, so the six tabs open
-- filled rather than empty and an admin edits from there instead of starting over.
--
-- This is a COPY, not a link, and that is the point: from here on each persona row lives
-- its own life, and editing LANDING changes the home page alone.
--
-- It copies NOTHING on an install whose LANDING row was never curated (a fresh tenant, or
-- anyone who has not opened Settings > Marketplace Highlights): the SELECT finds no source
-- rows, the six buckets stay empty, and the six tabs open empty exactly as they would have
-- without this statement. Where LANDING is curated, the reader's fallback to it (see
-- MarketplacePreview) stops being reachable on a persona page, since the row is no longer
-- empty. The fallback stays for both of those cases, and for a row an admin empties again.
--
-- The NOT EXISTS skips any bucket somebody has already curated: a re-run, or a later
-- re-baseline, must never overwrite a human decision. Ranks are copied as they are, so the
-- persona rows open in the landing's order.
--
-- There is deliberately NO `ON CONFLICT DO NOTHING` here, and it must not be added back.
-- It was, as belt and braces, and it made this migration IMPOSSIBLE to apply: the table's
-- pub_highlights_rank_unique is DEFERRABLE INITIALLY DEFERRED, and Postgres rejects a
-- deferrable constraint as an ON CONFLICT arbiter outright ("ON CONFLICT does not support
-- deferrable unique constraints/exclusion constraints as arbiters", SQLSTATE 55000). That
-- is a parse-time refusal, not a data-dependent one, so it failed on the first prod deploy
-- that ran it (2026-09-16 17:13) and would have failed on every install, everywhere,
-- forever - taking every later migration down with it, since Flyway stops at the first
-- failure. It was unnecessary anyway, for the reason its own comment gave: LANDING is
-- unique per publication (the primary key says so), so the cross join produces each
-- (bucket, publication_id) exactly once and each (bucket, rank) exactly once.
INSERT INTO publication.publication_highlights (display_mode, publication_id, rank, created_at, created_by)
SELECT target.bucket, h.publication_id, h.rank, now(), h.created_by
FROM publication.publication_highlights h
CROSS JOIN (VALUES
    ('LANDING_OPS'), ('LANDING_CREATOR'), ('LANDING_SUPPORT'),
    ('LANDING_SALES'), ('LANDING_MARKETING'), ('LANDING_RECRUITING')
) AS target(bucket)
WHERE h.display_mode = 'LANDING'
  AND NOT EXISTS (
      SELECT 1 FROM publication.publication_highlights existing
      WHERE existing.display_mode = target.bucket
  );
