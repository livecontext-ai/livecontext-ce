package com.apimarketplace.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Replays the REAL V503 against rows shaped like production.
 *
 * <p>V503 exists because {@code @Lob} on a String makes Hibernate store the value as a
 * PostgreSQL LARGE OBJECT and leave only its OID in the column. Dropping the annotation
 * makes every existing row read back as that number. It happened to the agent entities in
 * April and June and to both interface entities on 2026-09-19, and the second one blanked
 * every interface in the app and every frame of the interface video renderer.
 *
 * <p>The fixture builds REAL large objects with {@code lo_from_bytea} rather than pretending
 * with a number: the property under test is that the migration reads the object the column
 * points at, and a fake OID would let a broken statement pass.
 *
 * <p>Four things carry equal weight here, not one:
 * <ul>
 *   <li>the repair, on every table it targets, including the ones production had no rows in;</li>
 *   <li>every shape that must be LEFT ALONE, because this migration rewrites user content:
 *       digits that are content, a number no object answers to, a leading-zero string that
 *       still names a live object, a number past the OID range, the three columns where a
 *       bare number is ordinary, and a row a user edits while the migration runs;</li>
 *   <li>what it REPORTS, including the remediation query it prints, which is EXECUTED here
 *       rather than pattern-matched: the counters and that query are the only observable
 *       difference for several load-bearing guards;</li>
 *   <li>every refusal that must FAIL the deploy rather than be tolerated. Nothing re-runs an
 *       applied Flyway version, so a swallowed lock timeout or privilege error is not "retry
 *       later", it is "this row stays broken for ever".</li>
 * </ul>
 *
 * <p>Known gap, disclosed rather than hidden: the fixture hand-rolls its DDL, so it cannot see
 * a column renamed by a real migration. {@link #everyTargetOfTheMigrationIsExercised()} pins
 * both of the migration's lists against the fixture, which catches a target added or moved
 * without a fixture, but only replaying the whole real migration directory would catch the
 * other direction.
 *
 * <p>Lives in migration-service, on {@link FlywayTestSupport}, so it runs against CI's
 * {@code services: postgres} rather than a container: the ARC runners have no Docker socket.
 * It is named in the "Flyway migration guards" step of ci.yml; a regression test that runs in
 * no job is a comment. Every scratch database and role it creates is dropped again, because
 * that CI Postgres and a developer's are the same server for several of these tests.
 */
@DisplayName("V503 puts the content back in columns holding a large-object OID")
class LobOidRecoveryV503MigrationTest {

    /**
     * Databases and roles are cluster-global, and this project's documented mode is several
     * worktrees against ONE local Postgres. Fixed names would have two runs dropping each
     * other's databases and each other's roles half way through.
     */
    private static final String RUN = Long.toHexString(ProcessHandle.current().pid());

    private static final String DB = "lob_oid_recovery_v503_" + RUN;

    private static final String MIGRATION = "V503__recover_lob_oid_text_columns.sql";

    private static final String REAL_HTML = "<div id=\"app\">real html</div>";
    private static final String REAL_CSS = ".card{color:#111}";
    private static final String REAL_JS = "window.__DONE__ = true;";
    private static final String REAL_PROMPT = "You are an email classifier for a support inbox.";
    private static final String SHORT_SKILL = "3 bullets";
    private static final String REAL_TASK = "Draft the weekly report.";
    private static final String SHARED_HTML = "<p>shared by two rows</p>";

    /** Every row the migration repairs across the whole fixture. */
    private static final int EXPECTED_REPAIRS = 11;
    /** The invalid-UTF-8 row once per column, plus the skill too long for its VARCHAR width. */
    private static final int EXPECTED_FAILURES = 4;

    /**
     * The target tables, trimmed to what V503 reasons about, seeded with every row shape they
     * can hold. The tricky OIDs are built in a DO block because their value has to be derived
     * from an object that was really created, or really unlinked, or shared between two rows.
     *
     * <p>Three tables are deliberately awkward, to exercise the guards that skip or refuse
     * rather than fail the deploy: {@code interface_run_snapshots.js_template} is an INTEGER
     * (not a string column), {@code agent_task_recurrences} has no {@code id} for the UPDATE
     * to key on, and {@code skills.instructions} is a VARCHAR(10) too narrow for one of the
     * two objects seeded into it.
     */
    private static final String SCHEMA_AND_ROWS = """
            CREATE SCHEMA interface;
            CREATE SCHEMA agent;

            CREATE TABLE interface.interfaces (
                id            UUID PRIMARY KEY,
                name          TEXT NOT NULL,
                html_template TEXT,
                css_template  TEXT,
                js_template   TEXT
            );

            -- js_template is NOT a string column here: skip that target, do not fail.
            -- html_template is NOT NULL, so a recovered empty object has to be writable.
            CREATE TABLE interface.interface_run_snapshots (
                id            UUID PRIMARY KEY,
                name          TEXT NOT NULL,
                html_template TEXT NOT NULL,
                css_template  TEXT,
                js_template   INTEGER
            );

            CREATE TABLE agent.agents (
                id            UUID PRIMARY KEY,
                name          TEXT NOT NULL,
                system_prompt TEXT
            );

            -- VARCHAR, not TEXT: the migration must accept both, or a column typed this way is
            -- skipped while the run reports success. Narrow on purpose, so one seeded object
            -- does not fit and is refused per row.
            CREATE TABLE agent.skills (
                id           UUID PRIMARY KEY,
                name         TEXT NOT NULL,
                instructions VARCHAR(10)
            );

            CREATE TABLE agent.agent_tasks (
                id            UUID PRIMARY KEY,
                name          TEXT NOT NULL,
                instructions  TEXT,
                result        TEXT,
                error_message TEXT
            );

            CREATE TABLE agent.agent_task_notes (
                id      UUID PRIMARY KEY,
                name    TEXT NOT NULL,
                content TEXT
            );

            -- No `id` for the UPDATE to key on: skip, do not fail.
            CREATE TABLE agent.agent_task_recurrences (
                name         TEXT NOT NULL,
                instructions TEXT
            );

            INSERT INTO agent.agent_task_recurrences (name, instructions)
            VALUES ('recurrence', '885118');

            INSERT INTO interface.interface_run_snapshots (id, name, html_template, css_template, js_template)
            VALUES ('00000000-0000-0000-0000-000000000002', 'snapshot-oid-row',
                    lo_from_bytea(0, convert_to('<div id="app">real html</div>', 'UTF8'))::text,
                    lo_from_bytea(0, convert_to('.card{color:#111}', 'UTF8'))::text,
                    885118);

            INSERT INTO agent.agents (id, name, system_prompt)
            VALUES ('00000000-0000-0000-0000-000000000003', 'agent-oid-row',
                    lo_from_bytea(0, convert_to('You are an email classifier for a support inbox.', 'UTF8'))::text);

            INSERT INTO agent.agent_tasks (id, name, instructions, result, error_message)
            VALUES ('00000000-0000-0000-0000-00000000000d', 'task-oid-row',
                    lo_from_bytea(0, convert_to('Draft the weekly report.', 'UTF8'))::text,
                    lo_from_bytea(0, convert_to('Draft the weekly report.', 'UTF8'))::text,
                    lo_from_bytea(0, convert_to('Draft the weekly report.', 'UTF8'))::text);

            -- A live reference, a number no oid can hold, and a reference whose object is
            -- gone, all in a column the migration only REPORTS on. The query it prints has to
            -- survive all three: built on `col::oid` it raises "out of range" on the second
            -- and "large object does not exist" on the third, which is what the test that runs
            -- that query is there to catch.
            INSERT INTO agent.agent_task_notes (id, name, content)
            VALUES ('00000000-0000-0000-0000-0000000000c1', 'note-oid-row',
                    lo_from_bytea(0, convert_to('a note', 'UTF8'))::text),
                   ('00000000-0000-0000-0000-0000000000c2', 'note-wide-number', '99999999999999999999');

            INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
            VALUES ('00000000-0000-0000-0000-000000000001', 'oid-row',
                    lo_from_bytea(0, convert_to('<div id="app">real html</div>', 'UTF8'))::text,
                    lo_from_bytea(0, convert_to('.card{color:#111}', 'UTF8'))::text,
                    lo_from_bytea(0, convert_to('window.__DONE__ = true;', 'UTF8'))::text);

            -- An EMPTY object is still a reference, and recovering it writes an empty string
            -- rather than leaving a number that reads like content.
            INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
            VALUES ('00000000-0000-0000-0000-00000000002a', 'empty-object',
                    lo_from_bytea(0, ''::bytea)::text, NULL, NULL);

            -- Written after the annotation was dropped: already plain text, must not move.
            INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
            VALUES ('00000000-0000-0000-0000-000000000004', 'already-text',
                    '<div id="app">real html</div>', '.card{color:#111}', 'window.__DONE__ = true;');
            INSERT INTO agent.agents (id, name, system_prompt)
            VALUES ('00000000-0000-0000-0000-00000000000e', 'agent-already-text',
                    'You are an email classifier for a support inbox.');

            -- A template whose content IS digits, answering to no object. User content.
            INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
            VALUES ('00000000-0000-0000-0000-000000000005', 'digits-but-no-object',
                    '2026', '2026', '2026');

            -- Nothing to recover and nothing to break.
            INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
            VALUES ('00000000-0000-0000-0000-000000000006', 'nulls', NULL, NULL, NULL);
            INSERT INTO agent.agents (id, name, system_prompt)
            VALUES ('00000000-0000-0000-0000-000000000007', 'agent-null-prompt', NULL);

            -- Numbers no OID can hold. A migration that cast the COLUMN to oid would raise
            -- "value is out of range for type oid" on these, outside the per-row handler, and
            -- take the whole deploy with it; comparing m.oid::text to the column cannot.
            INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
            VALUES ('00000000-0000-0000-0000-000000000008', 'past-oid-range',
                    '5000000000', '99999999999999999999', '4294967296');

            DO $seed$
            DECLARE
                live_oid   oid;
                dead_oid   oid;
                bad_utf8   oid;
                shared_oid oid;
            BEGIN
                -- Leading zeros. An oid never renders with one, so the live-OID comparison is
                -- what excludes this row; the regex anchor merely keeps the scan cheap.
                live_oid := lo_from_bytea(0, convert_to('<p>not mine</p>', 'UTF8'));
                INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
                VALUES ('00000000-0000-0000-0000-000000000009', 'leading-zero',
                        '0' || live_oid::text, '0' || live_oid::text, '0' || live_oid::text);

                -- An OID whose object is gone: the reference is dangling, and rewriting from
                -- it is impossible. The row keeps the only evidence it ever pointed anywhere.
                dead_oid := lo_from_bytea(0, convert_to('deleted', 'UTF8'));
                PERFORM lo_unlink(dead_oid);
                INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
                VALUES ('00000000-0000-0000-0000-00000000000a', 'dangling-oid',
                        dead_oid::text, dead_oid::text, dead_oid::text);

                -- Bytes that are not valid UTF-8. convert_from RAISES on them, and this is the
                -- row that must not take the whole deploy down with it.
                bad_utf8 := lo_from_bytea(0, '\\xfffefd'::bytea);
                INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
                VALUES ('00000000-0000-0000-0000-00000000000b', 'invalid-utf8',
                        bad_utf8::text, bad_utf8::text, bad_utf8::text);

                -- TWO rows pointing at ONE object. A snapshot is copied from its interface, so
                -- this is the real shape, and it is what the `id` half of the UPDATE predicate
                -- exists for: without it the first UPDATE rewrites both rows at once and the
                -- second finds nothing, which the counters report as 1 repaired + 1 skipped.
                INSERT INTO agent.agent_task_notes (id, name, content)
                VALUES ('00000000-0000-0000-0000-0000000000c3', 'note-dangling', dead_oid::text);

                shared_oid := lo_from_bytea(0, convert_to('<p>shared by two rows</p>', 'UTF8'));
                INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template)
                VALUES ('00000000-0000-0000-0000-00000000001a', 'shared-oid-a', shared_oid::text, NULL, NULL),
                       ('00000000-0000-0000-0000-00000000001b', 'shared-oid-b', shared_oid::text, NULL, NULL);

                -- One skill fits its VARCHAR(10), the other cannot: a per-row refusal that is
                -- NOT the invalid-UTF-8 flavour, so the tolerance is shown to cover a second
                -- kind of error rather than one special case.
                INSERT INTO agent.skills (id, name, instructions)
                VALUES ('00000000-0000-0000-0000-00000000000c', 'skill-fits',
                        lo_from_bytea(0, convert_to('3 bullets', 'UTF8'))::text),
                       ('00000000-0000-0000-0000-00000000000f', 'skill-too-long',
                        lo_from_bytea(0, convert_to('Summarise the whole thread in three bullets.', 'UTF8'))::text);
            END
            $seed$;
            """;

    @Test
    @Timeout(180)
    @DisplayName("an OID column is rewritten with the large object's content, on every table it targets")
    void recoversEveryAffectedTable(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        withDatabase(DB, (postgres, db) -> {
            // The invalid-UTF-8 row, the too-narrow VARCHAR, the awkward targets and the
            // out-of-range numbers must all leave the migration standing.
            assertThatCode(() -> postgres.runFlyway(db, tempDir)).doesNotThrowAnyException();

            assertThat(value(postgres, db, "interface.interfaces", "html_template", "oid-row"))
                    .isEqualTo(REAL_HTML);
            assertThat(value(postgres, db, "interface.interfaces", "css_template", "oid-row"))
                    .isEqualTo(REAL_CSS);
            assertThat(value(postgres, db, "interface.interfaces", "js_template", "oid-row"))
                    .isEqualTo(REAL_JS);

            assertThat(value(postgres, db, "interface.interface_run_snapshots", "html_template", "snapshot-oid-row"))
                    .isEqualTo(REAL_HTML);
            assertThat(value(postgres, db, "interface.interface_run_snapshots", "css_template", "snapshot-oid-row"))
                    .isEqualTo(REAL_CSS);

            assertThat(value(postgres, db, "agent.agents", "system_prompt", "agent-oid-row"))
                    .isEqualTo(REAL_PROMPT);

            // The tables production had no OID rows in. They are covered because a self-hosted
            // install that wrote rows while the annotation was live does, and VARCHAR here is
            // the one a TEXT-only guard would skip.
            assertThat(value(postgres, db, "agent.skills", "instructions", "skill-fits"))
                    .isEqualTo(SHORT_SKILL);
            assertThat(value(postgres, db, "agent.agent_tasks", "instructions", "task-oid-row"))
                    .isEqualTo(REAL_TASK);

            // Both rows that share one object get it, each by its own id.
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "shared-oid-a"))
                    .isEqualTo(SHARED_HTML);
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "shared-oid-b"))
                    .isEqualTo(SHARED_HTML);

            // An empty object is a reference like any other: the number goes, and what it
            // pointed at - nothing - takes its place.
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "empty-object"))
                    .isEmpty();
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("every row that is not a live OID reference is left byte for byte as it was")
    void leavesEverythingElseAlone(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS);

        withDatabase(DB + "_untouched", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);

            // Read the seeded values BEFORE the migration: asserting a SHAPE afterwards
            // ("still digits") would pass for a row rewritten from the WRONG object, which is
            // precisely what over-reach looks like. The migration is therefore applied by hand
            // here, between the seed and the reads.
            String leadingZero = value(postgres, db, "interface.interfaces", "html_template", "leading-zero");
            String danglingOid = value(postgres, db, "interface.interfaces", "html_template", "dangling-oid");
            String invalidUtf8 = value(postgres, db, "interface.interfaces", "html_template", "invalid-utf8");
            String taskResult = value(postgres, db, "agent.agent_tasks", "result", "task-oid-row");
            String taskError = value(postgres, db, "agent.agent_tasks", "error_message", "task-oid-row");
            String noteContent = value(postgres, db, "agent.agent_task_notes", "content", "note-oid-row");
            String tooLongSkill = value(postgres, db, "agent.skills", "instructions", "skill-too-long");
            assertThat(leadingZero).startsWith("0");

            execute(postgres, db, realMigration());

            // Content written after the annotation was dropped, on both halves of the change.
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "already-text"))
                    .isEqualTo(REAL_HTML);
            assertThat(value(postgres, db, "interface.interfaces", "css_template", "already-text"))
                    .isEqualTo(REAL_CSS);
            assertThat(value(postgres, db, "interface.interfaces", "js_template", "already-text"))
                    .isEqualTo(REAL_JS);
            assertThat(value(postgres, db, "agent.agents", "system_prompt", "agent-already-text"))
                    .isEqualTo(REAL_PROMPT);

            // Digits that are user content, not a reference.
            for (String column : List.of("html_template", "css_template", "js_template")) {
                assertThat(value(postgres, db, "interface.interfaces", column, "digits-but-no-object"))
                        .isEqualTo("2026");
            }

            // Nothing there to begin with. The row must still EXIST: a helper that returns null
            // for "no such row" would let a missing fixture row assert as a passing NULL.
            assertThat(rowExists(postgres, db, "interface.interfaces", "nulls")).isTrue();
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "nulls")).isNull();
            assertThat(rowExists(postgres, db, "agent.agents", "agent-null-prompt")).isTrue();
            assertThat(value(postgres, db, "agent.agents", "system_prompt", "agent-null-prompt")).isNull();

            // Past what an oid can hold, in three widths.
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "past-oid-range"))
                    .isEqualTo("5000000000");
            assertThat(value(postgres, db, "interface.interfaces", "css_template", "past-oid-range"))
                    .isEqualTo("99999999999999999999");
            assertThat(value(postgres, db, "interface.interfaces", "js_template", "past-oid-range"))
                    .isEqualTo("4294967296");

            // Exactly what was seeded, not merely "still a number".
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "leading-zero"))
                    .isEqualTo(leadingZero);
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "dangling-oid"))
                    .isEqualTo(danglingOid);
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "invalid-utf8"))
                    .isEqualTo(invalidUtf8);
            assertThat(value(postgres, db, "agent.skills", "instructions", "skill-too-long"))
                    .isEqualTo(tooLongSkill);

            // The three columns where a bare number is an ordinary value are reported, never
            // rewritten: rewriting a task result from a colliding OID cannot be undone.
            assertThat(value(postgres, db, "agent.agent_tasks", "result", "task-oid-row"))
                    .isEqualTo(taskResult);
            assertThat(value(postgres, db, "agent.agent_tasks", "error_message", "task-oid-row"))
                    .isEqualTo(taskError);
            assertThat(value(postgres, db, "agent.agent_task_notes", "content", "note-oid-row"))
                    .isEqualTo(noteContent);

            // The awkward targets are skipped, not failed, and nothing in them moves.
            assertThat(value(postgres, db, "agent.agent_task_recurrences", "instructions", "recurrence"))
                    .isEqualTo("885118");
            assertThat(value(postgres, db, "interface.interface_run_snapshots", "js_template", "snapshot-oid-row"))
                    .isEqualTo("885118");
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("it reports what it did, and the remediation query it prints actually runs")
    void reportsItsOwnCountersAndAWorkingQuery(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS);

        withDatabase(DB + "_counters", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);

            // Run the migration directly so its NOTICEs can be read off the statement. These
            // counters are the only thing that moves for several guards: deleting the live-OID
            // check, or the `id` half of the UPDATE, leaves every asserted VALUE identical.
            List<String> notices = executeAndCollectNotices(postgres, db, realMigration());

            assertThat(notices).anySatisfy(line -> assertThat(line).contains(
                    "V503: recovered " + EXPECTED_REPAIRS + " row(s) in total, 0 changed underneath, "
                            + EXPECTED_FAILURES + " failure(s)"));
            // Four rows on this column: oid-row, the empty object and the two that share one
            // object. Two of them share an OID, so a predicate without `id` reports 3 + 1.
            assertThat(notices).anySatisfy(line -> assertThat(line).contains(
                    "V503: interface.interfaces.html_template recovered 4 row(s), 0 changed underneath, 1 failure(s)"));
            // The VARCHAR refusal is a second, distinct flavour of per-row failure.
            assertThat(notices).anySatisfy(line -> assertThat(line).contains(
                    "V503: agent.skills.instructions recovered 1 row(s), 0 changed underneath, 1 failure(s)"));
            // The awkward targets produce no line at all: they are skipped before any work.
            // A target it declined to look at must not read like one it looked at and found
            // clean, so each skip says which one and why.
            assertThat(notices).anySatisfy(line -> assertThat(line).contains(
                    "V503: interface.interface_run_snapshots.js_template skipped - the column is absent or is not a string column"));
            assertThat(notices).anySatisfy(line -> assertThat(line).contains(
                    "V503: agent.agent_task_recurrences skipped - no id column for the UPDATE to key on"));
            // ...and a skipped target is not counted as work done.
            assertThat(notices).noneSatisfy(line -> assertThat(line).contains(
                    "agent_task_recurrences.instructions recovered"));

            // The declined columns are not just counted: the query printed beside them is
            // EXECUTED here. A version built on `col::oid` raises "out of range" on a wide
            // number and "large object does not exist" on a dangling one, so an operator
            // pasting it would get an error instead of the list it promises.
            String printed = notices.stream()
                    .filter(line -> line.contains("agent.agent_task_notes.content has"))
                    .findFirst()
                    .map(LobOidRecoveryV503MigrationTest::remediationQueryOf)
                    .orElseThrow(() -> new AssertionError("no declined-column warning for agent_task_notes.content"));
            // That column holds a live reference, a number past the oid range and a dangling
            // one, so running the query is load-bearing rather than decorative: the `col::oid`
            // form raises on either of the last two, and it returns ONLY the live row.
            assertThat(queryOneColumn(postgres, db, printed, 3)).containsExactly("a note");

            // The aggregate line an operator greps for, not just the per-column ones.
            assertThat(notices).anySatisfy(line -> assertThat(line).contains(
                    "V503: " + EXPECTED_FAILURES + " row(s) still hold a large-object OID and need a manual look"));
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("an edit committed while it runs survives, and the rest of that row is still repaired")
    void aConcurrentEditSurvives(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS);
        String userEdit = "<p>USER EDIT DURING MIGRATION</p>";

        withDatabase(DB + "_concurrent", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);

            // A user saves an interface while the migration is walking the table. Under READ
            // COMMITTED the migration's UPDATE blocks on this row's lock, then re-reads it;
            // keyed on id alone it would overwrite the edit with the old large object.
            AtomicReference<List<String>> notices = new AtomicReference<>();
            try (Connection editor = connect(postgres, db)) {
                editor.setAutoCommit(false);
                try (Statement statement = editor.createStatement()) {
                    statement.executeUpdate("UPDATE interface.interfaces SET html_template = '"
                            + userEdit + "' WHERE name = 'oid-row'");
                }

                CountDownLatch started = new CountDownLatch(1);
                AtomicReference<Exception> failure = new AtomicReference<>();
                Thread migration = new Thread(() -> {
                    started.countDown();
                    try {
                        notices.set(executeAndCollectNotices(postgres, db, realMigration()));
                    } catch (Exception e) {
                        failure.set(e);
                    }
                });
                migration.start();
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

                // Wait for the migration to be genuinely BLOCKED on this row rather than
                // sleeping a guessed interval: a slow runner would otherwise let the editor
                // commit first, the row would never be a candidate, and every assertion below
                // would pass having tested nothing.
                assertThat(waitForBlockedBackend(postgres, db)).isTrue();
                editor.commit();

                migration.join(TimeUnit.SECONDS.toMillis(120));
                assertThat(migration.isAlive()).isFalse();
                assertThat(failure.get()).isNull();
            }

            // The edit is intact...
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "oid-row"))
                    .isEqualTo(userEdit);
            // ...and the guard is per VALUE, not per row: the columns the user did not touch
            // are still recovered in the same pass.
            assertThat(value(postgres, db, "interface.interfaces", "css_template", "oid-row"))
                    .isEqualTo(REAL_CSS);
            assertThat(value(postgres, db, "interface.interfaces", "js_template", "oid-row"))
                    .isEqualTo(REAL_JS);
            // And it SAYS so: one row changed underneath it, which is the counter that makes
            // "the edit survived" distinguishable from "the row was never a candidate".
            assertThat(notices.get()).anySatisfy(line -> assertThat(line).contains(
                    "V503: interface.interfaces.html_template recovered 3 row(s), 1 changed underneath"));
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("a row locked past the retries FAILS the migration instead of being abandoned")
    void aRowLockedPastTheRetriesFailsTheDeploy(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS);

        withDatabase(DB + "_locked", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);

            // Held for longer than MAX_ATTEMPTS x lock_timeout. Swallowing this would exit 0,
            // Flyway would record V503 as applied, and that interface would stay a number for
            // ever: nothing re-runs an applied version.
            try (Connection holder = connect(postgres, db)) {
                holder.setAutoCommit(false);
                try (Statement statement = holder.createStatement()) {
                    statement.executeUpdate("UPDATE interface.interfaces SET name = name "
                            + "WHERE name = 'oid-row'");
                }

                assertThatThrownBy(() -> execute(postgres, db, realMigration()))
                        .hasMessageContaining("still locked or contended")
                        .hasMessageContaining("has NOT been recorded as applied");

                holder.rollback();
            }

            // Nothing was left half-written by the failed attempt. The locked row proves
            // nothing on its own - it was never writable; these two are in tables the lock does
            // not touch, so they WERE repaired and then rolled back with the failure.
            assertThat(value(postgres, db, "agent.agents", "system_prompt", "agent-oid-row"))
                    .matches("^[0-9]+$");
            assertThat(value(postgres, db, "agent.skills", "instructions", "skill-fits"))
                    .matches("^[0-9]+$");
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("a privilege refusal FAILS the migration, and says which privilege")
    void aPrivilegeRefusalFailsTheDeploy(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS);
        String owner = "v503_no_lo_owner_" + RUN;
        String reader = "v503_read_only_" + RUN;
        String blind = "v503_blind_" + RUN;

        withDatabase(DB + "_denied", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);
            try {
                createRole(postgres, db, owner);
                createRole(postgres, db, reader);
                createRole(postgres, db, blind);
                execute(postgres, db, "GRANT USAGE ON SCHEMA interface, agent TO " + owner + ", " + reader + ", " + blind);
                execute(postgres, db, "GRANT SELECT, UPDATE ON ALL TABLES IN SCHEMA interface TO " + owner);
                execute(postgres, db, "GRANT SELECT, UPDATE ON ALL TABLES IN SCHEMA agent TO " + owner);
                execute(postgres, db, "GRANT SELECT ON ALL TABLES IN SCHEMA interface TO " + reader);
                execute(postgres, db, "GRANT SELECT ON ALL TABLES IN SCHEMA agent TO " + reader);

                // Owns the tables, not the large objects. Object ownership is per object in
                // PostgreSQL and no table grant confers it, so every lo_get is refused.
                assertThatThrownBy(() -> executeAs(postgres, db, owner, realMigration()))
                        .hasMessageContaining("permission denied for large object")
                        .hasMessageContaining("NOT conferred by a table grant")
                        .hasMessageContaining("has NOT been recorded as applied");

                // The SAME SQLSTATE with the opposite remedy: a missing table grant is not
                // fixed by granting objects, so the message must not send the operator there.
                assertThatThrownBy(() -> executeAs(postgres, db, reader, realMigration()))
                        .hasMessageContaining("missing SELECT or UPDATE on the target tables")
                        .hasMessageContaining("has NOT been recorded as applied");

                // Tables that EXIST but expose no column to this role. information_schema is
                // privilege filtered, so without the to_regclass cross-check this run would
                // print the same clean summary as a database with nothing to repair.
                assertThatThrownBy(() -> executeAs(postgres, db, blind, realMigration()))
                        .hasMessageContaining("exposes no columns to this role")
                        .hasMessageContaining("has NOT been recorded as applied");

                // Nothing was half-written under any of them.
                assertThat(value(postgres, db, "interface.interfaces", "html_template", "oid-row"))
                        .matches("^[0-9]+$");
            } finally {
                for (String role : List.of(owner, reader, blind)) {
                    try {
                        execute(postgres, db, "REVOKE ALL ON ALL TABLES IN SCHEMA interface, agent FROM " + role);
                        execute(postgres, db, "REVOKE ALL ON SCHEMA interface, agent FROM " + role);
                        execute(postgres, db, "DROP OWNED BY " + role);
                        execute(postgres, db, "DROP ROLE IF EXISTS " + role);
                    } catch (Exception ignored) {
                        // Best effort: the assertions above are what the test is about.
                    }
                }
            }
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("a row written WHILE the first scan runs is caught by the second pass")
    void aRowWrittenDuringTheFirstScanIsCaught(@TempDir Path tempDir) throws Exception {
        // An old pod, standing in as a trigger: it writes one fresh OID row the first time the
        // migration updates anything. That row is never "skipped" - it did not exist when the
        // scan started - so a second pass gated on skips would miss it, and it would stay a
        // number for ever behind a clean summary.
        Files.writeString(tempDir.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS + """

                CREATE FUNCTION interface.write_like_an_old_pod() RETURNS trigger AS $t$
                BEGIN
                    IF NOT EXISTS (SELECT 1 FROM interface.interfaces WHERE name = 'written-during-pass-1') THEN
                        INSERT INTO interface.interfaces (id, name, html_template)
                        VALUES ('00000000-0000-0000-0000-0000000000e1', 'written-during-pass-1',
                                lo_from_bytea(0, convert_to('<p>written by an old pod</p>', 'UTF8'))::text);
                    END IF;
                    RETURN NULL;
                END
                $t$ LANGUAGE plpgsql;

                CREATE TRIGGER write_like_an_old_pod AFTER UPDATE ON interface.interfaces
                FOR EACH STATEMENT EXECUTE FUNCTION interface.write_like_an_old_pod();
                """);

        withDatabase(DB + "_latewrite", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);

            execute(postgres, db, realMigration());

            assertThat(value(postgres, db, "interface.interfaces", "html_template", "written-during-pass-1"))
                    .isEqualTo("<p>written by an old pod</p>");
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("a row locked only briefly is retried and repaired, not counted as a failure")
    void aBrieflyLockedRowIsRetried(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS);

        withDatabase(DB + "_retry", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);

            // Held for ~12 s, which is chosen to separate the retry loop from the second pass
            // rather than to be merely "a while". With three attempts of 5 s the row is taken
            // on the THIRD (10-15 s), so the first pass repairs it. With one attempt the first
            // pass gives up at 5 s AND the second pass gives up at 10 s, both before the lock
            // goes, so the migration raises "still locked or contended". Shorter holds let the
            // second pass rescue the row and the retry loop is then pinned by nothing.
            AtomicReference<Exception> failure = new AtomicReference<>();
            try (Connection holder = connect(postgres, db)) {
                holder.setAutoCommit(false);
                try (Statement statement = holder.createStatement()) {
                    statement.executeUpdate("UPDATE interface.interfaces SET name = name WHERE name = 'oid-row'");
                }

                CountDownLatch started = new CountDownLatch(1);
                Thread migration = new Thread(() -> {
                    started.countDown();
                    try {
                        execute(postgres, db, realMigration());
                    } catch (Exception e) {
                        failure.set(e);
                    }
                });
                migration.start();
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(waitForBlockedBackend(postgres, db)).isTrue();

                Thread.sleep(12_000);
                holder.rollback();

                migration.join(TimeUnit.SECONDS.toMillis(120));
                assertThat(migration.isAlive()).isFalse();
            }

            assertThat(failure.get()).isNull();
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "oid-row"))
                    .isEqualTo(REAL_HTML);
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("an error that is not one of the three tolerated kinds FAILS the deploy")
    void aNonRowLevelErrorFailsTheDeploy(@TempDir Path tempDir) throws Exception {
        // Exactly three refusals are properties of ONE row: 22021 (the object's bytes are not
        // valid UTF-8), 22001 (the value is too long for the column) and 22P05 (its characters
        // have no representation in this database's encoding - a property of that row's content,
        // since ASCII rows beside it convert fine). Everything else fails the deploy, and the
        // trigger below stands in for that class with a deterministic P0001.
        Files.writeString(tempDir.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS + """

                CREATE FUNCTION interface.refuse_every_write() RETURNS trigger AS $t$
                BEGIN
                    RAISE EXCEPTION 'synthetic refusal that is not about this row';
                END
                $t$ LANGUAGE plpgsql;

                CREATE TRIGGER refuse_every_write BEFORE UPDATE ON interface.interfaces
                FOR EACH ROW EXECUTE FUNCTION interface.refuse_every_write();
                """);

        withDatabase(DB + "_notolerate", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);

            assertThatThrownBy(() -> execute(postgres, db, realMigration()))
                    .hasMessageContaining("not one of the three tolerated per-row kinds")
                    .hasMessageContaining("has NOT been recorded as applied");

            // And nothing was left half-written. Asserting the blocked row alone would prove
            // nothing - it was never writable. These two are in tables the trigger does not
            // touch, so they WERE repaired and then rolled back with the failure.
            assertThat(value(postgres, db, "agent.agents", "system_prompt", "agent-oid-row"))
                    .matches("^[0-9]+$");
            assertThat(value(postgres, db, "agent.agent_tasks", "instructions", "task-oid-row"))
                    .matches("^[0-9]+$");
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("a replay recovers nothing new, and still catches a row written later")
    void replayIsANoOpAndStillSweeps(@TempDir Path tempDir) throws Exception {
        writeFixture(tempDir);

        withDatabase(DB + "_replay", (postgres, db) -> {
            postgres.runFlyway(db, tempDir);

            // Flyway will not replay an applied version, so the statement is re-run directly:
            // what is under test is the SQL's idempotence, not Flyway's bookkeeping. The
            // repaired rows now hold text, which no longer matches the digits-only guard. The
            // failures are the SAME two unconvertible rows: a row that cannot be converted
            // stays a candidate for ever and is retried on every replay, which is right - it is
            // the only trace of where its content is.
            List<String> replay = executeAndCollectNotices(postgres, db, realMigration());
            assertThat(replay).anySatisfy(line -> assertThat(line).contains(
                    "V503: recovered 0 row(s) in total, 0 changed underneath, "
                            + EXPECTED_FAILURES + " failure(s)"));
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "oid-row"))
                    .isEqualTo(REAL_HTML);

            // A row written by something still carrying the old mapping is swept by the same
            // statement. Nothing re-runs it automatically, which is why the header tells a
            // future removal to deploy first and repair after.
            execute(postgres, db,
                    "INSERT INTO interface.interfaces (id, name, html_template, css_template, js_template) "
                    + "VALUES ('00000000-0000-0000-0000-0000000000ff','written-later',"
                    + "lo_from_bytea(0, convert_to('<div id=\"app\">real html</div>', 'UTF8'))::text, NULL, NULL)");
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "written-later"))
                    .matches("^[0-9]+$");

            execute(postgres, db, realMigration());
            assertThat(value(postgres, db, "interface.interfaces", "html_template", "written-later"))
                    .isEqualTo(REAL_HTML);
        });
    }

    @Test
    @Timeout(180)
    @DisplayName("a database where a target table does not exist migrates without failing")
    void toleratesAMissingTable(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("V1__seed.sql"), "CREATE SCHEMA agent;\n"
                + "CREATE TABLE agent.agents (id UUID PRIMARY KEY, name TEXT NOT NULL, system_prompt TEXT);\n");
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION), tempDir.resolve(MIGRATION));

        withDatabase(DB + "_partial", (postgres, db) -> {
            // The interface schema is absent here, and so are five of the six agent tables. A
            // migration that assumed them would abort, and migration-service runs Flyway for
            // EVERY schema: one absent table would stop the whole deploy. An ABSENT table is
            // not the same as an unreadable one, which fails (see the privilege test).
            assertThatCode(() -> postgres.runFlyway(db, tempDir)).doesNotThrowAnyException();
        });
    }

    @Test
    @DisplayName("both of the migration's lists are exercised, so a target cannot move or appear untested")
    void everyTargetOfTheMigrationIsExercised() throws Exception {
        String sql = realMigration();
        // The two lists are read separately. One regex over the whole file would let a column
        // move from "declined" to "rewritten" - the one change that silently turns a safe
        // report into an irreversible rewrite - without any assertion noticing.
        Set<String> rewritten = targetsBetween(sql, "AS t(schema_name, table_name, column_name)", 0);
        Set<String> declined = targetsBetween(sql, "AS t(schema_name, table_name, column_name)", 1);

        assertThat(rewritten).hasSize(10).contains("interface.interfaces.html_template");
        assertThat(declined).containsExactlyInAnyOrder(
                "agent.agent_tasks.result", "agent.agent_tasks.error_message",
                "agent.agent_task_notes.content");

        for (String target : rewritten) {
            String[] parts = target.split("\\.");
            String create = "CREATE TABLE " + parts[0] + "." + parts[1] + " (";
            assertThat(SCHEMA_AND_ROWS).as("fixture declares %s", target).contains(create);
            // Inside THAT table's CREATE, not anywhere in the fixture: `instructions` exists on
            // two tables, so a loose contains() would pass for a column the fixture never
            // declares on the table the migration names.
            int start = SCHEMA_AND_ROWS.indexOf(create);
            String body = SCHEMA_AND_ROWS.substring(start, SCHEMA_AND_ROWS.indexOf(");", start));
            assertThat(body).as("fixture declares %s on its own table", target).contains(parts[2]);
        }
    }

    /** The target tuples of the n-th VALUES list in the migration. */
    private static Set<String> targetsBetween(String sql, String listEnd, int index) {
        String[] chunks = sql.split(Pattern.quote(listEnd));
        assertThat(chunks).as("the migration still has two target lists").hasSizeGreaterThan(index + 1);
        Set<String> targets = new LinkedHashSet<>();
        Matcher m = Pattern.compile("\\('(interface|agent)',\\s*'(\\w+)',\\s*'(\\w+)'\\)").matcher(chunks[index]);
        while (m.find()) {
            targets.add(m.group(1) + "." + m.group(2) + "." + m.group(3));
        }
        return targets;
    }

    /** The SELECT the declined-column warning tells an operator to paste. */
    private static String remediationQueryOf(String warning) {
        int start = warning.indexOf("SELECT t.id");
        assertThat(start).as("the warning carries a remediation query").isGreaterThan(-1);
        return warning.substring(start, warning.indexOf(';', start) + 1);
    }

    private static String realMigration() throws Exception {
        return Files.readString(Path.of("src/main/resources/db/migration/" + MIGRATION));
    }

    private static void writeFixture(Path directory) throws Exception {
        Files.writeString(directory.resolve("V1__seed_lob_columns.sql"), SCHEMA_AND_ROWS);
        // The harness reads only this directory, so the real migration is copied in: a test
        // that forgot it would assert against untouched rows and fail loudly, not pass.
        Files.copy(Path.of("src/main/resources/db/migration/" + MIGRATION),
                directory.resolve(MIGRATION));
    }

    @FunctionalInterface
    private interface DatabaseBody {
        void run(FlywayTestSupport.PostgresTarget postgres, String db) throws Exception;
    }

    /**
     * Runs the body against a scratch database and DROPS it afterwards. CI's Postgres is a
     * service container per job, but a developer's is shared and long-lived, and this class
     * would otherwise leave eight databases behind on every run.
     */
    private static void withDatabase(String db, DatabaseBody body) throws Exception {
        try (FlywayTestSupport.PostgresTarget postgres = FlywayTestSupport.openPostgres()) {
            postgres.createDatabase(db);
            try {
                body.run(postgres, db);
            } finally {
                try (var connection = DriverManager.getConnection(
                             postgres.jdbcUrl("postgres"), postgres.username(), postgres.password());
                     Statement statement = connection.createStatement()) {
                    statement.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
                } catch (Exception ignored) {
                    // A container that is going away anyway; the assertions are the point.
                }
            }
        }
    }

    private static void createRole(FlywayTestSupport.PostgresTarget p, String db, String role) throws Exception {
        execute(p, db, "DO $r$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '" + role + "') "
                + "THEN CREATE ROLE " + role + " LOGIN PASSWORD 'v503'; END IF; END $r$;");
    }

    private static Connection connect(FlywayTestSupport.PostgresTarget p, String db) throws Exception {
        return DriverManager.getConnection(p.jdbcUrl(db), p.username(), p.password());
    }

    /** True once some backend in this database is waiting on a lock, i.e. the migration blocked. */
    private static boolean waitForBlockedBackend(FlywayTestSupport.PostgresTarget p, String db)
            throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            try (var connection = connect(p, db);
                 Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                         + "AND wait_event_type = 'Lock' AND state = 'active'")) {
                if (rs.next() && rs.getInt(1) > 0) return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static String value(FlywayTestSupport.PostgresTarget p, String db, String table,
                                String column, String name) throws Exception {
        try (var connection = connect(p, db);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT " + column + " FROM " + table + " WHERE name = '" + name + "'")) {
            if (!rs.next()) return null;
            return rs.getString(1);
        }
    }

    private static boolean rowExists(FlywayTestSupport.PostgresTarget p, String db, String table, String name)
            throws Exception {
        try (var connection = connect(p, db);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT 1 FROM " + table + " WHERE name = '" + name + "'")) {
            return rs.next();
        }
    }

    private static List<String> queryOneColumn(FlywayTestSupport.PostgresTarget p, String db,
                                               String sql, int column) throws Exception {
        List<String> values = new ArrayList<>();
        try (var connection = connect(p, db);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) values.add(rs.getString(column));
        }
        return values;
    }

    private static void execute(FlywayTestSupport.PostgresTarget p, String db, String sql) throws Exception {
        try (var connection = connect(p, db);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void executeAs(FlywayTestSupport.PostgresTarget p, String db, String role, String sql)
            throws Exception {
        try (var connection = DriverManager.getConnection(p.jdbcUrl(db), role, "v503");
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Every NOTICE and WARNING the statement raised, in order. */
    private static List<String> executeAndCollectNotices(FlywayTestSupport.PostgresTarget p, String db,
                                                         String sql) throws Exception {
        List<String> notices = new ArrayList<>();
        try (var connection = connect(p, db);
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                notices.add(warning.getMessage());
            }
        }
        return notices;
    }
}
