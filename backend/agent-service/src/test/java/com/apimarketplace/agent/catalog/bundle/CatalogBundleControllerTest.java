package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import com.apimarketplace.auth.client.AuthClient;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Controller-level contract: admin gating, response shapes, 404 paths.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogBundleController - admin gating + response shape")
class CatalogBundleControllerTest {

    @Mock private CatalogBundleService service;
    @Mock private CatalogBundleSigner signer;
    @Mock private CatalogBundleSyncStatusRepository syncStatusRepo;
    @Mock private ObjectProvider<CatalogBundleSyncScheduler> schedulerProvider;
    @Mock private CatalogBundleSyncScheduler scheduler;
    @Mock private AuthClient authClient;

    private CatalogBundleController controller;

    // A cloud-linked caller (gateway-injected X-User-ID + install header) the gate accepts.
    private static final String CLOUD_USER = "9001";
    private static final String INSTALL_ID = "install-1";

    @BeforeEach
    void setUp() {
        controller = new CatalogBundleController(service, signer, syncStatusRepo, schedulerProvider, authClient);
    }

    @Test
    @DisplayName("buildBundle as non-admin → 403 Forbidden")
    void buildForbiddenForNonAdmin() {
        ResponseEntity<?> resp = controller.buildBundle("USER");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("buildBundle as admin returns admin-view JSON")
    void buildReturnsAdminView() {
        CatalogBundleEntity built = new CatalogBundleEntity();
        built.setId(1L);
        built.setVersion(1000L);
        built.setChecksum("a".repeat(64));
        built.setSignature("sig");
        built.setSigningKeyId("k");
        built.setIssuer("cloud");
        built.setModelCount(50);
        built.setRawBytesSize(5000);
        built.setImportedAt(Instant.now());
        when(service.buildBundle()).thenReturn(built);

        ResponseEntity<?> resp = controller.buildBundle("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("version", 1000L);
        assertThat(body).containsEntry("modelCount", 50);
        assertThat(body).containsEntry("isActive", false);
    }

    @Test
    @DisplayName("buildBundle returns 400 when service rejects (no key, empty catalog)")
    void buildPropagatesServiceRejection() {
        when(service.buildBundle()).thenThrow(new IllegalStateException("empty catalog"));

        ResponseEntity<?> resp = controller.buildBundle("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("activateBundle as non-admin → 403")
    void activateForbidden() {
        ResponseEntity<?> resp = controller.activateBundle("USER", 7L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("activateBundle unknown id → 404")
    void activate404() {
        when(service.activateBundle(99L)).thenThrow(new IllegalArgumentException("not found"));

        ResponseEntity<?> resp = controller.activateBundle("ADMIN", 99L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("activateBundle → 409 when partial unique index rejects a concurrent activate race")
    void activate409OnConcurrentRace() {
        // Two admins hit activate simultaneously; the second TX loses against
        // idx_catalog_bundles_one_active. Surface as 409, not 500.
        when(service.activateBundle(5L))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"idx_catalog_bundles_one_active\""));

        ResponseEntity<?> resp = controller.activateBundle("ADMIN", 5L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("listBundles returns sorted bundles + signingKeyId + publicKey")
    void listIncludesTrustInfo() {
        CatalogBundleEntity older = bundle(1L, 1000L);
        CatalogBundleEntity newer = bundle(2L, 2000L);
        when(service.listBundles()).thenReturn(List.of(older, newer));
        when(signer.keyId()).thenReturn("k1");
        when(signer.publicKeyBase64()).thenReturn("PK==");

        ResponseEntity<?> resp = controller.listBundles("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("signingKeyId", "k1");
        assertThat(body).containsEntry("publicKeyBase64", "PK==");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bundles = (List<Map<String, Object>>) body.get("bundles");
        assertThat(bundles).hasSize(2);
        // sorted newest first
        assertThat(bundles.get(0)).containsEntry("version", 2000L);
        assertThat(bundles.get(1)).containsEntry("version", 1000L);
    }

    @Test
    @DisplayName("listBundles tolerates null publicKeyBase64 (no signing key configured)")
    void listHandlesMissingPublicKey() {
        // Regression: Map.of rejects null values. On envs without
        // CATALOG_BUNDLE_SIGNING_PUBLIC_KEY the admin history page crashed 500.
        when(service.listBundles()).thenReturn(List.of(bundle(1L, 1000L)));
        when(signer.keyId()).thenReturn("default");
        when(signer.publicKeyBase64()).thenReturn(null);

        ResponseEntity<?> resp = controller.listBundles("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("signingKeyId", "default");
        assertThat(body).containsKey("publicKeyBase64");
        assertThat(body.get("publicKeyBase64")).isNull();
    }

    @Test
    @DisplayName("CE /latest returns the active signed bundle payload when the caller owns an active cloud link")
    void latestReturnsSignedBundle() {
        when(authClient.userOwnsActiveCeLink(CLOUD_USER, INSTALL_ID)).thenReturn(true);
        SignedBundle sb = new SignedBundle(1L, 1, "cs", "sig", "k1", "cloud", 10, 1000, "cGF5bG9hZA==");
        when(service.getActiveSignedBundle()).thenReturn(Optional.of(sb));

        ResponseEntity<?> resp = controller.latestSignedBundle(CLOUD_USER, INSTALL_ID);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isEqualTo(sb);
    }

    @Test
    @DisplayName("CE /latest 404 when nothing active (caller is linked)")
    void latest404() {
        when(authClient.userOwnsActiveCeLink(CLOUD_USER, INSTALL_ID)).thenReturn(true);
        when(service.getActiveSignedBundle()).thenReturn(Optional.empty());
        ResponseEntity<?> resp = controller.latestSignedBundle(CLOUD_USER, INSTALL_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("CE /latest → 401 when there is no validated cloud identity (no X-User-ID)")
    void latestUnauthenticatedWithoutCloudIdentity() {
        ResponseEntity<?> resp = controller.latestSignedBundle(null, INSTALL_ID);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isEqualTo(Map.of("error", "AUTHENTICATION_REQUIRED"));
        // The gate short-circuits before resolving the bundle - no link check, no service call.
        verifyNoInteractions(authClient);
        verify(service, never()).getActiveSignedBundle();
    }

    @Test
    @DisplayName("CE /latest → 403 when the install is not a linked, active cloud install (anti-abuse: no link, no updates)")
    void latestForbiddenWhenInstallNotLinked() {
        when(authClient.userOwnsActiveCeLink(CLOUD_USER, "install-unlinked")).thenReturn(false);
        ResponseEntity<?> resp = controller.latestSignedBundle(CLOUD_USER, "install-unlinked");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(resp.getBody()).isEqualTo(Map.of("error", "CE_LINK_NOT_ACTIVE"));
        // The bundle is never served to an unlinked install.
        verify(service, never()).getActiveSignedBundle();
    }

    @Test
    @DisplayName("CE /{version} returns the requested version, 404 when unknown (caller is linked)")
    void byVersion() {
        when(authClient.userOwnsActiveCeLink(CLOUD_USER, INSTALL_ID)).thenReturn(true);
        SignedBundle sb = new SignedBundle(42L, 1, "cs", "sig", "k", "c", 1, 100, "cA==");
        when(service.getSignedBundleByVersion(42L)).thenReturn(Optional.of(sb));
        when(service.getSignedBundleByVersion(43L)).thenReturn(Optional.empty());

        assertThat(controller.signedBundleByVersion(CLOUD_USER, INSTALL_ID, 42L).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.signedBundleByVersion(CLOUD_USER, INSTALL_ID, 43L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("CE /{version} → 403 when the install is not linked (same gate as /latest)")
    void byVersionForbiddenWhenInstallNotLinked() {
        when(authClient.userOwnsActiveCeLink(CLOUD_USER, "install-unlinked")).thenReturn(false);
        ResponseEntity<?> resp = controller.signedBundleByVersion(CLOUD_USER, "install-unlinked", 42L);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).getSignedBundleByVersion(anyLong());
    }

    @Test
    @DisplayName("Signing-key endpoint returns public key info when configured")
    void signingKeyEndpoint() {
        when(signer.publicKeyBase64()).thenReturn("PK==");
        when(signer.keyId()).thenReturn("k1");
        when(signer.issuer()).thenReturn("cloud");

        ResponseEntity<?> resp = controller.signingKey();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("keyId", "k1").containsEntry("issuer", "cloud")
                .containsEntry("publicKeyBase64", "PK==")
                .containsEntry("algorithm", "Ed25519");
    }

    @Test
    @DisplayName("Signing-key endpoint 503 when no key configured")
    void signingKey503() {
        when(signer.publicKeyBase64()).thenReturn(null);
        ResponseEntity<?> resp = controller.signingKey();
        assertThat(resp.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("syncStatus as non-admin → 403")
    void syncStatusForbidden() {
        ResponseEntity<?> resp = controller.syncStatus("USER");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(syncStatusRepo);
    }

    @Test
    @DisplayName("syncStatus returns singleton row + schedulerEnabled flag")
    void syncStatusReturnsRow() {
        CatalogBundleSyncStatusEntity row = new CatalogBundleSyncStatusEntity();
        row.setLastAppliedVersion(42L);
        row.setLastAppliedAt(Instant.parse("2026-04-20T10:00:00Z"));
        row.setLastFetchStatus("OK");
        row.setConsecutiveFailures(0);
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(row));
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);

        ResponseEntity<?> resp = controller.syncStatus("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("lastAppliedVersion", 42L);
        assertThat(body).containsEntry("lastFetchStatus", "OK");
        assertThat(body).containsEntry("consecutiveFailures", 0);
        assertThat(body).containsEntry("schedulerEnabled", true);
    }

    @Test
    @DisplayName("syncStatus before first tick → 200 with null fields, schedulerEnabled=false on cloud")
    void syncStatusBeforeFirstTick() {
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.empty());
        when(schedulerProvider.getIfAvailable()).thenReturn(null);

        ResponseEntity<?> resp = controller.syncStatus("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsKey("lastAppliedVersion");
        assertThat(body.get("lastAppliedVersion")).isNull();
        assertThat(body).containsEntry("schedulerEnabled", false);
    }

    @Test
    @DisplayName("syncNow as non-admin → 403")
    void syncNowForbidden() {
        ResponseEntity<?> resp = controller.syncNow("USER");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(syncStatusRepo);
    }

    @Test
    @DisplayName("syncNow on cloud (no scheduler bean) → 503")
    void syncNow503OnCloud() {
        when(schedulerProvider.getIfAvailable()).thenReturn(null);

        ResponseEntity<?> resp = controller.syncNow("ADMIN");

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
    }

    @Test
    @DisplayName("syncNow triggers syncOnce and returns the refreshed status row")
    void syncNowFiresTickAndReturnsRow() {
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);
        CatalogBundleSyncStatusEntity row = new CatalogBundleSyncStatusEntity();
        row.setLastFetchStatus("OK");
        row.setLastAppliedVersion(7L);
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(row));

        ResponseEntity<?> resp = controller.syncNow("ADMIN");

        verify(scheduler).tick();
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("lastAppliedVersion", 7L);
    }

    @Test
    @DisplayName("syncNow still returns 200 even if tick() throws (failure already persisted)")
    void syncNowTolerantOfSyncOnceException() {
        when(schedulerProvider.getIfAvailable()).thenReturn(scheduler);
        doThrow(new RuntimeException("unexpected")).when(scheduler).tick();
        CatalogBundleSyncStatusEntity row = new CatalogBundleSyncStatusEntity();
        row.setLastFetchStatus("NETWORK_ERROR");
        when(syncStatusRepo.findById(CatalogBundleSyncStatusEntity.SINGLETON_ID))
                .thenReturn(Optional.of(row));

        ResponseEntity<?> resp = controller.syncNow("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("lastFetchStatus", "NETWORK_ERROR");
    }

    private CatalogBundleEntity bundle(Long id, long version) {
        CatalogBundleEntity b = new CatalogBundleEntity();
        b.setId(id);
        b.setVersion(version);
        b.setChecksum("a".repeat(64));
        b.setSignature("sig");
        b.setSigningKeyId("k1");
        b.setIssuer("cloud");
        b.setModelCount(1);
        b.setRawBytesSize(100);
        b.setImportedAt(Instant.now());
        b.setActive(false);
        return b;
    }

    // ==================== V381: DELETE + public seed ====================

    @Test
    @DisplayName("DELETE bundle: admin-only - USER role is refused before the service is touched")
    void deleteRequiresAdmin() {
        ResponseEntity<?> resp = controller.deleteBundle("USER", 5L);

        org.assertj.core.api.Assertions.assertThat(resp.getStatusCode().value()).isEqualTo(403);
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    @Test
    @DisplayName("DELETE bundle: unknown id maps to 404 with the error message")
    void deleteUnknownIs404() {
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Bundle not found: 7"))
                .when(service).deleteBundle(7L);

        ResponseEntity<?> resp = controller.deleteBundle("ADMIN", 7L);

        org.assertj.core.api.Assertions.assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("DELETE bundle: the ACTIVE bundle's IllegalStateException PROPAGATES (409 via GlobalExceptionHandler)")
    void deleteActivePropagatesConflict() {
        org.mockito.Mockito.doThrow(new IllegalStateException("is the ACTIVE bundle"))
                .when(service).deleteBundle(9L);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.deleteBundle("ADMIN", 9L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("DELETE bundle: happy path deletes through the service and returns the id")
    void deleteHappyPath() {
        ResponseEntity<?> resp = controller.deleteBundle("ADMIN", 3L);

        org.assertj.core.api.Assertions.assertThat(resp.getStatusCode().value()).isEqualTo(200);
        org.mockito.Mockito.verify(service).deleteBundle(3L);
    }

    @Test
    @DisplayName("PUBLIC /seed: serves the active signed bundle with NO auth and NO cloud-link check")
    void seedIsPublicAndUngated() {
        SignedBundle bundle = new SignedBundle(42L, 2, "c".repeat(64), "sig", "k", "cloud", 1, 10, "cGF5bG9hZA==");
        org.mockito.Mockito.when(service.getActiveSignedBundle()).thenReturn(java.util.Optional.of(bundle));

        // No roles header, no user, no install id - the method signature itself
        // pins the public contract (adding an auth param would break this test).
        ResponseEntity<?> resp = controller.seedBundle();

        org.assertj.core.api.Assertions.assertThat(resp.getStatusCode().value()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(resp.getBody()).isEqualTo(bundle);
        org.mockito.Mockito.verifyNoInteractions(authClient); // the link gate must NOT run for /seed
    }

    @Test
    @DisplayName("PUBLIC /seed: 404 when no bundle has ever been activated")
    void seedIs404WhenNoneActive() {
        org.mockito.Mockito.when(service.getActiveSignedBundle()).thenReturn(java.util.Optional.empty());

        ResponseEntity<?> resp = controller.seedBundle();

        org.assertj.core.api.Assertions.assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }
}
