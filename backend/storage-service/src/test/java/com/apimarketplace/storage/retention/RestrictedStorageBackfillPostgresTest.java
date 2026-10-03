package com.apimarketplace.storage.retention;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.testsupport.ScratchPostgres;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The storage backfill and the restricted retention sweep on real Postgres (CASA LC-066, LC-011).
 *
 * <p>Besides each Gmail / Drive step output, the backfill tags every other payload of the same RUN,
 * mirroring the write path ({@code StepPayloadService.isRunRestricted}). That scope is deliberate:
 * a regression review (2026-09-29) tried narrowing it to one trigger fire and an audit found data
 * that crosses fires of one run (interface actions, in-memory trigger and split caches, table
 * triggers dispatched to another replica), so a per-fire taint let Gmail content reach later fires
 * untagged. These pin the run-level rule and the retention deadline the backfill gives.
 *
 * <p>LC-011: the retention deadline lives in {@code retention_expires_at}, added by the real V562
 * migration file (applied below), and never in {@code expires_at}, which releases before V562
 * enforce on their own. The rollback-safety test runs the previous release's own expiry queries
 * against the backfilled table and expects them to select nothing.
 *
 * <p>Real SQL because the rule is the SQL: a mocked JdbcTemplate cannot see a WHERE clause that
 * lost a condition. Runs against the CI job's scratch Postgres ({@code STORAGE_TEST_PG_URL}); on a
 * laptop with none it is skipped, on CI it refuses to skip (see {@link ScratchPostgres}). Each test
 * runs in one transaction rolled back afterwards, schema included: the scratch database is shared
 * with other classes of the job, and a {@code storage} schema dropped or left behind would break them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("RestrictedStorageBackfill and the restricted retention sweep (real Postgres)")
class RestrictedStorageBackfillPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "STORAGE_TEST_PG",
            "it is the only proof, on real SQL, of which payloads the restricted backfill tags");

    /** Surefire runs with the module directory as working directory. */
    private static final Path V562 = Path.of(
            "../migration-service/src/main/resources/db/migration/V562__storage_retention_expires_at.sql");
    private static final Path V564 = Path.of(
            "../migration-service/src/main/resources/db/migration/V564__storage_restricted_run_since.sql");

    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    private Connection connection;
    private JdbcTemplate jdbc;

    @BeforeAll
    void requireDatabase() {
        DB.require();
    }

    @BeforeEach
    void openRolledBackSchema() throws Exception {
        connection = DriverManager.getConnection(DB.url(), DB.user(), DB.password());
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        // The columns the backfill and the sweep read or write as they were BEFORE V562, rebuilt
        // inside this test's transaction; V562 itself then adds retention_expires_at.
        jdbc.execute("DROP SCHEMA IF EXISTS storage CASCADE");
        jdbc.execute("CREATE SCHEMA storage");
        jdbc.execute("""
                CREATE TABLE storage.storage (
                    id               UUID PRIMARY KEY,
                    run_id           VARCHAR(255),
                    epoch            INTEGER NOT NULL DEFAULT 0,
                    storage_type     VARCHAR(32) NOT NULL,
                    s3_key           VARCHAR(512),
                    data             JSONB,
                    data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL',
                    expires_at       TIMESTAMPTZ,
                    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                    tenant_id        VARCHAR(255) NOT NULL DEFAULT 't1',
                    organization_id  VARCHAR(255),
                    size_bytes       INTEGER NOT NULL DEFAULT 0,
                    source_type      VARCHAR(50),
                    status           VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                )""");
        applyV562();
        applyMigration(V564);
    }

    /**
     * Runs the real V562 file. Its index builds are CONCURRENTLY (outside a transaction in
     * production); inside this rolled-back transaction they are built plainly, same definitions.
     */
    private void applyV562() throws IOException {
        applyMigration(V562);
    }

    private void applyMigration(Path migration) throws IOException {
        String withoutComments = Files.readAllLines(migration).stream()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"))
                .replace("CONCURRENTLY ", "");
        for (String statement : withoutComments.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }

    @AfterEach
    void rollBack() throws SQLException {
        connection.rollback();
        connection.close();
    }

    private UUID payload(String runId, int epoch, String json) {
        return payload(runId, epoch, json, Instant.parse("2026-09-01T00:00:00Z").plusSeconds(epoch));
    }

    private UUID payload(String runId, int epoch, String json, Instant createdAt) {
        UUID id = UUID.randomUUID();
        // The columns a pod of the previous release writes: no retention_expires_at.
        jdbc.update("INSERT INTO storage.storage (id, run_id, epoch, storage_type, data, created_at) "
                + "VALUES (?, ?, ?, 'JSON', CAST(? AS jsonb), ?)", id, runId, epoch, json,
                Timestamp.from(createdAt));
        return id;
    }

    private String sensitivityOf(UUID id) {
        return jdbc.queryForObject("SELECT data_sensitivity FROM storage.storage WHERE id = ?", String.class, id);
    }

    private RestrictedStorageBackfill backfill() {
        return new RestrictedStorageBackfill(jdbc, true, 30, 1000);
    }

    /** A row tagged RESTRICTED as a previous release wrote it: caller expiry only, no deadline. */
    private UUID restrictedRow(Instant createdAt, Instant expiresAt) {
        return restrictedRow(createdAt, expiresAt, null);
    }

    private UUID restrictedRow(Instant createdAt, Instant expiresAt, Instant retentionExpiresAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO storage.storage (id, run_id, storage_type, data, data_sensitivity, "
                        + "expires_at, retention_expires_at, created_at) "
                        + "VALUES (?, 'run-r', 'JSON', CAST('{}' AS jsonb), 'RESTRICTED', ?, ?, ?)",
                id, ts(expiresAt), ts(retentionExpiresAt), Timestamp.from(createdAt));
        return id;
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instantOf(String column, UUID id) {
        Timestamp ts = jdbc.queryForObject("SELECT " + column + " FROM storage.storage WHERE id = ?", Timestamp.class, id);
        return ts == null ? null : ts.toInstant();
    }

    private Instant expiryOf(UUID id) {
        return instantOf("expires_at", id);
    }

    private Instant retentionOf(UUID id) {
        return instantOf("retention_expires_at", id);
    }

    private boolean exists(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM storage.storage WHERE id = ?", Integer.class, id) == 1;
    }

    private RestrictedStorageRetentionSweeper sweeper(boolean armed) {
        return new RestrictedStorageRetentionSweeper(jdbc, mock(FileStorageService.class),
                mock(StorageBreakdownService.class), mock(QuotaOperations.class), armed, 500);
    }

    // --- V562 ---------------------------------------------------------------------------------

    @Test
    @DisplayName("V562 adds a nullable retention_expires_at with no default: a previous release's INSERT still works and leaves it NULL")
    void v560ColumnIsNullableWithNoDefault() {
        UUID oldPodRow = payload("run-old", 1, "{}");

        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_schema = 'storage' AND table_name = 'storage' AND column_name = 'retention_expires_at'",
                String.class)).isEqualTo("YES");
        assertThat(jdbc.queryForObject("SELECT column_default IS NULL FROM information_schema.columns "
                + "WHERE table_schema = 'storage' AND table_name = 'storage' AND column_name = 'retention_expires_at'",
                Boolean.class)).isTrue();
        assertThat(retentionOf(oldPodRow)).isNull();
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'storage'", String.class))
                .contains("idx_storage_restricted_deadline", "idx_storage_restricted_retention_unset");
    }

    @Test
    @DisplayName("V562: the sweep's candidate query can be served by idx_storage_restricted_deadline (same deadline expression)")
    void sweepQueryMatchesTheDeadlineIndex() {
        jdbc.execute("SET LOCAL enable_seqscan = off");

        List<String> plan = jdbc.queryForList("EXPLAIN SELECT id, s3_key FROM storage.storage "
                + "WHERE data_sensitivity = 'RESTRICTED' AND " + RestrictedStorageRetentionSweeper.DEADLINE
                + " < now() ORDER BY " + RestrictedStorageRetentionSweeper.DEADLINE + ", id LIMIT 500", String.class);

        assertThat(String.join("\n", plan)).contains("idx_storage_restricted_deadline");
    }

    // --- retention catch-up ---------------------------------------------------------------------

    @Test
    @DisplayName("LC-011 regression: a RESTRICTED row with no retention deadline gets created_at + window; NORMAL and stamped rows are left alone")
    void lc011CatchUpStampsRestrictedRowsWrittenWithoutDeadline() {
        Instant longAgo = NOW.minus(Duration.ofDays(90));
        Instant yesterday = NOW.minus(Duration.ofDays(1));
        Instant alreadyStamped = NOW.plus(Duration.ofDays(7));
        UUID oldGmail = restrictedRow(longAgo, null);
        UUID recentGmail = restrictedRow(yesterday, null);
        UUID stamped = restrictedRow(longAgo, null, alreadyStamped);
        UUID normal = payload("run-n", 1, "{\"summary\":\"ordinary\"}", longAgo);

        RestrictedStorageBackfill.Report report = backfill().run(NOW);

        // The window counts from when the row was written, not from now: arming the purge must
        // reach the backlog already older than the window.
        assertThat(retentionOf(oldGmail)).isEqualTo(longAgo.plus(Duration.ofDays(30)));
        assertThat(retentionOf(recentGmail)).isEqualTo(yesterday.plus(Duration.ofDays(30)));
        assertThat(retentionOf(stamped)).isEqualTo(alreadyStamped);
        assertThat(retentionOf(normal)).isNull();
        assertThat(sensitivityOf(normal)).isEqualTo("NORMAL");
        assertThat(report.retentionDeadlinesStamped()).isEqualTo(2);
    }

    @Test
    @DisplayName("LC-011 rollback safety: the catch-up and the tagging never write expires_at, so the previous release's expiry queries select nothing")
    void lc011CatchUpLeavesExpiresAtForAPreviousRelease() {
        Instant callerTtl = NOW.plus(Duration.ofDays(400));
        UUID gmailBacklog = restrictedRow(NOW.minus(Duration.ofDays(90)), null);
        UUID gmailWithTtl = restrictedRow(NOW.minus(Duration.ofDays(90)), callerTtl);
        UUID newlyTagged = payload("run-1", 1, "{\"metadata\":{\"iconSlug\":\"gmail\"}}", NOW.minus(Duration.ofDays(60)));

        backfill().run(NOW);

        // The deadline went to its own column...
        assertThat(retentionOf(gmailBacklog)).isBefore(NOW);
        assertThat(sensitivityOf(newlyTagged)).isEqualTo("RESTRICTED");
        // ...and expires_at is byte for byte what it was: null stays null, a TTL stays that TTL.
        assertThat(expiryOf(gmailBacklog)).isNull();
        assertThat(expiryOf(newlyTagged)).isNull();
        assertThat(expiryOf(gmailWithTtl)).isEqualTo(callerTtl);

        // The previous release's queries, verbatim in SQL, years later: the hourly generic cleanup
        // with the purge off (findExpiredStoragesIncludingRestricted), its read check, and its
        // armed sweep. None may select the backlog the catch-up just processed.
        Timestamp yearsLater = Timestamp.from(NOW.plus(Duration.ofDays(365)));
        assertThat(jdbc.queryForList("SELECT id FROM storage.storage WHERE expires_at < ? AND status = 'ACTIVE'",
                UUID.class, yearsLater)).isEmpty();
        assertThat(jdbc.queryForList("SELECT id FROM storage.storage WHERE data_sensitivity = 'RESTRICTED' "
                + "AND expires_at < ?", UUID.class, yearsLater)).isEmpty();
    }

    @Test
    @DisplayName("LC-011: the catch-up is idempotent, a second run stamps nothing and moves no deadline")
    void lc011CatchUpIsIdempotent() {
        UUID gmail = restrictedRow(NOW.minus(Duration.ofDays(40)), null);
        RestrictedStorageBackfill job = backfill();
        job.run(NOW);
        Instant first = retentionOf(gmail);

        RestrictedStorageBackfill.Report second = job.run(NOW.plus(Duration.ofDays(1)));

        assertThat(second.retentionDeadlinesStamped()).isZero();
        assertThat(retentionOf(gmail)).isEqualTo(first);
    }

    @Test
    @DisplayName("LC-011: the catch-up works through several batches")
    void lc011CatchUpBatches() {
        for (int i = 0; i < 5; i++) {
            restrictedRow(NOW.minus(Duration.ofDays(60 + i)), null);
        }

        int stamped = new RestrictedStorageBackfill(jdbc, true, 30, 2).stampMissingRetentionDeadlines(NOW);

        assertThat(stamped).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM storage.storage WHERE retention_expires_at IS NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM storage.storage WHERE expires_at IS NOT NULL",
                Integer.class)).isZero();
    }

    // --- the sweep ------------------------------------------------------------------------------

    @Test
    @DisplayName("LC-011: disarmed, the sweep deletes nothing; armed, it deletes by retention deadline, and by the caller's TTL when that comes first")
    void lc011SweepDeletesByRetentionDeadlineOnlyOnceArmed() {
        UUID writtenWhileDisarmed = restrictedRow(NOW.minus(Duration.ofDays(45)), null);
        UUID insideTheWindow = restrictedRow(NOW.minus(Duration.ofDays(5)), null);
        UUID callerTtlFirst = restrictedRow(NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofHours(1)));
        UUID oldNormal = payload("run-n", 1, "{\"summary\":\"ordinary\"}", NOW.minus(Duration.ofDays(400)));
        backfill().run(NOW);

        RestrictedStorageRetentionSweeper.SweepReport disarmed = sweeper(false).sweep(NOW);

        assertThat(disarmed.rowsDeleted()).isZero();
        assertThat(exists(writtenWhileDisarmed)).isTrue();
        assertThat(exists(callerTtlFirst)).isTrue();

        RestrictedStorageRetentionSweeper.SweepReport armed = sweeper(true).sweep(NOW);

        assertThat(armed.rowsDeleted()).isEqualTo(2);
        assertThat(exists(writtenWhileDisarmed)).isFalse();
        assertThat(exists(callerTtlFirst)).isFalse();
        assertThat(exists(insideTheWindow)).isTrue();
        assertThat(exists(oldNormal)).isTrue();
    }

    @Test
    @DisplayName("the armed sweep pages through several batches by (deadline, id)")
    void armedSweepPagesThroughBatches() {
        for (int i = 0; i < 5; i++) {
            restrictedRow(NOW.minus(Duration.ofDays(60)), null, NOW.minus(Duration.ofDays(i + 1)));
        }

        RestrictedStorageRetentionSweeper.SweepReport report = new RestrictedStorageRetentionSweeper(jdbc,
                mock(FileStorageService.class), mock(StorageBreakdownService.class), mock(QuotaOperations.class),
                true, 2).sweep(NOW);

        assertThat(report.rowsDeleted()).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM storage.storage", Integer.class)).isZero();
    }

    private Instant recordedRestrictedSince(String runId) {
        List<Timestamp> ts = jdbc.queryForList(
                "SELECT restricted_since FROM storage.restricted_run_since WHERE run_id = ?", Timestamp.class, runId);
        return ts.isEmpty() ? null : ts.get(0).toInstant();
    }

    private Instant oldestRestrictedRowLeft(String runId) {
        Timestamp ts = jdbc.queryForObject("SELECT MIN(created_at) FROM storage.storage "
                + "WHERE run_id = ? AND data_sensitivity = 'RESTRICTED'", Timestamp.class, runId);
        return ts == null ? null : ts.toInstant();
    }

    @Test
    @DisplayName("LC-066 retention skew: a partly purged run keeps its first restricted moment, not the oldest row left")
    void partlyPurgedRunKeepsItsRestrictedSince() {
        // A run written by a previous release (no record): its oldest restricted row is past its
        // deadline, a later one is still inside the window.
        Instant first = NOW.minus(Duration.ofDays(45));
        UUID oldest = restrictedRow(first, null, NOW.minus(Duration.ofDays(15)));
        UUID recent = restrictedRow(NOW.minus(Duration.ofDays(5)), null, NOW.plus(Duration.ofDays(25)));
        assertThat(recordedRestrictedSince("run-r")).isNull();

        assertThat(sweeper(true).sweep(NOW).rowsDeleted()).isEqualTo(1);

        assertThat(exists(oldest)).isFalse();
        assertThat(exists(recent)).isTrue();
        // What the rows alone now say moved five weeks later; the record keeps the truth.
        assertThat(oldestRestrictedRowLeft("run-r")).isEqualTo(NOW.minus(Duration.ofDays(5)));
        assertThat(recordedRestrictedSince("run-r")).isEqualTo(first);
    }

    @Test
    @DisplayName("LC-066: the sweep moves a recorded moment only EARLIER (a row tagged later by the backfill can be older)")
    void sweepOnlyMovesTheRecordEarlier() {
        Instant recorded = NOW.minus(Duration.ofDays(40));
        jdbc.update("INSERT INTO storage.restricted_run_since (run_id, restricted_since) VALUES ('run-r', ?)",
                Timestamp.from(recorded));
        restrictedRow(NOW.minus(Duration.ofDays(35)), null, NOW.minus(Duration.ofDays(1)));
        sweeper(true).sweep(NOW);
        assertThat(recordedRestrictedSince("run-r")).isEqualTo(recorded); // a later row never moves it

        restrictedRow(NOW.minus(Duration.ofDays(60)), null, NOW.minus(Duration.ofDays(1)));
        sweeper(true).sweep(NOW);
        assertThat(recordedRestrictedSince("run-r")).isEqualTo(NOW.minus(Duration.ofDays(60)));
    }

    @Test
    @DisplayName("V564 backfills every run that already holds restricted rows from its oldest one; NORMAL-only runs get nothing")
    void v562BackfillsExistingRuns() throws IOException {
        Instant first = NOW.minus(Duration.ofDays(20));
        restrictedRow(first, null);
        restrictedRow(NOW.minus(Duration.ofDays(2)), null);
        payload("run-n", 1, "{}");

        applyMigration(V564); // replayed: idempotent

        assertThat(recordedRestrictedSince("run-r")).isEqualTo(first);
        assertThat(recordedRestrictedSince("run-n")).isNull();
    }

    // --- tagging --------------------------------------------------------------------------------

    @Test
    @DisplayName("every payload of a run from its Gmail output on is tagged, whatever its fire; other runs are untouched")
    void wholeRunIsTagged() {
        UUID gmail = payload("run-1", 1, "{\"metadata\":{\"iconSlug\":\"gmail\"},\"messages\":[\"wire\"]}");
        UUID sameFire = payload("run-1", 1, "{\"summary\":\"the CEO approved the wire\"}");
        UUID laterFire = payload("run-1", 2, "{\"summary\":\"quoted from an interface of fire 1\"}");
        UUID otherRun = payload("run-2", 1, "{\"summary\":\"unrelated\"}");

        RestrictedStorageBackfill.Report report = backfill().run(Instant.now());

        assertThat(sensitivityOf(gmail)).isEqualTo("RESTRICTED");
        assertThat(sensitivityOf(sameFire)).isEqualTo("RESTRICTED");
        assertThat(sensitivityOf(laterFire)).isEqualTo("RESTRICTED");
        assertThat(sensitivityOf(otherRun)).isEqualTo("NORMAL");
        assertThat(report.stepOutputs()).isEqualTo(1);
        assertThat(report.runPayloads()).isEqualTo(2);
    }

    @Test
    @DisplayName("regression: a run's history from BEFORE it first read Gmail keeps its class; only later payloads are tagged")
    void historyBeforeTheFirstGmailOutputIsLeftAlone() {
        Instant firstGmail = Instant.parse("2026-09-20T10:00:00Z");
        UUID monthsBefore = payload("run-prod", 1, "{\"summary\":\"july report\"}", firstGmail.minusSeconds(86_400 * 60));
        UUID gmail = payload("run-prod", 40, "{\"metadata\":{\"iconSlug\":\"gmail\"}}", firstGmail);
        UUID after = payload("run-prod", 41, "{\"summary\":\"quoted mail\"}", firstGmail.plusSeconds(60));

        backfill().run(Instant.now());

        // The write path tags every LATER payload; the backfill must not reach further back and
        // put months of ordinary history on a 30-day purge.
        assertThat(sensitivityOf(monthsBefore)).isEqualTo("NORMAL");
        assertThat(sensitivityOf(gmail)).isEqualTo("RESTRICTED");
        assertThat(sensitivityOf(after)).isEqualTo("RESTRICTED");
    }

    @Test
    @DisplayName("a Drive step output whose metadata sits under output.metadata is recognised too")
    void nestedOutputMetadataIsRecognised() {
        UUID drive = payload("run-3", 1, "{\"output\":{\"metadata\":{\"iconSlug\":\"googledrive\"}}}");

        backfill().run(Instant.now());

        assertThat(sensitivityOf(drive)).isEqualTo("RESTRICTED");
    }

    @Test
    @DisplayName("a newly tagged row gets a fresh retention deadline (now + window), never an immediate deletion, and keeps its expires_at")
    void taggedRowsGetAFreshWindow() {
        UUID gmail = payload("run-1", 1, "{\"metadata\":{\"iconSlug\":\"gmail\"}}");
        Instant now = Instant.parse("2026-09-29T00:00:00Z");

        backfill().run(now);

        assertThat(retentionOf(gmail)).isEqualTo(now.plus(Duration.ofDays(30)));
        assertThat(expiryOf(gmail)).isNull();
    }
}
