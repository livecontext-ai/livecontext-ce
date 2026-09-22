package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

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
 * {@link #getActiveRawBundle()}, which serves the stored bytes verbatim -
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

    /**
     * Starting size of the buffer holding the COMPRESSED payload, so the build
     * does not spend the whole serialisation re-growing and copying it. The
     * catalog currently compresses to a few MB; the buffer grows on its own if
     * that stops being true.
     */
    private static final int GZIP_BUFFER_HINT_BYTES = 8 * 1024 * 1024;

    /**
     * Deflate buffer. Bigger than the default 512 bytes so a payload of this
     * size is compressed in fewer, larger passes.
     */
    private static final int GZIP_STREAM_BUFFER_BYTES = 64 * 1024;

    private final ApiCatalogBundleRepository bundleRepository;
    private final ApiCatalogSnapshotReader snapshotReader;
    private final ApiCatalogGenerationPriceReader priceReader;
    private final ApiCatalogBundleSigner signer;
    private final ApiCatalogBundleChunkReader chunkReader;

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

        // Read ONCE, before the retry loop: a version collision is a race with
        // another pod, not a reason to re-ask auth-service for the same prices.
        List<ApiCatalogBundlePayload.GenerationPriceRow> prices = priceReader.read(snapshot);

        long version = nextVersion();
        for (int attempt = 1; attempt <= BUILD_MAX_ATTEMPTS; attempt++) {
            try {
                return attemptBuild(version, snapshot, prices);
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

    private ApiCatalogBundleEntity attemptBuild(long version, ApiCatalogSnapshotReader.Snapshot snapshot,
                                                List<ApiCatalogBundlePayload.GenerationPriceRow> prices) {
        // Truncate to microseconds (Postgres TIMESTAMPTZ precision). The value
        // is embedded in the canonical JSON; keeping it micro-aligned avoids
        // any in-memory/DB drift in diagnostics, even though this bundle never
        // re-derives bytes from the row.
        Instant snapshotAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        // Serialised STRAIGHT INTO the gzip stream, never into a byte[]. The
        // canonical payload of the full catalog is hundreds of megabytes, and
        // materialising it (tree + Jackson segments + array copy + a second copy
        // to gzip it) is what made every build answer HTTP 500 with an
        // OutOfMemoryError once the catalog passed ~19k endpoints. Only the
        // COMPRESSED bytes are held, because the signature covers those and the
        // row persists them.
        ByteArrayOutputStream gzBuffer = new ByteArrayOutputStream(GZIP_BUFFER_HINT_BYTES);
        long rawPayloadSize;
        // The 2-arg constructor on purpose: it leaves syncFlush FALSE. Jackson flushes after every
        // row (FLUSH_AFTER_WRITE_VALUE) and that flush is passed through to this stream, so the
        // 3-arg form with syncFlush=true would emit a SYNC_FLUSH marker per row and inflate the
        // payload substantially. Nothing else would notice: the bytes still gunzip and still verify.
        try (GZIPOutputStream gz = new GZIPOutputStream(gzBuffer, GZIP_STREAM_BUFFER_BYTES)) {
            rawPayloadSize = ApiCatalogBundlePayload.writeCanonical(
                    gz, version, CURRENT_SCHEMA_VERSION, signer.issuer(), snapshotAt,
                    snapshot.apis(), snapshot.credentialTemplates(), prices);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to gzip catalog bundle payload", e);
        }
        byte[] gzipped = gzBuffer.toByteArray();
        if (gzipped.length == 0) {
            // Keeps "has a payload" a single rule across the two read paths:
            // the metadata projection can only test payload_gz IS NULL cheaply
            // (testing its length would have to read the blob, which is the
            // cost the projection exists to avoid), while the body path also
            // rejects an empty array. Refusing to persist an empty payload
            // makes the two statements equivalent instead of merely similar.
            throw new IllegalStateException(
                    "Refusing to persist an API catalog bundle with an empty payload");
        }

        ApiCatalogBundleEntity entity = new ApiCatalogBundleEntity();
        entity.setVersion(version);
        entity.setSchemaVersion(CURRENT_SCHEMA_VERSION);
        entity.setSigningKeyId(signer.keyId());
        entity.setIssuer(signer.issuer());
        entity.setApiCount(snapshot.apiCount());
        entity.setToolCount(snapshot.toolCount());
        entity.setRawBytesSize(narrowRawSize(rawPayloadSize));
        entity.setPayloadGz(gzipped);
        entity.setActive(false);
        entity.setImportedAt(snapshotAt);
        // Checksum + signature cover the GZIP bytes - the exact bytes served.
        entity.setChecksum(signer.checksum(gzipped));
        entity.setSignature(signer.sign(gzipped));

        ApiCatalogBundleEntity saved = bundleRepository.save(entity);
        log.info("Built API catalog bundle: version={}, apis={}, tools={}, generationPrices={}, "
                        + "rawBytes={}, gzBytes={}, checksum={}",
                saved.getVersion(), saved.getApiCount(), saved.getToolCount(),
                prices == null ? 0 : prices.size(),
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
     * Identity of the active bundle without reading {@code payload_gz}.
     *
     * <p>The conditional-GET path calls this first so an unchanged bundle costs
     * three scalars instead of ~24 MB of gzip pulled into heap. See
     * {@link ApiCatalogBundleRepository#findActiveMetadata()}.
     */
    @Transactional(readOnly = true)
    public Optional<ApiCatalogBundleRepository.ActiveBundleMeta> getActiveBundleMetadata() {
        return bundleRepository.findActiveMetadata().stream().findFirst();
    }

    /**
     * The active bundle with its payload still as raw GZIP bytes, for callers
     * that stream base64 straight to the response instead of materialising it.
     * Empty when there is no active row, or when the row carries no payload
     * (a CE-side applied row is not an origin).
     */
    @Transactional(readOnly = true)
    public Optional<RawBundle> getActiveRawBundle() {
        return bundleRepository.findActiveServingView().stream().findFirst().flatMap(this::toRawBundle);
    }

    /** As {@link #getActiveRawBundle()} for one specific version. */
    @Transactional(readOnly = true)
    public Optional<RawBundle> getRawBundleByVersion(long version) {
        return bundleRepository.findServingViewByVersion(version).stream().findFirst().flatMap(this::toRawBundle);
    }

    private Optional<RawBundle> toRawBundle(ApiCatalogBundleRepository.ServingView view) {
        // Read off the projection here, while the read-only transaction is still
        // open. The payload supplier below runs later, on the response-writing
        // thread, and must not reach back into `view` for anything.
        long version = view.getVersion();
        long length = chunkReader.payloadLength(version);
        if (length <= 0) {
            // CE-side rows record applied bundles without the payload and are
            // not servable; a zero-length one cannot happen (buildBundle refuses
            // to persist it) but is treated the same way.
            log.warn("API catalog bundle version {} has no stored payload - not servable", version);
            return Optional.empty();
        }
        return Optional.of(new RawBundle(
                version,
                // No default: schema_version is NOT NULL, and inventing one would
                // ship a fabricated field inside a SIGNED envelope. Failing is
                // the honest outcome if that column ever becomes nullable.
                view.getSchemaVersion(),
                view.getChecksum(),
                view.getSignature(),
                view.getSigningKeyId(),
                view.getIssuer(),
                view.getApiCount() == null ? 0 : view.getApiCount(),
                view.getToolCount() == null ? 0 : view.getToolCount(),
                view.getRawBytesSize() == null ? 0 : view.getRawBytesSize(),
                length,
                () -> new ChunkedPayloadInputStream(
                        chunkReader, version, length, ApiCatalogBundleChunkReader.CHUNK_BYTES)
        ));
    }

    /**
     * The served envelope, with the payload as a stream to open rather than
     * bytes already in hand.
     *
     * <p>Holding the bytes here is what put a ~24 MB humongous allocation in old
     * gen on every download. {@code payload} opens a slice-fed stream instead,
     * so peak memory is one slice regardless of how large the catalog grows.
     */
    public record RawBundle(
            long version,
            int schemaVersion,
            String checksum,
            String signature,
            String signingKeyId,
            String issuer,
            int apiCount,
            int toolCount,
            long rawBytesSize,
            long payloadLength,
            Supplier<InputStream> payload
    ) {}

    /**
     * Every bundle for the admin list, newest first and payload-free. See
     * {@link ApiCatalogBundleRepository#findAllSummariesNewestFirst()} for why
     * this must not load entities.
     */
    @Transactional(readOnly = true)
    public List<ApiCatalogBundleRepository.BundleSummary> listBundles() {
        return bundleRepository.findAllSummariesNewestFirst();
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

    /**
     * Fits the raw payload size into the {@code raw_bytes_size} column, clamping
     * rather than refusing to publish.
     *
     * <p>The column is an {@code INT} and the value is purely diagnostic: no
     * verification path reads it, the wire DTO already carries it as a {@code
     * long}, and CE's applier narrows it too. So a payload above 2 GB must not
     * become a reason the whole fleet stops receiving catalog updates - that is
     * the outage this change exists to end, and re-creating it over a display
     * number would be absurd. It is logged at WARN with the true size instead.
     *
     * <p>If this is ever actually reached, the fix is to widen the column to
     * {@code BIGINT} (entity, plus the cast in {@code ApiCatalogBundleApplier}),
     * not to clamp harder. Holding the compressed payload in a single {@code
     * byte[]} would need revisiting at that size anyway.
     */
    static int narrowRawSize(long rawPayloadSize) {
        if (rawPayloadSize > Integer.MAX_VALUE) {
            log.warn("Canonical API catalog payload is {} bytes, which exceeds the raw_bytes_size "
                            + "column; recording {} instead. The bundle itself is unaffected - this "
                            + "value is diagnostic only.",
                    rawPayloadSize, Integer.MAX_VALUE);
            return Integer.MAX_VALUE;
        }
        return (int) rawPayloadSize;
    }

}
