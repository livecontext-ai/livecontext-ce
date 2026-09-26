package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
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

    private final ApiCatalogMergeService mergeService;
    private final ApiCatalogGenerationPriceApplier priceApplier;
    private final ApiCatalogBundleRepository bundleRepo;
    private final ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate recordTx;

    public ApiCatalogBundleApplier(ApiCatalogMergeService mergeService,
                                   ApiCatalogGenerationPriceApplier priceApplier,
                                   ApiCatalogBundleRepository bundleRepo,
                                   ApiCatalogBundleSyncStatusRepository syncStatusRepo,
                                   ObjectMapper objectMapper,
                                   PlatformTransactionManager transactionManager) {
        this.mergeService = mergeService;
        this.priceApplier = priceApplier;
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
            // The CATALOG is already in place, but a price this version carries
            // can become applicable later than the version itself: the price
            // hangs off a platform credential, and an operator who pastes a
            // provider key next week had no credential for it to attach to when
            // this bundle first landed. Re-offering costs nothing (auth
            // publishes nothing when nothing changed) and is what eventually
            // prices that integration without waiting for the cloud to cut a new
            // bundle it has no reason to cut.
            // ONE gunzip + parse for both answers. Reading the payload twice
            // here would double the transient cost of the very path every
            // install takes while it captures its prices for the first time.
            ParsedPrices parsed = parsePricesQuietly(verifiedGzipBytes, bundle.version());
            priceApplier.apply(parsed.prices(), bundle.version());
            // Self-healing for an install that applied this bundle BEFORE the
            // prices column existed: its row has none stored, so it cannot go
            // conditional yet. Capturing them here lets the very next tick send
            // a validator and stop transferring the payload.
            //
            // Only on a READABLE payload: "the bundle declares no prices" and
            // "the re-read failed" both yield no prices, and recording the
            // second as a capture would freeze a transient failure into a
            // permanent "this version says nothing", after which the install
            // goes conditional and never re-offers them again.
            if (existing.get().getGenerationPrices() == null && parsed.readable()) {
                try {
                    existing.get().setGenerationPrices(
                            writePricesQuietly(parsed.prices() == null ? List.of() : parsed.prices(),
                                    bundle.version()));
                    bundleRepo.save(existing.get());
                } catch (Exception e) {
                    // Best effort: this is an optimisation for the NEXT tick, so
                    // it must never turn a healthy already-applied tick into a
                    // failure. The worst case is one more full fetch.
                    log.warn("API catalog bundle v{}: could not capture prices for the conditional path "
                            + "({}: {}) - the next tick will fetch in full", bundle.version(),
                            e.getClass().getSimpleName(), e.getMessage());
                }
            }
            writeSuccessStatus(bundle.version(), now());
            return ApplyResult.alreadyApplied(bundle.version());
        }

        // 1. Validate the payload in one streaming pass, WITHOUT materialising it: the
        // catalog gunzips to hundreds of MB and the CE heap is 1 GB (see
        // ApiCatalogPayloadStream). Every refusal on the payload's shape happens here,
        // before any row is written.
        ApiCatalogPayloadStream payload = new ApiCatalogPayloadStream(objectMapper, verifiedGzipBytes);
        ApiCatalogPayloadStream.Scan scan;
        try {
            scan = payload.scan();
        } catch (Exception e) {
            return ApplyResult.failed("payload gunzip/parse failed: " + e.getMessage());
        }
        if (!scan.apisIsArray()) return ApplyResult.failed("payload has no 'apis' array");
        if (scan.duplicateApis()) return ApplyResult.failed("payload has more than one 'apis' key");
        if (scan.nonObjectApis() > 0) {
            return ApplyResult.failed("payload 'apis' has " + scan.nonObjectApis() + " entries that are not objects");
        }
        List<Map<String, Object>> templateMaps = scan.templates();
        // Absent on every bundle built by a cloud older than V430, and on
        // any cloud with no published generation price. Absent means "this
        // bundle says nothing about prices", never "there are no prices",
        // so it is a no-op rather than a reason to fail or to unprice.
        List<Map<String, Object>> priceMaps = scan.prices();

        // 1b. Wipe guard: a signed-but-empty bundle would reach the orphan
        // sweep with an empty present-list and soft-deprecate EVERY
        // bundle-managed API on this install. Refuse before any merge work.
        if (scan.apiCount() == 0) {
            return ApplyResult.failed(
                    "bundle contains no APIs - refusing to deprecate the entire catalog");
        }

        // 2. Delegate the per-row merge (one TX per API + a final sweep TX), fed one API
        // at a time from a second streaming pass over the same verified bytes.
        ApiCatalogMergeService.MergeResult merge;
        try {
            merge = mergeService.merge(payload.apis(), templateMaps);
        } catch (java.io.UncheckedIOException e) {
            // The scan above read these exact bytes, so this is not expected. If it ever
            // happens the merge stopped part way: the APIs already upserted are committed
            // (one TX each, as on any partial apply), the orphan sweep and the template
            // upsert did NOT run, and the bundle row is not recorded, so the next tick
            // retries this version. The scheduler records APPLY_FAILED with this detail on
            // the sync-status row, as for every other failed apply (writing it here too would
            // count the failure twice).
            return ApplyResult.failed("payload re-read failed during merge: " + e.getMessage());
        }
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

        // 2b. Prices, once every endpoint has landed. Deliberately AFTER the
        // merge and only on a FULL success: a price row is keyed on an endpoint
        // UUID, so publishing prices over a half-applied catalog would price
        // endpoints this install does not have yet. A partial apply returns
        // above and is retried next tick, which is when its prices arrive.
        //
        // Deliberately OUTSIDE the bookkeeping transaction below as well: the
        // prices live in another service's schema, so there is no transaction
        // that could span both, and the honest arrangement is the one that
        // cannot lose the catalog because the price call failed.
        priceApplier.apply(priceMaps, bundle.version());

        Instant now = now();

        // 3+4. Persist / flip the api_catalog_bundles row and the sync-status
        // row atomically. MUST run inside a transaction: deactivateAll() is a
        // JPA @Modifying bulk update (TransactionRequiredException otherwise).
        try {
            recordTx.executeWithoutResult(txStatus -> {
                // Re-read INSIDE the tx: the line-100 idempotency check is a TOCTOU
                // window - a concurrent apply (a manual "Sync now" racing the 15-min
                // scheduler) may have inserted this exact version in the meantime.
                // Reuse that row (UPDATE) instead of inserting a duplicate that would
                // blow the api_catalog_bundles_version_key unique constraint.
                ApiCatalogBundleEntity entity = bundleRepo.findByVersion(bundle.version()).orElseGet(() -> {
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
                // Keep the prices this version carried so a later tick can
                // re-offer them without the payload (the 304 path).
                //
                // ALWAYS write, even for a bundle that carries none: an empty
                // array records "captured, this bundle says nothing", which is a
                // different statement from NULL ("never captured"). Collapsing
                // the two would leave a price-less bundle NULL forever, so the
                // install could never go conditional and would keep downloading
                // the whole payload every 15 minutes with nothing to show for it.
                entity.setGenerationPrices(writePricesQuietly(
                        priceMaps == null ? List.of() : priceMaps, bundle.version()));
                bundleRepo.deactivateAll();
                entity.setActive(true);
                entity.setActivatedAt(now);
                bundleRepo.save(entity);
                writeSuccessStatus(bundle.version(), now);
            });
        } catch (DataIntegrityViolationException dup) {
            // Expected ONLY for the unique-version race: two applies passed the in-tx re-read before
            // either committed, both INSERTed, and this loser tripped api_catalog_bundles_version_key
            // (its tx rolled back, so the winner's active row survives). Confirm that is what happened
            // - the version is now present AND active - before declaring an idempotent no-op. Any
            // OTHER integrity violation (a future NOT NULL / length / FK) must NOT be masked as a green
            // success, so rethrow it and let the caller record a real failure.
            if (bundleRepo.findByVersion(bundle.version()).filter(ApiCatalogBundleEntity::isActive).isEmpty()) {
                throw dup;
            }
            log.info("API catalog bundle v{} applied concurrently (unique-version race); treating as already applied",
                    bundle.version());
            writeSuccessStatus(bundle.version(), now);
            return ApplyResult.alreadyApplied(bundle.version());
        }

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
     * Re-offer stamp helper. Stamp the sync-status row with an APPLY_PARTIAL outcome: error detail +
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
    /**
     * Re-offer the prices the active bundle carried, read from the row rather
     * than from a payload.
     *
     * <p>This is the 304 path: the cloud confirmed our bundle is still the
     * active one, so there is no catalog work to do, but the price re-offer
     * still has to happen - it is what eventually prices an integration whose
     * provider key the operator pasted weeks after the bundle landed. Without
     * it, making the poll cheap would silently delete that behaviour.
     *
     * <p>Never throws: a pricing hiccup must not turn an up-to-date install into
     * a failing one. Writes the same OK status an {@code ALREADY_APPLIED} does,
     * so the operator UI sees a healthy sync with no new status value to render.
     */
    public void reofferStoredPrices() {
        // Read through the payload-free projection, ordered exactly like the one
        // that produced the validator: the two must agree on which row is the
        // active one, and this runs every 15 minutes on every install.
        Optional<ApiCatalogBundleRepository.ActiveBundlePrices> active =
                bundleRepo.findActivePrices().stream().findFirst();
        if (active.isEmpty()) {
            log.debug("API catalog bundle: nothing active, no prices to re-offer");
            return;
        }
        ApiCatalogBundleRepository.ActiveBundlePrices row = active.get();
        try {
            List<Map<String, Object>> prices = readPricesQuietly(row.getGenerationPrices(), row.getVersion());
            // Null is "this bundle says nothing about prices" - the applier
            // treats that as a no-op, never as "unprice everything".
            priceApplier.apply(prices, row.getVersion());
        } catch (Exception e) {
            log.warn("API catalog bundle v{}: stored prices could not be re-offered ({}: {}) - "
                    + "pricing left unchanged", row.getVersion(), e.getClass().getSimpleName(), e.getMessage());
        }
        writeSuccessStatus(row.getVersion(), now());
    }

    /** Serialise the prices for storage; null on failure, never throws. */
    private String writePricesQuietly(List<Map<String, Object>> priceMaps, long version) {
        try {
            return objectMapper.writeValueAsString(priceMaps);
        } catch (Exception e) {
            log.warn("API catalog bundle v{}: generation prices could not be stored ({}: {}) - "
                    + "the next 304 tick will have nothing to re-offer", version,
                    e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** Read back stored prices; null when absent or unreadable, never throws. */
    private List<Map<String, Object>> readPricesQuietly(String stored, long version) {
        if (stored == null || stored.isBlank()) return null;
        try {
            return listOfMaps(objectMapper.readValue(stored, Object.class));
        } catch (Exception e) {
            log.warn("API catalog bundle v{}: stored generation prices are unreadable ({}: {}) - "
                    + "pricing left unchanged", version, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

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

    /**
     * The {@code generationPrices} array of an already-applied bundle, or null.
     *
     * <p>Separate from the main parse because the two failures mean different
     * things. On the apply path a payload that will not parse is a real failure
     * and is reported. Here the catalog is already in place and the only thing
     * at stake is a price refresh, so a parse problem must not turn a green
     * ALREADY_APPLIED into a red one.
     */
    /**
     * The prices a payload declares, and whether the payload could be read at
     * all. The two are returned together because collapsing them loses the
     * difference between "declares none" and "could not be read", and because
     * answering them separately would mean gunzipping the payload twice.
     */
    private record ParsedPrices(boolean readable, List<Map<String, Object>> prices) {
        static ParsedPrices unreadable() {
            return new ParsedPrices(false, null);
        }
    }

    private ParsedPrices parsePricesQuietly(byte[] verifiedGzipBytes, long version) {
        try {
            // Streamed: the full payload does not fit the CE heap (see ApiCatalogPayloadStream).
            return new ParsedPrices(true, new ApiCatalogPayloadStream(objectMapper, verifiedGzipBytes).scan().prices());
        } catch (Exception e) {
            log.warn("API catalog bundle v{} is already applied but its payload could not be re-read "
                    + "for prices ({}: {}) - pricing left unchanged", version,
                    e.getClass().getSimpleName(), e.getMessage());
            return ParsedPrices.unreadable();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : null;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
