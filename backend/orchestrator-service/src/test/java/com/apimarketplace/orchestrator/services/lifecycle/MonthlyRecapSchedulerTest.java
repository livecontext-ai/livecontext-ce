package com.apimarketplace.orchestrator.services.lifecycle;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.AuthClient.LifecycleEventResult;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("MonthlyRecapScheduler - once per person and month, paced, never throws")
class MonthlyRecapSchedulerTest {

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    /** 1 September 2026, 09:00 UTC: the pass recaps August. */
    private static final Clock SEPT_FIRST = Clock.fixed(Instant.parse("2026-09-01T09:00:00Z"), ZoneOffset.UTC);

    private final MonthlyRecapStore store = mock(MonthlyRecapStore.class);
    private final AuthClient authClient = mock(AuthClient.class);
    private final List<Duration> sleeps = new ArrayList<>();

    /** Runs submitted work inline so a scheduled() call is observable. */
    private static final ExecutorService INLINE = new AbstractExecutorService() {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    };

    private MonthlyRecapScheduler scheduler(boolean enabled, int perSecond) {
        return new MonthlyRecapScheduler(store, authClient, SEPT_FIRST, sleeps::add, enabled, perSecond, INLINE);
    }

    private void candidates(MonthlyRecapStore.Recap... recaps) {
        when(store.candidates(AUGUST)).thenReturn(List.of(recaps));
        for (MonthlyRecapStore.Recap r : recaps) when(store.claim(r.tenantId(), AUGUST)).thenReturn(true);
    }

    private void auth(LifecycleEventResult result) {
        when(authClient.emitLifecycleEvent(anyString(), anyString(), anyMap())).thenReturn(result);
    }

    /** Runs {@code pass} and copies what the scheduler logged meanwhile into {@code into}. */
    private static MonthlyRecapScheduler.PassResult capturingLogs(List<ch.qos.logback.classic.spi.ILoggingEvent> into,
                                                                 java.util.function.Supplier<MonthlyRecapScheduler.PassResult> pass) {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(MonthlyRecapScheduler.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            return pass.get();
        } finally {
            logger.detachAppender(appender);
            into.addAll(appender.list);
        }
    }

    @Test
    @DisplayName("the 1st of the month recaps the PREVIOUS calendar month, with the three figures")
    void recapsPreviousMonth() {
        candidates(new MonthlyRecapStore.Recap("7", 42, 3, 2));
        auth(LifecycleEventResult.ACCEPTED);

        scheduler(true, 5).scheduled();

        verify(authClient).emitLifecycleEvent("7", "recap.monthly",
                Map.of("month", "2026-08", "runs", 42L, "active_workflows", 3L, "badges", 2L));
    }

    @Test
    @DisplayName("each person is claimed BEFORE the send, and someone already claimed is skipped")
    void claimedBeforeSendAndSkippedWhenTaken() {
        when(store.candidates(AUGUST)).thenReturn(List.of(
                new MonthlyRecapStore.Recap("7", 1, 1, 0), new MonthlyRecapStore.Recap("8", 1, 1, 0)));
        when(store.claim("7", AUGUST)).thenReturn(true);
        // Another pod (or an earlier pass) already took person 8 for August.
        when(store.claim("8", AUGUST)).thenReturn(false);
        auth(LifecycleEventResult.ACCEPTED);

        MonthlyRecapScheduler.PassResult result = scheduler(true, 5).sendRecaps(AUGUST);

        assertThat(result.sent()).isEqualTo(1);
        verify(authClient).emitLifecycleEvent(eq("7"), anyString(), anyMap());
        verify(authClient, never()).emitLifecycleEvent(eq("8"), anyString(), anyMap());
    }

    @Test
    @DisplayName("sends are paced at the configured rate, and never faster than 5 per second")
    void paced() {
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0), new MonthlyRecapStore.Recap("2", 1, 1, 0),
                new MonthlyRecapStore.Recap("3", 1, 1, 0));
        auth(LifecycleEventResult.ACCEPTED);

        scheduler(true, 4).sendRecaps(AUGUST);

        assertThat(sleeps).containsExactly(Duration.ofMillis(250), Duration.ofMillis(250), Duration.ofMillis(250));
        assertThat(scheduler(true, 50).spacing()).isEqualTo(Duration.ofMillis(200));
        assertThat(scheduler(true, 0).spacing()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("auth-service busy: retried with back-off, then the claim is given back and the pass stops")
    void busyReleasesAndStops() {
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0), new MonthlyRecapStore.Recap("2", 1, 1, 0));
        auth(LifecycleEventResult.RETRY_LATER);

        MonthlyRecapScheduler.PassResult result = scheduler(true, 5).sendRecaps(AUGUST);

        assertThat(result.stopped()).isTrue();
        assertThat(result.deferred()).isEqualTo(1);
        verify(authClient, times(MonthlyRecapScheduler.MAX_ATTEMPTS)).emitLifecycleEvent(eq("1"), anyString(), anyMap());
        assertThat(sleeps).containsExactly(MonthlyRecapScheduler.BACKOFF, MonthlyRecapScheduler.BACKOFF);
        verify(store).release("1", AUGUST);
        // The rest waits for the next slot, unclaimed.
        verify(store, never()).claim("2", AUGUST);
    }

    @Test
    @DisplayName("Regression (recap lost): an interrupt during the back-off gives the claim back, keeps the interrupt and stops")
    void interruptInBackoffReleasesAndStops() {
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0), new MonthlyRecapStore.Recap("2", 1, 1, 0));
        auth(LifecycleEventResult.RETRY_LATER);
        MonthlyRecapScheduler interrupted = new MonthlyRecapScheduler(store, authClient, SEPT_FIRST, d -> {
            throw new InterruptedException("shutdown");
        }, true, 5, INLINE);

        try {
            MonthlyRecapScheduler.PassResult result = interrupted.sendRecaps(AUGUST);

            assertThat(result.stopped()).isTrue();
            assertThat(result.sent()).isZero();
            assertThat(Thread.currentThread().isInterrupted()).as("interrupt status restored").isTrue();
            verify(store).release("1", AUGUST);
            verify(authClient, times(1)).emitLifecycleEvent(eq("1"), anyString(), anyMap());
            verify(store, never()).claim("2", AUGUST);
        } finally {
            Thread.interrupted(); // clear it for the next test
        }
    }

    @Test
    @DisplayName("an interrupt in the pacing pause AFTER an accepted send keeps that claim (the recap left)")
    void interruptAfterAcceptedKeepsClaim() {
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0), new MonthlyRecapStore.Recap("2", 1, 1, 0));
        auth(LifecycleEventResult.ACCEPTED);
        MonthlyRecapScheduler interrupted = new MonthlyRecapScheduler(store, authClient, SEPT_FIRST, d -> {
            throw new InterruptedException("shutdown");
        }, true, 5, INLINE);

        try {
            MonthlyRecapScheduler.PassResult result = interrupted.sendRecaps(AUGUST);

            assertThat(result.stopped()).isTrue();
            assertThat(result.sent()).isEqualTo(1);
            verify(store, never()).release(anyString(), any());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("a transient 503 that clears on the retry is sent once and keeps its claim")
    void transientBusyRecovers() {
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0));
        when(authClient.emitLifecycleEvent(anyString(), anyString(), anyMap()))
                .thenReturn(LifecycleEventResult.RETRY_LATER, LifecycleEventResult.ACCEPTED);

        MonthlyRecapScheduler.PassResult result = scheduler(true, 5).sendRecaps(AUGUST);

        assertThat(result.sent()).isEqualTo(1);
        verify(store, never()).release(anyString(), any());
    }

    @Test
    @DisplayName("Regression (month consumed by the kill switch): lifecycle emails off gives the claim back and stops the pass")
    void inactiveReleasesAndStops() {
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0), new MonthlyRecapStore.Recap("2", 1, 1, 0));
        auth(LifecycleEventResult.INACTIVE);
        List<ch.qos.logback.classic.spi.ILoggingEvent> logged = new ArrayList<>();

        MonthlyRecapScheduler.PassResult result = capturingLogs(logged, () -> scheduler(true, 5).sendRecaps(AUGUST));

        // Said as what it is, not as an outage: nobody should chase a busy auth-service.
        assertThat(logged).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("lifecycle emails are off"));
        assertThat(logged).noneSatisfy(e -> assertThat(e.getFormattedMessage()).contains("busy or unreachable"));

        assertThat(result.stopped()).isTrue();
        assertThat(result.sent()).isZero();
        assertThat(result.deferred()).isEqualTo(1);
        // Not a transient failure: no back-off retries against a switched-off sender.
        verify(authClient, times(1)).emitLifecycleEvent(eq("1"), anyString(), anyMap());
        assertThat(sleeps).isEmpty();
        verify(store).release("1", AUGUST);
        verify(store, never()).claim("2", AUGUST);
    }

    @Test
    @DisplayName("end to end through the real AuthClient: auth-service's 204 (emails off) releases the claim, its 202 keeps it")
    void realClientMapsNoContentToRelease() throws Exception {
        org.springframework.web.client.RestTemplate bounded = mock(org.springframework.web.client.RestTemplate.class);
        AuthClient realClient = new AuthClient(mock(org.springframework.web.client.RestTemplate.class), "http://auth.test");
        java.lang.reflect.Field field = AuthClient.class.getDeclaredField("boundedRestTemplate");
        field.setAccessible(true);
        field.set(realClient, bounded);
        when(bounded.exchange(anyString(), any(org.springframework.http.HttpMethod.class),
                any(org.springframework.http.HttpEntity.class), eq(Void.class)))
                .thenReturn(org.springframework.http.ResponseEntity.accepted().build(),
                        org.springframework.http.ResponseEntity.noContent().build());
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0), new MonthlyRecapStore.Recap("2", 1, 1, 0),
                new MonthlyRecapStore.Recap("3", 1, 1, 0));

        MonthlyRecapScheduler.PassResult result =
                new MonthlyRecapScheduler(store, realClient, SEPT_FIRST, sleeps::add, true, 5, INLINE).sendRecaps(AUGUST);

        assertThat(result.sent()).isEqualTo(1);
        assertThat(result.stopped()).isTrue();
        verify(store, never()).release("1", AUGUST);
        verify(store).release("2", AUGUST);
        verify(store, never()).claim("3", AUGUST);
    }

    @Test
    @DisplayName("Regression (claim held silently): a release that throws is logged and the pass still ends as deferred")
    void failingReleaseIsLoggedAndPassEndsDeferred() {
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0));
        auth(LifecycleEventResult.RETRY_LATER);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(store).release("1", AUGUST);
        List<ch.qos.logback.classic.spi.ILoggingEvent> logged = new ArrayList<>();

        MonthlyRecapScheduler.PassResult result = capturingLogs(logged, () -> scheduler(true, 5).sendRecaps(AUGUST));

        assertThat(result.stopped()).isTrue();
        assertThat(result.deferred()).isEqualTo(1);
        assertThat(logged).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN);
            assertThat(e.getFormattedMessage()).contains("could not be released").contains("1").contains("db down");
        });
    }

    @Test
    @DisplayName("a refused recap (unknown user) keeps its claim: it is never retried")
    void refusedKeepsClaim() {
        candidates(new MonthlyRecapStore.Recap("ghost", 1, 1, 0), new MonthlyRecapStore.Recap("2", 1, 1, 0));
        when(authClient.emitLifecycleEvent(eq("ghost"), anyString(), anyMap())).thenReturn(LifecycleEventResult.REFUSED);
        when(authClient.emitLifecycleEvent(eq("2"), anyString(), anyMap())).thenReturn(LifecycleEventResult.ACCEPTED);

        MonthlyRecapScheduler.PassResult result = scheduler(true, 5).sendRecaps(AUGUST);

        assertThat(result.refused()).isEqualTo(1);
        assertThat(result.sent()).isEqualTo(1);
        verify(authClient, times(1)).emitLifecycleEvent(eq("ghost"), anyString(), anyMap());
        verify(store, never()).release(anyString(), any());
    }

    @Test
    @DisplayName("a client that throws anyway still gives the claim back")
    void throwingClientReleases() {
        candidates(new MonthlyRecapStore.Recap("1", 1, 1, 0));
        when(authClient.emitLifecycleEvent(anyString(), anyString(), anyMap())).thenThrow(new IllegalStateException("boom"));

        MonthlyRecapScheduler.PassResult result = scheduler(true, 5).sendRecaps(AUGUST);

        assertThat(result.stopped()).isTrue();
        verify(store).release("1", AUGUST);
    }

    @Test
    @DisplayName("a database failure ends the pass quietly instead of throwing")
    void storeFailureNeverThrows() {
        when(store.candidates(AUGUST)).thenThrow(new IllegalStateException("db down"));

        MonthlyRecapScheduler.PassResult result = scheduler(true, 5).sendRecaps(AUGUST);

        assertThat(result.stopped()).isTrue();
        verifyNoInteractions(authClient);
    }

    @Test
    @DisplayName("disabled, or a self-hosted edition: the scheduled slot does nothing")
    void disabledAndSelfHostedAreInert() {
        scheduler(false, 5).scheduled();

        AppEditionProvider selfHosted = mock(AppEditionProvider.class);
        when(selfHosted.isSelfHosted()).thenReturn(true);
        new MonthlyRecapScheduler(store, authClient, selfHosted, true, 5).scheduled();

        verifyNoInteractions(store, authClient);
    }
}
