package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the CE catalog-bundle sync scheduler. The scheduler is the
 * failure-persistence layer for the whole pipeline: every non-OK outcome must
 * land on {@code catalog_bundle_sync_status} so operators see it in the UI,
 * and the scheduler MUST survive every exception so Spring keeps firing it.
 *
 * <p>Happy path assertion: on APPLIED the applier writes the OK status row
 * itself (atomically with the apply transaction) - the scheduler must NOT
 * overwrite it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CatalogBundleSyncScheduler - fetch → verify → apply orchestration")
class CatalogBundleSyncSchedulerTest {

    @Mock private CatalogBundleFetcher fetcher;
    @Mock private CatalogBundleVerifier verifier;
    @Mock private CatalogBundleApplier applier;
    @Mock private CatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private TrustedKeyRegistry trustedKeys;
    @Mock private CatalogBundleTrustBootstrap trustBootstrap;
    @Mock private ObjectProvider<CloudLlmRuntimeAccess> runtimeAccessProvider;
    @Mock private CloudLlmRuntimeAccess runtimeAccess;

    @InjectMocks private CatalogBundleSyncScheduler scheduler;

    private static final CloudLlmRuntimeCredentials CREDS =
            new CloudLlmRuntimeCredentials("tok-123", "install-1", "https://cloud.example/api");

    private SignedBundle bundle;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "cloudUrl", "https://cloud.example");
        bundle = new SignedBundle(7L, 1, "a".repeat(64), "sig", "k1", "cloud",
                10, 1000, "cGF5bG9hZA==");
        // Default to a preloaded status row - loadOrInit uses findById.
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(new CatalogBundleSyncStatusEntity()));
        // Default to a cloud-LINKED install so the existing fetch/verify/apply tests
        // exercise the download path. The not-linked gate is covered explicitly below.
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(CREDS));
    }

    @Test
    @DisplayName("Linked + empty registry + TOFU bootstrap fails → TRUST_UNCONFIGURED, no bundle fetch")
    void trustUnconfiguredWhenBootstrapFails() {
        // Cloud-linked (default from setUp) but no pinned key, and the cloud has no
        // signing key to bootstrap from → the tick must still bail with TRUST_UNCONFIGURED.
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(CatalogBundleTrustBootstrap.Result.skipped("HTTP 503 from cloud signing-key"));

        scheduler.tick();

        verify(trustBootstrap).bootstrapTrust();
        verifyNoInteractions(fetcher, verifier, applier);
        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("TRUST_UNCONFIGURED");
        assertThat(saved.getLastFetchError()).contains("503");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("Linked + empty registry + TOFU bootstrap pins → proceeds to fetch (no TRUST_UNCONFIGURED)")
    void tofuBootstrapThenProceeds() {
        // Empty registry but the linked cloud publishes a signing key: TOFU pins it and the
        // tick proceeds into the normal fetch path instead of recording TRUST_UNCONFIGURED.
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(CatalogBundleTrustBootstrap.Result.pinned("livecontext-prod-v1"));
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.NO_ACTIVE, null, null));

        scheduler.tick();

        verify(trustBootstrap).bootstrapTrust();
        verify(fetcher).fetchLatest(any());
        CatalogBundleSyncStatusEntity saved = captureSaved();
        // NO_ACTIVE is recorded by the downstream path - crucially NOT TRUST_UNCONFIGURED.
        assertThat(saved.getLastFetchStatus()).isEqualTo("NO_ACTIVE");
    }

    @Test
    @DisplayName("Linked + key already pinned → TOFU bootstrap NOT attempted, fetch proceeds")
    void alreadyPinnedSkipsBootstrap() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.NO_ACTIVE, null, null));

        scheduler.tick();

        // A pinned key (operator env or earlier TOFU) must never trigger a re-fetch of the
        // signing key - requirement 1: bootstrap only when the registry is empty.
        verifyNoInteractions(trustBootstrap);
        verify(fetcher).fetchLatest(any());
    }

    @Test
    @DisplayName("Not cloud-linked → NOT_LINKED recorded (informational), NO fetch - no link, no updates")
    void notLinkedSkipsFetch() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        // This install has no active cloud link → resolveActiveCloudRuntime is empty.
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.empty());
        CatalogBundleSyncStatusEntity existing = new CatalogBundleSyncStatusEntity();
        existing.setConsecutiveFailures(2); // not-linked is not a failure → counter must stay
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(existing));

        scheduler.tick();

        // The bundle is never fetched/verified/applied for an unlinked install, and TOFU
        // never fetches the signing key (requirement 1: bootstrap only when cloud-linked).
        verifyNoInteractions(fetcher, verifier, applier, trustBootstrap);
        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NOT_LINKED");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(2); // recordFetchOnly, not a failure
    }

    @Test
    @DisplayName("CloudLlmRuntimeAccess bean absent (misconfigured stack) → treated as not linked, NO fetch")
    void runtimeAccessBeanAbsentSkipsFetch() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(null);

        scheduler.tick();

        verifyNoInteractions(fetcher, verifier, applier, trustBootstrap);
        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NOT_LINKED");
    }

    @Test
    @DisplayName("FETCHED + verify OK + apply APPLIED → scheduler writes no extra status row")
    void happyPathNoDoubleWrite() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.FETCHED, bundle, null));
        when(verifier.verify(bundle))
                .thenReturn(CatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), eq("https://cloud.example")))
                .thenReturn(new CatalogBundleApplier.ApplyResult(
                        CatalogBundleApplier.Status.APPLIED, 7L, 1, 0, 0, 0, 0, null));

        scheduler.tick();

        // Applier owns the OK status row. Scheduler must not save on top of it.
        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("FETCHED + verify OK + apply ALREADY_APPLIED → scheduler writes no extra status row")
    void idempotentPathNoDoubleWrite() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.FETCHED, bundle, null));
        when(verifier.verify(bundle))
                .thenReturn(CatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), anyString()))
                .thenReturn(CatalogBundleApplier.ApplyResult.alreadyApplied(7L));

        scheduler.tick();

        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("FETCHED + verifier SIGNATURE_INVALID → failure row, applier never called")
    void signatureInvalid() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.FETCHED, bundle, null));
        when(verifier.verify(bundle))
                .thenReturn(CatalogBundleVerifier.Result.fail(
                        CatalogBundleVerifier.Status.SIGNATURE_INVALID, "tampered"));

        scheduler.tick();

        verifyNoInteractions(applier);
        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("SIGNATURE_INVALID");
        assertThat(saved.getLastFetchError()).isEqualTo("tampered");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("FETCHED + applier returns APPLY_FAILED → failure row persisted")
    void applyFailedResult() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.FETCHED, bundle, null));
        when(verifier.verify(bundle))
                .thenReturn(CatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), anyString()))
                .thenReturn(CatalogBundleApplier.ApplyResult.failed("missing models"));

        scheduler.tick();

        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("APPLY_FAILED");
        assertThat(saved.getLastFetchError()).isEqualTo("missing models");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("Applier throws unexpectedly → caught, APPLY_FAILED persisted")
    void applierThrows() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.FETCHED, bundle, null));
        when(verifier.verify(bundle))
                .thenReturn(CatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        scheduler.tick();

        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("APPLY_FAILED");
        assertThat(saved.getLastFetchError()).contains("db down");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("NO_ACTIVE → informational row recorded, consecutiveFailures NOT incremented")
    void noActive() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        CatalogBundleSyncStatusEntity existing = new CatalogBundleSyncStatusEntity();
        existing.setConsecutiveFailures(3); // prior failures persist until a real success
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(existing));
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.NO_ACTIVE, null, null));

        scheduler.tick();

        verifyNoInteractions(verifier, applier);
        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NO_ACTIVE");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(3); // unchanged
    }

    @Test
    @DisplayName("HTTP_ERROR → failure row, consecutiveFailures incremented from prior value")
    void httpError() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        CatalogBundleSyncStatusEntity existing = new CatalogBundleSyncStatusEntity();
        existing.setConsecutiveFailures(2);
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(existing));
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.HTTP_ERROR, null, "HTTP 500"));

        scheduler.tick();

        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("HTTP_ERROR");
        assertThat(saved.getLastFetchError()).isEqualTo("HTTP 500");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("NETWORK_ERROR → failure row persisted")
    void networkError() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.NETWORK_ERROR, null, "Connection refused"));

        scheduler.tick();

        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NETWORK_ERROR");
        assertThat(saved.getLastFetchError()).contains("Connection refused");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("NOT_CONFIGURED → failure row persisted")
    void notConfigured() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.NOT_CONFIGURED, null, "cloud-url empty"));

        scheduler.tick();

        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("First-ever tick with no status row yet → loadOrInit creates fresh row")
    void firstEverTickInitsRow() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        when(fetcher.fetchLatest(any()))
                .thenReturn(new CatalogBundleFetcher.FetchResult(
                        CatalogBundleFetcher.Status.NETWORK_ERROR, null, "timeout"));

        scheduler.tick();

        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NETWORK_ERROR");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
        assertThat(saved.getLastFetchAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("tick() inner failure → outer catch persists UNEXPECTED_ERROR, does not propagate")
    void outerCatchKeepsSchedulerAlive() {
        when(trustedKeys.hasKeys()).thenThrow(new RuntimeException("registry exploded"));

        // Must not throw - Spring's scheduler would otherwise stop firing.
        scheduler.tick();

        CatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("UNEXPECTED_ERROR");
        assertThat(saved.getLastFetchError()).contains("registry exploded");
    }

    @Test
    @DisplayName("Outer catch survives even if status persistence itself fails")
    void outerCatchSurvivesStatusPersistenceFailure() {
        when(trustedKeys.hasKeys()).thenThrow(new RuntimeException("registry exploded"));
        when(syncStatusRepo.findById(any())).thenThrow(new RuntimeException("db dead"));

        // Belt-and-braces: nothing we can do if status persistence fails, but
        // the scheduler must not die - Spring's @Scheduled would otherwise
        // stop firing it for the rest of the app's lifetime.
        assertThatCode(() -> scheduler.tick()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Regression: @Scheduled and @SchedulerLock sit on the SAME method (tick)")
    void schedulerLockSitsOnScheduledEntryPoint() throws NoSuchMethodException {
        // C1 regression guard: if a future refactor splits tick() into a
        // lock-less entry + a @SchedulerLock-annotated helper (invoked via
        // this.x()), AOP will not fire on the scheduled path and every pod
        // will race on deactivateAll() + save(active=true). Catching it here
        // is cheap - reproducing it in prod is not.
        java.lang.reflect.Method tick =
                CatalogBundleSyncScheduler.class.getDeclaredMethod("tick");
        assertThat(tick.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class))
                .as("@Scheduled must be on tick()")
                .isTrue();
        assertThat(tick.isAnnotationPresent(net.javacrumbs.shedlock.spring.annotation.SchedulerLock.class))
                .as("@SchedulerLock must be on tick() - self-invocation would bypass AOP")
                .isTrue();
    }

    private CatalogBundleSyncStatusEntity captureSaved() {
        ArgumentCaptor<CatalogBundleSyncStatusEntity> cap =
                ArgumentCaptor.forClass(CatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(cap.capture());
        return cap.getValue();
    }
}
