package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Kind;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Medium;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationDeliveryLog.Status;
import com.apimarketplace.testsupport.ScratchPostgres;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The anti-spam rules are SQL: "only the first failure opens an incident" is a partial unique
 * index plus an {@code xmax = 0} upsert, and "a reminder is sent once" is a compare-and-set. No
 * mock can prove either, so this runs them on a real Postgres, against the DDL of V528 itself
 * (read from the migration file, never restated here).
 */
@DisplayName("Notification delivery stores (real Postgres)")
class NotificationDeliveryStoresPostgresTest {

    private static final ScratchPostgres DB = ScratchPostgres.forPrefix(
            "ORCHESTRATOR_TEST_PG",
            "it is the only executable proof that a failing workflow opens ONE incident and sends "
                    + "ONE message, instead of one per failed run");

    private static final String TENANT = "42";
    private static final String ORG = "org-1";

    static JdbcTemplate jdbc;
    private NotificationIncidentStore incidents;
    private NotificationDeliveryLog log;
    private NotificationPreferenceStore preferences;

    @BeforeAll
    static void schema() throws Exception {
        DB.require();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(DB.url(), DB.user(), DB.password()));
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS orchestrator");
        jdbc.execute("DROP TABLE IF EXISTS orchestrator.notification_preferences, orchestrator.notification_incidents, "
                + "orchestrator.notification_deliveries CASCADE");
        for (String statement : orchestratorStatementsOfV528()) {
            jdbc.execute(statement);
        }
        // Only the columns the V528 seed reads; the real tables carry many more.
        jdbc.execute("DROP TABLE IF EXISTS orchestrator.workflows, orchestrator.workflow_runs CASCADE");
        jdbc.execute("CREATE TABLE orchestrator.workflow_runs (id UUID PRIMARY KEY, status VARCHAR(32), metadata JSONB)");
        jdbc.execute("CREATE TABLE orchestrator.workflows (id UUID PRIMARY KEY, production_run_id UUID)");
        // Only the columns the digest reads; the real table (V172) carries more.
        jdbc.execute("DROP TABLE IF EXISTS orchestrator.notifications CASCADE");
        jdbc.execute("CREATE TABLE orchestrator.notifications (id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(255), "
                + "organization_id VARCHAR(255), category VARCHAR(64), subject_type VARCHAR(32), subject_id UUID, "
                + "payload JSONB, occurred_at TIMESTAMPTZ)");
    }

    /** The one-time seeding INSERT of V528, run by the test that pins it. */
    static String seedStatementOfV528() throws Exception {
        for (String raw : v525WithoutComments().split(";")) {
            String s = raw.trim();
            if (s.startsWith("INSERT INTO orchestrator.notification_incidents")) return s;
        }
        throw new AssertionError("V528 has no incident seeding statement");
    }

    static String v525WithoutComments() throws Exception {
        Path file = Path.of("..", "migration-service", "src", "main", "resources", "db", "migration",
                "V528__notification_delivery.sql");
        return Files.readString(file, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "");
    }

    /** The CREATE statements of V528 that build the three orchestrator delivery tables and their indexes. */
    static List<String> orchestratorStatementsOfV528() throws Exception {
        String sql = v525WithoutComments();
        List<String> out = new java.util.ArrayList<>();
        for (String raw : sql.split(";")) {
            String s = raw.trim();
            if (s.startsWith("CREATE") && s.contains("orchestrator.notification_")) out.add(s);
        }
        assertThat(out).as("V528 orchestrator DDL").hasSizeGreaterThanOrEqualTo(8);
        return out;
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE orchestrator.notification_preferences, orchestrator.notification_incidents, "
                + "orchestrator.notification_deliveries, orchestrator.notifications, orchestrator.workflows, "
                + "orchestrator.workflow_runs RESTART IDENTITY");
        incidents = new NotificationIncidentStore(jdbc);
        log = new NotificationDeliveryLog(jdbc);
        preferences = new NotificationPreferenceStore(jdbc);
    }

    @Test
    @DisplayName("Regression (spam): 50 failures of one workflow open ONE incident; only the first reports 'opened'")
    void oneIncidentPerFailingWorkflow() {
        UUID wf = UUID.randomUUID();
        Instant t = Instant.parse("2026-09-24T03:00:00Z");
        int opened = 0;
        for (int i = 0; i < 50; i++) {
            if (incidents.recordFailure(TENANT, ORG, wf, t.plusSeconds(60L * i)) == NotificationIncidentStore.FailureOutcome.OPENED) opened++;
        }

        assertThat(opened).isEqualTo(1);
        Integer count = jdbc.queryForObject("SELECT failure_count FROM orchestrator.notification_incidents "
                + "WHERE workflow_id = ? AND resolved_at IS NULL", Integer.class, wf);
        assertThat(count).isEqualTo(50);
        assertThat(incidents.hasOpen(wf)).isTrue();
    }

    @Test
    @DisplayName("A recovery closes the incident, once; a failure within 24h reopens it rather than opening a new one")
    void recoveryThenReopen() {
        UUID wf = UUID.randomUUID();
        incidents.recordFailure(TENANT, ORG, wf, Instant.now());
        incidents.recordFailure(TENANT, ORG, wf, Instant.now());

        List<NotificationIncidentStore.Incident> closed = incidents.resolve(wf, Instant.now());

        assertThat(closed).singleElement().satisfies(i -> assertThat(i.failureCount()).isEqualTo(2));
        assertThat(incidents.hasOpen(wf)).isFalse();
        assertThat(incidents.resolve(wf, Instant.now())).as("a second close claims nothing").isEmpty();
        assertThat(incidents.recordFailure(TENANT, ORG, wf, Instant.now())).as("reopened: failing again, once")
                .isEqualTo(NotificationIncidentStore.FailureOutcome.FAILING_AGAIN);
        assertThat(incidents.hasOpen(wf)).isTrue();
    }

    @Test
    @DisplayName("Regression (flapping): a failure within 24h of a recovery reopens the SAME incident with ONE 'failing again'")
    void flapReopensWithOneFailingAgain() {
        UUID wf = UUID.randomUUID();
        incidents.recordFailure(TENANT, ORG, wf, Instant.now());
        List<NotificationIncidentStore.Incident> first = incidents.resolve(wf, Instant.now());
        assertThat(first).singleElement().satisfies(i -> assertThat(i.announceRecovery()).isTrue());

        assertThat(incidents.recordFailure(TENANT, ORG, wf, Instant.now())).as("not a new incident")
                .isEqualTo(NotificationIncidentStore.FailureOutcome.FAILING_AGAIN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orchestrator.notification_incidents WHERE workflow_id = ?",
                Integer.class, wf)).isEqualTo(1);

        List<NotificationIncidentStore.Incident> second = incidents.resolve(wf, Instant.now());
        assertThat(second).singleElement()
                .satisfies(i -> assertThat(i.announceRecovery()).as("corrects the 'failing again'").isTrue());
    }

    @Test
    @DisplayName("Regression (flapping): a workflow flipping all day sends failed, recovered, failing again, recovered, then nothing")
    void flappingIsBoundedToFourMessages() {
        UUID wf = UUID.randomUUID();
        int announced = 0;
        if (incidents.recordFailure(TENANT, ORG, wf, Instant.now()) == NotificationIncidentStore.FailureOutcome.OPENED) announced++;
        for (int flip = 0; flip < 20; flip++) {
            if (incidents.resolve(wf, Instant.now()).stream().anyMatch(NotificationIncidentStore.Incident::announceRecovery)) {
                announced++;
            }
            if (incidents.recordFailure(TENANT, ORG, wf, Instant.now()) == NotificationIncidentStore.FailureOutcome.FAILING_AGAIN) announced++;
        }

        assertThat(announced).isEqualTo(4);
    }

    /** A pinned workflow whose production run's latest outcome is the given cycle result. */
    private void production(UUID workflow, String status, String lastCycleResult) {
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO orchestrator.workflow_runs (id, status, metadata) VALUES (?, ?, CAST(? AS jsonb))",
                run, status, lastCycleResult == null ? null : "{\"lastCycleResult\":\"" + lastCycleResult + "\"}");
        jdbc.update("INSERT INTO orchestrator.workflows (id, production_run_id) VALUES (?, ?)", workflow, run);
    }

    @Test
    @DisplayName("Regression (first-deploy burst): V528 seeds workflows STILL failing; their next failure JOINS")
    void v525SeedsStillFailingWorkflows() throws Exception {
        UUID failing = UUID.randomUUID();
        UUID terminalFailed = UUID.randomUUID();
        UUID longAgo = UUID.randomUUID();
        production(failing, "WAITING_TRIGGER", "failed");
        production(terminalFailed, "FAILED", null);
        production(longAgo, "WAITING_TRIGGER", "failed");
        insertFailure(failing, Instant.now().minus(Duration.ofHours(3)));
        insertFailure(failing, Instant.now().minus(Duration.ofHours(2)));
        insertFailure(terminalFailed, Instant.now().minus(Duration.ofHours(1)));
        insertFailure(longAgo, Instant.now().minus(Duration.ofDays(3)));

        jdbc.execute(seedStatementOfV528());

        assertThat(incidents.recordFailure(TENANT, ORG, failing, Instant.now())).isEqualTo(NotificationIncidentStore.FailureOutcome.JOINED);
        assertThat(incidents.recordFailure(TENANT, ORG, terminalFailed, Instant.now()))
                .as("a terminal FAILED run is still failing").isEqualTo(NotificationIncidentStore.FailureOutcome.JOINED);
        assertThat(incidents.recordFailure(TENANT, ORG, longAgo, Instant.now()))
                .as("a failure older than 24h is not 'still failing'").isEqualTo(NotificationIncidentStore.FailureOutcome.OPENED);
        assertThat(incidents.dueReminders(Instant.now().minus(Duration.ofHours(24)), 0L, 10))
                .as("seeded as reported at its last failure (2h ago): no reminder due yet").isEmpty();
    }

    @Test
    @DisplayName("A seed whose last failure is 23h old is reported at that failure: its reminder is due at the next failure after 24h")
    void seedReminderClockStartsAtLastFailure() throws Exception {
        UUID wf = UUID.randomUUID();
        production(wf, "WAITING_TRIGGER", "failed");
        insertFailure(wf, Instant.now().minus(Duration.ofHours(23)));
        jdbc.execute(seedStatementOfV528());

        incidents.recordFailure(TENANT, ORG, wf, Instant.now());

        assertThat(incidents.dueReminders(Instant.now().minus(Duration.ofHours(24)), 0L, 10))
                .as("reported 23h ago, not yet 24h").isEmpty();
        assertThat(incidents.dueReminders(Instant.now().minus(Duration.ofHours(22)), 0L, 10))
                .as("an hour later the reminder is due, since it failed again").hasSize(1);
    }

    @Test
    @DisplayName("Regression (unannounced 'recovered'): V528 does NOT seed a workflow that failed and has since recovered")
    void v525SkipsRecoveredWorkflows() throws Exception {
        UUID recovered = UUID.randomUUID();
        production(recovered, "WAITING_TRIGGER", "completed");
        insertFailure(recovered, Instant.now().minus(Duration.ofHours(3)));

        jdbc.execute(seedStatementOfV528());

        assertThat(incidents.hasOpen(recovered)).isFalse();
        assertThat(incidents.resolve(recovered, Instant.now())).as("no 'recovered' to announce").isEmpty();
    }

    @Test
    @DisplayName("Regression (two clocks): a failure that HAPPENED before the recovery but is processed after it is stale")
    void queuedFailureOlderThanRecoveryIsStale() {
        UUID wf = UUID.randomUUID();
        Instant t = Instant.now();
        incidents.recordFailure(TENANT, ORG, wf, t.minusSeconds(120), t.minusSeconds(120));
        assertThat(incidents.resolve(wf, t)).singleElement()
                .satisfies(i -> assertThat(i.announceRecovery()).isTrue());

        // Failure F happened at t - 30 s, queued behind the success, processed at t + 5 s.
        assertThat(incidents.recordFailure(TENANT, ORG, wf, t.minusSeconds(30), t.plusSeconds(5)))
                .as("no false 'failed again, after it had recovered'").isEqualTo(NotificationIncidentStore.FailureOutcome.JOINED);
        assertThat(incidents.hasOpen(wf)).as("and no new incident either").isFalse();

        // A real failure after the recovery still announces 'failing again', and its recovery is announced.
        assertThat(incidents.recordFailure(TENANT, ORG, wf, t.plusSeconds(60), t.plusSeconds(61)))
                .isEqualTo(NotificationIncidentStore.FailureOutcome.FAILING_AGAIN);
        assertThat(incidents.resolve(wf, t.plusSeconds(120))).singleElement()
                .satisfies(i -> assertThat(i.announceRecovery()).as("last word is not 'failing again'").isTrue());
    }

    @Test
    @DisplayName("Regression (lost first alert): an earlier failure row whose delivery has not run yet does not silence the next")
    void outOfOrderFailuresStillAlertOnce() {
        UUID wf = UUID.randomUUID();
        insertFailure(wf, Instant.now().minus(Duration.ofSeconds(5)));   // failure A, still queued
        insertFailure(wf, Instant.now());                                  // failure B, handled first

        NotificationIncidentStore.FailureOutcome b = incidents.recordFailure(TENANT, ORG, wf, Instant.now());
        NotificationIncidentStore.FailureOutcome a =
                incidents.recordFailure(TENANT, ORG, wf, Instant.now().minus(Duration.ofSeconds(5)));

        assertThat(List.of(a, b)).containsExactlyInAnyOrder(NotificationIncidentStore.FailureOutcome.OPENED, NotificationIncidentStore.FailureOutcome.JOINED);
    }

    @Test
    @DisplayName("Regression (last word 'failing again'): the recovery after a 'failing again' is announced")
    void recoveryAfterFailingAgainAnnounced() {
        UUID wf = UUID.randomUUID();
        incidents.recordFailure(TENANT, ORG, wf, Instant.now());
        incidents.resolve(wf, Instant.now());                                      // recovered (announced)
        assertThat(incidents.recordFailure(TENANT, ORG, wf, Instant.now())).isEqualTo(NotificationIncidentStore.FailureOutcome.FAILING_AGAIN);

        assertThat(incidents.resolve(wf, Instant.now())).singleElement()
                .satisfies(i -> assertThat(i.announceRecovery()).isTrue());
    }

    @Test
    @DisplayName("A stale close (no recovery announced) is never silently reopened: the next failure is a new alert")
    void staleCloseNotReopened() {
        UUID wf = UUID.randomUUID();
        incidents.recordFailure(TENANT, ORG, wf, Instant.now().minus(Duration.ofDays(8)));
        incidents.closeStale(Instant.now().minus(Duration.ofDays(7)), Instant.now());

        assertThat(incidents.recordFailure(TENANT, ORG, wf, Instant.now())).isEqualTo(NotificationIncidentStore.FailureOutcome.OPENED);
    }

    @Test
    @DisplayName("Reminders are paged by id, so every due incident is visited")
    void remindersPaged() {
        Instant old = Instant.now().minus(Duration.ofHours(30));
        for (int i = 0; i < 3; i++) {
            UUID wf = UUID.randomUUID();
            incidents.recordFailure(TENANT, ORG, wf, old);
            incidents.recordFailure(TENANT, ORG, wf, Instant.now());
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));

        List<NotificationIncidentStore.Incident> first = incidents.dueReminders(cutoff, 0L, 2);
        List<NotificationIncidentStore.Incident> second = incidents.dueReminders(cutoff, first.get(1).id(), 2);

        assertThat(first).hasSize(2);
        assertThat(second).hasSize(1);
    }

    @Test
    @DisplayName("Concurrency: 8 replicas recording the first failure at once open ONE incident, report ONE opening")
    void concurrentFirstFailures() throws Exception {
        UUID wf = UUID.randomUUID();
        int threads = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<NotificationIncidentStore.FailureOutcome>> results = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return incidents.recordFailure(TENANT, ORG, wf, Instant.now());
            }));
        }
        start.countDown();
        int opened = 0;
        for (var f : results) {
            if (f.get(30, java.util.concurrent.TimeUnit.SECONDS) == NotificationIncidentStore.FailureOutcome.OPENED) opened++;
        }
        pool.shutdown();

        assertThat(opened).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT failure_count FROM orchestrator.notification_incidents "
                + "WHERE workflow_id = ?", Integer.class, wf)).isEqualTo(threads);
    }

    @Test
    @DisplayName("Concurrency: two replicas closing the same incident announce ONE recovery")
    void concurrentResolve() throws Exception {
        UUID wf = UUID.randomUUID();
        incidents.recordFailure(TENANT, ORG, wf, Instant.now());
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var a = pool.submit(() -> { start.await(); return incidents.resolve(wf, Instant.now()); });
        var b = pool.submit(() -> { start.await(); return incidents.resolve(wf, Instant.now()); });
        start.countDown();
        int closed = a.get(30, java.util.concurrent.TimeUnit.SECONDS).size() + b.get(30, java.util.concurrent.TimeUnit.SECONDS).size();
        pool.shutdown();

        assertThat(closed).isEqualTo(1);
    }

    @Test
    @DisplayName("Regression (lost digest row): a row stamped before 'now' but committed after the pass reaches the NEXT digest")
    void lateCommitReachesNextDigest() {
        UUID subject = UUID.randomUUID();
        NotificationPreferenceStore prefs = preferences;
        prefs.save(TENANT, ORG, NotificationTopic.ACCOUNT, DeliveryMode.EMAIL);
        NotificationEmailEntitlement entitled = new NotificationEmailEntitlement();
        NotificationMessageComposer composer = mock(NotificationMessageComposer.class);
        org.mockito.Mockito.when(composer.digest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList())).thenAnswer(inv ->
                ((List<?>) inv.getArgument(1)).isEmpty() ? null
                        : new NotificationMessage("d", List.of(String.valueOf(((List<?>) inv.getArgument(1)).size())), "/app", "Open"));
        NotificationSender sender = mock(NotificationSender.class);
        org.mockito.Mockito.when(sender.email(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    log.record(TENANT, ORG, null, Kind.DIGEST, Medium.EMAIL, Status.SENT, null, inv.getArgument(5));
                    return true;
                });
        NotificationDigestScheduler scheduler = new NotificationDigestScheduler(incidents, log, prefs, entitled,
                composer, sender, mock(NotificationDeliveryService.class), jdbc, new ObjectMapper(), true);
        Instant pass = Instant.now();

        insertNotification("CRED_EXPIRED", subject, pass.minus(Duration.ofHours(1)));
        scheduler.digest(TENANT, ORG, pass);
        // Stamped 10 s before the pass, committed only now: after the pass's reads.
        insertNotification("CRED_EXPIRED", subject, pass.minusSeconds(10));
        scheduler.digest(TENANT, ORG, pass.plus(Duration.ofDays(1)));

        org.mockito.ArgumentCaptor<List<NotificationMessageComposer.DigestItem>> items =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(composer, org.mockito.Mockito.times(4)).digest(org.mockito.ArgumentMatchers.eq(TENANT), items.capture());
        // email then channel per pass: [email1, channel1, email2, channel2]
        assertThat(items.getAllValues().get(0)).as("first pass").hasSize(1);
        assertThat(items.getAllValues().get(2)).as("the late row lands in the next summary").hasSize(1);
    }

    @Test
    @DisplayName("A recipient with only DEFERRED rows is still a digest candidate")
    void deferredOnlyCandidate() {
        log.record("99", "org-9", 5L, Kind.ALERT, Medium.EMAIL, Status.DEFERRED, null);

        assertThat(log.digestCandidates(NotificationTopic.digestCategories(), Instant.now().minus(Duration.ofHours(1))))
                .containsExactly(new NotificationDeliveryLog.Recipient("99", "org-9"));
    }

    @Test
    @DisplayName("A failure more than 24h after the recovery is a NEW incident, reported again")
    void oldRecoveryDoesNotReopen() {
        UUID wf = UUID.randomUUID();
        incidents.recordFailure(TENANT, ORG, wf, Instant.now().minus(Duration.ofDays(3)));
        incidents.resolve(wf, Instant.now().minus(Duration.ofDays(2)));

        assertThat(incidents.recordFailure(TENANT, ORG, wf, Instant.now())).isEqualTo(NotificationIncidentStore.FailureOutcome.OPENED);
        assertThat(incidents.resolve(wf, Instant.now())).singleElement()
                .satisfies(i -> assertThat(i.announceRecovery()).isTrue());
    }

    @Test
    @DisplayName("Announcing a recovery counts as reporting it: a reopen right after is not an instant reminder")
    void recoveryResetsReminderClock() {
        UUID wf = UUID.randomUUID();
        incidents.recordFailure(TENANT, ORG, wf, Instant.now().minus(Duration.ofHours(30)));
        incidents.recordFailure(TENANT, ORG, wf, Instant.now().minus(Duration.ofHours(29)));
        incidents.resolve(wf, Instant.now());
        incidents.recordFailure(TENANT, ORG, wf, Instant.now());

        assertThat(incidents.dueReminders(Instant.now().minus(Duration.ofHours(24)), 0L, 10)).isEmpty();
    }

    @Test
    @DisplayName("Two people (or two workspaces) failing on the same workflow id have separate incidents")
    void incidentsArePerRecipient() {
        UUID wf = UUID.randomUUID();
        assertThat(incidents.recordFailure(TENANT, ORG, wf, Instant.now())).isEqualTo(NotificationIncidentStore.FailureOutcome.OPENED);
        assertThat(incidents.recordFailure("43", ORG, wf, Instant.now())).isEqualTo(NotificationIncidentStore.FailureOutcome.OPENED);
        assertThat(incidents.recordFailure(TENANT, "org-2", wf, Instant.now())).isEqualTo(NotificationIncidentStore.FailureOutcome.OPENED);
    }

    @Test
    @DisplayName("A reminder is due only after 24h AND a new failure, and can be claimed exactly once")
    void reminderClaimedOnce() {
        UUID wf = UUID.randomUUID();
        Instant dayAgo = Instant.now().minus(Duration.ofHours(25)).truncatedTo(ChronoUnit.MILLIS);
        incidents.recordFailure(TENANT, ORG, wf, dayAgo);
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));

        assertThat(incidents.dueReminders(cutoff, 0L, 10)).as("no failure since it was reported").isEmpty();

        incidents.recordFailure(TENANT, ORG, wf, Instant.now());
        List<NotificationIncidentStore.Incident> due = incidents.dueReminders(cutoff, 0L, 10);
        assertThat(due).hasSize(1);

        assertThat(incidents.claimReminder(due.get(0), Instant.now())).isTrue();
        assertThat(incidents.claimReminder(due.get(0), Instant.now())).as("stale read loses").isFalse();
        assertThat(incidents.dueReminders(cutoff, 0L, 10)).as("reported again, not due").isEmpty();
    }

    @Test
    @DisplayName("An incident with no failure for a week closes silently")
    void staleIncidentCloses() {
        UUID wf = UUID.randomUUID();
        incidents.recordFailure(TENANT, ORG, wf, Instant.now().minus(Duration.ofDays(8)));

        assertThat(incidents.closeStale(Instant.now().minus(Duration.ofDays(7)), Instant.now())).isEqualTo(1);
        assertThat(incidents.hasOpen(wf)).isFalse();
    }

    @Test
    @DisplayName("The daily cap counts SENT immediate messages only: not digests, not failures, not deferrals")
    void capCountsSentImmediateOnly() {
        log.record(TENANT, ORG, 1L, Kind.ALERT, Medium.EMAIL, Status.SENT, null);
        log.record(TENANT, "org-2", 2L, Kind.REMINDER, Medium.EMAIL, Status.SENT, null);
        log.record(TENANT, ORG, null, Kind.DIGEST, Medium.EMAIL, Status.SENT, null);
        log.record(TENANT, ORG, 3L, Kind.ALERT, Medium.EMAIL, Status.FAILED, "smtp");
        log.record(TENANT, ORG, 4L, Kind.ALERT, Medium.EMAIL, Status.DEFERRED, null);
        log.record(TENANT, ORG, 5L, Kind.ALERT, Medium.CHANNEL, Status.SENT, null);

        assertThat(log.countImmediateSentSince(TENANT, Medium.EMAIL, Instant.now().minus(Duration.ofHours(24))))
                .as("across workspaces: one inbox").isEqualTo(2);
        assertThat(log.deferredBetween(TENANT, ORG, Medium.EMAIL, Instant.now().minus(Duration.ofHours(1)), Instant.now()))
                .containsExactly(4L);
        assertThat(log.lastDigestAt(TENANT, ORG, Medium.EMAIL)).isNotNull();
        assertThat(log.lastDigestAt(TENANT, ORG, Medium.CHANNEL)).as("one cursor per medium").isNull();
        assertThat(log.lastDigestAt(TENANT, "org-2", Medium.EMAIL)).isNull();
    }

    @Test
    @DisplayName("The digest loads rows of the wanted categories since the cursor, plus the deferred ones")
    void digestLoad() {
        UUID subject = UUID.randomUUID();
        Instant since = Instant.now().minus(Duration.ofHours(24));
        insertNotification("CRED_EXPIRED", subject, Instant.now().minus(Duration.ofHours(2)));
        insertNotification("CRED_EXPIRED", subject, Instant.now().minus(Duration.ofHours(30)));   // before cursor
        insertNotification("AGENT_TASK_ASSIGNED", subject, Instant.now().minus(Duration.ofHours(1)));
        long deferred = insertNotification("RUN_FAILED", subject, Instant.now().minus(Duration.ofHours(3)));

        NotificationDigestScheduler scheduler = new NotificationDigestScheduler(incidents, log, preferences,
                mock(NotificationEmailEntitlement.class), mock(NotificationMessageComposer.class),
                mock(NotificationSender.class), mock(NotificationDeliveryService.class), jdbc, new ObjectMapper(), true);

        List<NotificationMessageComposer.DigestItem> items =
                scheduler.load(TENANT, ORG, Set.of("CRED_EXPIRED"), List.of(deferred), since, Instant.now());

        assertThat(items).extracting(NotificationMessageComposer.DigestItem::category)
                .containsExactly("RUN_FAILED", "CRED_EXPIRED");
        assertThat(items.get(1).payload()).containsEntry("subjectName", "Gmail");
        assertThat(log.digestCandidates(NotificationTopic.digestCategories(), since))
                .containsExactly(new NotificationDeliveryLog.Recipient(TENANT, ORG));
    }

    @Test
    @DisplayName("Preferences: defaults without a row, an explicit choice once saved, per workspace")
    void preferencesPerWorkspace() {
        assertThat(preferences.resolve(TENANT, ORG, NotificationTopic.TASKS)).isEqualTo(DeliveryMode.EMAIL);

        preferences.save(TENANT, ORG, NotificationTopic.FAILURES, DeliveryMode.CHANNEL);
        preferences.save(TENANT, ORG, NotificationTopic.FAILURES, DeliveryMode.OFF);

        assertThat(preferences.resolve(TENANT, ORG, NotificationTopic.FAILURES)).isEqualTo(DeliveryMode.OFF);
        assertThat(preferences.resolve(TENANT, "org-2", NotificationTopic.FAILURES)).isEqualTo(DeliveryMode.BOTH);
    }

    @Test
    @DisplayName("Regression (ignored choice): Credits set to OFF from a TEAM workspace governs the alert in the personal one")
    void creditsChoiceIsPersonWide() {
        preferences.save(TENANT, "team-org", NotificationTopic.CREDITS, DeliveryMode.OFF);

        assertThat(preferences.resolve(TENANT, "personal-org", NotificationTopic.CREDITS)).isEqualTo(DeliveryMode.OFF);
        assertThat(preferences.resolve(TENANT, "team-org", NotificationTopic.CREDITS)).isEqualTo(DeliveryMode.OFF);
        assertThat(preferences.resolve("43", "personal-org", NotificationTopic.CREDITS))
                .as("another person keeps the default").isEqualTo(DeliveryMode.BOTH);
    }

    @Test
    @DisplayName("A stray workspace row for Credits never shadows the person's choice")
    void strayWorkspaceRowIgnored() {
        jdbc.update("INSERT INTO orchestrator.notification_preferences (tenant_id, organization_id, topic, delivery) "
                + "VALUES (?, ?, 'CREDITS', 'OFF')", TENANT, ORG);

        assertThat(preferences.resolve(TENANT, ORG, NotificationTopic.CREDITS)).isEqualTo(DeliveryMode.BOTH);
    }

    private void insertFailure(UUID workflow, Instant at) {
        jdbc.update("INSERT INTO orchestrator.notifications (tenant_id, organization_id, category, subject_type, "
                        + "subject_id, payload, occurred_at) VALUES (?, ?, 'RUN_FAILED', 'WORKFLOW', ?, "
                        + "CAST('{\"status\":\"failed\"}' AS jsonb), ?)",
                TENANT, ORG, workflow, Timestamp.from(at));
    }

    private long insertNotification(String category, UUID subject, Instant at) {
        return jdbc.queryForObject("INSERT INTO orchestrator.notifications "
                        + "(tenant_id, organization_id, category, subject_type, subject_id, payload, occurred_at) "
                        + "VALUES (?, ?, ?, 'CREDENTIAL', ?, CAST(? AS jsonb), ?) RETURNING id",
                Long.class, TENANT, ORG, category, subject, "{\"status\":\"x\",\"subjectName\":\"Gmail\"}",
                Timestamp.from(at));
    }
}
