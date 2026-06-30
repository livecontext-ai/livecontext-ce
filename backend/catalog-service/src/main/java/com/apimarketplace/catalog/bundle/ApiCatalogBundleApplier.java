package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CE-side: apply a verified {@link ApiCatalogSignedBundle} to {@code catalog.*}.
 * The per-row merge lives in {@link ApiCatalogMergeService}; this class owns
 * the bundle-specific bits: gunzip + parse, idempotency,
 * {@code api_catalog_bundles} persistence, and sync-status bookkeeping -
 * mirroring {@code agent-service CatalogBundleApplier}.
 *
 * <p>The verified payload bytes handed in by the caller are the GZIPPED
 * canonical JSON (the signature covers the gzip bytes) - this class gunzips
 * them before parsing.
 *
 * <p>Idempotent: re-applying the version recorded as the active bundle is a
 * no-op ({@code ALREADY_APPLIED}); the sync-status row is still refreshed so
 * operators see the OK heartbeat. A bundle row is only recorded on FULL
 * success - partial failure ({@code APPLY_PARTIAL}) leaves the version
 * unrecorded so the next tick retries it.
 *
 * <p>Deliberately NOT {@code @Transactional} as a whole: the merge runs one
 * transaction per API so a single bad API cannot roll back the other 600. The
 * post-merge bookkeeping (deactivate + record the bundle row + sync status)
 * runs in ITS OWN transaction via {@link TransactionTemplate} -
 * {@code deactivateAll()} is a JPA {@code @Modifying} bulk update that throws
 * {@code TransactionRequiredException} outside one (caught live in e2e: the
 * full 538-API merge succeeded, then the apply failed forever on this step).
 */
@Slf4j
@Service
public class ApiCatalogBundleApplier {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final ApiCatalogMergeService mergeService;
    private final ApiCatalogBundleRepository bundleRepo;
    private final ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate recordTx;

    public ApiCatalogBundleApplier(ApiCatalogMergeService mergeService,
                                   ApiCatalogBundleRepository bundleRepo,
                                   ApiCatalogBundleSyncStatusRepository syncStatusRepo,
                                   ObjectMapper objectMapper,
                                   PlatformTransactionManager transactionManager) {
        this.mergeService = mergeService;
        this.bundleRepo = bundleRepo;
        this.syncStatusRepo = syncStatusRepo;
        this.objectMapper = objectMapper;
        this.recordTx = new TransactionTemplate(transactionManager);
    }

    /** Max chars of per-API failure detail persisted on the sync-status row. */
    static final int MAX_FAILURE_DETAIL_LENGTH = 2000;
    /** Max number of per-API error lines included in the failure detail. */
    static final int MAX_FAILURE_DETAIL_ERRORS = 5;

    public record ApplyResult(Status status, long version, int upsertedApis, int upsertedTools,
                              int deprecatedApis, int skippedCustom, int failedApis,
                              String detail) {
        public static ApplyResult alreadyApplied(long v) {
            return new ApplyResult(Status.ALREADY_APPLIED, v, 0, 0, 0, 0, 0, null);
        }
        public static ApplyResult failed(String d) {
            return new ApplyResult(Status.APPLY_FAILED, 0, 0, 0, 0, 0, 0, d);
        }
    }

    /**
     * {@code APPLY_PARTIAL}: some APIs landed, some failed. The bundle row is
     * NOT recorded (so the version is retried next tick - the merge is an
     * idempotent UPSERT) and the sync-status row carries the per-API failure
     * detail. The applier writes that row itself, mirroring how it owns OK.
     */
    public enum Status { APPLIED, ALREADY_APPLIED, APPLY_PARTIAL, APPLY_FAILED }

    /**
     * Apply a verified bundle. Caller MUST have verified the signature first
     * (via {@link ApiCatalogBundleVerifier}) - this method assumes
     * {@code verifiedGzipBytes} are authentic.
     */
    public ApplyResult apply(ApiCatalogSignedBundle bundle, byte[] verifiedGzipBytes, String sourceUrl) {
        // 0. Idempotency: if this version is already the active bundle, skip.
        Optional<ApiCatalogBundleEntity> existing = bundleRepo.findByVersion(bundle.version());
        if (existing.isPresent() && existing.get().isActive()) {
            writeSuccessStatus(bundle.version(), now());
            return ApplyResult.alreadyApplied(bundle.version());
        }

        // 1. Gunzip + parse payload.
        List<Map<String, Object>> apiMaps;
        List<Map<String, Object>> templateMaps;
        try {
            byte[] raw = ApiCatalogBundlePayload.gunzip(verifiedGzipBytes);
            Map<String, Object> root = objectMapper.readValue(raw, JSON_MAP);
            apiMaps = listOfMaps(root.get("apis"));
            if (apiMaps == null) return ApplyResult.failed("payload has no 'apis' array");
            templateMaps = listOfMaps(root.get("credentialTemplates"));
            if (templateMaps == null) templateMaps = List.of();
        } catch (Exception e) {
            return ApplyResult.failed("payload gunzip/parse failed: " + e.getMessage());
        }

        // 1b. Wipe guard: a signed-but-empty bundle would reach the orphan
        // sweep with an empty present-list and soft-deprecate EVERY
        // bundle-managed API on this install. Refuse before any merge work.
        if (apiMaps.isEmpty()) {
            return ApplyResult.failed(
                    "bundle contains no APIs - refusing to deprecate the entire catalog");
        }

        // 2. Delegate the per-row merge (one TX per API + a final sweep TX).
        ApiCatalogMergeService.MergeResult merge = mergeService.merge(apiMaps, templateMaps);
        if (merge.upsertedApis() == 0 && merge.failedApis() > 0) {
            // Nothing landed - treat as failure so operators see it, and do
            // NOT record the bundle row (a retry must not be short-circuited
            // by idempotency).
            String detail = "all " + merge.failedApis() + " API upserts failed; first errors: "
                    + String.join(" | ", merge.errors().stream().limit(3).toList());
            return ApplyResult.failed(detail);
        }
        if (merge.failedApis() > 0) {
            // Partial failure: some APIs landed, some did not. Do NOT record
            // the bundle row - otherwise the idempotency check would
            // short-circuit every retry of this version as ALREADY_APPLIED and
            // the failed APIs would never land. Surface the failure on the
            // sync-status row instead; next tick re-applies the same version
            // (the merge is an idempotent UPSERT, so re-doing the successful
            // APIs is safe).
            String detail = failureSummary(merge);
            writePartialStatus(detail, now());
            log.warn("API catalog bundle v{} partially applied ({} of {} APIs failed) - " +
                            "bundle row NOT recorded, will retry next tick: {}",
                    bundle.version(), merge.failedApis(),
                    merge.upsertedApis() + merge.failedApis(), detail);
            return new ApplyResult(Status.APPLY_PARTIAL, bundle.version(),
                    merge.upsertedApis(), merge.upsertedTools(), merge.deprecatedApis(),
                    merge.skippedCustom(), merge.failedApis(), detail);
        }

        Instant now = now();

        // 3+4. Persist / flip the api_catalog_bundles row and the sync-status
        // row atomically. MUST run inside a transaction: deactivateAll() is a
        // JPA @Modifying bulk update (TransactionRequiredException otherwise).
        recordTx.executeWithoutResult(txStatus -> {
            ApiCatalogBundleEntity entity = existing.orElseGet(() -> {
                ApiCatalogBundleEntity e = new ApiCatalogBundleEntity();
                e.setVersion(bundle.version());
                e.setSchemaVersion(bundle.schemaVersion());
                e.setChecksum(bundle.checksum());
                e.setSignature(bundle.signature());
                e.setSigningKeyId(bundle.signingKeyId());
                e.setIssuer(bundle.issuer());
                e.setApiCount(bundle.apiCount());
                e.setToolCount(bundle.toolCount());
                e.setRawBytesSize((int) bundle.rawBytesSize());
                e.setImportedAt(now);
                // payload_gz intentionally left NULL on CE - the content now lives
                // in catalog.* and a CE never serves bundles.
                return e;
            });
            entity.setSourceUrl(sourceUrl);
            bundleRepo.deactivateAll();
            entity.setActive(true);
            entity.setActivatedAt(now);
            bundleRepo.save(entity);
            writeSuccessStatus(bundle.version(), now);
        });

        log.info("Applied API catalog bundle v{} - apis={}, tools={}, deprecatedApis={}, " +
                        "deprecatedTools={}, skippedCustom={}, templates={}",
                bundle.version(), merge.upsertedApis(), merge.upsertedTools(),
                merge.deprecatedApis(), merge.deprecatedTools(), merge.skippedCustom(),
                merge.upsertedTemplates());

        return new ApplyResult(Status.APPLIED, bundle.version(),
                merge.upsertedApis(), merge.upsertedTools(), merge.deprecatedApis(),
                merge.skippedCustom(), 0, null);
    }

    /**
     * Per-API failure summary persisted as {@code last_fetch_error}: failed
     * counts plus the first error lines (each already "api &lt;id&gt; (&lt;name&gt;):
     * exception"), truncated to a sane length for a status row.
     */
    private static String failureSummary(ApiCatalogMergeService.MergeResult merge) {
        String errors = String.join(" | ",
                merge.errors().stream().limit(MAX_FAILURE_DETAIL_ERRORS).toList());
        String summary = merge.failedApis() + " of "
                + (merge.upsertedApis() + merge.failedApis())
                + " API upserts failed; first errors: " + errors;
        return summary.length() <= MAX_FAILURE_DETAIL_LENGTH
                ? summary
                : summary.substring(0, MAX_FAILURE_DETAIL_LENGTH - 1) + "…";
    }

    /**
     * Stamp the sync-status row with an APPLY_PARTIAL outcome: error detail +
     * consecutive-failure bump so operators see amber, not green. The
     * last-applied marker is NOT advanced - this version did not fully land.
     */
    private void writePartialStatus(String detail, Instant now) {
        ApiCatalogBundleSyncStatusEntity status = syncStatusRepo
                .findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(ApiCatalogBundleSyncStatusEntity::new);
        status.setLastFetchAt(now);
        status.setLastFetchStatus("APPLY_PARTIAL");
        status.setLastFetchError(detail);
        status.setConsecutiveFailures(status.getConsecutiveFailures() + 1);
        status.setUpdatedAt(now);
        syncStatusRepo.save(status);
    }

    /** Stamp the sync-status row with an OK outcome for {@code version}. */
    private void writeSuccessStatus(long version, Instant now) {
        ApiCatalogBundleSyncStatusEntity status = syncStatusRepo
                .findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(ApiCatalogBundleSyncStatusEntity::new);
        status.setLastAppliedVersion(version);
        status.setLastAppliedAt(now);
        status.setLastFetchAt(now);
        status.setLastFetchStatus("OK");
        status.setLastFetchError(null);
        status.setConsecutiveFailures(0);
        status.setUpdatedAt(now);
        syncStatusRepo.save(status);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : null;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
