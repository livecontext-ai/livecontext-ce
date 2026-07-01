package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleTrustBootstrap;
import com.apimarketplace.agent.catalog.bundle.TrustedKeyRegistry;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CE sync loop branch coverage: skip-when-not-linked, skip-when-no-trust, the
 * fetch -> verify -> apply happy path, and persisting a verification failure. Every outcome
 * leaves the sync-status row updated and the method returns normally (never throws).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SkillBundleSyncScheduler - tick")
class SkillBundleSyncSchedulerTest {

    @Mock private SkillBundleFetcher fetcher;
    @Mock private SkillBundleVerifier verifier;
    @Mock private SkillBundleApplier applier;
    @Mock private SkillBundleSyncStatusRepository syncStatusRepo;
    @Mock private TrustedKeyRegistry trustedKeys;
    @Mock private CatalogBundleTrustBootstrap trustBootstrap;
    @Mock private ObjectProvider<CloudLlmRuntimeAccess> runtimeAccessProvider;
    @Mock private CloudLlmRuntimeAccess runtimeAccess;

    private SkillBundleSyncScheduler scheduler;

    private final CloudLlmRuntimeCredentials creds =
            new CloudLlmRuntimeCredentials("tok", "install-1", "https://cloud");

    @BeforeEach
    void setUp() {
        scheduler = new SkillBundleSyncScheduler(fetcher, verifier, applier, syncStatusRepo,
                trustedKeys, trustBootstrap, runtimeAccessProvider);
        lenient().when(syncStatusRepo.findById(SkillBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        lenient().when(syncStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("cloud-linked but trust cannot be bootstrapped -> records TRUST_UNCONFIGURED and never fetches")
    void trustUnconfiguredWhenBootstrapSkips() {
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(CatalogBundleTrustBootstrap.Result.skipped("cloud signing-key endpoint unreachable"));

        scheduler.tick();

        verify(fetcher, never()).fetchLatest(any());
        assertThat(captureStatus().getLastFetchStatus()).isEqualTo("TRUST_UNCONFIGURED");
    }

    @Test
    @DisplayName("empty registry on a linked CE -> TOFU-bootstraps trust (shared with model-catalog) then fetches")
    void tofuBootstrapsTrustThenFetches() {
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        // Registry starts empty (as on a TOFU install with an empty catalog.bundle.trusted-keys),
        // then the SHARED bootstrap pins the cloud key - the skill path must proceed, NOT get stuck.
        when(trustedKeys.hasKeys()).thenReturn(false);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(CatalogBundleTrustBootstrap.Result.pinned("livecontext-prod-v1"));
        when(fetcher.fetchLatest(creds)).thenReturn(SkillBundleFetcher.FetchResult.noActive());

        scheduler.tick();

        // Pre-fix (private empty snapshot) this returned at TRUST_UNCONFIGURED and never fetched.
        verify(fetcher).fetchLatest(creds);
        assertThat(captureStatus().getLastFetchStatus()).isEqualTo("NO_ACTIVE");
    }

    @Test
    @DisplayName("sibling pins the shared key concurrently (bootstrap loses the race, 'already present') -> proceeds, no spurious TRUST_UNCONFIGURED")
    void siblingPinnedConcurrentlyProceeds() {
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        // hasKeys(): false at the pre-check (we bootstrap), true at the re-check (the model-catalog
        // sibling TOFU-pinned the same shared key concurrently at startup). Our bootstrap loses the
        // race and returns not-pinned ("already present"), but trust IS configured - we must proceed,
        // NOT record the transient boot-time failure the losing racer used to log.
        when(trustedKeys.hasKeys()).thenReturn(false, true);
        when(trustBootstrap.bootstrapTrust())
                .thenReturn(CatalogBundleTrustBootstrap.Result.skipped("could not be pinned (already present)"));
        when(fetcher.fetchLatest(creds)).thenReturn(SkillBundleFetcher.FetchResult.noActive());

        scheduler.tick();

        verify(fetcher).fetchLatest(creds);
        assertThat(captureStatus().getLastFetchStatus()).isEqualTo("NO_ACTIVE");
    }

    @Test
    @DisplayName("not cloud-linked -> records NOT_LINKED (informational, not a consecutive failure) and never fetches")
    void notLinked() {
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.empty());

        scheduler.tick();

        verify(fetcher, never()).fetchLatest(any());
        SkillBundleSyncStatusEntity row = captureStatus();
        assertThat(row.getLastFetchStatus()).isEqualTo("NOT_LINKED");
        assertThat(row.getConsecutiveFailures()).as("informational tick does not bump failures").isZero();
    }

    @Test
    @DisplayName("happy path: fetch -> verify ok -> apply; the applier owns the success status (no failure recorded)")
    void happyApply() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        SignedSkillBundle bundle = new SignedSkillBundle(1, 1, "c", "s", "k", "i", 1, 10, "p");
        when(fetcher.fetchLatest(creds)).thenReturn(SkillBundleFetcher.FetchResult.fetched(bundle));
        byte[] bytes = "{}".getBytes();
        when(verifier.verify(bundle)).thenReturn(SkillBundleVerifier.Result.success(bytes));
        when(applier.apply(eqBundle(bundle), any(byte[].class), any()))
                .thenReturn(new SkillBundleApplier.ApplyResult(SkillBundleApplier.Status.APPLIED, 1, 1, 0, 0, null));

        scheduler.tick();

        verify(applier).apply(any(), any(), any());
        // No failure status written by the scheduler on a successful apply.
        verify(syncStatusRepo, never()).save(argThatFailure());
    }

    @Test
    @DisplayName("verification failure -> records the verifier's status and does NOT apply")
    void verifyFailure() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        SignedSkillBundle bundle = new SignedSkillBundle(1, 1, "c", "s", "k", "i", 1, 10, "p");
        when(fetcher.fetchLatest(creds)).thenReturn(SkillBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle))
                .thenReturn(SkillBundleVerifier.Result.fail(SkillBundleVerifier.Status.SIGNATURE_INVALID, "bad"));

        scheduler.tick();

        verify(applier, never()).apply(any(), any(), any());
        assertThat(captureStatus().getLastFetchStatus()).isEqualTo("SIGNATURE_INVALID");
    }

    @Test
    @DisplayName("cloud has no active bundle (404) -> NO_ACTIVE fetch-only, no failure")
    void noActive() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        when(fetcher.fetchLatest(creds)).thenReturn(SkillBundleFetcher.FetchResult.noActive());

        scheduler.tick();

        verify(verifier, never()).verify(any());
        assertThat(captureStatus().getLastFetchStatus()).isEqualTo("NO_ACTIVE");
    }

    @Test
    @DisplayName("HTTP error from the cloud -> records the fetch status and bumps consecutive failures")
    void httpErrorRecordsFailure() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        when(fetcher.fetchLatest(creds)).thenReturn(SkillBundleFetcher.FetchResult.httpError("HTTP 500"));

        scheduler.tick();

        SkillBundleSyncStatusEntity row = captureStatus();
        assertThat(row.getLastFetchStatus()).isEqualTo("HTTP_ERROR");
        assertThat(row.getConsecutiveFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("an APPLY_FAILED result is persisted as a failure status")
    void applyFailedRecordsFailure() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        SignedSkillBundle bundle = new SignedSkillBundle(1, 1, "c", "s", "k", "i", 1, 10, "p");
        when(fetcher.fetchLatest(creds)).thenReturn(SkillBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(SkillBundleVerifier.Result.success("{}".getBytes()));
        when(applier.apply(any(), any(), any()))
                .thenReturn(SkillBundleApplier.ApplyResult.failed("bad payload"));

        scheduler.tick();

        assertThat(captureStatus().getLastFetchStatus()).isEqualTo("APPLY_FAILED");
    }

    @Test
    @DisplayName("an apply that THROWS is caught and persisted (the scheduler never crashes)")
    void applyThrowsIsCaught() {
        when(trustedKeys.hasKeys()).thenReturn(true);
        when(runtimeAccessProvider.getIfAvailable()).thenReturn(runtimeAccess);
        when(runtimeAccess.resolveActiveCloudRuntime()).thenReturn(Optional.of(creds));
        SignedSkillBundle bundle = new SignedSkillBundle(1, 1, "c", "s", "k", "i", 1, 10, "p");
        when(fetcher.fetchLatest(creds)).thenReturn(SkillBundleFetcher.FetchResult.fetched(bundle));
        when(verifier.verify(bundle)).thenReturn(SkillBundleVerifier.Result.success("{}".getBytes()));
        when(applier.apply(any(), any(), any())).thenThrow(new RuntimeException("boom"));

        scheduler.tick();  // must not throw

        assertThat(captureStatus().getLastFetchStatus()).isEqualTo("APPLY_FAILED");
    }

    private SkillBundleSyncStatusEntity captureStatus() {
        ArgumentCaptor<SkillBundleSyncStatusEntity> captor =
                ArgumentCaptor.forClass(SkillBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(captor.capture());
        return captor.getValue();
    }

    private static SignedSkillBundle eqBundle(SignedSkillBundle b) {
        return org.mockito.ArgumentMatchers.eq(b);
    }

    private static SkillBundleSyncStatusEntity argThatFailure() {
        return org.mockito.ArgumentMatchers.argThat(s ->
                s != null && s.getLastFetchStatus() != null
                        && !s.getLastFetchStatus().equals("OK")
                        && !s.getLastFetchStatus().equals("NO_ACTIVE")
                        && !s.getLastFetchStatus().equals("NOT_LINKED"));
    }
}
