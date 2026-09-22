-- ============================================================================
-- V483: publication.workflow_publications.node_types - denormalized node-type
--       tokens per publication, so the applications list can be filtered by
--       node type ("show me the apps that use Gmail").
--
-- Token grammar (owned by WorkflowNodeTypeExtractor in publication-service,
-- which writes this column from the plan snapshot, and its byte-identical twin
-- in orchestrator-service, which derives the same tokens on read):
--   trigger:<type>  mcp:<slug>  core:<type>  agent:<type>  table:<type>  interface
--
-- Only this table gets a column. The workflows list derives the same tokens
-- from the plan it has already loaded, so a stored copy there would duplicate
-- data already in memory and could go stale. Here there is no such choice: the
-- publication list queries deliberately never select plan_snapshot (~200KB a
-- row), so without this column a publication's node types are simply unknown at
-- list time.
--
-- No index: the applications list is scope-bounded (one publisher's rows) and
-- already loads its whole set to filter and sort it client-side, so no query
-- looks this column up. An index would only add write amplification per publish.
--
-- NOT NULL DEFAULT '[]' because a NULL is invisible to every filter: a row that
-- lost its tokens would silently vanish from a filtered list rather than fail.
--
-- LOCK POSTURE - why this script runs outside Flyway's transaction wrapper.
-- Postgres holds a lock until COMMIT, so with the default wrapper the ALTER's
-- ACCESS EXCLUSIVE would be held across the backfill too - and the backfill
-- detoasts a ~200KB plan_snapshot and walks it for every row in the table.
-- ACCESS EXCLUSIVE conflicts with ACCESS SHARE, so that would block every READ
-- of this table for the whole backfill: the marketplace, the applications list,
-- acquire and publish, all down for the duration of a deploy. Split across two
-- transactions the ALTER commits instantly (a defaulted column is a catalog-only
-- change since PG11 - no rewrite) and the UPDATE then takes only ROW EXCLUSIVE,
-- which readers do not conflict with.
--
-- On crash between the two: the column exists and some rows still read '[]'.
-- The backfill is re-runnable as-is (its WHERE targets exactly those rows), so
-- recovery is flyway:repair + re-run, and until then the only symptom is that
-- the not-yet-backfilled publications do not match a node-type filter.
-- ============================================================================

-- flyway:executeInTransaction=false

ALTER TABLE publication.workflow_publications
    ADD COLUMN IF NOT EXISTS node_types JSONB NOT NULL DEFAULT '[]'::jsonb;

-- ---------------------------------------------------------------------------
-- Backfill from the existing snapshots. This SQL mirrors the Java extractor and
-- is deliberately TEMPORARY (dropped at the bottom): the extractor owns the
-- write path, and a permanent SQL twin of it would be a silent drift surface.
--
-- Each branch is guarded by jsonb_typeof: one malformed snapshot in the table
-- would otherwise abort the whole migration.
-- ---------------------------------------------------------------------------
-- Java's String.trim() strips every character <= U+0020; SQL trim() strips
-- spaces only. Without this a tab-padded type backfills a token that no later
-- save can reproduce - it shows up in the picker once and vanishes on the next
-- publish. NUL cannot appear in a Postgres text value, so the range starts at
-- \x01.
CREATE OR REPLACE FUNCTION public.lc_v483_trim(value TEXT) RETURNS TEXT AS $t$
    SELECT regexp_replace(value, '^[\x01-\x20]+|[\x01-\x20]+$', '', 'g');
$t$ LANGUAGE SQL IMMUTABLE;

CREATE OR REPLACE FUNCTION public.lc_v483_node_types(plan JSONB) RETURNS JSONB AS $fn$
    -- COLLATE "C" so the array is ordered by code point, exactly as the Java
    -- extractor's String::compareTo does. Under a locale collation (en_US.UTF-8
    -- ignores ':' '-' '_' at the primary level) 'mcp:zohobooks' sorts before
    -- 'mcp:zoho_crm', the reverse of what Java writes - two orderings for the
    -- same plan, for no reason a reader could see.
    --
    -- The DISTINCT is a subquery rather than jsonb_agg(DISTINCT ... ORDER BY):
    -- Postgres requires an aggregate's ORDER BY expression to appear in its
    -- argument list, and "tok COLLATE \"C\"" is a different expression from
    -- "tok", so that form is rejected at CREATE FUNCTION time.
    SELECT COALESCE(jsonb_agg(tok ORDER BY tok COLLATE "C"), '[]'::jsonb)
    FROM (
      SELECT DISTINCT tok FROM (
            -- jsonb_typeof(...) = 'string' throughout: the Java extractor reads
            -- these fields as String and ignores anything else, while ->> would
            -- happily stringify a number. Without the guard the backfill would
            -- write a token no re-save could ever reproduce.
            SELECT 'trigger:' || lower(public.lc_v483_trim(t ->> 'type')) AS tok
            FROM jsonb_array_elements(
                     CASE WHEN jsonb_typeof(plan -> 'triggers') = 'array'
                          THEN plan -> 'triggers' ELSE '[]'::jsonb END) t
            WHERE jsonb_typeof(t -> 'type') = 'string'
              AND public.lc_v483_trim(t ->> 'type') <> ''

        UNION ALL
            -- Same slug resolution as WorkflowIconExtractor: an explicit
            -- iconSlug wins, except the catalog's 'mcp' placeholder, which
            -- falls back to the apiSlug prefix of the node id.
            SELECT 'mcp:' || lower(public.lc_v483_trim(COALESCE(
                       -- lower() BEFORE the 'mcp' comparison: the Java extractor
                       -- rejects the placeholder case-insensitively, so an
                       -- iconSlug of "MCP" must fall back to the apiSlug here too
                       -- rather than backfilling a bogus mcp:mcp bucket.
                       NULLIF(NULLIF(
                           CASE WHEN jsonb_typeof(m -> 'iconSlug') = 'string'
                                THEN lower(public.lc_v483_trim(m ->> 'iconSlug')) END, ''), 'mcp'),
                       split_part(m ->> 'id', '/', 1))))
            FROM jsonb_array_elements(
                     CASE WHEN jsonb_typeof(plan -> 'mcps') = 'array'
                          THEN plan -> 'mcps' ELSE '[]'::jsonb END) m
            WHERE jsonb_typeof(m -> 'id') = 'string'
              AND public.lc_v483_trim(m ->> 'id') <> ''

        UNION ALL
            SELECT 'core:' || lower(public.lc_v483_trim(c ->> 'type'))
            FROM jsonb_array_elements(
                     CASE WHEN jsonb_typeof(plan -> 'cores') = 'array'
                          THEN plan -> 'cores' ELSE '[]'::jsonb END) c
            WHERE jsonb_typeof(c -> 'type') = 'string'
              AND public.lc_v483_trim(c ->> 'type') <> ''

        UNION ALL
            -- An agent node whose type is absent, blank or not a string is a
            -- plain agent - same rule as the Java extractor.
            SELECT 'agent:' || lower(COALESCE(
                       NULLIF(CASE WHEN jsonb_typeof(a -> 'type') = 'string'
                                   THEN public.lc_v483_trim(a ->> 'type') END, ''), 'agent'))
            FROM jsonb_array_elements(
                     CASE WHEN jsonb_typeof(plan -> 'agents') = 'array'
                          THEN plan -> 'agents' ELSE '[]'::jsonb END) a
            -- The only branch with no field guard to imply it, so it needs its
            -- own: the Java extractor skips non-map elements, and without this a
            -- stray string in agents[] would backfill a phantom agent:agent.
            WHERE jsonb_typeof(a) = 'object'

        UNION ALL
            -- "crud-" stripped so the token matches the frontend registry key.
            SELECT 'table:' || lower(public.lc_v483_trim(regexp_replace(public.lc_v483_trim(tb ->> 'type'), '^crud-', '')))
            FROM jsonb_array_elements(
                     CASE WHEN jsonb_typeof(plan -> 'tables') = 'array'
                          THEN plan -> 'tables' ELSE '[]'::jsonb END) tb
            WHERE jsonb_typeof(tb -> 'type') = 'string'
              AND public.lc_v483_trim(tb ->> 'type') <> ''

        UNION ALL
            -- No element guard here, unlike agents: the Java extractor (and the
            -- icon extractor it mirrors) emit this token for ANY non-empty
            -- interfaces array, because nothing is read off the elements.
            SELECT 'interface'
            FROM jsonb_array_elements(
                     CASE WHEN jsonb_typeof(plan -> 'interfaces') = 'array'
                          THEN plan -> 'interfaces' ELSE '[]'::jsonb END) i
      ) s(tok)
      -- Drop tokens whose value half came out empty: an mcp id of "/gmail/send"
      -- splits to '', a table type of exactly "crud-" strips to ''. The Java
      -- extractor skips a blank value; without this the picker would offer a
      -- nameless "mcp:" option on backfilled rows that vanishes on the next save.
      WHERE tok IS NOT NULL AND right(tok, 1) <> ':'
    ) d(tok);
$fn$ LANGUAGE SQL IMMUTABLE;

UPDATE publication.workflow_publications
   SET node_types = public.lc_v483_node_types(plan_snapshot)
 WHERE node_types = '[]'::jsonb;

DROP FUNCTION IF EXISTS public.lc_v483_node_types(JSONB);
DROP FUNCTION IF EXISTS public.lc_v483_trim(TEXT);
