-- V503: put the CONTENT back in the string columns that hold a large-object OID.
--
-- WHAT HAPPENED
--
-- `@Lob` on a String field makes Hibernate extract and bind the column through
-- ClobJdbcType. On PostgreSQL that is a LARGE OBJECT: the bytes go to pg_largeobject and
-- the column receives only the object's OID, printed as digits. The column is TEXT in the
-- DDL either way, so nothing about the schema shows it.
--
-- Removing `@Lob` therefore does two things, and only the first one was intended:
--   1. new writes store the real text, which is what the column was always declared to hold;
--   2. EVERY EXISTING ROW starts reading back as its OID, because a plain text read returns
--      what is actually in the column - a number.
--
-- It has happened twice:
--   * the agent entities, removed 2026-06-14 (0fafc31bb1) on top of agent_tasks removed
--     2026-04-13 (d1f4b64110). 22 agents have run without their instructions since: the
--     model received the string "885118".
--   * interface.interfaces and interface.interface_run_snapshots, removed 2026-09-19
--     (08c1d98030). Every interface rendered as a blank page with a number on it, in the app
--     and in the video renderer, from the next deploy onward.
--
-- Nothing was lost either time: the bytes stay in pg_largeobject, addressed by the OID still
-- sitting in the column. This migration reads them back and writes them where they belong.
--
-- THE TARGET LIST IS EVERY COLUMN THAT EVER CARRIED THE ANNOTATION AND STILL EXISTS. A
-- pickaxe over the whole history finds fourteen annotated fields; the fourteenth is
-- WorkflowEdgeEntity.dataMappingJson -> workflow_edges.data_mapping, which is out of scope
-- for two independent reasons: the column is JSONB (V12), so it never held an OID, and the
-- entity was deleted the day after it appeared. On 2026-09-20 production held OID rows in 7
-- of the ten rewritten targets (325 interfaces x3, 1075 snapshots x3, 22 agent prompts); the
-- rest were clean there and are listed anyway, because a self-hosted install that wrote rows
-- while the annotation was live has them and nobody would be looking. A column with nothing
-- to repair costs one scan of a small table.
--
-- THREE COLUMNS ARE REPORTED AND NOT REWRITTEN: agent_tasks.result, agent_tasks.error_message
-- and agent_task_notes.content. The whole repair rests on "a value that is only digits and
-- names a live large object is a reference, not content", which holds for an HTML template or
-- a system prompt and does NOT hold there: a task result of '664168' is an ordinary value, and
-- if that number happens to name a live object the rewrite would destroy real data with no way
-- back. Under-reaching on those three costs nothing permanent, because the objects are never
-- unlinked; over-reaching cannot be undone. Their candidates are counted, and the query printed
-- beside them is the same join this migration uses, so pasting it lists rows instead of raising.
--
-- COPIES MADE WHILE THE COLUMNS WERE BROKEN ARE NOT IN SCOPE AND ARE NOT FIXED HERE:
-- publication.workflow_publications.showcase_snapshot froze htmlTemplate/cssTemplate/
-- jsTemplate as JSONB, and agent.agent_executions.system_prompt keeps a per-execution copy.
-- Neither ever carried the annotation, so neither is a mapping bug, but both captured the
-- digits during the window. They stay recoverable ONLY while the objects exist. Note what does
-- and does not change that: `vacuumlo` only follows columns typed `oid`/`lo`, so it never saw
-- these TEXT columns as references and would have deleted the objects just as readily BEFORE
-- this migration. What this migration changes is that nothing points at them any more at all.
-- Either way, a reclaim - by `vacuumlo` or by a later migration - MUST sweep both of these
-- first:
--   SELECT count(*) FROM publication.workflow_publications
--    WHERE showcase_snapshot->>'htmlTemplate' ~ '^[1-9][0-9]{0,9}$';
--   SELECT count(*) FROM agent.agent_executions e
--     JOIN pg_largeobject_metadata m ON m.oid::text = e.system_prompt;
--
-- WHAT THIS DOES NOT DO
--
-- It does not unlink the recovered large objects. They become orphans (about 5 MB on the
-- 2026-09-20 production set) and a later migration can reclaim them once the repair has been
-- confirmed AND the copies above have been swept. An unlink here would make the repair
-- irreversible in the one release where it is most likely to need a second look.
--
-- SAFETY
--
-- A row is only rewritten when its value is an OID that is LIVE in pg_largeobject_metadata.
-- Note how that is asked: `m.oid::text = <column>`, never `<column>::oid`. Casting arbitrary
-- user text to oid RAISES on anything above 4294967295 ("value ... is out of range for type
-- oid"), and that SELECT sits OUTSIDE the per-row exception handler below, so one such row
-- would abort the whole DO block and fail the deploy. Comparing in the other direction cannot
-- raise whatever the column holds, and it is also what excludes a leading zero, since an oid
-- never renders as `0885118`. The regex is NOT load-bearing for either: it is there to keep
-- the scan cheap, and an earlier draft that credited it, and a numeric bound beside it, with
-- the safety was describing guards that do nothing in this direction.
--
-- Residual over-reach on the columns that ARE rewritten: a template or prompt whose content is
-- only digits AND whose number happens to name a live object is replaced by that object's
-- bytes. interface_run_snapshots mints three objects per run, so the live OID space is dense,
-- not sparse. Accepted there, and only there, because the alternative leaves authored content
-- unreadable and because none of those columns holds a bare number in practice.
--
-- The source encoding is UTF-8 because that is what put the bytes there: pgjdbc pins
-- client_encoding to UTF8, so a Clob written through it holds UTF-8 whatever the database's
-- own encoding is. Reading them back as server_encoding would be wrong on any database that
-- is not UTF8.
--
-- WHAT IS TOLERATED IS AN ALLOW-LIST, NOT A CATCH-ALL. Three refusals are properties of one
-- ROW and are counted instead of raising: 22021 (the object's bytes are not valid UTF-8),
-- 22001 (the recovered value is too long for the column's width) and 22P05 (the content has no
-- representation in the database's encoding - real on a LATIN1 database, and a property of
-- that row's characters, not of the database, since the ASCII rows beside it convert fine).
-- Everything else FAILS the deploy, in the REWRITE loop: a privilege refusal, a lock that
-- outlasts its retries, a serialization failure, a deadlock, anything unnamed. A failure in the
-- report-only loop never does - see below.
--
-- There is no "and if it repaired nothing at all, fail" rule on top of that, and the reason
-- is worth recording: it cannot be told apart from a REPLAY, where every row is already
-- repaired and only the unconvertible ones are still candidates. Both read as "0 repaired, N
-- refused". It is not needed either - all three tolerated states are properties of the row's
-- own bytes, not of the database, so "all of them failed" means every row was bad, which is
-- what the per-row warnings already say, one line each.
--
-- Re-running is a no-op for every row this migration repaired, EXCEPT the pathological case
-- where the recovered content is itself a bare number naming another live object.
--
-- ORDER OF RELEASE, for the next person doing this: deploy the annotation removal FIRST, then
-- this repair, in two releases. Migrations run BEFORE the new pods roll, so shipping both at
-- once leaves the old pods writing fresh OIDs after the pass that would have swept them. That
-- ordering cannot always be had: a CE upgrade carries one image, so the removal and this
-- migration travel together, and on cloud the removal reached prod through the dev image
-- channel rather than through main, so a later dev->main release replays the same window for
-- anyone deploying from main. That is why EVERY target is scanned TWICE, unconditionally,
-- rather than only when a row was seen to change: a row an old pod writes while the first scan
-- is walking the table never shows up as a skip, and the second scan is the only thing that
-- catches it.

-- A per-row UPDATE loop against live tables, during a deploy. One long-running app
-- transaction holding a row would otherwise make this wait for ever, and Flyway's advisory
-- lock turns that into a hung `helm --atomic --wait`. lock_timeout, set inside the block,
-- bounds one statement. statement_timeout has to be set OUT HERE, as its own statement: the
-- timer is armed when a statement starts, so setting it inside the DO would arm nothing and
-- leave the whole-block ceiling - the thing a hung deploy actually needs - missing. Measured:
-- 2.6 s for the 4222 production rows, so this is three orders of magnitude of headroom and
-- only ever fires on a pathology.
SET LOCAL statement_timeout = '15min';

DO $$
DECLARE
    -- Refusals that belong to ONE row. Anything outside this list is treated as a property of
    -- the column or the database and fails the deploy. So does a run that repairs nothing at
    -- all while refusing something, however each refusal is classified.
    TOLERATED     constant text[] := ARRAY['22021', '22001', '22P05'];
    -- The row was locked, or two writers collided: worth retrying, fatal if it outlasts them.
    TRANSIENT     constant text[] := ARRAY['55P03', '40001', '40P01'];
    MAX_ATTEMPTS  constant integer := 3;
    MAX_PASSES    constant integer := 2;

    target        record;
    candidate     record;
    statement     text;
    err_state     text;
    err_message   text;
    attempt       integer;
    pass          integer;
    touched       integer;
    repaired      bigint;
    skipped       bigint;
    failed        bigint;
    pass_failed   bigint;
    pass_denied_o bigint;
    pass_denied_t bigint;
    pass_stuck    bigint;
    pass_other    bigint;
    table_exists  boolean;
    columns_seen  bigint;
    pending       bigint;
    report_failed bigint := 0;
    denied_object bigint := 0;
    denied_table  bigint := 0;
    stuck         bigint := 0;
    other_fatal   bigint := 0;
    unreadable    bigint := 0;
    all_repaired  bigint := 0;
    all_skipped   bigint := 0;
    all_failed    bigint := 0;
BEGIN
    SET LOCAL lock_timeout = '5s';

    FOR target IN
        SELECT * FROM (VALUES
            -- Removed 2026-09-19 (08c1d98030).
            ('interface', 'interfaces',              'html_template'),
            ('interface', 'interfaces',              'css_template'),
            ('interface', 'interfaces',              'js_template'),
            ('interface', 'interface_run_snapshots', 'html_template'),
            ('interface', 'interface_run_snapshots', 'css_template'),
            ('interface', 'interface_run_snapshots', 'js_template'),
            -- Removed 2026-06-14 (0fafc31bb1).
            ('agent',     'agents',                  'system_prompt'),
            ('agent',     'skills',                  'instructions'),
            ('agent',     'agent_task_recurrences',  'instructions'),
            -- Removed 2026-04-13 (d1f4b64110).
            ('agent',     'agent_tasks',             'instructions')
        ) AS t(schema_name, table_name, column_name)
    LOOP
        -- "The table is not in this database" and "I am not allowed to see it" are opposite
        -- situations that information_schema reports identically, because it is filtered by
        -- privilege. A target that EXISTS but shows no columns at all is a privilege problem,
        -- and skipping it silently would print the same clean summary as a database with
        -- nothing to repair. Existence is read from pg_class rather than to_regclass, which
        -- RAISES without USAGE on the schema - outside any handler, in the very branch built
        -- to give that case a clear message.
        SELECT EXISTS (
            SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = target.schema_name AND c.relname = target.table_name)
          INTO table_exists;
        SELECT count(*) INTO columns_seen
          FROM information_schema.columns
         WHERE table_schema = target.schema_name AND table_name = target.table_name;
        IF columns_seen = 0 THEN
            IF table_exists THEN
                unreadable := unreadable + 1;
                RAISE WARNING 'V503: %.% exists but none of its columns are visible to this role', target.schema_name, target.table_name;
            END IF;
            CONTINUE;
        END IF;

        -- The column must be a string column. Both TEXT and VARCHAR are accepted: what this
        -- repairs is a JDBC type, not a DDL width, and a column silently skipped for being
        -- VARCHAR would be a repair reporting success having done nothing. The two skips below
        -- SAY so for the same reason: a target this migration declined to look at must not be
        -- indistinguishable from one it looked at and found clean.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = target.schema_name
              AND table_name   = target.table_name
              AND column_name  = target.column_name
              AND data_type IN ('text', 'character varying')) THEN
            RAISE NOTICE 'V503: %.%.% skipped - the column is absent or is not a string column', target.schema_name, target.table_name, target.column_name;
            CONTINUE;
        END IF;

        -- The table must carry the `id` the UPDATE targets. ctid would need no such
        -- assumption and is exactly what must not be used: app pods are still writing during
        -- the migration, and a row that moves between the SELECT and the UPDATE would be
        -- rewritten by its former address.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = target.schema_name
              AND table_name   = target.table_name
              AND column_name  = 'id') THEN
            RAISE NOTICE 'V503: %.% skipped - no id column for the UPDATE to key on', target.schema_name, target.table_name;
            CONTINUE;
        END IF;

        repaired := 0;
        skipped  := 0;
        failed   := 0;
        pass     := 0;

        -- Both projected columns are cast to text on purpose. `candidate` is ONE record
        -- variable reused across targets of different column types, and the RAISE below is a
        -- single compiled expression: reading a field that is text for one target and varchar
        -- for the next fails with "type of parameter N does not match that when preparing the
        -- plan", OUTSIDE the per-row handler, taking the deploy with it. Casting here makes
        -- the record's shape identical for every target.
        statement := format(
            'SELECT id::text AS id_text, %1$I::text AS oid_text FROM %2$I.%3$I '
            || 'WHERE %1$I ~ ''^[1-9][0-9]{0,9}$'' '
            || '  AND EXISTS (SELECT 1 FROM pg_largeobject_metadata m WHERE m.oid::text = %1$I)',
            target.column_name, target.schema_name, target.table_name);

        <<passes>>
        LOOP
            pass := pass + 1;
            -- Counted per PASS and kept from the LAST one. The same unconvertible row is a
            -- candidate on every pass, and adding them up would report one broken row twice.
            pass_failed   := 0;
            pass_denied_o := 0;
            pass_denied_t := 0;
            pass_stuck    := 0;
            pass_other    := 0;

            BEGIN
                FOR candidate IN EXECUTE statement LOOP
                    attempt := 0;
                    <<attempts>>
                    LOOP
                        attempt := attempt + 1;
                        BEGIN
                            -- `AND <column> = <the OID this row was read with>` is what makes
                            -- this safe to run against a live database. Without it, a user edit
                            -- committed between the SELECT and this UPDATE is silently
                            -- overwritten with the old large object: under READ COMMITTED the
                            -- blocked UPDATE re-reads the row and still matches on id alone.
                            -- With it the row no longer matches, nothing is written, and the
                            -- edit survives. `id` is equally load-bearing: without it the
                            -- UPDATE rewrites every row sharing that OID and scans the whole
                            -- table to find them.
                            --
                            -- Literals rather than USING parameters, for the same reason the
                            -- SELECT casts: a USING expression is compiled once for this single
                            -- source location, so a record field whose type changes between
                            -- targets breaks it. %L also leaves `id` compared against its own
                            -- type, so its index is still used.
                            EXECUTE format('UPDATE %1$I.%2$I SET %3$I = convert_from(lo_get(%4$L::oid), ''UTF8'') '
                                           || 'WHERE id = %5$L AND %3$I = %4$L',
                                           target.schema_name, target.table_name, target.column_name,
                                           candidate.oid_text, candidate.id_text);
                            GET DIAGNOSTICS touched = ROW_COUNT;
                            IF touched > 0 THEN
                                repaired := repaired + 1;
                            ELSE
                                skipped := skipped + 1;
                            END IF;
                            EXIT attempts;
                        EXCEPTION WHEN others THEN
                            GET STACKED DIAGNOSTICS err_state = RETURNED_SQLSTATE, err_message = MESSAGE_TEXT;
                            IF err_state = ANY (TRANSIENT) AND attempt < MAX_ATTEMPTS THEN
                                CONTINUE attempts;
                            END IF;
                            pass_failed := pass_failed + 1;
                            IF err_state = '42501' THEN
                                -- Same SQLSTATE, opposite remedies: object ownership is not
                                -- conferred by a table grant, and a missing table grant is not
                                -- fixed by granting objects. Which one it is is asked of the
                                -- CATALOG, not read out of the message: PostgreSQL localises
                                -- error text, so matching 'large object' would send an operator
                                -- on a non-English server to the opposite remedy - the exact
                                -- confusion this branch exists to remove. The table is asked
                                -- first and wins, because a role that has neither cannot be
                                -- helped by granting the objects while it still cannot write
                                -- the row.
                                IF NOT has_table_privilege(
                                       format('%I.%I', target.schema_name, target.table_name), 'UPDATE')
                                   OR NOT has_table_privilege(
                                       format('%I.%I', target.schema_name, target.table_name), 'SELECT') THEN
                                    pass_denied_t := pass_denied_t + 1;
                                ELSE
                                    pass_denied_o := pass_denied_o + 1;
                                END IF;
                            ELSIF err_state = ANY (TRANSIENT) THEN
                                pass_stuck := pass_stuck + 1;
                            ELSIF NOT (err_state = ANY (TOLERATED)) THEN
                                pass_other := pass_other + 1;
                            END IF;
                            -- Only on the last pass: a row that fails here may well be repaired
                            -- by the next one, and a WARNING about it would be a false alarm.
                            IF pass >= MAX_PASSES THEN
                                RAISE WARNING 'V503: %.%.% id=% oid=% could not be recovered after % attempt(s) (%): %',
                                    target.schema_name, target.table_name, target.column_name,
                                    candidate.id_text, candidate.oid_text, attempt, err_state, err_message;
                            END IF;
                            EXIT attempts;
                        END;
                    END LOOP attempts;
                END LOOP;
            EXCEPTION WHEN others THEN
                -- The candidate SELECT itself was refused. It sits outside the per-row handler,
                -- so without this the block aborts with a raw "permission denied for table",
                -- losing the diagnosis the four messages below exist to give.
                GET STACKED DIAGNOSTICS err_state = RETURNED_SQLSTATE, err_message = MESSAGE_TEXT;
                pass_failed := pass_failed + 1;
                IF err_state = '42501' THEN
                    pass_denied_t := pass_denied_t + 1;
                ELSE
                    pass_other := pass_other + 1;
                END IF;
                IF pass >= MAX_PASSES THEN
                    RAISE WARNING 'V503: %.%.% could not even be scanned (%): %',
                        target.schema_name, target.table_name, target.column_name, err_state, err_message;
                END IF;
            END;

            -- Twice, unconditionally. A row an old pod writes WHILE the first scan is walking
            -- the table never shows up as a skip, so a second pass gated on skips would miss
            -- exactly the case the two-release rule above cannot always avoid.
            EXIT passes WHEN pass >= MAX_PASSES;
        END LOOP passes;

        failed        := pass_failed;
        denied_object := denied_object + pass_denied_o;
        denied_table  := denied_table + pass_denied_t;
        stuck         := stuck + pass_stuck;
        other_fatal   := other_fatal + pass_other;

        IF repaired > 0 OR skipped > 0 OR failed > 0 THEN
            RAISE NOTICE 'V503: %.%.% recovered % row(s), % changed underneath, % failure(s)',
                target.schema_name, target.table_name, target.column_name,
                repaired, skipped, failed;
        END IF;

        all_repaired := all_repaired + repaired;
        all_skipped  := all_skipped + skipped;
        all_failed   := all_failed + failed;
    END LOOP;

    -- The three columns where a bare number is an ordinary value: counted, never rewritten.
    FOR target IN
        SELECT * FROM (VALUES
            ('agent', 'agent_tasks',      'result'),
            ('agent', 'agent_tasks',      'error_message'),
            ('agent', 'agent_task_notes', 'content')
        ) AS t(schema_name, table_name, column_name)
    LOOP
        SELECT EXISTS (
            SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = target.schema_name AND c.relname = target.table_name)
          INTO table_exists;
        SELECT count(*) INTO columns_seen
          FROM information_schema.columns
         WHERE table_schema = target.schema_name AND table_name = target.table_name;
        IF columns_seen = 0 THEN
            -- Same distinction as the rewrite loop: a table nobody can see is not a table that
            -- has nothing to report. Unlike the rewrite loop, it is not fatal here.
            IF table_exists THEN
                report_failed := report_failed + 1;
                RAISE WARNING 'V503: %.% exists but none of its columns are visible to this role - its declined-column report is missing, nothing else', target.schema_name, target.table_name;
            END IF;
            CONTINUE;
        END IF;

        CONTINUE WHEN NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = target.schema_name
              AND table_name   = target.table_name
              AND column_name  = target.column_name
              AND data_type IN ('text', 'character varying'));

        -- The same handler the rewrite loop has. Without it a locked table or a missing
        -- SELECT grant aborts the block with a raw error HERE, sixty lines after the curated
        -- messages, and every diagnosis below is lost with it.
        BEGIN
            EXECUTE format(
                'SELECT count(*) FROM %2$I.%3$I t '
                || 'JOIN pg_largeobject_metadata m ON m.oid::text = t.%1$I '
                || 'WHERE t.%1$I ~ ''^[1-9][0-9]{0,9}$''',
                target.column_name, target.schema_name, target.table_name) INTO pending;
        EXCEPTION WHEN others THEN
            -- Counted apart from the rewrite loop's, and never escalated. These three columns
            -- are the ones this migration refuses to touch precisely because under-reaching on
            -- them costs nothing permanent; failing the deploy over an informational count(*),
            -- and rolling back every row that WAS repaired, would be the worst of both.
            GET STACKED DIAGNOSTICS err_state = RETURNED_SQLSTATE, err_message = MESSAGE_TEXT;
            pending := 0;
            report_failed := report_failed + 1;
            RAISE WARNING 'V503: %.%.% could not be counted (%): % - the rows it would have listed are NOT repaired by this migration either way, so this is a report you did not get, not a repair you lost',
                target.schema_name, target.table_name, target.column_name, err_state, err_message;
        END;

        IF pending > 0 THEN
            -- The printed query is the SAME join, not a rewrite of it: a version using
            -- `t.col::oid` raises "out of range" on a wide number and "large object does not
            -- exist" on a dangling one, which is exactly the trap this file refuses to fall
            -- into and would hand the operator an error instead of a list.
            RAISE WARNING 'V503: %.%.% has % row(s) that look like a large-object OID and were NOT rewritten, because a bare number is a legitimate value there. Inspect them and repair by hand: SELECT t.id, t.%, convert_from(lo_get(m.oid), ''UTF8'') FROM %.% t JOIN pg_largeobject_metadata m ON m.oid::text = t.% WHERE t.% ~ ''^[1-9][0-9]{0,9}$'';',
                target.schema_name, target.table_name, target.column_name, pending,
                target.column_name, target.schema_name, target.table_name,
                target.column_name, target.column_name;
        END IF;
    END LOOP;

    RAISE NOTICE 'V503: recovered % row(s) in total, % changed underneath, % failure(s)',
        all_repaired, all_skipped, all_failed;

    IF all_failed > 0 THEN
        RAISE WARNING 'V503: % row(s) still hold a large-object OID and need a manual look', all_failed;
    END IF;
    IF report_failed > 0 THEN
        RAISE WARNING 'V503: % declined-column report(s) could not be produced. Nothing was repaired or lost by that; those columns are never rewritten.', report_failed;
    END IF;

    -- Everything below fails the deploy on purpose. Flyway records an applied version and
    -- nothing re-runs it, so tolerating any of these would consume the one automatic attempt
    -- at this recovery and leave the rows broken with only a WARNING nobody greps. Failing
    -- leaves V503 pending, and the next deploy runs it properly. (The rollback also discards
    -- whatever this run DID repair, which is the price of not having a second attempt.)
    IF denied_object > 0 THEN
        RAISE EXCEPTION 'V503: % row(s) refused with "permission denied for large object". Large-object ownership is per object and is NOT conferred by a table grant, so the repair cannot run as this role. Run it as the role that created the objects (or reassign them) and deploy again; this migration has NOT been recorded as applied.', denied_object;
    END IF;
    IF denied_table > 0 THEN
        RAISE EXCEPTION 'V503: % refusal(s) with a permission error that is NOT about large objects - the role is missing SELECT or UPDATE on the target tables. Grant it and deploy again; this migration has NOT been recorded as applied.', denied_table;
    END IF;
    IF stuck > 0 THEN
        RAISE EXCEPTION 'V503: % row(s) were still locked or contended after % attempts. They are NOT abandoned: this migration has NOT been recorded as applied, so the next deploy retries them. Look for a long-running transaction holding those rows.', stuck, MAX_ATTEMPTS;
    END IF;
    IF unreadable > 0 THEN
        RAISE EXCEPTION 'V503: % target(s) sit in a table that exists but exposes no columns to this role, so the repair could not even look at them. Grant the role access and deploy again; this migration has NOT been recorded as applied.', unreadable;
    END IF;
    IF other_fatal > 0 THEN
        RAISE EXCEPTION 'V503: % failure(s) with an error that is not one of the three tolerated per-row kinds (22021 invalid UTF-8, 22001 too long for the column, 22P05 not representable in this database encoding). It has NOT been recorded as applied; read the warnings above for the SQLSTATE.', other_fatal;
    END IF;
END $$;
