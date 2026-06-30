package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Orchestration contract of the CE applier: gunzip+parse of the VERIFIED gzip
 * bytes, idempotency on the active version, merge delegation, bundle-row and
 * sync-status bookkeeping. The row-level SQL is covered by
 * {@link ApiCatalogMergeServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiCatalogBundleApplier - gunzip/parse + idempotency + bookkeeping")
class ApiCatalogBundleApplierTest {

    @Mock private ApiCatalogMergeService mergeService;
    @Mock private ApiCatalogBundleRepository bundleRepo;
    @Mock private ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private PlatformTransactionManager txManager;

    private ApiCatalogBundleApplier applier;

    @BeforeEach
    void setUp() {
        applier = new ApiCatalogBundleApplier(mergeService, bundleRepo, syncStatusRepo, new ObjectMapper(), txManager);
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(new ApiCatalogBundleSyncStatusEntity()));
        when(bundleRepo.findByVersion(any())).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ApiCatalogSignedBundle bundle(long version) {
        return new ApiCatalogSignedBundle(version, 1, "a".repeat(64), "sig", "k1", "cloud",
                2, 5, 1000, "ignored-in-applier");
    }

    private static byte[] gzPayload(String json) {
        return ApiCatalogBundlePayload.gzip(json.getBytes(StandardCharsets.UTF_8));
    }

    private static ApiCatalogMergeService.MergeResult okMerge() {
        return new ApiCatalogMergeService.MergeResult(2, 5, 0, 0, 1, 0, 3, List.of());
    }

    @Test
    @DisplayName("Re-applying the active version → ALREADY_APPLIED, merge never runs, OK heartbeat refreshed")
    void idempotentOnActiveVersion() {
        ApiCatalogBundleEntity active = new ApiCatalogBundleEntity();
        active.setVersion(7L);
        active.setActive(true);
        when(bundleRepo.findByVersion(7L)).thenReturn(Optional.of(active));

        ApiCatalogBundleApplier.ApplyResult r =
                applier.apply(bundle(7L), gzPayload("{\"apis\":[]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.ALREADY_APPLIED);
        verifyNoInteractions(mergeService);
        verify(bundleRepo, never()).save(any());
        ApiCatalogBundleSyncStatusEntity status = capturedStatus();
        assertThat(status.getLastFetchStatus()).isEqualTo("OK");
        assertThat(status.getLastAppliedVersion()).isEqualTo(7L);
        assertThat(status.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("Non-gzip verified bytes → APPLY_FAILED, nothing persisted")
    void garbageBytesFail() {
        ApiCatalogBundleApplier.ApplyResult r =
                applier.apply(bundle(8L), new byte[]{1, 2, 3}, "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("gunzip/parse failed");
        verifyNoInteractions(mergeService);
        verify(bundleRepo, never()).save(any());
    }

    @Test
    @DisplayName("Payload without an 'apis' array → APPLY_FAILED")
    void missingApisArrayFails() {
        ApiCatalogBundleApplier.ApplyResult r =
                applier.apply(bundle(8L), gzPayload("{\"foo\":1}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("no 'apis' array");
        verifyNoInteractions(mergeService);
    }

    @Test
    @DisplayName("Payload with an EMPTY 'apis' array → APPLY_FAILED before any merge work (wipe guard)")
    void emptyApisPayloadRejected() {
        ApiCatalogBundleApplier.ApplyResult r =
                applier.apply(bundle(8L), gzPayload("{\"apis\":[]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("refusing to deprecate the entire catalog");
        // The guard fires BEFORE the merge - the orphan sweep (which would
        // soft-deprecate every bundle-managed API) must never even start.
        verifyNoInteractions(mergeService);
        // No bundle row recorded, no OK heartbeat written.
        verify(bundleRepo, never()).save(any());
        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("Happy path: gunzips, delegates to merge, flips bundle row WITHOUT storing the payload, writes OK status")
    void happyPathBookkeeping() {
        when(mergeService.merge(anyList(), anyList())).thenReturn(okMerge());

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(9L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}],\"credentialTemplates\":[{\"credentialName\":\"slack\"}]}"),
                "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLIED);
        assertThat(r.version()).isEqualTo(9L);
        assertThat(r.upsertedApis()).isEqualTo(2);
        assertThat(r.upsertedTools()).isEqualTo(5);
        assertThat(r.deprecatedApis()).isEqualTo(1);

        // Merge received the parsed lists.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> apisCap = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> templatesCap = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(apisCap.capture(), templatesCap.capture());
        assertThat(apisCap.getValue()).hasSize(1);
        assertThat(templatesCap.getValue()).hasSize(1);

        // Bundle row: deactivate-all then save active, payload_gz left NULL on CE.
        verify(bundleRepo).deactivateAll();
        ArgumentCaptor<ApiCatalogBundleEntity> rowCap = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(rowCap.capture());
        ApiCatalogBundleEntity row = rowCap.getValue();
        assertThat(row.isActive()).isTrue();
        assertThat(row.getVersion()).isEqualTo(9L);
        assertThat(row.getPayloadGz()).isNull();
        assertThat(row.getSourceUrl()).isEqualTo("https://cloud");

        ApiCatalogBundleSyncStatusEntity status = capturedStatus();
        assertThat(status.getLastFetchStatus()).isEqualTo("OK");
        assertThat(status.getLastAppliedVersion()).isEqualTo(9L);
        assertThat(status.getLastFetchError()).isNull();
        assertThat(status.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("Missing credentialTemplates key is tolerated (treated as empty)")
    void missingTemplatesTolerated() {
        when(mergeService.merge(anyList(), anyList())).thenReturn(okMerge());

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(10L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLIED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> templatesCap = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(anyList(), templatesCap.capture());
        assertThat(templatesCap.getValue()).isEmpty();
    }

    @Test
    @DisplayName("Every API failing in merge → APPLY_FAILED and NO bundle row (retry must not be short-circuited)")
    void allFailedMeansApplyFailed() {
        when(mergeService.merge(anyList(), anyList())).thenReturn(
                new ApiCatalogMergeService.MergeResult(0, 0, 0, 3, 0, 0, 0,
                        List.of("api A: boom", "api B: boom", "api C: boom")));

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(11L),
                gzPayload("{\"apis\":[{\"id\":\"a\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("all 3 API upserts failed");
        verify(bundleRepo, never()).save(any());
        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("Partial failure → APPLY_PARTIAL: no bundle row, failure detail on sync-status, retry NOT short-circuited")
    void partialFailureIsRetryableAndSurfaced() {
        ApiCatalogBundleSyncStatusEntity existing = new ApiCatalogBundleSyncStatusEntity();
        existing.setConsecutiveFailures(2);
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(existing));
        when(mergeService.merge(anyList(), anyList())).thenReturn(
                new ApiCatalogMergeService.MergeResult(5, 12, 0, 1, 0, 0, 2,
                        List.of("api 7e57ed-x (Slack): DataIntegrityViolationException: unique collision")));

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(12L),
                gzPayload("{\"apis\":[{\"id\":\"a\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_PARTIAL);
        assertThat(r.failedApis()).isEqualTo(1);
        assertThat(r.upsertedApis()).isEqualTo(5);

        // No bundle row write - the version stays unrecorded so the
        // idempotency check cannot short-circuit the retry.
        verify(bundleRepo, never()).save(any());
        verify(bundleRepo, never()).deactivateAll();

        // Sync-status carries APPLY_PARTIAL + the per-API failure summary and
        // bumps the consecutive-failure counter (UI must not show green).
        ApiCatalogBundleSyncStatusEntity status = capturedStatus();
        assertThat(status.getLastFetchStatus()).isEqualTo("APPLY_PARTIAL");
        assertThat(status.getLastFetchError())
                .contains("1 of 6 API upserts failed")
                .contains("api 7e57ed-x (Slack)")
                .contains("unique collision");
        assertThat(status.getConsecutiveFailures()).isEqualTo(3);
        assertThat(status.getLastAppliedVersion()).isNull(); // not advanced

        // A subsequent apply of the SAME version runs the merge again (no
        // ALREADY_APPLIED short-circuit) because no active row was recorded.
        applier.apply(bundle(12L), gzPayload("{\"apis\":[{\"id\":\"a\"}]}"), "https://cloud");
        verify(mergeService, org.mockito.Mockito.times(2)).merge(anyList(), anyList());
    }

    @Test
    @DisplayName("Partial-failure detail is truncated to a sane length (long error lines, many failures)")
    void partialFailureDetailTruncated() {
        String hugeError = "api " + "x".repeat(3000) + ": boom";
        when(mergeService.merge(anyList(), anyList())).thenReturn(
                new ApiCatalogMergeService.MergeResult(1, 0, 0, 9, 0, 0, 0,
                        List.of(hugeError, "api b: boom", "api c: boom", "api d: boom",
                                "api e: boom", "api f: boom", "api g: boom")));

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(14L),
                gzPayload("{\"apis\":[{\"id\":\"a\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_PARTIAL);
        ApiCatalogBundleSyncStatusEntity status = capturedStatus();
        assertThat(status.getLastFetchError())
                .hasSizeLessThanOrEqualTo(ApiCatalogBundleApplier.MAX_FAILURE_DETAIL_LENGTH);
        assertThat(status.getLastFetchError()).startsWith("9 of 10 API upserts failed");
    }

    @Test
    @DisplayName("Re-apply of a known-but-inactive version reuses the existing row (no duplicate insert)")
    void reusesExistingInactiveRow() {
        ApiCatalogBundleEntity stale = new ApiCatalogBundleEntity();
        stale.setId(33L);
        stale.setVersion(13L);
        stale.setActive(false);
        when(bundleRepo.findByVersion(13L)).thenReturn(Optional.of(stale));
        when(mergeService.merge(anyList(), anyList())).thenReturn(okMerge());

        applier.apply(bundle(13L), gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        ArgumentCaptor<ApiCatalogBundleEntity> rowCap = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getId()).isEqualTo(33L);
        assertThat(rowCap.getValue().isActive()).isTrue();
    }

    private ApiCatalogBundleSyncStatusEntity capturedStatus() {
        ArgumentCaptor<ApiCatalogBundleSyncStatusEntity> cap =
                ArgumentCaptor.forClass(ApiCatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(cap.capture());
        return cap.getValue();
    }
}
