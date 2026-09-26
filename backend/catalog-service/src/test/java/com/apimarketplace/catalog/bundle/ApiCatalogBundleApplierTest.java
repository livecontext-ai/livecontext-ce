package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import com.fasterxml.jackson.core.type.TypeReference;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
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
    @Mock private ApiCatalogGenerationPriceApplier priceApplier;
    @Mock private ApiCatalogBundleRepository bundleRepo;
    @Mock private ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private PlatformTransactionManager txManager;

    private ApiCatalogBundleApplier applier;

    @BeforeEach
    void setUp() {
        applier = new ApiCatalogBundleApplier(mergeService, priceApplier, bundleRepo, syncStatusRepo,
                new ObjectMapper(), txManager);
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
        // The row IS saved now, but only to capture the prices for the
        // conditional path - never to re-record the bundle. The version and the
        // active flag are untouched, which is what "idempotent" means here.
        ArgumentCaptor<ApiCatalogBundleEntity> rowCap = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getVersion()).isEqualTo(7L);
        assertThat(rowCap.getValue().isActive()).isTrue();
        verify(bundleRepo, never()).deactivateAll();
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
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(9L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}],\"credentialTemplates\":[{\"credentialName\":\"slack\"}]}"),
                "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLIED);
        assertThat(r.version()).isEqualTo(9L);
        assertThat(r.upsertedApis()).isEqualTo(2);
        assertThat(r.upsertedTools()).isEqualTo(5);
        assertThat(r.deprecatedApis()).isEqualTo(1);

        // Merge received the parsed lists.
        // The APIs arrive as a stream over the payload; iterating the captured value reads it.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<Map<String, Object>>> apisCap = ArgumentCaptor.forClass(Iterable.class);
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
    @DisplayName("The merge consumes the APIs as a stream DURING apply, receiving exactly the payload's maps")
    void mergeDrainsTheStreamDuringApply() {
        List<Map<String, Object>> seen = new java.util.ArrayList<>();
        when(mergeService.merge(any(), anyList())).thenAnswer(inv -> {
            Iterable<Map<String, Object>> apis = inv.getArgument(0);
            apis.forEach(seen::add);
            return okMerge();
        });

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(13L), gzPayload(
                "{\"apis\":[{\"id\":\"a\",\"tools\":[{\"toolSlug\":\"t1\",\"n\":2}]},{\"id\":\"b\"}]}"),
                "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLIED);
        assertThat(seen).extracting(m -> m.get("id")).containsExactly("a", "b");
        assertThat(seen.get(0).get("tools")).isEqualTo(List.of(Map.of("toolSlug", "t1", "n", 2)));
    }

    @Test
    @DisplayName("'apis' null or an object: 'payload has no apis array', and the merge never runs")
    void apisNotAnArrayRefused() {
        for (String json : List.of("{\"apis\":null}", "{\"apis\":{\"id\":\"x\"}}", "{\"credentialTemplates\":[]}")) {
            ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(14L), gzPayload(json), "https://cloud");
            assertThat(r.status()).as(json).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
            assertThat(r.detail()).as(json).isEqualTo("payload has no 'apis' array");
        }
        verifyNoInteractions(mergeService);
    }

    @Test
    @DisplayName("TIGHTENING: a second 'apis' key is refused before the merge runs")
    void duplicateApisKeyRefused() {
        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(15L),
                gzPayload("{\"apis\":[{\"id\":\"a\"}],\"apis\":[{\"id\":\"b\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).isEqualTo("payload has more than one 'apis' key");
        verifyNoInteractions(mergeService);
    }

    @Test
    @DisplayName("TIGHTENING: a non-object template or price entry refuses the apply up front, not after the merge")
    void nonObjectTemplateOrPriceRefusedUpFront() {
        for (String json : List.of(
                "{\"apis\":[{\"id\":\"a\"}],\"credentialTemplates\":[\"x\"]}",
                "{\"apis\":[{\"id\":\"a\"}],\"generationPrices\":[42]}")) {
            ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(16L), gzPayload(json), "https://cloud");
            assertThat(r.status()).as(json).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
            assertThat(r.detail()).as(json).startsWith("payload gunzip/parse failed");
        }
        verifyNoInteractions(mergeService);
        verifyNoInteractions(priceApplier);
    }

    @Test
    @DisplayName("An 'apis' entry that is not an object is refused before the merge runs")
    void nonObjectApiEntryRefusedBeforeMerge() {
        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(11L),
                gzPayload("{\"apis\":[{\"id\":\"x\"},\"oops\"]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).contains("1 entries that are not objects");
        verifyNoInteractions(mergeService);
    }

    @Test
    @DisplayName("A payload read failure DURING the merge is APPLY_FAILED, never an escaping exception or a recorded row")
    void rereadFailureDuringMergeIsApplyFailed() {
        when(mergeService.merge(any(), anyList()))
                .thenThrow(new java.io.UncheckedIOException(new java.io.IOException("truncated")));

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(12L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_FAILED);
        assertThat(r.detail()).startsWith("payload re-read failed during merge");
        verify(bundleRepo, never()).save(any());
        // The applier does not write the status row itself: the scheduler records APPLY_FAILED
        // once, as for every failed apply.
        verify(syncStatusRepo, never()).save(any());
    }

    @Test
    @DisplayName("Missing credentialTemplates key is tolerated (treated as empty)")
    void missingTemplatesTolerated() {
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(10L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLIED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> templatesCap = ArgumentCaptor.forClass(List.class);
        verify(mergeService).merge(any(), templatesCap.capture());
        assertThat(templatesCap.getValue()).isEmpty();
    }

    @Test
    @DisplayName("Every API failing in merge → APPLY_FAILED and NO bundle row (retry must not be short-circuited)")
    void allFailedMeansApplyFailed() {
        when(mergeService.merge(any(), anyList())).thenReturn(
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
        when(mergeService.merge(any(), anyList())).thenReturn(
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
        verify(mergeService, org.mockito.Mockito.times(2)).merge(any(), anyList());
    }

    @Test
    @DisplayName("Partial-failure detail is truncated to a sane length (long error lines, many failures)")
    void partialFailureDetailTruncated() {
        String hugeError = "api " + "x".repeat(3000) + ": boom";
        when(mergeService.merge(any(), anyList())).thenReturn(
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
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());

        applier.apply(bundle(13L), gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        ArgumentCaptor<ApiCatalogBundleEntity> rowCap = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getId()).isEqualTo(33L);
        assertThat(rowCap.getValue().isActive()).isTrue();
    }

    @Test
    @DisplayName("Concurrent apply of the same version (unique-version race) -> ALREADY_APPLIED, not a failure")
    void concurrentDuplicateVersionRaceIsTreatedAsAlreadyApplied() {
        // findByVersion is empty at the pre-check AND the in-tx re-read: a concurrent
        // apply (a manual "Sync now" racing the 15-min scheduler) commits the SAME
        // version first, so our INSERT trips api_catalog_bundles_version_key. Pre-fix
        // this propagated a DataIntegrityViolationException and the sync surfaced
        // "apply failed"; post-fix it is swallowed as an idempotent no-op.
        ApiCatalogBundleEntity winner = new ApiCatalogBundleEntity();
        winner.setVersion(1782901281458L);
        winner.setActive(true);
        // Empty at the pre-check AND the in-tx re-read (both applies saw no row); then the concurrent
        // winner's active row IS visible on the catch's confirm-read (else the catch would rethrow).
        when(bundleRepo.findByVersion(any()))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());
        when(bundleRepo.save(any())).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"api_catalog_bundles_version_key\""));

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(1782901281458L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.ALREADY_APPLIED);
        assertThat(r.version()).isEqualTo(1782901281458L);
        // The heartbeat stays green (OK, version advanced, no error, failures reset), not a failure row.
        ApiCatalogBundleSyncStatusEntity status = capturedStatus();
        assertThat(status.getLastFetchStatus()).isEqualTo("OK");
        assertThat(status.getLastAppliedVersion()).isEqualTo(1782901281458L);
        assertThat(status.getConsecutiveFailures()).isZero();
        assertThat(status.getLastFetchError()).isNull();
    }

    @Test
    @DisplayName("A non-race integrity violation is NOT masked - it rethrows (the catch is bounded to the version race)")
    void nonRaceIntegrityViolationIsRethrownNotMasked() {
        // save() fails an integrity constraint, but the version is NOT present+active afterwards
        // (findByVersion stays empty) - i.e. this is a genuine failure, not the concurrent-winner race.
        when(bundleRepo.findByVersion(any())).thenReturn(Optional.empty());
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());
        when(bundleRepo.save(any())).thenThrow(new DataIntegrityViolationException(
                "null value in column \"checksum\" violates not-null constraint"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> applier.apply(bundle(9L),
                        gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Version that lands between the pre-check and the record tx is reused in-tx (no duplicate insert)")
    void versionAppearingAfterPrecheckIsReusedInTx() {
        ApiCatalogBundleEntity landedConcurrently = new ApiCatalogBundleEntity();
        landedConcurrently.setId(77L);
        landedConcurrently.setVersion(15L);
        landedConcurrently.setActive(false);
        // Empty at the top-of-method idempotency pre-check, then PRESENT at the in-tx re-read: a
        // concurrent apply committed the row in between. Pre-fix used the (empty) pre-check Optional
        // and INSERTed a duplicate; post-fix the in-tx re-read reuses the row (UPDATE).
        when(bundleRepo.findByVersion(15L)).thenReturn(Optional.empty(), Optional.of(landedConcurrently));
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());

        applier.apply(bundle(15L), gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        ArgumentCaptor<ApiCatalogBundleEntity> rowCap = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getId()).isEqualTo(77L);
        assertThat(rowCap.getValue().isActive()).isTrue();
    }

    // ── V430: the generation prices the bundle carries ──────────────────────

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> capturedPrices() {
        ArgumentCaptor<List<Map<String, Object>>> cap = ArgumentCaptor.forClass(List.class);
        verify(priceApplier).apply(cap.capture(), any());
        return cap.getValue();
    }

    @Test
    @DisplayName("The prices reach the price applier only after every endpoint has landed")
    void pricesAreAppliedAfterTheMerge() {
        // A price row is keyed on an endpoint UUID, so offering prices over a
        // catalog that has not landed would price endpoints this install does
        // not have.
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(21L), gzPayload(
                "{\"apis\":[{\"id\":\"x\"}],\"generationPrices\":[{\"integrationName\":\"seedance\","
                        + "\"apiToolId\":\"33333333-3333-3333-3333-333333333333\","
                        + "\"modelId\":\"seedance-2.0\",\"priceUnit\":\"second\","
                        + "\"baseCredits\":\"0\",\"unitCredits\":\"60\"}]}"),
                "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLIED);
        assertThat(capturedPrices()).hasSize(1);
        assertThat(capturedPrices().get(0)).containsEntry("modelId", "seedance-2.0");
        InOrder order = inOrder(mergeService, priceApplier);
        order.verify(mergeService).merge(any(), anyList());
        order.verify(priceApplier).apply(anyList(), any());
    }

    @Test
    @DisplayName("A bundle from an older cloud has no 'generationPrices' key - the applier is still "
            + "called, with null, and the catalog still lands")
    void olderBundleShapeStillApplies() {
        // The compatibility posture. A missing key must not fail the apply, and
        // must not be turned into an empty list that could be read as "no
        // prices exist".
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());

        ApiCatalogBundleApplier.ApplyResult r =
                applier.apply(bundle(22L), gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLIED);
        assertThat(capturedPrices()).isNull();
    }

    @Test
    @DisplayName("Re-applying the ACTIVE version still re-offers its prices: a platform key pasted "
            + "later has no other way to get priced")
    void alreadyAppliedStillReOffersPrices() {
        // The price hangs off a platform credential. An operator who pastes a
        // provider key a week after this version landed had nothing for the
        // price to attach to at the time, and the cloud has no reason to cut a
        // new bundle. Re-offering costs nothing (auth publishes nothing when
        // nothing changed) and is what eventually prices that integration.
        ApiCatalogBundleEntity active = new ApiCatalogBundleEntity();
        active.setVersion(23L);
        active.setActive(true);
        when(bundleRepo.findByVersion(23L)).thenReturn(Optional.of(active));

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(23L), gzPayload(
                "{\"apis\":[],\"generationPrices\":[{\"integrationName\":\"seedance\","
                        + "\"apiToolId\":\"33333333-3333-3333-3333-333333333333\","
                        + "\"priceUnit\":\"call\",\"baseCredits\":\"9\",\"unitCredits\":\"0\"}]}"),
                "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.ALREADY_APPLIED);
        verifyNoInteractions(mergeService);
        assertThat(capturedPrices()).hasSize(1);
    }

    @Test
    @DisplayName("A partially-applied bundle offers NO prices - half a catalog is not something to "
            + "price against")
    void partialApplyOffersNoPrices() {
        // The version is not recorded either, so the next tick retries the whole
        // thing; that is when its prices arrive.
        when(mergeService.merge(any(), anyList())).thenReturn(
                new ApiCatalogMergeService.MergeResult(1, 1, 0, 2, 0, 0, 0, List.of("api a: boom")));

        ApiCatalogBundleApplier.ApplyResult r = applier.apply(bundle(24L), gzPayload(
                "{\"apis\":[{\"id\":\"x\"}],\"generationPrices\":[{\"integrationName\":\"seedance\","
                        + "\"apiToolId\":\"33333333-3333-3333-3333-333333333333\","
                        + "\"priceUnit\":\"call\",\"baseCredits\":\"9\",\"unitCredits\":\"0\"}]}"),
                "https://cloud");

        assertThat(r.status()).isEqualTo(ApiCatalogBundleApplier.Status.APPLY_PARTIAL);
        verifyNoInteractions(priceApplier);
    }

    private ApiCatalogBundleSyncStatusEntity capturedStatus() {
        ArgumentCaptor<ApiCatalogBundleSyncStatusEntity> cap =
                ArgumentCaptor.forClass(ApiCatalogBundleSyncStatusEntity.class);
        verify(syncStatusRepo).save(cap.capture());
        return cap.getValue();
    }

    @Test
    @DisplayName("Applying stores the prices the bundle carried, so a later 304 tick has something to re-offer")
    void applyStoresGenerationPrices() {
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());

        applier.apply(bundle(11L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}],\"generationPrices\":[{\"toolSlug\":\"flux-generate\",\"credits\":12}]}"),
                "https://cloud");

        ArgumentCaptor<ApiCatalogBundleEntity> rowCap = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getGenerationPrices())
                .as("without this the 304 path has no local source for the price re-offer")
                .contains("flux-generate");
    }

    @Test
    @DisplayName("Applying records THIS version's prices: the row describes one signed bundle, so an empty capture is the truth for a price-less one")
    void applyRecordsThisVersionsCapture() {
        // The row is per version and a version is one signed payload, so there
        // is nothing older to preserve here. The "do not overwrite" rule lives
        // on the already-applied path instead, where the payload may be re-read
        // for a version whose capture already exists.
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());

        applier.apply(bundle(12L), gzPayload("{\"apis\":[{\"id\":\"x\"}]}"), "https://cloud");

        ArgumentCaptor<ApiCatalogBundleEntity> rowCap = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getGenerationPrices()).isEqualTo("[]");
    }

    @Test
    @DisplayName("Re-applying the same version backfills the prices, so an upgraded install can go conditional next tick")
    void alreadyAppliedBackfillsStoredPrices() {
        ApiCatalogBundleEntity active = new ApiCatalogBundleEntity();
        active.setVersion(13L);
        active.setActive(true);
        active.setGenerationPrices(null); // applied before the column existed
        when(bundleRepo.findByVersion(13L)).thenReturn(Optional.of(active));

        applier.apply(bundle(13L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}],\"generationPrices\":[{\"toolSlug\":\"flux\"}]}"),
                "https://cloud");

        ArgumentCaptor<ApiCatalogBundleEntity> rowCap = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(rowCap.capture());
        assertThat(rowCap.getValue().getGenerationPrices()).contains("flux");
    }

    @Test
    @DisplayName("Re-applying does not overwrite prices already stored")
    void alreadyAppliedKeepsExistingStoredPrices() {
        ApiCatalogBundleEntity active = new ApiCatalogBundleEntity();
        active.setVersion(14L);
        active.setActive(true);
        active.setGenerationPrices("[{\"toolSlug\":\"already-there\"}]");
        when(bundleRepo.findByVersion(14L)).thenReturn(Optional.of(active));

        applier.apply(bundle(14L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}],\"generationPrices\":[{\"toolSlug\":\"flux\"}]}"),
                "https://cloud");

        verify(bundleRepo, never()).save(any());
        assertThat(active.getGenerationPrices()).contains("already-there");
    }

    @Test
    @DisplayName("Round trip: what apply stores is exactly what a later 304 re-offers")
    void storedPricesRoundTripThroughReoffer() {
        // The two halves are written independently, so pin them against each
        // other: a serialisation change on one side would otherwise only show up
        // as prices quietly not being applied.
        when(mergeService.merge(any(), anyList())).thenReturn(okMerge());
        applier.apply(bundle(15L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}],\"generationPrices\":"
                        + "[{\"toolSlug\":\"flux\",\"credits\":12}]}"),
                "https://cloud");
        ArgumentCaptor<ApiCatalogBundleEntity> saved = ArgumentCaptor.forClass(ApiCatalogBundleEntity.class);
        verify(bundleRepo).save(saved.capture());

        // Feed exactly the captured string back through the read side.
        String captured = saved.getValue().getGenerationPrices();
        when(bundleRepo.findActivePrices()).thenReturn(List.of(
                new ApiCatalogBundleRepository.ActiveBundlePrices() {
                    @Override public Long getVersion() { return 15L; }
                    @Override public String getGenerationPrices() { return captured; }
                }));
        applier.reofferStoredPrices();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> offered = ArgumentCaptor.forClass(List.class);
        verify(priceApplier, atLeastOnce()).apply(offered.capture(), eq(15L));
        assertThat(offered.getAllValues().get(offered.getAllValues().size() - 1))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m).containsEntry("toolSlug", "flux");
                    assertThat(String.valueOf(m.get("credits"))).isEqualTo("12");
                });
    }

    @Test
    @DisplayName("The already-applied path reads the payload in ONE streaming pass and never inflates it whole")
    void alreadyAppliedReadsThePayloadOnce() throws Exception {
        // Regression on cost, not correctness. Answering "which prices?" and
        // "was the payload readable?" with two separate gunzip+parse passes
        // doubled the transient allocation of a 24 MB payload on exactly the
        // path every install takes once, during the switch to conditional polls.
        ObjectMapper spy = Mockito.spy(new ObjectMapper());
        ApiCatalogBundleApplier withSpy = new ApiCatalogBundleApplier(
                mergeService, priceApplier, bundleRepo, syncStatusRepo, spy, txManager);
        ApiCatalogBundleEntity active = new ApiCatalogBundleEntity();
        active.setVersion(20L);
        active.setActive(true);
        when(bundleRepo.findByVersion(20L)).thenReturn(Optional.of(active));

        withSpy.apply(bundle(20L),
                gzPayload("{\"apis\":[{\"id\":\"x\"}],\"generationPrices\":[{\"toolSlug\":\"flux\"}]}"),
                "https://cloud");

        // One parser opened (one pass), and no whole-document readValue: inflating the payload
        // whole is what ran the 1 GB CE heap out of memory on the 243 MB bundle.
        verify(spy, times(1)).getFactory();
        verify(spy, never()).readValue(any(byte[].class), any(TypeReference.class));
        verify(priceApplier).apply(org.mockito.ArgumentMatchers.argThat(l -> l != null && l.size() == 1), eq(20L));
    }

    @Nested
    @DisplayName("Re-offering prices without the payload (the 304 path)")
    class ReofferStoredPrices {

        /** The payload-free projection the re-offer now reads. */
        private ApiCatalogBundleRepository.ActiveBundlePrices activeRow(String storedPrices) {
            return new ApiCatalogBundleRepository.ActiveBundlePrices() {
                @Override public Long getVersion() { return 42L; }
                @Override public String getGenerationPrices() { return storedPrices; }
            };
        }

        @Test
        @DisplayName("Re-offers the prices stored on the active row")
        void reoffersStored() {
            when(bundleRepo.findActivePrices())
                    .thenReturn(List.of(activeRow("[{\"toolSlug\":\"flux-generate\",\"credits\":12}]")));

            applier.reofferStoredPrices();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
            verify(priceApplier).apply(captor.capture(), eq(42L));
            assertThat(captor.getValue()).hasSize(1);
            assertThat(captor.getValue().get(0)).containsEntry("toolSlug", "flux-generate");
        }

        @Test
        @DisplayName("Writes the same healthy status an already-applied tick does, so no new status value reaches the UI")
        void writesOkStatus() {
            when(bundleRepo.findActivePrices()).thenReturn(List.of(activeRow("[]")));

            applier.reofferStoredPrices();

            ArgumentCaptor<ApiCatalogBundleSyncStatusEntity> captor =
                    ArgumentCaptor.forClass(ApiCatalogBundleSyncStatusEntity.class);
            verify(syncStatusRepo).save(captor.capture());
            assertThat(captor.getValue().getLastFetchStatus()).isEqualTo("OK");
            assertThat(captor.getValue().getConsecutiveFailures()).isZero();
            assertThat(captor.getValue().getLastAppliedVersion()).isEqualTo(42L);
        }

        @Test
        @DisplayName("A row that stored no prices offers null, which the price applier reads as \"says nothing\", never as \"unprice everything\"")
        void nullStoredPricesOffersNull() {
            when(bundleRepo.findActivePrices()).thenReturn(List.of(activeRow(null)));

            applier.reofferStoredPrices();

            verify(priceApplier).apply(isNull(), eq(42L));
        }

        @Test
        @DisplayName("Unreadable stored prices leave pricing untouched and still report a healthy sync")
        void unreadableStoredPricesAreSurvivable() {
            when(bundleRepo.findActivePrices()).thenReturn(List.of(activeRow("{ this is not json")));

            applier.reofferStoredPrices();

            verify(priceApplier).apply(isNull(), eq(42L));
            verify(syncStatusRepo).save(any());
        }

        @Test
        @DisplayName("A pricing failure never turns an up-to-date install into a failing one")
        void pricingFailureDoesNotFailTheTick() {
            when(bundleRepo.findActivePrices()).thenReturn(List.of(activeRow("[]")));
            doThrow(new RuntimeException("auth-service unreachable"))
                    .when(priceApplier).apply(any(), eq(42L));

            assertThatCode(() -> applier.reofferStoredPrices()).doesNotThrowAnyException();

            ArgumentCaptor<ApiCatalogBundleSyncStatusEntity> captor =
                    ArgumentCaptor.forClass(ApiCatalogBundleSyncStatusEntity.class);
            verify(syncStatusRepo).save(captor.capture());
            assertThat(captor.getValue().getLastFetchStatus()).isEqualTo("OK");
        }

        @Test
        @DisplayName("With nothing active there is nothing to re-offer and no status is invented")
        void noActiveBundleIsANoOp() {
            when(bundleRepo.findActivePrices()).thenReturn(List.of());

            applier.reofferStoredPrices();

            verifyNoInteractions(priceApplier);
            verify(syncStatusRepo, never()).save(any());
        }
    }
}
