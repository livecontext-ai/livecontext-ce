package com.apimarketplace.auth.web.version;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.auth.repository.CeInstallPingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * This recorder hangs off an endpoint that is public, unauthenticated and free to call, and has to
 * bound it without ever throttling the fleet it exists to measure. The split between those two jobs
 * is the design: a sighting of an install already in the ledger is an UPDATE that cannot consume
 * disk and is therefore never gated, while creating a row is the only thing an anonymous caller can
 * use to fill the table and carries every bound. Each test below pins one half of that.
 */
class CeInstallPingRecorderTest {

    private static final UUID INSTALL = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");
    private static final UUID OTHER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    /** A ceiling far above anything a test writes, so only the guard under test is in play. */
    private static final long NO_ROW_CAP = 1_000_000_000L;
    /** A refresh budget far above anything a test sends, so only the guard under test is in play. */
    private static final int NO_REFRESH_CAP = 1_000_000;

    /** A clock the test moves by hand, so the interval and the backoff need no sleeping. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-08-27T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private final MovableClock clock = new MovableClock();

    /** Budget and ceiling wide open, standard 6h interval. */
    private CeInstallPingRecorder recorder(CeInstallPingRepository repository) {
        return new CeInstallPingRecorder(repository, 6, 1_000_000, NO_REFRESH_CAP, NO_ROW_CAP, clock);
    }

    /** A ledger that does not know any install: every refresh reports 0 rows. */
    private static CeInstallPingRepository emptyLedger() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.refreshSighting(any(), any())).thenReturn(0);
        return repository;
    }

    /** A ledger that knows every install: every refresh reports 1 row. */
    private static CeInstallPingRepository ledgerKnowingEverything() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.refreshSighting(any(), any())).thenReturn(1);
        return repository;
    }

    @Test
    @DisplayName("an install the ledger has never seen is inserted, with its version")
    void firstSightingInserts() {
        CeInstallPingRepository repository = emptyLedger();

        recorder(repository).record(INSTALL, "0.2.13");

        verify(repository).refreshSighting(INSTALL, "0.2.13");
        verify(repository).insertSighting(INSTALL, "0.2.13");
    }

    @Test
    @DisplayName("an install the ledger already knows is refreshed and never inserted")
    void knownInstallIsOnlyRefreshed() {
        CeInstallPingRepository repository = ledgerKnowingEverything();

        recorder(repository).record(INSTALL, "0.2.13");

        verify(repository).refreshSighting(INSTALL, "0.2.13");
        verify(repository, never()).insertSighting(any(), any());
    }

    @Test
    @DisplayName("a repeat sighting inside the interval does not touch the database at all")
    void repeatWithinIntervalIsDropped() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = recorder(repository);

        recorder.record(INSTALL, "0.2.13");
        reset(repository);
        recorder.record(INSTALL, "0.2.13");
        recorder.record(INSTALL, "0.2.13");

        // A crash-looping install polls on every boot. Without this, every restart is another
        // round trip for an install we already know about.
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("the interval is per install, not global")
    void intervalIsPerInstall() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = recorder(repository);

        recorder.record(INSTALL, "0.2.13");
        recorder.record(OTHER, "0.2.12");

        // A shared cooldown would mean the busiest install silences every other one.
        verify(repository).insertSighting(INSTALL, "0.2.13");
        verify(repository).insertSighting(OTHER, "0.2.12");
    }

    @Test
    @DisplayName("a sighting is written again once the interval has elapsed")
    void writesAgainAfterInterval() {
        CeInstallPingRepository repository = ledgerKnowingEverything();
        CeInstallPingRecorder recorder = recorder(repository);

        recorder.record(INSTALL, "0.2.13");
        clock.advance(Duration.ofHours(7));
        recorder.record(INSTALL, "0.2.13");

        // Without this the install would be written once and never again, and last_seen_at would
        // stop meaning "still alive", which is the only thing the fleet count rests on.
        verify(repository, times(2)).refreshSighting(INSTALL, "0.2.13");
    }

    @Test
    @DisplayName("the budget caps inserts from invented ids")
    void writeBudgetCapsFloodOfNewIds() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 6, 5, NO_REFRESH_CAP, NO_ROW_CAP, clock);

        for (int i = 0; i < 200; i++) {
            recorder.record(UUID.randomUUID(), "0.2.13");
        }

        // times, not atMost: atMost(5) also passes when the guard blocks EVERYTHING, which is the
        // regression that would silently end collection.
        verify(repository, times(5)).insertSighting(any(), any());
    }

    @Test
    @DisplayName("a flood cannot stop installs already in the ledger from being counted")
    void floodCannotStarveTheRealFleet() {
        CeInstallPingRepository repository = ledgerKnowingEverything();
        when(repository.refreshSighting(any(), any())).thenReturn(1);
        when(repository.count()).thenReturn(9_999L);
        // Budget of one and a ledger already at its ceiling: the harshest state the bounds produce.
        // The cache is cold, exactly as it is after every deploy, so this install looks new to the
        // pod and only the LEDGER can say otherwise.
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 6, 1, NO_REFRESH_CAP, 9_999L, clock);
        recorder.record(UUID.randomUUID(), "0.0.1");   // some other install, already counted too

        recorder.record(INSTALL, "0.2.13");

        // This is the property the whole two-statement split exists for. Gate the refresh on the
        // per-pod cache instead and a flood freezes last_seen_at fleet-wide after every restart,
        // taking active7d to zero: a cheaper attack than inflating the count, and a silent one.
        verify(repository).refreshSighting(INSTALL, "0.2.13");
    }

    @Test
    @DisplayName("at the row ceiling, new installs are refused but known ones still refresh")
    void ceilingRefusesInsertsOnly() {
        CeInstallPingRepository repository = emptyLedger();
        when(repository.count()).thenReturn(5_000L);
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 6, 1_000_000, NO_REFRESH_CAP, 5_000L, clock);

        recorder.record(INSTALL, "0.2.13");

        // The refresh is attempted first and always: only after the ledger says "no such row" does
        // the ceiling apply, and then only to the insert.
        verify(repository).refreshSighting(INSTALL, "0.2.13");
        verify(repository, never()).insertSighting(any(), any());
    }

    @Test
    @DisplayName("the ledger size is counted at most once per TTL")
    void ledgerSizeIsCountedSparingly() {
        CeInstallPingRepository repository = emptyLedger();
        when(repository.count()).thenReturn(0L);
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 6, 1_000_000, NO_REFRESH_CAP, 10L, clock);

        for (int i = 0; i < 50; i++) {
            recorder.record(UUID.randomUUID(), "0.2.13");
        }

        // COUNT(*) on a 5M-row table is not free and this runs on the request thread of the feed
        // the whole fleet polls. Without the TTL it would run once per new install.
        verify(repository, times(1)).count();

        clock.advance(CeInstallPingRecorder.SIZE_TTL.plusSeconds(1));
        recorder.record(UUID.randomUUID(), "0.2.13");
        verify(repository, times(2)).count();
    }

    @Test
    @DisplayName("a real fleet fits through the shipped budget without being truncated")
    void aRealFleetFitsThroughTheBudget() {
        CeInstallPingRepository repository = emptyLedger();
        // 200 is the shipped default. A 100,000-install fleet polling once a day averages about 70
        // new-or-refreshed writes a minute, and only first sightings reach the budget at all.
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 6, 200, NO_REFRESH_CAP, NO_ROW_CAP, clock);

        for (int i = 0; i < 150; i++) {
            recorder.record(new UUID(7L, i), "0.2.13");
        }

        // Every other budget test asks whether strangers are blocked; none asked whether customers
        // get in. A budget that dropped part of a genuine burst would leave the count plateaued, at
        // DEBUG, looking exactly like stalled adoption.
        verify(repository, times(150)).insertSighting(any(), any());
    }

    @Test
    @DisplayName("the budget refills on the next minute instead of ending collection for good")
    void budgetRefillsEachMinute() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 6, 2, NO_REFRESH_CAP, NO_ROW_CAP, clock);

        recorder.record(UUID.randomUUID(), "0.2.13");
        recorder.record(UUID.randomUUID(), "0.2.13");
        recorder.record(UUID.randomUUID(), "0.2.13");
        verify(repository, times(2)).insertSighting(any(), any());

        clock.advance(Duration.ofSeconds(61));
        recorder.record(UUID.randomUUID(), "0.2.13");

        // Without the window reset the budget is spent once and never refills: each pod stops
        // recording new installs for the rest of its life, with one DEBUG line to show for it.
        verify(repository, times(3)).insertSighting(any(), any());
    }

    @Test
    @DisplayName("a request the budget dropped leaves no stamp behind")
    void droppedRequestIsNotStamped() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 6, 1, NO_REFRESH_CAP, NO_ROW_CAP, clock);
        recorder.record(UUID.randomUUID(), "0.2.13");   // spends the budget

        recorder.record(INSTALL, "0.2.13");             // dropped
        clock.advance(Duration.ofSeconds(61));          // budget refills
        recorder.record(INSTALL, "0.2.13");

        // A stray stamp on the drop path would make this install invisible for its whole interval.
        verify(repository).insertSighting(INSTALL, "0.2.13");
    }

    @Test
    @DisplayName("a null install id is ignored and never reaches the database")
    void nullInstallIdIsIgnored() {
        CeInstallPingRepository repository = emptyLedger();

        recorder(repository).record(null, "0.2.13");

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a blank version is stored as null so the refresh keeps the known one")
    void blankVersionBecomesNull() {
        CeInstallPingRepository repository = emptyLedger();

        recorder(repository).record(INSTALL, "   ");

        // The refresh COALESCEs a null version onto the previous value; passing "" through would
        // blank a version we already knew.
        verify(repository).refreshSighting(eq(INSTALL), isNull());
    }

    @Test
    @DisplayName("a version is trimmed, and the length cap is inclusive at the boundary")
    void versionIsTrimmedAndCappedAtTheBoundary() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 0, 1_000_000, NO_REFRESH_CAP, NO_ROW_CAP, clock);
        String exactlyAtCap = "9".repeat(CeInstallPingRecorder.MAX_VERSION_LENGTH);

        recorder.record(INSTALL, "  0.2.13  ");
        recorder.record(INSTALL, exactlyAtCap);

        // The column is bounded and the value is unauthenticated caller data, so the boundary is
        // where an off-by-one turns into an insert the database rejects. A value AT the cap is
        // still a publishable version; one character more is not (see
        // overLongValueIsNotTruncatedIntoValidity).
        verify(repository).refreshSighting(INSTALL, "0.2.13");
        verify(repository).refreshSighting(INSTALL, exactlyAtCap);
    }


    @Test
    @DisplayName("a version containing a control character is stored as dev, not silently repaired")
    void embeddedControlCharacterIsRejectedNotRepaired() {
        CeInstallPingRepository repository = emptyLedger();

        recorder(repository).record(INSTALL, "0.2\u000113");

        // An EMBEDDED control character, because trim() already removes the trailing kind and a
        // test using one of those cannot fail. This value is unauthenticated caller data that comes
        // back out of the fleet read verbatim and goes into log lines. Stripping it before
        // validating would turn it into the plausible-looking "0.213" and store that as real.
        verify(repository).refreshSighting(INSTALL, CeInstallPingRecorder.UNPUBLISHED_VERSION);
    }

    @Test
    @DisplayName("a released image's tag is recorded as its version, without the v")
    void releaseTagIsRecordedWithoutItsV() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 0, 1_000_000, NO_REFRESH_CAP, NO_ROW_CAP, clock);

        recorder.record(INSTALL, "v0.3.1");
        recorder.record(INSTALL, "V0.2.14-rc1");

        // Regression: every released image reports APP_VERSION=vX.Y.Z verbatim, and the pattern
        // demanded a leading digit, so the whole released fleet (v0.2.14 to v0.3.1) was stored as
        // "dev". The v is dropped so a tag and a bare number land in the same bucket, the form the
        // release feed advertises.
        verify(repository).refreshSighting(INSTALL, "0.3.1");
        verify(repository).refreshSighting(INSTALL, "0.2.14-rc1");
    }

    @Test
    @DisplayName("a lone v, or a v in front of anything but a version, is still dev")
    void vAloneIsNotAVersion() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 0, 1_000_000, NO_REFRESH_CAP, NO_ROW_CAP, clock);

        recorder.record(INSTALL, "v");
        recorder.record(INSTALL, "vv0.3.1");
        recorder.record(INSTALL, "vdev-4a55253");
        recorder.record(INSTALL, "v0.2\u000113");
        recorder.record(INSTALL, "v" + "9".repeat(CeInstallPingRecorder.MAX_VERSION_LENGTH));

        // A control character after the v is judged on the raw value, exactly like one without it,
        // and the length cap counts the v: stripping first would let both through.
        verify(repository, times(5)).refreshSighting(INSTALL, CeInstallPingRecorder.UNPUBLISHED_VERSION);
    }

    @Test
    @DisplayName("a tag is stripped of surrounding whitespace and keeps its build suffix")
    void tagWhitespaceAndBuildSuffix() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 0, 1_000_000, NO_REFRESH_CAP, NO_ROW_CAP, clock);

        recorder.record(INSTALL, "  v0.3.1 ");
        recorder.record(INSTALL, "v0.3.1+b7");

        verify(repository).refreshSighting(INSTALL, "0.3.1");
        verify(repository).refreshSighting(INSTALL, "0.3.1+b7");
    }

    @Test
    @DisplayName("a from-source build is recorded as dev, not by its commit id")
    void fromSourceBuildIsNotFingerprinted() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 0, 1_000_000, NO_REFRESH_CAP, NO_ROW_CAP, clock);

        recorder.record(INSTALL, "dev-4a55253");
        recorder.record(INSTALL, "0.2.13-rc1");

        // A fork's commit sha is a near-unique string. Storing it would attach an identifying value
        // to a stable install id for the life of the row, and scatter the version breakdown across
        // one bucket per fork. A published version, pre-release suffix and all, is kept as-is.
        verify(repository).refreshSighting(INSTALL, CeInstallPingRecorder.UNPUBLISHED_VERSION);
        verify(repository).refreshSighting(INSTALL, "0.2.13-rc1");
    }

    @Test
    @DisplayName("the per-install cache is bounded, so invented ids cannot grow it without limit")
    void perInstallCacheIsBounded() {
        CeInstallPingRepository repository = ledgerKnowingEverything();
        CeInstallPingRecorder recorder = recorder(repository);

        // The cache is fed by caller-chosen UUIDs on a public endpoint. Its eviction is the only
        // thing between that and an OOM, and it is invisible from the outside, so this reaches for
        // the field: the alternative is no coverage of the one bound that matters here.
        for (int i = 0; i < CeInstallPingRecorder.MAX_TRACKED_INSTALLS + 500; i++) {
            recorder.record(new UUID(0L, i), "0.2.13");
        }

        // Exactly the cap: removeEldestEntry evicts at size() > MAX, so after MAX + 500 inserts the
        // size is precisely MAX. A less-than-or-equal assertion would also pass on an empty map.
        assertThat(trackedInstalls(recorder)).isEqualTo(CeInstallPingRecorder.MAX_TRACKED_INSTALLS);
    }

    @Test
    @DisplayName("a write failure is swallowed and pauses collection instead of retrying per request")
    void writeFailurePausesCollection() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.refreshSighting(any(), any())).thenThrow(new IllegalStateException("db down"));
        CeInstallPingRecorder recorder = recorder(repository);

        assertThatCode(() -> recorder.record(INSTALL, "0.2.13")).doesNotThrowAnyException();

        recorder.record(OTHER, "0.2.13");
        // The backoff is what stops a database outage from turning the public feed into one
        // connection attempt per request.
        verify(repository, times(1)).refreshSighting(any(), any());
    }

    @Test
    @DisplayName("collection resumes on its own once the backoff has elapsed")
    void collectionResumesAfterBackoff() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.refreshSighting(any(), any())).thenThrow(new IllegalStateException("db down"));
        CeInstallPingRecorder recorder = recorder(repository);

        recorder.record(INSTALL, "0.2.13");
        clock.advance(CeInstallPingRecorder.FAILURE_BACKOFF.plusSeconds(1));
        reset(repository);
        when(repository.refreshSighting(any(), any())).thenReturn(1);

        recorder.record(OTHER, "0.2.13");

        // A pause that never lifts is an outage that silently ends collection for the life of the
        // pod, with nothing but one warn line to say so.
        verify(repository).refreshSighting(OTHER, "0.2.13");
    }

    @Test
    @DisplayName("the install whose write failed is retried once the backoff lifts")
    void theFailedInstallItselfIsRetried() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.refreshSighting(any(), any())).thenThrow(new IllegalStateException("db down"));
        CeInstallPingRecorder recorder = recorder(repository);

        recorder.record(INSTALL, "0.2.13");
        clock.advance(CeInstallPingRecorder.FAILURE_BACKOFF.plusSeconds(1));
        reset(repository);
        when(repository.refreshSighting(any(), any())).thenReturn(1);

        recorder.record(INSTALL, "0.2.13");

        // The stamp is written only after a SUCCESSFUL call. Move that line above the write and the
        // two sibling backoff tests still pass, because both retry a DIFFERENT id, while in
        // production the failed install is marked written and misses its whole 6h interval.
        verify(repository).refreshSighting(INSTALL, "0.2.13");
    }

    @Test
    @DisplayName("under concurrency the budget holds across a window roll and nothing throws")
    void concurrentFloodStaysWithinBudget() throws Exception {
        CeInstallPingRepository repository = emptyLedger();
        int cap = 20;
        // A clock that advances on every read, so the one-minute window rolls WHILE threads are
        // contending on it. The roll is the branch the immutable-window record exists for, and a
        // fixed clock never takes it: with a counter that a concurrent roll can reset, increments
        // land in a window that no longer exists and the cap is exceeded.
        Clock rolling = new Clock() {
            private final AtomicInteger ticks = new AtomicInteger();
            private final Instant base = Instant.parse("2026-08-27T10:00:00Z");

            @Override
            public Instant instant() {
                return base.plusSeconds(ticks.getAndIncrement() / 40L * 61L);
            }

            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }
        };
        CeInstallPingRecorder recorder = new CeInstallPingRecorder(repository, 6, cap, NO_REFRESH_CAP, NO_ROW_CAP, rolling);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 50; i++) {
                        recorder.record(UUID.randomUUID(), "0.2.13");
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(errors.get()).isZero();
        // Both ends. atMost alone passes on ZERO inserts, i.e. on a guard that regressed into
        // blocking everything, which is exactly the silent failure this class warns about
        // elsewhere. The clock advances one window per 40 reads, so 800 calls span 20 windows and
        // the true ceiling is cap per window; the floor is one window's worth.
        verify(repository, atMost(cap * 21)).insertSighting(any(), anyString());
        verify(repository, org.mockito.Mockito.atLeast(cap)).insertSighting(any(), anyString());
    }

    @SuppressWarnings("unchecked")
    private static int trackedInstalls(CeInstallPingRecorder recorder) {
        try {
            java.lang.reflect.Field field = CeInstallPingRecorder.class.getDeclaredField("lastWritten");
            field.setAccessible(true);
            return ((java.util.Map<UUID, Instant>) field.get(recorder)).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the per-install cache was renamed", e);
        }
    }

    @Test
    @DisplayName("a known install is never counted against the ledger ceiling")
    void ceilingNeverBlocksAKnownInstall() {
        CeInstallPingRepository repository = ledgerKnowingEverything();
        when(repository.count()).thenReturn(5_000L);
        CeInstallPingRecorder recorder =
                new CeInstallPingRecorder(repository, 6, 1, NO_REFRESH_CAP, 5_000L, clock);

        recorder.record(INSTALL, "0.2.13");

        // Cold cache, ceiling reached and a budget of one: the harshest state the bounds produce.
        // The ledger, not the per-pod cache, is what says this install is not new, and the ceiling
        // is never even consulted for it.
        verify(repository).refreshSighting(INSTALL, "0.2.13");
        verify(repository, never()).count();
    }

    @Test
    @DisplayName("the refresh budget bounds what a forged id costs the database")
    void refreshBudgetBoundsTheDatabaseCost() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder =
                new CeInstallPingRecorder(repository, 6, 1_000_000, 5, NO_ROW_CAP, clock);

        for (int i = 0; i < 200; i++) {
            recorder.record(UUID.randomUUID(), "0.2.13");
        }

        // "Cannot consume disk" is not "costs nothing": the refresh runs for ids that are NOT in
        // the ledger too, which is exactly the forged case, so without this bound every request to
        // a public unauthenticated endpoint buys a transaction and a pooled connection. Nothing
        // else in this class looks at the refreshes a flood produces.
        verify(repository, times(5)).refreshSighting(any(), any());
    }

    @Test
    @DisplayName("the two budgets are independent, so inserts cannot spend the refresh allowance")
    void budgetsAreIndependent() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder =
                new CeInstallPingRecorder(repository, 6, 1, 100, NO_ROW_CAP, clock);

        for (int i = 0; i < 50; i++) {
            recorder.record(UUID.randomUUID(), "0.2.13");
        }

        // One shared counter would mean the tight insert bound silently throttled the refreshes
        // that keep the fleet measurable, which is the failure the split exists to avoid.
        verify(repository, times(50)).refreshSighting(any(), any());
        verify(repository, times(1)).insertSighting(any(), any());
    }

    @Test
    @DisplayName("the ledger-full warning is rate limited rather than printed per request")
    void ledgerFullWarningIsRateLimited() {
        CeInstallPingRepository repository = emptyLedger();
        when(repository.count()).thenReturn(5_000L);
        CeInstallPingRecorder recorder =
                new CeInstallPingRecorder(repository, 6, 1_000_000, NO_REFRESH_CAP, 5_000L, clock);
        Logger logger = (Logger) LoggerFactory.getLogger(CeInstallPingRecorder.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Level original = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(events);

        try {
            for (int i = 0; i < 20; i++) {
                recorder.record(UUID.randomUUID(), "0.2.13");
            }
            clock.advance(CeInstallPingRecorder.FULL_WARNING_INTERVAL.plusMinutes(1));
            recorder.record(UUID.randomUUID(), "0.2.13");
        } finally {
            logger.detachAppender(events);
            logger.setLevel(original);
        }

        // At the ceiling the fleet count stops growing, which looks exactly like adoption stalling,
        // so it has to be visible. Printing it per request on a public endpoint would make it an
        // unauthenticated log-volume amplifier instead.
        assertThat(events.list.stream()
                .filter(e -> e.getFormattedMessage().contains("row ceiling"))
                .count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a failure on the insert path never stops refreshing installs already counted")
    void insertFailureDoesNotBlockRefreshes() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        // Unknown on the first call, known on the second: a new install whose INSERT fails, then a
        // sighting of an install the ledger already holds.
        when(repository.refreshSighting(any(), any())).thenReturn(0, 1);
        doThrow(new IllegalStateException("deadlock")).when(repository).insertSighting(any(), any());
        CeInstallPingRecorder recorder = recorder(repository);

        recorder.record(UUID.randomUUID(), "0.2.13");
        recorder.record(INSTALL, "0.2.13");

        // One shared backoff let a failure that only concerns NEW installs (a deadlock on insert, a
        // count(*) that timed out on a large ledger) blank the live fleet for 30 seconds on that
        // pod. The two paths mean different things and now suppress independently.
        verify(repository).refreshSighting(INSTALL, "0.2.13");
    }

    @Test
    @DisplayName("a count() failure while sizing the ledger does not stop refreshes either")
    void ledgerSizingFailureDoesNotBlockRefreshes() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.refreshSighting(any(), any())).thenReturn(0, 1);
        when(repository.count()).thenThrow(new IllegalStateException("statement timeout"));
        CeInstallPingRecorder recorder =
                new CeInstallPingRecorder(repository, 6, 1_000_000, NO_REFRESH_CAP, 10L, clock);

        recorder.record(UUID.randomUUID(), "0.2.13");
        recorder.record(INSTALL, "0.2.13");

        // count(*) is a heap scan that gets slower as the ledger grows, so this fires exactly when
        // the table is largest, which is when losing the live count matters most.
        verify(repository).refreshSighting(INSTALL, "0.2.13");
    }

    @Test
    @DisplayName("an over-long value is stored as dev, not truncated into a plausible version")
    void overLongValueIsNotTruncatedIntoValidity() {
        CeInstallPingRepository repository = emptyLedger();
        CeInstallPingRecorder recorder =
                new CeInstallPingRecorder(repository, 0, 1_000_000, NO_REFRESH_CAP, NO_ROW_CAP, clock);

        recorder.record(INSTALL, "9".repeat(CeInstallPingRecorder.MAX_VERSION_LENGTH + 1));
        recorder.record(INSTALL, "9".repeat(CeInstallPingRecorder.MAX_VERSION_LENGTH) + "\u0001");

        // Truncating before validating IS cleaning before validating: it turns a value that should
        // have been rejected into one that passes, and the second case is the proof - sixty-four
        // nines followed by a control character would have been stored as sixty-four nines.
        verify(repository, times(2)).refreshSighting(INSTALL, CeInstallPingRecorder.UNPUBLISHED_VERSION);
    }

    @Test
    @DisplayName("new installs are recorded again once the insert backoff lifts")
    void insertBackoffExpires() {
        CeInstallPingRepository repository = mock(CeInstallPingRepository.class);
        when(repository.refreshSighting(any(), any())).thenReturn(0);
        doThrow(new IllegalStateException("deadlock")).when(repository).insertSighting(any(), any());
        CeInstallPingRecorder recorder = recorder(repository);
        recorder.record(UUID.randomUUID(), "0.2.13");

        clock.advance(CeInstallPingRecorder.FAILURE_BACKOFF.plusSeconds(1));
        reset(repository);
        when(repository.refreshSighting(any(), any())).thenReturn(0);
        recorder.record(INSTALL, "0.2.13");

        // The sibling tests only show the backoff being SET. Change that line to never expire and
        // they all still pass, while one transient insert failure permanently ends new-install
        // recording on that pod, with a single WARN as the only trace.
        verify(repository).insertSighting(INSTALL, "0.2.13");
    }
}
