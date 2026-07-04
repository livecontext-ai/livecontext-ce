package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.CatalogBundleRepository;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apimarketplace.agent.cloud.CeBlockedProviders;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cloud-side catalog-bundle publisher.
 *
 * <p>Pipeline: {@link #buildBundle()} snapshots {@code model_config_overrides}
 * → canonical JSON → SHA-256 + Ed25519 sign → insert row with
 * {@code isActive=false}. A separate admin-initiated {@link #activateBundle(Long)}
 * flips the active flag (with a deactivate-all first, same TX) so the
 * partial unique index {@code idx_catalog_bundles_one_active} is never violated.
 * CE pulls via {@link #getActiveSignedBundle()}.
 *
 * <p>Versions are monotonically increasing. They are NOT the row PK - they're
 * the timestamp-millis of snapshot time. CE can use the version to decide
 * whether its copy is stale without a round-trip checksum compare.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogBundleService {

    private final CatalogBundleRepository bundleRepository;
    private final ModelConfigOverrideRepository modelRepository;
    private final ModelCategorySettingsRepository categoryRepository;
    private final CatalogBundleSigner signer;

    /**
     * Bundle payload schema version emitted by this publisher. v2 added the
     * {@code categories} field on each model row (V156 sidecar). The applier
     * is forward-compatible - v1 bundles have no {@code categories} field and
     * the per-row applier just skips the category-write step.
     */
    static final int CURRENT_SCHEMA_VERSION = 2;

    /** Max {@code buildBundle()} attempts before giving up on version collision. */
    static final int BUILD_MAX_ATTEMPTS = 5;

    /**
     * Snapshot the current catalog, sign it, and insert a new {@code is_active=false}
     * row. Returns the persisted bundle; call {@link #activateBundle(Long)}
     * when the cloud admin is ready to roll it out.
     *
     * <p><b>Horizontal-scaling safety:</b> two pods (or two admins on the same
     * pod) hitting Build within the same millisecond compute the same
     * {@code nextVersion()}; one {@code save()} wins, the other bumps the
     * version by 1 and retries. Not {@code @Transactional} at the method level
     * - each {@code save()} auto-commits so we can observe the unique-constraint
     * rejection and retry with a higher version. Bounded to {@link #BUILD_MAX_ATTEMPTS}
     * attempts; exceeding that throws {@link IllegalStateException}.
     *
     * @throws IllegalStateException if the signing key is not configured, the
     *     catalog is empty, or the retry budget is exhausted
     */
    public CatalogBundleEntity buildBundle() {
        if (!signer.canSign()) {
            throw new IllegalStateException(
                    "CATALOG_BUNDLE_SIGNING_KEY_PEM is not configured - cannot build a signed bundle");
        }

        List<ModelConfigOverrideEntity> models = modelRepository.findAllByOrderByRankingAsc();
        if (models.isEmpty()) {
            throw new IllegalStateException(
                    "model_config_overrides is empty - refusing to publish an empty catalog bundle");
        }

        // The model-catalog bundle is a CE-only distribution artifact. Exclude the
        // CE-blocked providers (openrouter aggregator, cohere) from the snapshot so
        // a self-hosted install never receives them via the signed catalog - cloud
        // keeps them in its own catalog + relay fallback, only the CE-bound bundle
        // drops them. CE also strips them defensively on apply
        // (CatalogBundleApplier); filtering at the source means every CE (linked or
        // not) gets a clean bundle, and CE's deprecate-missing merge self-heals any
        // openrouter/cohere row a prior bundle already applied.
        int ceBlockedFiltered = models.size();
        models = models.stream()
                .filter(m -> !com.apimarketplace.agent.cloud.CeBlockedProviders.isBlocked(m.getProvider()))
                .toList();
        ceBlockedFiltered -= models.size();
        if (ceBlockedFiltered > 0) {
            log.info("Catalog bundle: excluded {} CE-blocked model row(s) (openrouter/cohere) from the CE artifact",
                    ceBlockedFiltered);
        }

        // Load the V156 sidecar in the same logical snapshot. findAll() returns
        // every category row; canonicalBytes() filters orphans by joining on
        // model_config_id at serialisation time.
        List<ModelCategorySettingsEntity> categorySettings = categoryRepository.findAll();

        long version = nextVersion();
        for (int attempt = 1; attempt <= BUILD_MAX_ATTEMPTS; attempt++) {
            try {
                return attemptBuild(version, models, categorySettings);
            } catch (DataIntegrityViolationException e) {
                // Another pod beat us to this version. Bump and retry.
                long next = version + 1;
                log.warn("Bundle version {} already taken (attempt {}/{}): {} - retrying with {}",
                        version, attempt, BUILD_MAX_ATTEMPTS, e.getMostSpecificCause().getMessage(), next);
                version = next;
            }
        }
        throw new IllegalStateException(
                "Failed to build bundle after " + BUILD_MAX_ATTEMPTS +
                " version-collision retries - another pod is racing at an unexpected rate");
    }

    private CatalogBundleEntity attemptBuild(long version,
                                             List<ModelConfigOverrideEntity> models,
                                             List<ModelCategorySettingsEntity> categorySettings) {
        int schemaVersion = CURRENT_SCHEMA_VERSION;
        // Pin the snapshot time on the entity BEFORE computing the payload - the
        // canonical JSON includes it, so the re-signature path (toSignedBundle)
        // must read the same value back to rehydrate the exact same bytes.
        // Truncate to microseconds: Postgres TIMESTAMPTZ has microsecond
        // precision, Java Instant has nanoseconds. Without this, the value read
        // back from the DB after reload differs from the in-memory value used
        // to sign, and the stored checksum never matches the freshly computed
        // one on the first /latest fetch.
        Instant snapshotAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        CatalogBundleEntity entity = new CatalogBundleEntity();
        entity.setVersion(version);
        entity.setSchemaVersion(schemaVersion);
        entity.setSigningKeyId(signer.keyId());
        entity.setIssuer(signer.issuer());
        entity.setModelCount(models.size());
        entity.setActive(false);
        entity.setImportedAt(snapshotAt);

        byte[] payload = CatalogBundlePayload.canonicalBytes(
                version, schemaVersion, signer.issuer(), snapshotAt, models, categorySettings);

        entity.setChecksum(signer.checksum(payload));
        entity.setSignature(signer.sign(payload));
        entity.setRawBytesSize(payload.length);
        // V381: persist the exact signed bytes. Serving reads THIS, never the
        // live table - later catalog edits can no longer unserve any bundle.
        entity.setPayload(new String(payload, java.nio.charset.StandardCharsets.UTF_8));

        CatalogBundleEntity saved = bundleRepository.save(entity);
        log.info("Built catalog bundle: version={}, models={}, bytes={}, checksum={}",
                saved.getVersion(), saved.getModelCount(), saved.getRawBytesSize(), saved.getChecksum());
        return saved;
    }

    /**
     * Flip a bundle to {@code is_active=true}, deactivating any previously
     * active row in the same TX. No-op if the bundle is already active.
     *
     * @throws IllegalArgumentException if the bundle id doesn't exist
     */
    @Transactional
    public CatalogBundleEntity activateBundle(Long bundleId) {
        CatalogBundleEntity bundle = bundleRepository.findById(bundleId)
                .orElseThrow(() -> new IllegalArgumentException("Bundle not found: " + bundleId));

        if (bundle.isActive()) {
            log.info("Bundle {} (version={}) already active - no-op", bundleId, bundle.getVersion());
            return bundle;
        }

        int deactivated = bundleRepository.deactivateAll();
        bundle.setActive(true);
        bundle.setActivatedAt(Instant.now());
        CatalogBundleEntity saved = bundleRepository.save(bundle);
        log.info("Activated catalog bundle: version={} (deactivated {} previously active)",
                saved.getVersion(), deactivated);
        return saved;
    }

    /**
     * Return the envelope for the currently active bundle, re-signing the
     * payload from the live table. The persisted {@code checksum} is verified
     * against the freshly computed one - if the live table was tampered with
     * (admin edit without rebuilding the bundle) this throws to force the
     * operator to rebuild.
     */
    @Transactional(readOnly = true)
    public Optional<SignedBundle> getActiveSignedBundle() {
        return bundleRepository.findFirstByActiveTrue().map(this::toSignedBundle);
    }

    @Transactional(readOnly = true)
    public Optional<SignedBundle> getSignedBundleByVersion(long version) {
        return bundleRepository.findByVersion(version).map(this::toSignedBundle);
    }

    /** Issuer stamped on the exported seed doc (matches the shipped models.json). */
    static final String SEED_ISSUER = "model-catalog-seed";

    private static final com.fasterxml.jackson.databind.ObjectMapper SEED_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Build the model-catalog SEED document (the {@code models.json} shape:
     * {@code {version, issuer, models[]}}) from the CURRENT cloud catalog, for a
     * release to bake into the CE image. Reuses the exact canonical model
     * serialisation the signed bundle uses (so seed rows == bundle rows), minus:
     * <ul>
     *   <li>the {@code categories} sidecar (per-category curation stays the
     *       signed bundle's job; the seed must never wipe an admin's per-category
     *       toggles, which are cloud-authoritative in merge);</li>
     *   <li>{@code is_custom} rows (cloud-local additions, not a CE baseline);</li>
     *   <li>deprecated rows (retired models);</li>
     *   <li>{@link CeBlockedProviders} (openrouter, cohere) - the CE seed must
     *       never carry a provider a CE install hides at runtime.</li>
     * </ul>
     *
     * @param version monotonic seed version to stamp (the release supplies it, so
     *                a CE re-applies the refreshed seed exactly once on upgrade)
     * @throws IllegalStateException if version is non-positive or the catalog is empty
     */
    @Transactional(readOnly = true)
    public Map<String, Object> buildSeedExport(long version) {
        if (version <= 0) {
            throw new IllegalStateException(
                    "seed version must be a positive integer (got " + version +
                    ") - CE treats a non-positive version as unversioned");
        }
        List<ModelConfigOverrideEntity> models = modelRepository.findAllByOrderByRankingAsc().stream()
                .filter(m -> !m.isCustom())
                .filter(m -> m.getDeprecatedAt() == null)
                .filter(m -> !CeBlockedProviders.isBlocked(m.getProvider()))
                .toList();
        if (models.isEmpty()) {
            throw new IllegalStateException(
                    "No curated models to export - refusing to write an empty seed");
        }

        // 5-arg overload => NO categories field (seed omits per-category data).
        byte[] canonical = CatalogBundlePayload.canonicalBytes(
                version, 1, SEED_ISSUER, Instant.now(), models);

        try {
            Map<String, Object> doc = SEED_MAPPER.readValue(canonical,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            // Keep only the seed keys; the bundle metadata (schemaVersion,
            // snapshotAt) is irrelevant to the boot-time seed reader.
            Map<String, Object> seed = new LinkedHashMap<>();
            seed.put("version", version);
            seed.put("issuer", SEED_ISSUER);
            seed.put("models", doc.get("models"));
            return seed;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to build seed export", e);
        }
    }

    @Transactional(readOnly = true)
    public List<CatalogBundleEntity> listBundles() {
        return bundleRepository.findAll();
    }

    private SignedBundle toSignedBundle(CatalogBundleEntity entity) {
        // V381: serve the exact bytes that were signed at build time. Later
        // edits to the live catalog can never unserve a bundle any more - they
        // only mean a newer bundle can be built (auto-rebuild handles that).
        String stored = entity.getPayload();
        if (stored == null) {
            // Legacy row built before payload persistence. Its bytes cannot be
            // reconstructed reliably (the live table has moved on), so refuse
            // with a message written for the CE ADMIN who will actually read it
            // in the Bundles tab - the operator detail goes to the cloud log.
            log.warn("Catalog bundle v{} predates payload persistence (V381) and cannot be served; " +
                    "the auto-rebuild scheduler will publish a replacement on its next tick " +
                    "(or rebuild manually via the cloud admin Bundles tab).", entity.getVersion());
            throw new IllegalStateException(
                    "The cloud is republishing its model catalog. This install keeps its " +
                    "current models until the new bundle is available - no action needed.");
        }

        byte[] payload = stored.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String freshChecksum = signer.checksum(payload);
        if (!freshChecksum.equals(entity.getChecksum())) {
            // Genuine row corruption (the stored bytes no longer hash to the
            // signed checksum) - never expected in normal operation.
            log.error("Catalog bundle v{} STORED payload diverges from its checksum - row corrupted; " +
                    "rebuild + activate a new bundle.", entity.getVersion());
            throw new IllegalStateException(
                    "The cloud is republishing its model catalog. This install keeps its " +
                    "current models until the new bundle is available - no action needed.");
        }

        return new SignedBundle(
                entity.getVersion(),
                entity.getSchemaVersion(),
                entity.getChecksum(),
                entity.getSignature(),
                entity.getSigningKeyId(),
                entity.getIssuer(),
                entity.getModelCount(),
                entity.getRawBytesSize(),
                Base64.getEncoder().encodeToString(payload)
        );
    }

    /**
     * True when the ACTIVE bundle no longer reflects the live catalog: either
     * it predates payload persistence (V381, unservable) or the canonical
     * re-serialisation of the live table diverges from its checksum (the
     * catalog was edited since it was built). Used by the auto-rebuild
     * scheduler; no active bundle means "not stale" (first activation is a
     * deliberate admin decision).
     */
    @Transactional(readOnly = true)
    public boolean isActiveBundleStale() {
        Optional<CatalogBundleEntity> activeOpt = bundleRepository.findFirstByActiveTrue();
        if (activeOpt.isEmpty()) return false;
        CatalogBundleEntity active = activeOpt.get();
        if (active.getPayload() == null) return true; // legacy row: replace it

        List<ModelConfigOverrideEntity> models = modelRepository.findAllByOrderByRankingAsc();
        List<ModelCategorySettingsEntity> categorySettings = categoryRepository.findAll();
        byte[] fresh = CatalogBundlePayload.canonicalBytes(
                active.getVersion(), active.getSchemaVersion(),
                active.getIssuer(), active.getImportedAt(), models, categorySettings);
        return !signer.checksum(fresh).equals(active.getChecksum());
    }

    /**
     * Delete a bundle row. The ACTIVE bundle is protected - deactivate it by
     * activating another bundle first.
     *
     * @throws IllegalArgumentException when the id does not exist
     * @throws IllegalStateException when the bundle is active
     */
    @Transactional
    public void deleteBundle(Long bundleId) {
        CatalogBundleEntity bundle = bundleRepository.findById(bundleId)
                .orElseThrow(() -> new IllegalArgumentException("Bundle not found: " + bundleId));
        if (bundle.isActive()) {
            throw new IllegalStateException(
                    "Bundle " + bundleId + " (version " + bundle.getVersion() + ") is the ACTIVE bundle " +
                    "and cannot be deleted - activate another bundle first.");
        }
        bundleRepository.delete(bundle);
        log.info("Deleted catalog bundle id={} version={}", bundleId, bundle.getVersion());
    }

    /**
     * Retention: delete INACTIVE bundles beyond the {@code keep} most recent
     * (by version). The active bundle is never touched. Returns the number of
     * rows deleted. Called by the auto-rebuild scheduler after each publish so
     * the table cannot grow without bound.
     */
    @Transactional
    public int pruneInactiveBundles(int keep) {
        List<CatalogBundleEntity> inactive = bundleRepository.findByActiveFalseOrderByVersionDesc();
        if (inactive.size() <= keep) return 0;
        List<CatalogBundleEntity> toDelete = inactive.subList(keep, inactive.size());
        int count = toDelete.size();
        bundleRepository.deleteAll(toDelete);
        log.info("Pruned {} inactive catalog bundles (kept the {} most recent)", count, keep);
        return count;
    }

    /**
     * Monotonically-increasing version. Uses epoch millis for operator
     * readability (a version is a point in time) but falls back to
     * {@code max(version)+1} if the clock is behind the last bundle.
     */
    private long nextVersion() {
        long now = System.currentTimeMillis();
        Long lastVersion = bundleRepository.findTopByOrderByVersionDesc()
                .map(CatalogBundleEntity::getVersion).orElse(0L);
        return Math.max(now, lastVersion + 1);
    }
}
