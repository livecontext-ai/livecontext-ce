package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
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

import java.util.List;
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
 * Unit tests for the CE API-catalog bundle sync scheduler. Mirrors the LLM
 * model bundle scheduler tests: every non-OK outcome must land on
 * {@code api_catalog_bundle_sync_status}, and the scheduler MUST survive every
 * exception so Spring keeps firing it.
 *
 * <p>Happy-path assertion: on APPLIED/ALREADY_APPLIED the applier writes the
 * OK status row itself - the scheduler must NOT overwrite it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiCatalogBundleSyncScheduler - fetch → verify → apply orchestration")
class ApiCatalogBundleSyncSchedulerTest {

    @Mock private ApiCatalogBundleFetcher fetcher;
    @Mock private ApiCatalogBundleVerifier verifier;
    @Mock private ApiCatalogBundleApplier applier;
    @Mock private ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private ApiCatalogTrustedKeyRegistry trustedKeys;
    @Mock private ApiCatalogBundleTrustBootstrap trustBootstrap;

    @InjectMocks private ApiCatalogBundleSyncScheduler scheduler;

    private ApiCatalogSignedBundle bundle;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "cloudUrl", "https://cloud.example");
        bundle = new ApiCatalogSignedBundle(7L, 1, "a".repeat(64), "sig", "k1", "cloud",
                10, 50, 100_000, "cGF5bG9hZA==");
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(new ApiCatalogBundleSyncStatusEntity()));
    }

    @Test
    @DisplayName("Empty registry + TOFU bootstrap fails (no cloud URL / no cloud key) → TRUST_UNCONFIGURED, no bundle fetch")
    void trustUnconfiguredWhenBootstrapFails() {
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(ApiCatalogBundleTrustBootstrap.Result.skipped("api-catalog.bundle.cloud-url is empty"));

        scheduler.tick();

        verify(trustBootstrap).bootstrapTrust();
        verifyNoInteractions(fetcher, verifier, applier);
        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("TRUST_UNCONFIGURED");
        assertThat(saved.getLastFetchError()).contains("cloud-url is empty");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("Empty registry + TOFU bootstrap pins → proceeds to fetch (no TRUST_UNCONFIGURED)")
    void tofuBootstrapThenProceeds() {
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(ApiCatalogBundleTrustBootstrap.Result.pinned("livecontext-prod-v1"));
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.noActive());

        scheduler.tick();

        verify(trustBootstrap).bootstrapTrust();
        verify(fetcher).fetchLatest();
        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        // NO_ACTIVE recorded by the downstream path - crucially NOT TRUST_UNCONFIGURED.
        assertThat(saved.getLastFetchStatus()).isEqualTo("NO_ACTIVE");
    }

    @Test
    @DisplayName("Key already pinned → TOFU bootstrap NOT attempted, fetch proceeds")
    void alreadyPinnedSkipsBootstrap() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.noActive());

        scheduler.tick();

        // A pinned key (operator env or earlier TOFU) must never trigger a re-fetch of the
        // signing key - requirement 1: bootstrap only when the registry is empty.
        verifyNoInteractions(trustBootstrap);
        verify(fetcher).fetchLatest();
    }

    @Test
    @DisplayName("FETCHED + verify OK + apply APPLIED → scheduler writes no extra status row")
    void happyPathNoDoubleWrite() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle))
                .thenReturn(ApiCatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), eq("https://cloud.example")))
                .thenReturn(new ApiCatalogBundleApplier.ApplyResult(
                        ApiCatalogBundleApplier.Status.APPLIED, 7L, 10, 50, 0, 0, 0, null));

        scheduler.tick();

        // Applier owns the OK status row. Scheduler must not save on top of it.
        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("FETCHED + apply ALREADY_APPLIED → scheduler writes no extra status row")
    void idempotentPathNoDoubleWrite() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle))
                .thenReturn(ApiCatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), anyString()))
                .thenReturn(ApiCatalogBundleApplier.ApplyResult.alreadyApplied(7L));

        scheduler.tick();

        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("FETCHED + verifier SIGNATURE_INVALID → failure row, applier never called")
    void signatureInvalid() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle))
                .thenReturn(ApiCatalogBundleVerifier.Result.fail(
                        ApiCatalogBundleVerifier.Status.SIGNATURE_INVALID, "tampered"));

        scheduler.tick();

        verifyNoInteractions(applier);
        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("SIGNATURE_INVALID");
        assertThat(saved.getLastFetchError()).isEqualTo("tampered");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("FETCHED + applier returns APPLY_FAILED → failure row persisted")
    void applyFailedResult() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle))
                .thenReturn(ApiCatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), anyString()))
                .thenReturn(ApiCatalogBundleApplier.ApplyResult.failed("payload gunzip/parse failed"));

        scheduler.tick();

        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("APPLY_FAILED");
        assertThat(saved.getLastFetchError()).contains("gunzip/parse");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("FETCHED + applier returns APPLY_PARTIAL → scheduler does NOT double-write (applier owns the row)")
    void applyPartialNoDoubleWrite() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle))
                .thenReturn(ApiCatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), anyString()))
                .thenReturn(new ApiCatalogBundleApplier.ApplyResult(
                        ApiCatalogBundleApplier.Status.APPLY_PARTIAL, 7L, 9, 40, 0, 0, 1,
                        "1 of 10 API upserts failed; first errors: api x: boom"));

        scheduler.tick();

        // The applier already wrote APPLY_PARTIAL + error detail + the
        // consecutive-failure bump; a scheduler save would overwrite it.
        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("Applier throws unexpectedly → caught, APPLY_FAILED persisted")
    void applierThrows() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle))
                .thenReturn(ApiCatalogBundleVerifier.Result.success(new byte[]{1, 2, 3}));
        when(applier.apply(eq(bundle), any(), anyString()))
                .thenThrow(new RuntimeException("db down"));

        scheduler.tick();

        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("APPLY_FAILED");
        assertThat(saved.getLastFetchError()).contains("db down");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("NO_ACTIVE → informational row recorded, consecutiveFailures NOT incremented")
    void noActive() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        ApiCatalogBundleSyncStatusEntity existing = new ApiCatalogBundleSyncStatusEntity();
        existing.setConsecutiveFailures(3);
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(existing));
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.noActive());

        scheduler.tick();

        verifyNoInteractions(verifier, applier);
        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NO_ACTIVE");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(3); // unchanged
    }

    @Test
    @DisplayName("HTTP_ERROR → failure row, consecutiveFailures incremented from prior value")
    void httpError() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        ApiCatalogBundleSyncStatusEntity existing = new ApiCatalogBundleSyncStatusEntity();
        existing.setConsecutiveFailures(2);
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(existing));
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.httpError("HTTP 500"));

        scheduler.tick();

        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("HTTP_ERROR");
        assertThat(saved.getLastFetchError()).isEqualTo("HTTP 500");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(3);
    }

    @Test
    @DisplayName("NETWORK_ERROR → failure row persisted")
    void networkError() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.networkError("Connection refused"));

        scheduler.tick();

        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NETWORK_ERROR");
        assertThat(saved.getLastFetchError()).contains("Connection refused");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("NOT_CONFIGURED → failure row persisted")
    void notConfigured() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.notConfigured());

        scheduler.tick();

        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("NOT_CONFIGURED");
        assertThat(saved.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("First-ever tick with no status row yet → loadOrInit creates a fresh row")
    void firstEverTickInitsRow() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        when(fetcher.fetchLatest())
                .thenReturn(ApiCatalogBundleFetcher.FetchResult.networkError("timeout"));

        scheduler.tick();

        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
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

        ApiCatalogBundleSyncStatusEntity saved = captureSaved();
        assertThat(saved.getLastFetchStatus()).isEqualTo("UNEXPECTED_ERROR");
        assertThat(saved.getLastFetchError()).contains("registry exploded");
    }

    @Test
    @DisplayName("Outer catch survives even if status persistence itself fails")
    void outerCatchSurvivesStatusPersistenceFailure() {
        when(trustedKeys.hasKeys()).thenThrow(new RuntimeException("registry exploded"));
        when(syncStatusRepo.findById(any())).thenThrow(new RuntimeException("db dead"));

        assertThatCode(() -> scheduler.tick()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("@Scheduled cron sits on tick() with the 15-minute default")
    void scheduledOnTick() throws NoSuchMethodException {
        java.lang.reflect.Method tick =
                ApiCatalogBundleSyncScheduler.class.getDeclaredMethod("tick");
        org.springframework.scheduling.annotation.Scheduled scheduled =
                tick.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class);
        assertThat(scheduled).as("@Scheduled must be on tick()").isNotNull();
        assertThat(scheduled.cron()).contains("api-catalog.bundle.sync.cron")
                .contains("0 */15 * * * *");
    }

    @Test
    @DisplayName("Scheduler bean is gated on api-catalog.bundle.sync.enabled=true (cloud never syncs)")
    void conditionalOnSyncEnabled() {
        org.springframework.boot.autoconfigure.condition.ConditionalOnProperty cond =
                ApiCatalogBundleSyncScheduler.class.getAnnotation(
                        org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);
        assertThat(cond).isNotNull();
        assertThat(List.of(cond.name())).containsExactly("api-catalog.bundle.sync.enabled");
        assertThat(cond.havingValue()).isEqualTo("true");
        assertThat(cond.matchIfMissing()).isFalse();
    }

    private ApiCatalogBundleSyncStatusEntity captureSaved() {
        ArgumentCaptor<ApiCatalogBundleSyncStatusEntity> cap =
                ArgumentCaptor.forClass(ApiCatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(cap.capture());
        return cap.getValue();
    }
}
