package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleRepository;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * CE-side: apply a verified {@link SignedBundle} to
 * {@code model_config_overrides}. The per-row merge loop lives in
 * {@link CatalogMergeService} and is shared with
 * {@code ModelCatalogSyncService}; this class owns the bundle-specific bits:
 * idempotency, {@code catalog_bundles} persistence, and sync-status
 * bookkeeping.
 *
 * <p>Merge semantics (enforced by {@link CatalogMergeService}):
 * <ul>
 *   <li>{@code is_custom=true} rows - untouched.</li>
 *   <li>Fields listed in {@code user_modified_fields} - preserved.</li>
 *   <li>Rows absent from the bundle (non-custom, non-bridge, not already
 *       deprecated) - {@code deprecated_at=now()}.</li>
 *   <li>Pricing changes - flushed to auth-service after the enclosing TX
 *       commits.</li>
 * </ul>
 *
 * <p>Idempotent: re-applying the same bundle version is a no-op (detected via
 * {@code catalog_bundles.is_active} + version match).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogBundleApplier {

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final CatalogMergeService mergeService;
    private final CatalogBundleRepository bundleRepo;
    private final CatalogBundleSyncStatusRepository syncStatusRepo;
    private final ObjectMapper objectMapper;

    public record ApplyResult(Status status, long version, int inserted, int updated,
                              int deprecated, int skippedCustom, int skippedUserModified,
                              String detail) {
        public static ApplyResult alreadyApplied(long v) {
            return new ApplyResult(Status.ALREADY_APPLIED, v, 0, 0, 0, 0, 0, null);
        }
        public static ApplyResult failed(String d) {
            return new ApplyResult(Status.APPLY_FAILED, 0, 0, 0, 0, 0, 0, d);
        }
    }

    public enum Status { APPLIED, ALREADY_APPLIED, APPLY_FAILED }

    /**
     * Apply a verified bundle. Caller MUST have verified the signature first
     * (via {@link CatalogBundleVerifier}) - this method assumes the bytes it
     * receives are authentic.
     */
    @Transactional
    public ApplyResult apply(SignedBundle bundle, byte[] verifiedPayloadBytes, String sourceUrl) {
        // 0. Idempotency: if this version is already the active bundle, skip.
        Optional<CatalogBundleEntity> existing = bundleRepo.findByVersion(bundle.version());
        if (existing.isPresent() && existing.get().isActive()) {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            writeSuccessStatus(bundle.version(), now);
            return ApplyResult.alreadyApplied(bundle.version());
        }

        // 1. Parse payload.
        List<Map<String, Object>> modelMaps;
        try {
            Map<String, Object> root = objectMapper.readValue(verifiedPayloadBytes, JSON_MAP);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> raw = (List<Map<String, Object>>) root.get("models");
            if (raw == null) return ApplyResult.failed("payload has no 'models' array");
            modelMaps = raw;
        } catch (Exception e) {
            return ApplyResult.failed("payload parse failed: " + e.getMessage());
        }

        // 2. Delegate the actual per-row merge.
        CatalogMergeService.MergeResult merge =
                mergeService.merge(modelMaps, MergeOptions.forBundle(bundle.version()));

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // 3. Persist / flip the catalog_bundles row.
        CatalogBundleEntity entity = existing.orElseGet(() -> {
            CatalogBundleEntity e = new CatalogBundleEntity();
            e.setVersion(bundle.version());
            e.setSchemaVersion(bundle.schemaVersion());
            e.setChecksum(bundle.checksum());
            e.setSignature(bundle.signature());
            e.setSigningKeyId(bundle.signingKeyId());
            e.setIssuer(bundle.issuer());
            e.setModelCount(bundle.modelCount());
            e.setRawBytesSize(bundle.rawBytesSize());
            e.setImportedAt(now);
            return e;
        });
        entity.setSourceUrl(sourceUrl);
        // Deactivate all others atomically, then flip this one on.
        bundleRepo.deactivateAll();
        entity.setActive(true);
        entity.setActivatedAt(now);
        bundleRepo.save(entity);

        // 4. Update sync-status row.
        writeSuccessStatus(bundle.version(), now);

        log.info("Applied catalog bundle v{} - inserted={}, updated={}, deprecated={}, " +
                        "skippedCustom={}, skippedUserModified={}, pricingChanges={}",
                bundle.version(), merge.inserted(), merge.updated(), merge.deprecated(),
                merge.skippedCustom(), merge.skippedUserModified(), merge.pricingChangeCount());

        return new ApplyResult(Status.APPLIED, bundle.version(),
                merge.inserted(), merge.updated(), merge.deprecated(),
                merge.skippedCustom(), merge.skippedUserModified(), null);
    }

    /**
     * Stamp the sync-status row with an OK outcome for {@code version}.
     */
    private void writeSuccessStatus(long version, Instant now) {
        CatalogBundleSyncStatusEntity status = syncStatusRepo
                .findById(CatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(CatalogBundleSyncStatusEntity::new);
        status.setLastAppliedVersion(version);
        status.setLastAppliedAt(now);
        status.setLastFetchAt(now);
        status.setLastFetchStatus("OK");
        status.setLastFetchError(null);
        status.setConsecutiveFailures(0);
        status.setUpdatedAt(now);
        syncStatusRepo.save(status);
    }
}
