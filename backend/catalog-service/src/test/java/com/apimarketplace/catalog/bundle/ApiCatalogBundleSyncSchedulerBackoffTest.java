package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The API-catalog poller's backoff, the case that motivated it: a 32 MB bundle an install could
 * not apply was downloaded again on every 15-minute tick, and an OutOfMemoryError during the
 * apply recorded no failure at all.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiCatalogBundleSyncScheduler - poll backoff")
class ApiCatalogBundleSyncSchedulerBackoffTest {

    @Mock private ApiCatalogBundleFetcher fetcher;
    @Mock private ApiCatalogBundleService bundleService;
    @Mock private ApiCatalogBundleVerifier verifier;
    @Mock private ApiCatalogBundleApplier applier;
    @Mock private ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private ApiCatalogTrustedKeyRegistry trustedKeys;
    @Mock private ApiCatalogBundleTrustBootstrap trustBootstrap;

    @InjectMocks private ApiCatalogBundleSyncScheduler scheduler;

    private final ApiCatalogSignedBundle bundle = new ApiCatalogSignedBundle(7L, 1, "a".repeat(64), "sig", "k1",
            "cloud", 10, 50, 100_000, "cGF5bG9hZA==");
    private ApiCatalogBundleSyncStatusEntity row;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "cloudUrl", "https://cloud.example");
        row = new ApiCatalogBundleSyncStatusEntity();
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(bundleService.getActiveBundleMetadata()).thenReturn(Optional.empty());
        when(trustedKeys.hasKeys()).thenReturn(true);
    }

    private void fetchAndVerifyOk() {
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(ApiCatalogBundleVerifier.Result.success(new byte[]{1}));
    }

    @Test
    @DisplayName("scheduled tick inside the backoff window: no download, no bookkeeping")
    void scheduledTickDeferred() {
        row.setBackoffLevel(5);
        row.setNextAttemptAt(Instant.now().plus(Duration.ofHours(3)));

        scheduler.tickIfDue();

        verifyNoInteractions(fetcher, verifier, applier, trustBootstrap);
        verify(syncStatusRepo, never()).updateBackoff(anyInt(), any());
        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("scheduled tick once the window has passed: it downloads")
    void scheduledTickDueRuns() {
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.notModified());

        scheduler.tickIfDue();

        verify(fetcher).fetchLatest(any());
    }

    @Test
    @DisplayName("manual sync now ignores the window")
    void manualTickIgnoresBackoff() {
        row.setNextAttemptAt(Instant.now().plus(Duration.ofHours(6)));
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.notModified());

        scheduler.tick();

        verify(fetcher).fetchLatest(any());
    }

    @Test
    @DisplayName("Regression: an OutOfMemoryError while applying leaves the backoff armed (it used to record nothing)")
    void outOfMemoryDuringApplyLeavesBackoffArmed() {
        row.setBackoffLevel(1);
        fetchAndVerifyOk();
        when(applier.apply(any(), any(), any())).thenThrow(new OutOfMemoryError("Java heap space"));

        Instant before = Instant.now();
        assertThatThrownBy(() -> scheduler.tick()).isInstanceOf(OutOfMemoryError.class);

        InOrder order = inOrder(syncStatusRepo, fetcher);
        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        order.verify(syncStatusRepo).updateBackoff(eq(2), next.capture());
        order.verify(fetcher).fetchLatest(any());
        // Level 2 = 30 min: the next 15-minute tick will NOT download the 32 MB again.
        assertThat(next.getValue()).isAfterOrEqualTo(before.plus(Duration.ofMinutes(30)));
        verify(syncStatusRepo, never()).updateBackoff(eq(0), isNull());
    }

    @Test
    @DisplayName("APPLY_PARTIAL is not a success: the next attempt re-downloads, so the backoff holds")
    void partialApplyKeepsBackoff() {
        row.setBackoffLevel(2);
        fetchAndVerifyOk();
        when(applier.apply(any(), any(), any())).thenReturn(new ApiCatalogBundleApplier.ApplyResult(
                ApiCatalogBundleApplier.Status.APPLY_PARTIAL, 7L, 5, 20, 0, 0, 0, "3 APIs failed"));

        Instant before = Instant.now();
        scheduler.tick();

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(syncStatusRepo, times(2)).updateBackoff(eq(3), next.capture());
        assertThat(next.getAllValues().get(1)).isAfterOrEqualTo(before.plus(Duration.ofHours(1)));
        verify(syncStatusRepo, never()).updateBackoff(eq(0), isNull());
    }

    @Test
    @DisplayName("APPLIED clears the backoff")
    void appliedClears() {
        row.setBackoffLevel(4);
        fetchAndVerifyOk();
        when(applier.apply(any(), any(), any())).thenReturn(new ApiCatalogBundleApplier.ApplyResult(
                ApiCatalogBundleApplier.Status.APPLIED, 7L, 10, 50, 0, 0, 0, null));

        scheduler.tick();

        verify(syncStatusRepo).updateBackoff(0, null);
    }

    @Test
    @DisplayName("a 304 clears the backoff")
    void notModifiedClears() {
        row.setBackoffLevel(3);
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.notModified());

        scheduler.tick();

        verify(applier).reofferStoredPrices();
        verify(syncStatusRepo).updateBackoff(0, null);
    }

    @Test
    @DisplayName("a 304 whose local price re-offer then fails does not push a healthy install into a wait")
    void reofferFailureAfter304DoesNotBackOff() {
        statefulBackoffRow();
        row.setBackoffLevel(2);
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.notModified());
        org.mockito.Mockito.doThrow(new IllegalStateException("price table locked"))
                .when(applier).reofferStoredPrices();

        scheduler.tick();

        // The failure is still recorded for the operator...
        ArgumentCaptor<ApiCatalogBundleSyncStatusEntity> saved =
                ArgumentCaptor.forClass(ApiCatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(saved.capture());
        assertThat(saved.getValue().getLastFetchStatus()).isEqualTo("UNEXPECTED_ERROR");
        // ...but the cloud answered, so no wait is left behind.
        assertThat(row.getBackoffLevel()).isZero();
        assertThat(row.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("an unreadable body (the Jackson-cap failure) backs off instead of re-downloading every tick")
    void unreadableBodyBacksOff() {
        row.setBackoffLevel(1);
        when(fetcher.fetchLatest(any())).thenReturn(
                ApiCatalogBundleFetcher.FetchResult.httpError("unreadable bundle body (32418851 bytes)"));

        scheduler.tick();

        verify(syncStatusRepo, times(2)).updateBackoff(eq(2), any(Instant.class));
        verify(syncStatusRepo, never()).updateBackoff(eq(0), isNull());
    }

    @Test
    @DisplayName("the throttle's 429 + Retry-After travels from the fetcher to the backoff")
    @SuppressWarnings("unchecked")
    void throttleRetryAfterEndToEnd() {
        RestTemplate rest = mock(RestTemplate.class);
        HttpHeaders h = new HttpHeaders();
        h.set(HttpHeaders.RETRY_AFTER, String.valueOf(Duration.ofHours(5).toSeconds()));
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(byte[].class)))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many", h, null, null));
        ReflectionTestUtils.setField(scheduler, "fetcher", new ApiCatalogBundleFetcher(rest, "https://cloud.example"));

        Instant before = Instant.now();
        scheduler.tick();

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(syncStatusRepo, times(2)).updateBackoff(eq(1), next.capture());
        assertThat(next.getAllValues().get(1)).isAfterOrEqualTo(before.plus(Duration.ofHours(5)));
        ArgumentCaptor<ApiCatalogBundleSyncStatusEntity> saved =
                ArgumentCaptor.forClass(ApiCatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(saved.capture());
        assertThat(saved.getValue().getLastFetchStatus()).isEqualTo("HTTP_ERROR");
        assertThat(saved.getValue().getLastFetchError()).contains("429");
    }

    /** Make updateBackoff write into {@link #row}, like the real UPDATE on the singleton row. */
    private void statefulBackoffRow() {
        org.mockito.Mockito.doAnswer(inv -> {
            row.setBackoffLevel(inv.getArgument(0));
            row.setNextAttemptAt(inv.getArgument(1));
            return 1;
        }).when(syncStatusRepo).updateBackoff(anyInt(), any());
    }

    @Test
    @DisplayName("the whole ladder on one row: a blip retries at the next tick, a streak silences the following ticks, a success reopens")
    void ladderEndToEnd() {
        statefulBackoffRow();
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.networkError("timeout"));

        scheduler.tickIfDue();                                         // failure 1: level 1, 10 min
        assertThat(row.getBackoffLevel()).isEqualTo(1);
        assertThat(org.mockito.Mockito.mockingDetails(fetcher).getInvocations()).hasSize(1);

        // The next quarter-hour tick is past a 10-minute wait: it runs (a single blip costs nothing).
        row.setNextAttemptAt(row.getNextAttemptAt().minus(Duration.ofMinutes(15)));
        scheduler.tickIfDue();                                         // failure 2: level 2, 30 min
        assertThat(row.getBackoffLevel()).isEqualTo(2);
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now().plus(Duration.ofMinutes(29)));

        scheduler.tickIfDue();                                         // inside the 30 minutes: silent
        assertThat(org.mockito.Mockito.mockingDetails(fetcher).getInvocations()).hasSize(2);

        // Operator presses "sync now" once the cloud is back: runs despite the wait, and reopens.
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.notModified());
        scheduler.tick();
        assertThat(row.getBackoffLevel()).isZero();
        assertThat(row.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("an empty cloud URL downloads nothing, so it must not leave a wait behind (the fixed install would stay silent for hours)")
    void notConfiguredLeavesNoWait() {
        statefulBackoffRow();
        row.setBackoffLevel(5);
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.notConfigured());

        scheduler.tickIfDue();

        assertThat(row.getBackoffLevel()).isZero();
        assertThat(row.getNextAttemptAt()).isNull();
    }

    @Test
    @DisplayName("a trust bootstrap failure happens before any download: the backoff is left exactly as it was")
    void trustFailureTouchesNoBackoff() {
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(ApiCatalogBundleTrustBootstrap.Result.skipped("signing-key endpoint 503"));

        scheduler.tick();

        verifyNoInteractions(fetcher);
        verify(syncStatusRepo, never()).updateBackoff(anyInt(), any());
    }

    @Test
    @DisplayName("if arming the attempt itself fails, the fallback bookkeeping does not reuse a previous attempt's level")
    void beginAttemptFailureUsesNoStaleLevel() {
        // A first run leaves level 3 behind in memory...
        row.setBackoffLevel(2);
        when(fetcher.fetchLatest(any())).thenReturn(ApiCatalogBundleFetcher.FetchResult.networkError("x"));
        scheduler.tick();
        org.mockito.Mockito.clearInvocations(syncStatusRepo);

        // ...then the next run cannot even arm its attempt.
        when(syncStatusRepo.updateBackoff(anyInt(), any())).thenThrow(new IllegalStateException("db blip"));
        scheduler.tick();

        // The only backoff write is the failing arm (level row+1); nothing with the stale level 3.
        verify(syncStatusRepo).updateBackoff(eq(3), any(Instant.class));
        verify(syncStatusRepo, times(1)).updateBackoff(anyInt(), any());
    }

    @Test
    @DisplayName("a status row that cannot be read skips the scheduled tick instead of throwing out of @Scheduled")
    void unreadableRowSkipsTick() {
        when(syncStatusRepo.findById(any())).thenThrow(new IllegalStateException("db down"));

        org.assertj.core.api.Assertions.assertThatCode(() -> scheduler.tickIfDue()).doesNotThrowAnyException();
        verifyNoInteractions(fetcher);
    }

    @Test
    @DisplayName("sync now while a sync is running returns at once: no second download, no request blocked for a whole apply")
    void manualDuringRunningSyncReturnsAtOnce() throws Exception {
        java.util.concurrent.CountDownLatch inFetch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(fetcher.fetchLatest(any())).thenAnswer(inv -> {
            inFetch.countDown();
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return ApiCatalogBundleFetcher.FetchResult.notModified();
        });
        Thread scheduled = new Thread(scheduler::tickIfDue);
        scheduled.start();
        assertThat(inFetch.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        boolean ran = scheduler.tick();
        scheduler.tickIfDue();                                         // a second cron firing too

        release.countDown();
        scheduled.join(5_000);
        assertThat(ran).isFalse();
        verify(fetcher, times(1)).fetchLatest(any());
    }
}
