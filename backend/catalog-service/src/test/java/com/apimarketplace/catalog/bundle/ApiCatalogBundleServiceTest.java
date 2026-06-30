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
    @Mock private ApiCatalogSnapshotReader snapshotReader;

    private ApiCatalogBundleSigner signer;
    private ApiCatalogBundleService service;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String priv = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        String pub  = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());

        signer = new ApiCatalogBundleSigner(priv, pub, "test-key", "test-cloud");
        service = new ApiCatalogBundleService(bundleRepo, snapshotReader, signer);
    }

    private static ApiCatalogSnapshotReader.Snapshot snapshotWith(int apiCount, int toolsPerApi) {
        List<ApiRow> apis = new java.util.ArrayList<>();
        for (int i = 0; i < apiCount; i++) {
            List<ToolRow> tools = new java.util.ArrayList<>();
            for (int j = 0; j < toolsPerApi; j++) {
                tools.add(new ToolRow(UUID.randomUUID(), "tool-" + j, "d", null, "GET", "/x",
                        "HTTP", null, null, null, null, null, null, null, null, "ACTIVE", null,
                        true, "1.0.0", List.of(), List.of(), List.of()));
            }
            apis.add(new ApiRow(UUID.randomUUID(), "Api" + i, "api" + i, "d", "https://x", null,
                    "Cat", "cat", "Sub", "sub", "apikey", null, null, "public", true, true, false,
                    "free", "APPROVED", "1.0.0", "api" + i, "api" + i + "_cred", null, null, null,
                    null, tools));
        }
        return new ApiCatalogSnapshotReader.Snapshot(apis, List.of());
    }

    @Test
    @DisplayName("buildBundle persists a signed inactive row with the gzipped payload stored")
    void buildBundlePersistsSignedWithPayload() {
        when(snapshotReader.snapshot()).thenReturn(snapshotWith(2, 3));
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
        // And gunzipping it yields the canonical JSON (uncompressed size matches).
        assertThat(ApiCatalogBundlePayload.gunzip(saved.getPayloadGz()))
                .hasSize(saved.getRawBytesSize());
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
        ApiCatalogBundleService svc = new ApiCatalogBundleService(bundleRepo, snapshotReader, noKey);

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
    @DisplayName("getActiveSignedBundle serves the STORED gzip bytes - verifiable end-to-end")
    void serveActiveBundle() {
        when(snapshotReader.snapshot()).thenReturn(snapshotWith(1, 2));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApiCatalogBundleEntity built = service.buildBundle();

        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));
        Optional<ApiCatalogSignedBundle> served = service.getActiveSignedBundle();

        assertThat(served).isPresent();
        ApiCatalogSignedBundle sb = served.get();
        assertThat(sb.version()).isEqualTo(built.getVersion());
        assertThat(sb.checksum()).isEqualTo(built.getChecksum());
        assertThat(sb.signature()).isEqualTo(built.getSignature());
        assertThat(sb.apiCount()).isEqualTo(1);
        assertThat(sb.toolCount()).isEqualTo(2);

        byte[] decoded = Base64.getDecoder().decode(sb.payloadBase64());
        // Signature + checksum cover the gzip bytes (decoded), per contract.
        assertThat(signer.verify(decoded, sb.signature())).isTrue();
        assertThat(signer.checksum(decoded)).isEqualTo(sb.checksum());
        assertThat(ApiCatalogBundlePayload.gunzip(decoded)).hasSize((int) sb.rawBytesSize());
    }

    @Test
    @DisplayName("getActiveSignedBundle: a row WITHOUT stored payload (CE-applied record) is not servable")
    void serveSkipsPayloadlessRow() {
        ApiCatalogBundleEntity ceRow = new ApiCatalogBundleEntity();
        ceRow.setVersion(5L);
        ceRow.setPayloadGz(null);
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(ceRow));

        assertThat(service.getActiveSignedBundle()).isEmpty();
    }

    @Test
    @DisplayName("getActiveSignedBundle returns empty when no active bundle")
    void noActiveBundle() {
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());
        assertThat(service.getActiveSignedBundle()).isEmpty();
    }

    @Test
    @DisplayName("Serving stays valid after live-catalog drift (payload is pinned at build time)")
    void liveTableDriftDoesNotInvalidateServing() {
        // Build with one snapshot…
        when(snapshotReader.snapshot()).thenReturn(snapshotWith(1, 1));
        when(bundleRepo.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());
        when(bundleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        ApiCatalogBundleEntity built = service.buildBundle();

        // …then the live catalog changes (next snapshot would differ). Unlike
        // the model bundle (re-derives at read time and throws on drift), the
        // API bundle serves the stored bytes - still verifiable.
        when(bundleRepo.findFirstByActiveTrue()).thenReturn(Optional.of(built));
        Optional<ApiCatalogSignedBundle> served = service.getActiveSignedBundle();

        assertThat(served).isPresent();
        byte[] decoded = Base64.getDecoder().decode(served.get().payloadBase64());
        assertThat(signer.verify(decoded, served.get().signature())).isTrue();
    }
}
