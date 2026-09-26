package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleRepository;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The model-catalog poller's backoff: a failing install must slow down instead of downloading
 * on every 15-minute tick forever, and an attempt that never reports back (an Error thrown out of
 * the apply, a killed JVM) must count as a failure. Also covers the conditional GET: the checksum
 * this install holds is sent so an unchanged bundle is a 304.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CatalogBundleSyncScheduler - poll backoff and conditional fetch")
class CatalogBundleSyncSchedulerBackoffTest {

    @Mock private CatalogBundleFetcher fetcher;
    @Mock private CatalogBundleVerifier verifier;
    @Mock private CatalogBundleApplier applier;
    @Mock private CatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private CatalogBundleRepository bundleRepo;
    @Mock private TrustedKeyRegistry trustedKeys;
    @Mock private CatalogBundleTrustBootstrap trustBootstrap;
    @Mock private ObjectProvider<CloudLlmRuntimeAccess> runtimeAccessProvider;
    @Mock private CloudLlmRuntimeAccess runtimeAccess;

    @InjectMocks private CatalogBundleSyncScheduler scheduler;

    private static final CloudLlmRuntimeCredentials CREDS =
            new CloudLlmRuntimeCredentials("tok", "install-1", "https://cloud.example/api");

    private final SignedBundle bundle = new SignedBundle(7L, 1, "a".repeat(64), "sig", "k1", "cloud",
            10, 1000, "cGF5bG9hZA==");
    private CatalogBundleSyncStatusEntity row;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "cloudUrl", "https://cloud.example");
        row = new CatalogBundleSyncStatusEntity();
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(CREDS));
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(bundleRepo.findActiveChecksum()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("scheduled tick inside the backoff window: no cloud call, no bookkeeping")
    void scheduledTickDeferred() {
        row.setBackoffLevel(4);
        row.setNextAttemptAt(Instant.now().plus(Duration.ofHours(1)));

        scheduler.tickIfDue();

        verifyNoInteractions(fetcher, verifier, applier, runtimeAccessProvider, trustBootstrap);
        verify(syncStatusRepo, never()).updateBackoff(anyInt(), any());
        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("scheduled tick once the window has passed: it runs")
    void scheduledTickDueRuns() {
        row.setBackoffLevel(4);
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.noActive());

        scheduler.tickIfDue();

        verify(fetcher).fetchLatest(eq(CREDS), any());
    }

    @Test
    @DisplayName("manual sync now ignores the backoff window: the operator asked for it")
    void manualTickIgnoresBackoff() {
        row.setBackoffLevel(6);
        row.setNextAttemptAt(Instant.now().plus(Duration.ofHours(6)));
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.noActive());

        scheduler.tick();

        verify(fetcher).fetchLatest(eq(CREDS), any());
    }

    @Test
    @DisplayName("the attempt is counted BEFORE the download: level raised and wait armed, then the fetch")
    void attemptCountedBeforeFetch() {
        row.setBackoffLevel(2);
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.noActive());

        Instant before = Instant.now();
        scheduler.tick();
        Instant after = Instant.now();

        InOrder order = inOrder(syncStatusRepo, fetcher);
        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        order.verify(syncStatusRepo).updateBackoff(eq(3), next.capture());
        order.verify(fetcher).fetchLatest(any(), any());
        assertThat(next.getValue()).isBetween(before.plus(Duration.ofHours(1)), after.plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("Regression: an Error escaping the apply (the OutOfMemoryError case) leaves the backoff armed")
    void errorDuringApplyLeavesBackoffArmed() {
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(CatalogBundleVerifier.Result.success(new byte[]{1}));
        when(applier.apply(any(), any(), any())).thenThrow(new OutOfMemoryError("Java heap space"));

        // runSync() catches Exception, not Error: nothing after the throw runs. Before the fix
        // no failure was ever recorded and the next tick downloaded the bundle again.
        assertThatThrownBy(() -> scheduler.tick()).isInstanceOf(OutOfMemoryError.class);

        verify(syncStatusRepo).updateBackoff(eq(1), any(Instant.class));
        verify(syncStatusRepo, never()).updateBackoff(eq(0), isNull());
    }

    @Test
    @DisplayName("a network failure keeps the raised level and waits the ladder's delay")
    void failureWaitsTheLadder() {
        row.setBackoffLevel(3);
        when(fetcher.fetchLatest(any(), any()))
                .thenReturn(CatalogBundleFetcher.FetchResult.networkError("connect timed out"));

        Instant before = Instant.now();
        scheduler.tick();
        Instant after = Instant.now();

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(syncStatusRepo, org.mockito.Mockito.times(2)).updateBackoff(eq(4), next.capture());
        assertThat(next.getAllValues().get(1))
                .isBetween(before.plus(Duration.ofHours(2)), after.plus(Duration.ofHours(2)));
        verify(syncStatusRepo, never()).updateBackoff(eq(0), isNull());
    }

    @Test
    @DisplayName("a 429 with Retry-After longer than the ladder is honoured")
    void retryAfterHonoured() {
        when(fetcher.fetchLatest(any(), any())).thenReturn(new CatalogBundleFetcher.FetchResult(
                CatalogBundleFetcher.Status.HTTP_ERROR, null, "HTTP 429", Duration.ofHours(5)));

        Instant before = Instant.now();
        scheduler.tick();

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(syncStatusRepo, org.mockito.Mockito.times(2)).updateBackoff(eq(1), next.capture());
        assertThat(next.getAllValues().get(1)).isAfterOrEqualTo(before.plus(Duration.ofHours(5)));
    }

    @Test
    @DisplayName("a successful apply clears the backoff")
    void appliedClears() {
        row.setBackoffLevel(5);
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(CatalogBundleVerifier.Result.success(new byte[]{1}));
        when(applier.apply(any(), any(), any())).thenReturn(new CatalogBundleApplier.ApplyResult(
                CatalogBundleApplier.Status.APPLIED, 7L, 1, 0, 0, 0, 0, null));

        scheduler.tick();

        verify(syncStatusRepo).updateBackoff(0, null);
    }

    @Test
    @DisplayName("APPLY_FAILED does NOT clear the backoff")
    void applyFailedKeepsBackoff() {
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(CatalogBundleVerifier.Result.success(new byte[]{1}));
        when(applier.apply(any(), any(), any())).thenReturn(CatalogBundleApplier.ApplyResult.failed("boom"));

        scheduler.tick();

        verify(syncStatusRepo, never()).updateBackoff(eq(0), isNull());
    }

    @Test
    @DisplayName("the checksum this install holds is sent as If-None-Match")
    void sendsKnownChecksum() {
        when(bundleRepo.findActiveChecksum()).thenReturn(Optional.of("b".repeat(64)));
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.notModified());

        scheduler.tick();

        verify(fetcher).fetchLatest(CREDS, "b".repeat(64));
    }

    @Test
    @DisplayName("a 304 is a healthy sync: OK status, failures reset, backoff cleared, nothing applied")
    void notModifiedIsHealthy() {
        row.setConsecutiveFailures(3);
        row.setLastFetchStatus("NETWORK_ERROR");
        row.setLastFetchError("timeout");
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.notModified());

        scheduler.tick();

        verifyNoInteractions(verifier, applier);
        ArgumentCaptor<CatalogBundleSyncStatusEntity> saved = ArgumentCaptor.forClass(CatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(saved.capture());
        assertThat(saved.getValue().getLastFetchStatus()).isEqualTo("OK");
        assertThat(saved.getValue().getLastFetchError()).isNull();
        assertThat(saved.getValue().getConsecutiveFailures()).isZero();
        verify(syncStatusRepo).updateBackoff(0, null);
    }

    @Test
    @DisplayName("an empty cloud URL downloads nothing: it clears the wait instead of arming one")
    void notConfiguredLeavesNoWait() {
        row.setBackoffLevel(4);
        when(fetcher.fetchLatest(any(), any())).thenReturn(CatalogBundleFetcher.FetchResult.notConfigured());

        scheduler.tick();

        // The attempt is armed before the fetch (level 5), then cleared: the LAST write wins.
        InOrder order = inOrder(syncStatusRepo);
        order.verify(syncStatusRepo).updateBackoff(eq(5), any(Instant.class));
        order.verify(syncStatusRepo).updateBackoff(0, null);
        verify(syncStatusRepo, org.mockito.Mockito.times(2)).updateBackoff(anyInt(), any());
    }

    @Test
    @DisplayName("a trust bootstrap failure happens before any download: the backoff is left exactly as it was")
    void trustFailureTouchesNoBackoff() {
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(CatalogBundleTrustBootstrap.Result.skipped("HTTP 503 from cloud signing-key"));

        scheduler.tick();

        verifyNoInteractions(fetcher);
        verify(syncStatusRepo, never()).updateBackoff(anyInt(), any());
    }

    @Test
    @DisplayName("a status row that cannot be read skips the scheduled tick instead of throwing out of @Scheduled")
    void unreadableRowSkipsTick() {
        when(syncStatusRepo.findById(any())).thenThrow(new IllegalStateException("db down"));

        org.assertj.core.api.Assertions.assertThatCode(() -> scheduler.tickIfDue()).doesNotThrowAnyException();
        verifyNoInteractions(fetcher);
    }

    @Test
    @DisplayName("an unlinked install makes no cloud call, so it neither raises nor clears the backoff")
    void notLinkedTouchesNoBackoff() {
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.empty());

        scheduler.tick();

        verify(syncStatusRepo, never()).updateBackoff(anyInt(), any());
        verifyNoInteractions(fetcher);
    }
}
