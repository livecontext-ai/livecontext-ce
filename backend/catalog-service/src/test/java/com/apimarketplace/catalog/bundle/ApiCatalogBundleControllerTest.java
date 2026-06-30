package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Controller-level contract: admin gating (X-User-Roles) on the management
 * endpoints vs the deliberately unauthenticated public download endpoints
 * (under /api/catalog/public/**, which the gateway allowlists), response
 * shapes, 404/409 paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiCatalogBundleController - admin gating + public download split")
class ApiCatalogBundleControllerTest {

    @Mock private ApiCatalogBundleService service;
    @Mock private ApiCatalogBundleSigner signer;
    @Mock private ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private ObjectProvider<ApiCatalogBundleSyncScheduler> schedulerProvider;
    @Mock private ApiCatalogBundleSyncScheduler scheduler;

    private ApiCatalogBundleController controller;

    @BeforeEach
    void setUp() {
        controller = new ApiCatalogBundleController(service, signer, syncStatusRepo, schedulerProvider);
    }

    private static ApiCatalogBundleEntity bundle(Long id, long version) {
        ApiCatalogBundleEntity b = new ApiCatalogBundleEntity();
        b.setId(id);
        b.setVersion(version);
        b.setChecksum("a".repeat(64));
        b.setSignature("sig");
        b.setSigningKeyId("k1");
        b.setIssuer("cloud");
        b.setApiCount(600);
        b.setToolCount(2400);
        b.setRawBytesSize(5_000_000);
        b.setImportedAt(Instant.now());
        b.setActive(false);
        return b;
    }

    // ── Admin gating ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("All admin endpoints reject non-admin roles with 403")
    void adminEndpointsForbiddenForNonAdmin() {
        assertThat(controller.buildBundle("USER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.activateBundle("USER", 1L).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.listBundles("USER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.syncStatus("USER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.syncNow("USER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(service, syncStatusRepo);
    }

    @Test
    @DisplayName("Public download endpoints take NO roles header - auth split is structural")
    void publicEndpointsAreUnauthenticated() throws NoSuchMethodException {
        // The download endpoints live under /api/catalog/public/** (gateway
        // public prefix) and their signatures carry no X-User-Roles parameter:
        // CE trust is the Ed25519 signature, not transport auth.
        for (Method m : List.of(
                ApiCatalogBundleController.class.getDeclaredMethod("latestSignedBundle"),
                ApiCatalogBundleController.class.getDeclaredMethod("signingKey"))) {
            GetMapping mapping = m.getAnnotation(GetMapping.class);
            assertThat(mapping.value()[0]).startsWith("/api/catalog/public/bundles");
            assertThat(m.getParameterCount()).isZero();
        }
        Method byVersion = ApiCatalogBundleController.class
                .getDeclaredMethod("signedBundleByVersion", long.class);
        assertThat(byVersion.getAnnotation(GetMapping.class).value()[0])
                .startsWith("/api/catalog/public/bundles");
    }

    // ── Build / activate / list ──────────────────────────────────────────────

    @Test
    @DisplayName("buildBundle as admin returns the admin view")
    void buildReturnsAdminView() {
        when(service.buildBundle()).thenReturn(bundle(1L, 1000L));

        ResponseEntity<?> resp = controller.buildBundle("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("version", 1000L);
        assertThat(body).containsEntry("apiCount", 600);
        assertThat(body).containsEntry("toolCount", 2400);
        assertThat(body).containsEntry("isActive", false);
        // The (multi-MB) payload must never leak into the admin view.
        assertThat(body).doesNotContainKey("payloadGz");
    }

    @Test
    @DisplayName("buildBundle returns 400 when service rejects (no key, empty catalog)")
    void buildPropagatesServiceRejection() {
        when(service.buildBundle()).thenThrow(new IllegalStateException("empty catalog"));

        assertThat(controller.buildBundle("ADMIN").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("activateBundle unknown id → 404")
    void activate404() {
        when(service.activateBundle(99L)).thenThrow(new IllegalArgumentException("not found"));
        assertThat(controller.activateBundle("ADMIN", 99L).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("activateBundle → 409 when the partial unique index rejects a concurrent race")
    void activate409OnConcurrentRace() {
        when(service.activateBundle(5L))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"idx_api_catalog_bundles_one_active\""));

        assertThat(controller.activateBundle("ADMIN", 5L).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("listBundles returns newest-first bundles + signing key info (null public key tolerated)")
    void listIncludesTrustInfo() {
        when(service.listBundles()).thenReturn(List.of(bundle(1L, 1000L), bundle(2L, 2000L)));
        when(signer.keyId()).thenReturn("k1");
        when(signer.publicKeyBase64()).thenReturn(null); // local dev: no key configured

        ResponseEntity<?> resp = controller.listBundles("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("signingKeyId", "k1");
        assertThat(body).containsKey("publicKeyBase64");
        assertThat(body.get("publicKeyBase64")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bundles = (List<Map<String, Object>>) body.get("bundles");
        assertThat(bundles.get(0)).containsEntry("version", 2000L);
        assertThat(bundles.get(1)).containsEntry("version", 1000L);
    }

    // ── Public downloads ─────────────────────────────────────────────────────

    @Test
    @DisplayName("/latest returns the active signed bundle, 404 when none")
    void latestSignedBundle() {
        ApiCatalogSignedBundle sb = new ApiCatalogSignedBundle(1L, 1, "cs", "sig", "k1", "cloud",
                600, 2400, 5_000_000, "cGF5bG9hZA==");
        when(service.getActiveSignedBundle()).thenReturn(Optional.of(sb));
        assertThat(controller.latestSignedBundle().getBody()).isEqualTo(sb);

        when(service.getActiveSignedBundle()).thenReturn(Optional.empty());
        assertThat(controller.latestSignedBundle().getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("/{version} returns the requested version, 404 when unknown")
    void byVersion() {
        ApiCatalogSignedBundle sb = new ApiCatalogSignedBundle(42L, 1, "cs", "sig", "k", "c",
                1, 1, 100, "cA==");
        when(service.getSignedBundleByVersion(42L)).thenReturn(Optional.of(sb));
        when(service.getSignedBundleByVersion(43L)).thenReturn(Optional.empty());

        assertThat(controller.signedBundleByVersion(42L).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.signedBundleByVersion(43L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("/signing-key returns key material when configured, 503 otherwise")
    void signingKeyEndpoint() {
        when(signer.publicKeyBase64()).thenReturn("PK==");
        when(signer.keyId()).thenReturn("k1");
        when(signer.issuer()).thenReturn("cloud");

        ResponseEntity<?> resp = controller.signingKey();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("keyId", "k1").containsEntry("issuer", "cloud")
                .containsEntry("publicKeyBase64", "PK==").containsEntry("algorithm", "Ed25519");

        when(signer.publicKeyBase64()).thenReturn(null);
        assertThat(controller.signingKey().getStatusCode().value()).isEqualTo(503);
    }

    // ── Sync status / sync now ───────────────────────────────────────────────

    @Test
    @DisplayName("syncStatus returns the singleton row + schedulerEnabled flag")
    void syncStatusReturnsRow() {
        ApiCatalogBundleSyncStatusEntity row = new ApiCatalogBundleSyncStatusEntity();
        row.setLastAppliedVersion(42L);
        row.setLastFetchStatus("OK");
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(row));
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);

        ResponseEntity<?> resp = controller.syncStatus("ADMIN");

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("lastAppliedVersion", 42L);
        assertThat(body).containsEntry("lastFetchStatus", "OK");
        assertThat(body).containsEntry("schedulerEnabled", true);
    }

    @Test
    @DisplayName("syncStatus before first tick → 200 with null fields, schedulerEnabled=false on cloud")
    void syncStatusBeforeFirstTick() {
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        when(schedulerProvider.getIfAvailable()).thenReturn(null);

        ResponseEntity<?> resp = controller.syncStatus("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body.get("lastAppliedVersion")).isNull();
        assertThat(body).containsEntry("schedulerEnabled", false);
    }

    @Test
    @DisplayName("syncNow on cloud (no scheduler bean) → 503")
    void syncNow503OnCloud() {
        when(schedulerProvider.getIfAvailable()).thenReturn(null);
        assertThat(controller.syncNow("ADMIN").getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("syncNow triggers a tick and returns the refreshed status row")
    void syncNowFiresTick() {
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);
        ApiCatalogBundleSyncStatusEntity row = new ApiCatalogBundleSyncStatusEntity();
        row.setLastAppliedVersion(7L);
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(row));

        ResponseEntity<?> resp = controller.syncNow("ADMIN");

        verify(scheduler).tick();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("lastAppliedVersion", 7L);
    }

    @Test
    @DisplayName("syncNow still returns 200 even if tick() throws (failure already persisted)")
    void syncNowTolerantOfTickException() {
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);
        doThrow(new RuntimeException("unexpected")).when(scheduler).tick();
        ApiCatalogBundleSyncStatusEntity row = new ApiCatalogBundleSyncStatusEntity();
        row.setLastFetchStatus("NETWORK_ERROR");
        when(syncStatusRepo.findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(row));

        ResponseEntity<?> resp = controller.syncNow("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("lastFetchStatus", "NETWORK_ERROR");
    }
}
