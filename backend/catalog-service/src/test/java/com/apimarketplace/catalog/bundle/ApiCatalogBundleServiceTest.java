package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ApiRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ToolRow;
import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit contract for {@link ApiCatalogBundleService}: build → activate → serve.
 * Uses a real {@link ApiCatalogBundleSigner} with an ephemeral Ed25519 keypair
 * so the sign-over-gzip + serve-stored-bytes path is exercised for real.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiCatalogBundleService - build/activate/serve")
class ApiCatalogBundleServiceTest {

    @Mock private ApiCatalogBundleRepository bundleRepo;
    @Mock private ApiCatalogBundleChunkReader chunkReader;
    @Mock private ApiCatalogSnapshotReader snapshotReader;
    @Mock private ApiCatalogGenerationPriceReader priceReader;

    private ApiCatalogBundleSigner signer;
    private ApiCatalogBundleService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pub  = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        signer = new ApiCatalogBundleSigner(priv, pub, "test-key", "test-cloud");
        service = new ApiCatalogBundleService(bundleRepo, snapshotReader, priceReader, signer, chunkReader);
    }

    private static ApiCatalogSnapshotReader.Snapshot snapshotWith(int apiCount, int toolsPerApi) {
        List<ApiRow> apis = new java.util.ArrayList<>();
        for (int i = 0; i < apiCount; i++) {
            List<ToolRow> tools = new java.util.ArrayList<>();
            for (int j = 0; j < toolsPerApi; j++) {
                tools.add(new ToolRow(UUID.randomUUID(), "tool-" + j, "d", null, "GET", "/x",
                        "HTTP", null, null, null, null, null, null, null, null, null, "ACTIVE",
                        null, true, "1.0.0", List.of(), List.of(), List.of()));
            }
            apis.add(new ApiRow(UUID.randomUUID(), "Api" + i, "api" + i, "d", "https://x", null,
                    "Cat", "cat", "Sub", "sub", "apikey", null, null, "public", true, true, false,
                    "free", "APPROVED", "1.0.0", "api" + i, "api" + i + "_cred", null, null, null,
                    null, null, tools));
        }
        return new ApiCatalogSnapshotReader.Snapshot(apis, List.of());
    }

    @Test
    @DisplayName("buildBundle persists a signed inactive row with the gzipped payload stored")
    void buildBundlePersistsSignedWithPayload() {
        // Captured ONCE: the helper mints fresh UUIDs per call, so a second call is a different
        // catalog and the byte-for-byte assertion below would compare two unrelated payloads.
        ApiCatalogSnapshotReader.Snapshot snapshot = snapshotWith(2, 3);
        when(snapshotReader.snapshot()).thenReturn(snapshot);
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiCatalogBundleEntity saved = service.buildBundle();

        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getApiCount()).isEqualTo(2);
        assertThat(saved.getToolCount()).isEqualTo(6);
        assertThat(saved.getChecksum()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(saved.getSignature()).isNotBlank();
        assertThat(saved.getSigningKeyId()).isEqualTo("test-key");
        assertThat(saved.getIssuer()).isEqualTo("test-cloud");
        assertThat(saved.getVersion()).isPositive();
        assertThat(saved.getRawBytesSize()).isPositive();
        // The stored payload IS the signed artefact: checksum + signature
        // verify against payload_gz directly.
        assertThat(saved.getPayloadGz()).isNotEmpty();
        assertThat(signer.checksum(saved.getPayloadGz())).isEqualTo(saved.getChecksum());
        assertThat(signer.verify(saved.getPayloadGz(), saved.getSignature())).isTrue();
        // And gunzipping it yields the canonical JSON. Compared BYTE FOR BYTE, not by length: the
        // build streams the payload straight into the gzip stream, and a composition that lost or
        // duplicated a slice mid-stream can easily land on the same total while carrying different
        // content - which would be signed, would verify, and would apply.
        assertThat(ApiCatalogBundlePayload.gunzip(saved.getPayloadGz()))
                .isEqualTo(ApiCatalogBundlePayload.canonicalBytes(
                        saved.getVersion(), saved.getSchemaVersion(), saved.getIssuer(),
                        saved.getImportedAt(), snapshot.apis(),
                        snapshot.credentialTemplates(), List.of()));
        assertThat(ApiCatalogBundlePayload.gunzip(saved.getPayloadGz()))
                .hasSize(saved.getRawBytesSize());
    }

    @Test
    @DisplayName("a snapshot that cannot be read persists NOTHING - a half-built bundle row would "
            + "be served to the fleet as though it were a catalog")
    void aFailureBeforeTheBytesPersistsNoRow() {
        // The row is what /latest serves. It is created only after the payload exists, is sized,
        // checksummed and signed, and this pins that order: an exception on the way there must
        // leave the table exactly as it was.
        when(snapshotReader.snapshot()).thenThrow(new IllegalStateException("catalog unreadable"));

        assertThatThrownBy(() -> service.buildBundle())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog unreadable");

        verify(bundleRepo, never()).save(any());
    }

    @Test
    @DisplayName("a raw size beyond the column CLAMPS and keeps building - the fleet must not stop "
            + "receiving catalogs because a diagnostic number does not fit")
    void anOversizedRawCountIsClampedRatherThanFatal() {
        // This started as a thrown IllegalStateException, which turned a display value into a veto
        // on the whole distribution - re-creating, for a different reason, the outage the streaming
        // change was made to end. raw_bytes_size is read by no verification path; the wire DTO
        // already carries it as a long.
        assertThat(ApiCatalogBundleService.narrowRawSize(1_234L)).isEqualTo(1_234);
        assertThat(ApiCatalogBundleService.narrowRawSize(Integer.MAX_VALUE))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(ApiCatalogBundleService.narrowRawSize((long) Integer.MAX_VALUE + 1))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(ApiCatalogBundleService.narrowRawSize(9_000_000_000L))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("buildBundle rejects an empty snapshot - refuses to publish")
    void buildBundleRejectsEmptySnapshot() {
        when(snapshotReader.snapshot())
                .thenReturn(new ApiCatalogSnapshotReader.Snapshot(List.of(), List.of()));

        assertThatThrownBy(service::buildBundle)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty");
    }

    @Test
    @DisplayName("buildBundle throws if signing key not configured")
    void buildBundleRequiresKey() {
        ApiCatalogBundleSigner noKey = new ApiCatalogBundleSigner("", "", "k", "i");
        ApiCatalogBundleService svc =
                new ApiCatalogBundleService(bundleRepo, snapshotReader, priceReader, noKey, chunkReader);

        assertThatThrownBy(svc::buildBundle)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CATALOG_BUNDLE_SIGNING_KEY_PEM");
    }

    @Test
    @DisplayName("Version monotonically increases even if clock goes backwards")
    void versionMonotonic() {
        when(snapshotReader.snapshot()).thenReturn(snapshotWith(1, 1));
        ApiCatalogBundleEntity prior = new ApiCatalogBundleEntity();
        prior.setVersion(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.of(prior));
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiCatalogBundleEntity saved = service.buildBundle();

        assertThat(saved.getVersion()).isGreaterThan(prior.getVersion());
    }

    @Test
    @DisplayName("Version collision (horizontal-scaling race) retries with bumped version and succeeds")
    void buildBundleRetriesOnVersionCollision() {
        when(snapshotReader.snapshot()).thenReturn(snapshotWith(1, 1));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any()))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"api_catalog_bundles_version_key\""))
                .thenAnswer(inv -> inv.getArgument(0));

        ApiCatalogBundleEntity saved = service.buildBundle();

        verify(bundleRepo, times(2)).save(any());
        assertThat(saved).isNotNull();
        assertThat(saved.getVersion()).isPositive();
    }

    @Test
    @DisplayName("Version collision retries are bounded - persistent collisions throw IllegalStateException")
    void buildBundleGivesUpAfterMaxRetries() {
        when(snapshotReader.snapshot()).thenReturn(snapshotWith(1, 1));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        assertThatThrownBy(service::buildBundle)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retries");
        verify(bundleRepo, times(ApiCatalogBundleService.BUILD_MAX_ATTEMPTS)).save(any());
    }

    @Test
    @DisplayName("activateBundle deactivates others, flips this one active")
    void activateDeactivatesOthers() {
        ApiCatalogBundleEntity target = new ApiCatalogBundleEntity();
        target.setId(7L);
        target.setActive(false);
        when(bundleRepo.findById(7L)).thenReturn(Optional.of(target));
        when(bundleRepo.deactivateAll()).thenReturn(1);
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiCatalogBundleEntity activated = service.activateBundle(7L);

        verify(bundleRepo).deactivateAll();
        assertThat(activated.isActive()).isTrue();
        assertThat(activated.getActivatedAt()).isNotNull();
    }

    @Test
    @DisplayName("activateBundle on already-active is a no-op (no deactivate call)")
    void activateOnAlreadyActive() {
        ApiCatalogBundleEntity bundle = new ApiCatalogBundleEntity();
        bundle.setId(3L);
        bundle.setActive(true);
        when(bundleRepo.findById(3L)).thenReturn(Optional.of(bundle));

        ApiCatalogBundleEntity out = service.activateBundle(3L);

        verify(bundleRepo, never()).deactivateAll();
        verify(bundleRepo, never()).save(any());
        assertThat(out.isActive()).isTrue();
    }

    @Test
    @DisplayName("activateBundle with unknown id → IllegalArgumentException")
    void activateUnknown() {
        when(bundleRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.activateBundle(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The served bundle carries the STORED gzip bytes - signature, checksum and size all verify")
    void serveActiveBundle() throws Exception {
        when(snapshotReader.snapshot()).thenReturn(snapshotWith(1, 2));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApiCatalogBundleEntity built = service.buildBundle();

        serveFromRow(built);
        Optional<ApiCatalogBundleService.RawBundle> served = service.getActiveRawBundle();

        assertThat(served).isPresent();
        ApiCatalogBundleService.RawBundle raw = served.get();
        byte[] servedBytes = payloadOf(raw);
        assertThat(raw.version()).isEqualTo(built.getVersion());
        assertThat(raw.checksum()).isEqualTo(built.getChecksum());
        assertThat(raw.signature()).isEqualTo(built.getSignature());
        assertThat(raw.apiCount()).isEqualTo(1);
        assertThat(raw.toolCount()).isEqualTo(2);

        // Signature + checksum cover the gzip bytes, per contract.
        assertThat(signer.verify(servedBytes, raw.signature())).isTrue();
        assertThat(signer.checksum(servedBytes)).isEqualTo(raw.checksum());
        assertThat(ApiCatalogBundlePayload.gunzip(servedBytes)).hasSize((int) raw.rawBytesSize());
        assertThat(servedBytes)
                .as("the sliced reader must reassemble the exact stored payload")
                .isEqualTo(built.getPayloadGz());
    }

    @Test
    @DisplayName("Serving returns empty when no bundle is active")
    void noActiveBundle() {
        when(bundleRepo.findActiveServingView()).thenReturn(List.of());
        assertThat(service.getActiveRawBundle()).isEmpty();
    }

    @Test
    @DisplayName("An active row whose payload measures zero is not servable - a CE-applied record must "
            + "never go out as an empty envelope")
    void aRowMeasuringZeroBytesIsNotServable() {
        // Since serving reads the length through the reader, an absent payload
        // and a zero-length one arrive here as the same 0 and share this branch,
        // so one test covers it. That the two DB states really do both measure 0
        // is a property of the SQL, verified against a real Postgres in
        // ApiCatalogBundlePostgresMappingTest#missingPayloadReportsZeroLength.
        ApiCatalogBundleEntity ceRow = new ApiCatalogBundleEntity();
        ceRow.setVersion(5L);
        when(bundleRepo.findActiveServingView()).thenReturn(List.of(viewOf(ceRow)));
        when(chunkReader.payloadLength(5L)).thenReturn(0L);

        assertThat(service.getActiveRawBundle()).isEmpty();
    }

    @Test
    @DisplayName("getRawBundleByVersion returns the requested version, empty when unknown")
    void rawByVersion() {
        ApiCatalogBundleEntity row = new ApiCatalogBundleEntity();
        row.setVersion(42L);
        row.setChecksum("cs");
        row.setPayloadGz(new byte[]{7, 7});
        when(bundleRepo.findServingViewByVersion(42L)).thenReturn(List.of(viewOf(row)));
        when(bundleRepo.findServingViewByVersion(43L)).thenReturn(List.of());
        when(chunkReader.payloadLength(42L)).thenReturn(2L);

        assertThat(service.getRawBundleByVersion(42L)).isPresent();
        assertThat(service.getRawBundleByVersion(43L)).isEmpty();
    }

    @Test
    @DisplayName("getActiveBundleMetadata reads the projection, never the payload-bearing entity")
    void metadataUsesTheProjection() {
        when(bundleRepo.findActiveMetadata()).thenReturn(List.of(new ApiCatalogBundleRepository.ActiveBundleMeta() {
            @Override public String getChecksum() { return "cs9"; }
            @Override public Integer getServable() { return 1; }
            @Override public Integer getPricesStored() { return 1; }
        }));

        Optional<ApiCatalogBundleRepository.ActiveBundleMeta> meta = service.getActiveBundleMetadata();

        assertThat(meta).isPresent();
        assertThat(meta.get().getChecksum()).isEqualTo("cs9");
        verify(bundleRepo, never()).findFirstByActiveTrue();
    }

    @Test
    @DisplayName("getActiveBundleMetadata is empty when nothing is active")
    void metadataEmptyWhenNoActiveRow() {
        when(bundleRepo.findActiveMetadata()).thenReturn(List.of());

        assertThat(service.getActiveBundleMetadata()).isEmpty();
    }

    @Test
    @DisplayName("Serving stays valid after live-catalog drift (payload is pinned at build time)")
    void liveTableDriftDoesNotInvalidateServing() throws Exception {
        // Build with one snapshot…
        when(snapshotReader.snapshot()).thenReturn(snapshotWith(1, 1));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApiCatalogBundleEntity built = service.buildBundle();

        // …then the live catalog changes (next snapshot would differ). Unlike
        // the model bundle (re-derives at read time and throws on drift), the
        // API bundle serves the stored bytes - still verifiable.
        serveFromRow(built);
        Optional<ApiCatalogBundleService.RawBundle> served = service.getActiveRawBundle();

        assertThat(served).isPresent();
        assertThat(signer.verify(payloadOf(served.get()), served.get().signature())).isTrue();
    }

    /** The projection the serving path now reads, built from a persisted row. */
    private static ApiCatalogBundleRepository.ServingView viewOf(ApiCatalogBundleEntity e) {
        return new ApiCatalogBundleRepository.ServingView() {
            @Override public Long getVersion() { return e.getVersion(); }
            @Override public Integer getSchemaVersion() { return e.getSchemaVersion(); }
            @Override public String getChecksum() { return e.getChecksum(); }
            @Override public String getSignature() { return e.getSignature(); }
            @Override public String getSigningKeyId() { return e.getSigningKeyId(); }
            @Override public String getIssuer() { return e.getIssuer(); }
            @Override public Integer getApiCount() { return e.getApiCount(); }
            @Override public Integer getToolCount() { return e.getToolCount(); }
            @Override public Integer getRawBytesSize() { return e.getRawBytesSize(); }
        };
    }

    /** Serves the row's payload through the sliced reader, as production does. */
    private void serveFromRow(ApiCatalogBundleEntity e) {
        byte[] gz = e.getPayloadGz();
        when(bundleRepo.findActiveServingView()).thenReturn(List.of(viewOf(e)));
        when(chunkReader.payloadLength(e.getVersion())).thenReturn((long) gz.length);
        when(chunkReader.readChunk(eq(e.getVersion()), anyLong(), anyInt())).thenAnswer(inv -> {
            long offset = inv.getArgument(1);
            int want = inv.getArgument(2);
            int from = (int) Math.min(offset, gz.length);
            int to = (int) Math.min((long) from + want, gz.length);
            return java.util.Arrays.copyOfRange(gz, from, to);
        });
    }

    /** Drains a served bundle's payload stream. */
    private static byte[] payloadOf(ApiCatalogBundleService.RawBundle bundle) throws Exception {
        try (java.io.InputStream in = bundle.payload().get()) {
            return in.readAllBytes();
        }
    }
}
