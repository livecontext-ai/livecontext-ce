package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleTrustBootstrap;
import com.apimarketplace.agent.catalog.bundle.TrustedKeyRegistry;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.SkillBundleRepository;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The skill poller's backoff and conditional fetch; mirrors the model-catalog poller's test. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SkillBundleSyncScheduler - poll backoff and conditional fetch")
class SkillBundleSyncSchedulerBackoffTest {

    @Mock private SkillBundleFetcher fetcher;
    @Mock private SkillBundleVerifier verifier;
    @Mock private SkillBundleApplier applier;
    @Mock private SkillBundleSyncStatusRepository syncStatusRepo;
    @Mock private SkillBundleRepository bundleRepo;
    @Mock private TrustedKeyRegistry trustedKeys;
    @Mock private CatalogBundleTrustBootstrap trustBootstrap;
    @Mock private ObjectProvider<CloudLlmRuntimeAccess> runtimeAccessProvider;
    @Mock private CloudLlmRuntimeAccess runtimeAccess;

    private static final CloudLlmRuntimeCredentials CREDS =
            new CloudLlmRuntimeCredentials("tok", "install-1", "https://cloud");

    private final SignedSkillBundle bundle = new SignedSkillBundle(
            3L, 1, "c".repeat(64), "sig", "k1", "cloud", 2, 100, "cGF5bG9hZA==");
    private SkillBundleSyncScheduler scheduler;
    private SkillBundleSyncStatusEntity row;

    @BeforeEach
    void setUp() {
        scheduler = new SkillBundleSyncScheduler(fetcher, verifier, applier, syncStatusRepo, bundleRepo,
                trustedKeys, trustBootstrap, runtimeAccessProvider);
        row = new SkillBundleSyncStatusEntity();
        when(syncStatusRepo.findById(SkillBundleSyncStatusEntity.SINGLETON_ID)).thenReturn(Optional.of(row));
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(CREDS));
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(bundleRepo.findActiveChecksum()).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("scheduled tick inside the backoff window: no cloud call")
    void scheduledTickDeferred() {
        row.setNextAttemptAt(Instant.now().plus(Duration.ofMinutes(20)));

        scheduler.tickIfDue();

        verifyNoInteractions(fetcher, runtimeAccessProvider);
        verify(syncStatusRepo, never()).updateBackoff(anyInt(), any());
    }

    @Test
    @DisplayName("manual sync now ignores the backoff window")
    void manualTickIgnoresBackoff() {
        row.setNextAttemptAt(Instant.now().plus(Duration.ofHours(6)));
        when(fetcher.fetchLatest(any(), any())).thenReturn(SkillBundleFetcher.FetchResult.noActive());

        scheduler.tick();

        verify(fetcher).fetchLatest(eq(CREDS), any());
        verify(syncStatusRepo).updateBackoff(0, null);
    }

    @Test
    @DisplayName("the attempt is counted before the download, and an Error during apply leaves it armed")
    void errorDuringApplyLeavesBackoffArmed() {
        when(fetcher.fetchLatest(any(), any())).thenReturn(SkillBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(SkillBundleVerifier.Result.success(new byte[]{1}));
        when(applier.apply(any(), any(), any())).thenThrow(new OutOfMemoryError("Java heap space"));

        assertThatThrownBy(() -> scheduler.tick()).isInstanceOf(OutOfMemoryError.class);

        InOrder order = inOrder(syncStatusRepo, fetcher);
        order.verify(syncStatusRepo).updateBackoff(eq(1), any(Instant.class));
        order.verify(fetcher).fetchLatest(any(), any());
        verify(syncStatusRepo, never()).updateBackoff(eq(0), isNull());
    }

    @Test
    @DisplayName("a Retry-After from the cloud lengthens the wait")
    void retryAfterHonoured() {
        when(fetcher.fetchLatest(any(), any())).thenReturn(new SkillBundleFetcher.FetchResult(
                SkillBundleFetcher.Status.HTTP_ERROR, null, "HTTP 429", Duration.ofHours(2)));

        Instant before = Instant.now();
        scheduler.tick();

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        verify(syncStatusRepo, times(2)).updateBackoff(eq(1), next.capture());
        assertThat(next.getAllValues().get(1)).isAfterOrEqualTo(before.plus(Duration.ofHours(2)));
    }

    @Test
    @DisplayName("a successful apply clears the backoff")
    void appliedClears() {
        when(fetcher.fetchLatest(any(), any())).thenReturn(SkillBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(SkillBundleVerifier.Result.success(new byte[]{1}));
        when(applier.apply(any(), any(), any())).thenReturn(SkillBundleApplier.ApplyResult.alreadyApplied(3L));

        scheduler.tick();

        verify(syncStatusRepo).updateBackoff(0, null);
    }

    @Test
    @DisplayName("APPLY_FAILED keeps the backoff")
    void applyFailedKeepsBackoff() {
        when(fetcher.fetchLatest(any(), any())).thenReturn(SkillBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(SkillBundleVerifier.Result.success(new byte[]{1}));
        when(applier.apply(any(), any(), any())).thenReturn(SkillBundleApplier.ApplyResult.failed("boom"));

        scheduler.tick();

        verify(syncStatusRepo, never()).updateBackoff(eq(0), isNull());
    }

    @Test
    @DisplayName("an empty cloud URL downloads nothing: it clears the wait instead of arming one")
    void notConfiguredLeavesNoWait() {
        row.setBackoffLevel(4);
        when(fetcher.fetchLatest(any(), any())).thenReturn(SkillBundleFetcher.FetchResult.notConfigured());

        scheduler.tick();

        // The attempt is armed before the fetch (level 5), then cleared: the LAST write wins.
        InOrder order = inOrder(syncStatusRepo);
        order.verify(syncStatusRepo).updateBackoff(eq(5), any(Instant.class));
        order.verify(syncStatusRepo).updateBackoff(0, null);
        verify(syncStatusRepo, org.mockito.Mockito.times(2)).updateBackoff(anyInt(), any());
    }

    @Test
    @DisplayName("a trust failure happens before any download: the backoff is left exactly as it was")
    void trustFailureTouchesNoBackoff() {
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust()).thenReturn(CatalogBundleTrustBootstrap.Result.skipped("503"));

        scheduler.tick();

        verifyNoInteractions(fetcher);
        verify(syncStatusRepo, never()).updateBackoff(anyInt(), any());
    }

    @Test
    @DisplayName("tickIfDue carries @Scheduled and the lock; the manual tick() the SAME lock and no @Scheduled")
    void lockPlacement() throws NoSuchMethodException {
        java.lang.reflect.Method scheduledEntry = SkillBundleSyncScheduler.class.getDeclaredMethod("tickIfDue");
        java.lang.reflect.Method manualEntry = SkillBundleSyncScheduler.class.getDeclaredMethod("tick");
        net.javacrumbs.shedlock.spring.annotation.SchedulerLock scheduledLock =
                scheduledEntry.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);
        net.javacrumbs.shedlock.spring.annotation.SchedulerLock manualLock =
                manualEntry.getAnnotation(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class);

        assertThat(scheduledEntry.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)).isTrue();
        assertThat(manualEntry.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class))
                .as("a scheduled tick() would ignore the backoff on every firing").isFalse();
        assertThat(scheduledLock).isNotNull();
        assertThat(manualLock).isNotNull();
        assertThat(manualLock.name()).isEqualTo(scheduledLock.name());
    }

    @Test
    @DisplayName("the held checksum is sent, and a 304 is a healthy sync with nothing applied")
    void notModifiedIsHealthy() {
        row.setConsecutiveFailures(2);
        when(bundleRepo.findActiveChecksum()).thenReturn(Optional.of("c".repeat(64)));
        when(fetcher.fetchLatest(any(), any())).thenReturn(SkillBundleFetcher.FetchResult.notModified());

        scheduler.tick();

        verify(fetcher).fetchLatest(CREDS, "c".repeat(64));
        verifyNoInteractions(verifier, applier);
        ArgumentCaptor<SkillBundleSyncStatusEntity> saved = ArgumentCaptor.forClass(SkillBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(saved.capture());
        assertThat(saved.getValue().getLastFetchStatus()).isEqualTo("OK");
        assertThat(saved.getValue().getConsecutiveFailures()).isZero();
        verify(syncStatusRepo).updateBackoff(0, null);
    }
}
