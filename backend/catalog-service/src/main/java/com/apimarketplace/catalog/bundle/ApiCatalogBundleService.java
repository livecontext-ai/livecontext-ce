package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Cloud-side API-catalog bundle publisher. Mirrors the LLM model bundle
 * ({@code agent-service CatalogBundleService}) with one structural change:
 * the signed payload is PERSISTED on the bundle row ({@code payload_gz}).
 *
 * <p>Pipeline: {@link #buildBundle()} snapshots {@code catalog.*}
 * (import/bundle-sourced, non-deprecated rows) → canonical JSON → gzip →
 * SHA-256 + Ed25519 over the GZIP bytes → insert row with the gzip payload and
 * {@code isActive=false}. {@link #activateBundle(Long)} flips the active flag
 * (deactivate-all first, same TX, so the partial unique index
 * {@code idx_api_catalog_bundles_one_active} is never violated). CE pulls via
 * {@link #getActiveSignedBundle()}, which serves the stored bytes verbatim -
 * later edits to the live catalog can never invalidate an already-built
 * bundle (unlike the model bundle, which re-derives at read time).
 *
 * <p>Versions are monotonically increasing epoch-millis with a
 * {@code max(version)+1} fallback, same contract as the model bundle.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiCatalogBundleService {

    /** Bundle payload schema version emitted by this publisher. */
    static final int CURRENT_SCHEMA_VERSION = 1;

    /** Max {@code buildBundle()} attempts before giving up on version collision. */
    static final int BUILD_MAX_ATTEMPTS = 5;

    private final ApiCatalogBundleRepository bundleRepository;
    private final ApiCatalogSnapshotReader snapshotReader;
    private final ApiCatalogBundleSigner signer;

    /**
     * Snapshot the current catalog, sign it, and insert a new
     * {@code is_active=false} row carrying the signed gzip payload. Returns the
     * persisted bundle; call {@link #activateBundle(Long)} to roll it out.
     *
     * <p><b>Horizontal-scaling safety:</b> not {@code @Transactional} at the
     * method level - each {@code save()} auto-commits so a UNIQUE(version)
     * collision from a racing pod can be observed and retried with a bumped
     * version, bounded to {@link #BUILD_MAX_ATTEMPTS}.
     *
     * @throws IllegalStateException if the signing key is not configured, the
     *     catalog snapshot is empty, or the retry budget is exhausted
     */
    public ApiCatalogBundleEntity buildBundle() {
        if (!signer.canSign()) {
            throw new IllegalStateException(
                    "CATALOG_BUNDLE_SIGNING_KEY_PEM is not configured - cannot build a signed API catalog bundle");
        }

        ApiCatalogSnapshotReader.Snapshot snapshot = snapshotReader.snapshot();
        if (snapshot.apis().isEmpty()) {
            throw new IllegalStateException(
                    "catalog.apis has no bundle-managed rows (source IN ('import','bundle'), " +
                    "deprecated_at IS NULL) - refusing to publish an empty API catalog bundle");
        }

        long version = nextVersion();
        for (int attempt = 1; attempt <= BUILD_MAX_ATTEMPTS; attempt++) {
            try {
                return attemptBuild(version, snapshot);
            } catch (DataIntegrityViolationException e) {
                long next = version + 1;
                log.warn("API catalog bundle version {} already taken (attempt {}/{}): {} - retrying with {}",
                        version, attempt, BUILD_MAX_ATTEMPTS, e.getMostSpecificCause().getMessage(), next);
                version = next;
            }
        }
        throw new IllegalStateException(
                "Failed to build API catalog bundle after " + BUILD_MAX_ATTEMPTS +
                " version-collision retries - another pod is racing at an unexpected rate");
    }

    private ApiCatalogBundleEntity attemptBuild(long version, ApiCatalogSnapshotReader.Snapshot snapshot) {
        // Truncate to microseconds (Postgres TIMESTAMPTZ precision). The value
        // is embedded in the canonical JSON; keeping it micro-aligned avoids
        // any in-memory/DB drift in diagnostics, even though this bundle never
        // re-derives bytes from the row.
        Instant snapshotAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        byte[] rawPayload = ApiCatalogBundlePayload.canonicalBytes(
                version, CURRENT_SCHEMA_VERSION, signer.issuer(), snapshotAt,
                snapshot.apis(), snapshot.credentialTemplates());
        byte[] gzipped = ApiCatalogBundlePayload.gzip(rawPayload);

        ApiCatalogBundleEntity entity = new ApiCatalogBundleEntity();
        entity.setVersion(version);
        entity.setSchemaVersion(CURRENT_SCHEMA_VERSION);
        entity.setSigningKeyId(signer.keyId());
        entity.setIssuer(signer.issuer());
        entity.setApiCount(snapshot.apiCount());
        entity.setToolCount(snapshot.toolCount());
        entity.setRawBytesSize(rawPayload.length);
        entity.setPayloadGz(gzipped);
        entity.setActive(false);
        entity.setImportedAt(snapshotAt);
        // Checksum + signature cover the GZIP bytes - the exact bytes served.
        entity.setChecksum(signer.checksum(gzipped));
        entity.setSignature(signer.sign(gzipped));

        ApiCatalogBundleEntity saved = bundleRepository.save(entity);
        log.info("Built API catalog bundle: version={}, apis={}, tools={}, rawBytes={}, gzBytes={}, checksum={}",
                saved.getVersion(), saved.getApiCount(), saved.getToolCount(),
                saved.getRawBytesSize(), gzipped.length, saved.getChecksum());
        return saved;
    }

    /**
     * Flip a bundle to {@code is_active=true}, deactivating any previously
     * active row in the same TX. No-op if the bundle is already active.
     *
     * @throws IllegalArgumentException if the bundle id doesn't exist
     */
    @Transactional
    public ApiCatalogBundleEntity activateBundle(Long bundleId) {
        ApiCatalogBundleEntity bundle = bundleRepository.findById(bundleId)
                .orElseThrow(() -> new IllegalArgumentException("Bundle not found: " + bundleId));

        if (bundle.isActive()) {
            log.info("API catalog bundle {} (version={}) already active - no-op", bundleId, bundle.getVersion());
            return bundle;
        }

        int deactivated = bundleRepository.deactivateAll();
        bundle.setActive(true);
        bundle.setActivatedAt(Instant.now());
        ApiCatalogBundleEntity saved = bundleRepository.save(bundle);
        log.info("Activated API catalog bundle: version={} (deactivated {} previously active)",
                saved.getVersion(), deactivated);
        return saved;
    }

    /**
     * Envelope for the currently active bundle, served verbatim from the
     * stored {@code payload_gz} - no re-snapshot, no checksum drift.
     */
    @Transactional(readOnly = true)
    public Optional<ApiCatalogSignedBundle> getActiveSignedBundle() {
        return bundleRepository.findFirstByActiveTrue().flatMap(this::toSignedBundle);
    }

    @Transactional(readOnly = true)
    public Optional<ApiCatalogSignedBundle> getSignedBundleByVersion(long version) {
        return bundleRepository.findByVersion(version).flatMap(this::toSignedBundle);
    }

    @Transactional(readOnly = true)
    public List<ApiCatalogBundleEntity> listBundles() {
        return bundleRepository.findAll();
    }

    private Optional<ApiCatalogSignedBundle> toSignedBundle(ApiCatalogBundleEntity entity) {
        byte[] gz = entity.getPayloadGz();
        if (gz == null || gz.length == 0) {
            // CE-side rows record applied bundles without the payload; they are
            // not servable (a CE never acts as a bundle origin).
            log.warn("API catalog bundle version {} has no stored payload - not servable", entity.getVersion());
            return Optional.empty();
        }
        return Optional.of(new ApiCatalogSignedBundle(
                entity.getVersion(),
                entity.getSchemaVersion(),
                entity.getChecksum(),
                entity.getSignature(),
                entity.getSigningKeyId(),
                entity.getIssuer(),
                entity.getApiCount() == null ? 0 : entity.getApiCount(),
                entity.getToolCount() == null ? 0 : entity.getToolCount(),
                entity.getRawBytesSize() == null ? 0 : entity.getRawBytesSize(),
                Base64.getEncoder().encodeToString(gz)
        ));
    }

    /**
     * Monotonically-increasing version: epoch millis with a
     * {@code max(version)+1} fallback if the clock is behind the last bundle.
     */
    private long nextVersion() {
        long now = System.currentTimeMillis();
        Long lastVersion = bundleRepository.findTopByOrderByVersionDesc()
                .map(ApiCatalogBundleEntity::getVersion).orElse(0L);
        return Math.max(now, lastVersion + 1);
    }
}
