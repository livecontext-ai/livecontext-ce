-- V540: the time zone becomes a SETTING, not only an observation.
--
-- Numbered 540, and it took three tries, which is the point worth recording. 535 was free on dev
-- but claimed by the in-flight CASA branch (V535-V538). 539 was free everywhere when it was
-- picked, and dev merged its own V539 while this branch was being audited. 540 is free on dev and
-- on every unmerged branch as of this commit.
--
-- Two files with the same version make Flyway refuse to start ("Found more than one migration with
-- version N"), which takes down migration-service and with it the whole deploy under helm
-- --atomic, whichever of the two merges second. So a number has to be free across every unmerged
-- branch AND it can stop being free without this branch touching anything. The CI gate re-checks
-- it against the dev tip on every push, which is the only place that check can be trusted.
--
-- V527 added auth.users.time_zone as "the IANA zone the browser reported, last wins". That is
-- the right rule for a fact the app OBSERVES, and the wrong one for a preference the person
-- PICKS: without a guard, the next session's context report (useProfileContextReport, sent
-- once per tab session) would silently overwrite the zone they chose in Settings, and their
-- dates would drift back to wherever their browser happens to be.
--
-- locale already carries that guard (locale_explicit, V527). The time zone gets the symmetric
-- one, so both preferences obey the same rule:
--
--   time_zone_explicit = true   picked in Settings. The browser report never overwrites it.
--   time_zone_explicit = false  reported by the browser. Last wins, exactly as before V540.
--
-- Backfill: false for every existing row, which is accurate - nobody could pick a zone before
-- this release, so every stored value came from a browser report.

ALTER TABLE auth.users
    ADD COLUMN IF NOT EXISTS time_zone_explicit BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN auth.users.time_zone_explicit IS
    'True when the person picked this zone in Settings; the browser context report then never overwrites it. Mirrors locale_explicit. V540.';
