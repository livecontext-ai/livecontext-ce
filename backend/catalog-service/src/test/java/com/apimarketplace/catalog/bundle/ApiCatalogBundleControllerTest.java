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
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.bind.annotation.GetMapping;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
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

    private static final byte[] PAYLOAD_BYTES = {1, 2, 3};

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
        // public prefix) and none of them declares the gateway-injected
        // X-User-Roles header: CE trust is the Ed25519 signature, not transport
        // auth. They may take other headers (the conditional-GET validator is
        // one), so the invariant is the ABSENCE of the roles header, not an
        // empty parameter list.
        for (Method m : List.of(
                ApiCatalogBundleController.class.getDeclaredMethod("latestSignedBundle", String.class),
                ApiCatalogBundleController.class.getDeclaredMethod("signedBundleByVersion", long.class),
                ApiCatalogBundleController.class.getDeclaredMethod("signingKey"))) {
            GetMapping mapping = m.getAnnotation(GetMapping.class);
            assertThat(mapping.value()[0]).startsWith("/api/catalog/public/bundles");
            assertThat(declaredHeaderNames(m))
                    .as("%s must not read the roles header", m.getName())
                    .doesNotContain("X-User-Roles");
            // Beyond the roles header: a public handler must not take ANY
            // caller-identity input. Only a path variable and a request header
            // (the conditional-GET validator) are permitted, so a later
            // @RequestParam / @AuthenticationPrincipal / HttpServletRequest
            // parameter fails here instead of silently becoming an auth input.
            assertThat(declaredParameterKinds(m))
                    .as("%s takes an unexpected parameter kind", m.getName())
                    .isSubsetOf("RequestHeader", "PathVariable");
        }
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
    @DisplayName("a serialisation or compression failure comes back as a 400 NAMING the failure, "
            + "not as the opaque 500 that hid this area's last outage")
    void aStreamFailureIsReportedNotSwallowed() {
        // The build streams its payload, so it can now fail with UncheckedIOException rather than
        // IllegalStateException. Uncaught, that is an HTTP 500 rendered as "An unexpected error
        // occurred" - which is precisely how an OutOfMemoryError in this endpoint went
        // undiagnosed while the whole CE fleet stopped receiving catalog updates.
        when(service.buildBundle()).thenThrow(
                new java.io.UncheckedIOException("Failed to gzip catalog bundle payload",
                        new java.io.IOException("no space left on device")));

        ResponseEntity<?> resp = controller.buildBundle("ADMIN");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(String.valueOf(body.get("error"))).contains("gzip catalog bundle payload");
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
    @DisplayName("listBundles renders the payload-free summaries in query order + signing key info (null public key tolerated)")
    void listIncludesTrustInfo() {
        when(service.listBundles()).thenReturn(List.of(summary(2L, 2000L), summary(1L, 1000L)));
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

    @Test
    @DisplayName("The listed view keeps every key, in order, with each value from the matching field")
    void listedViewShapeIsPinned() {
        // toAdminView(BundleSummary) forwards twelve positional arguments, three
        // of them String, three Integer and two Instant. A swapped pair compiles
        // and only shows up here, so every field carries a distinct value and
        // every one is asserted.
        Instant imported = Instant.parse("2026-09-01T10:00:00Z");
        Instant activated = Instant.parse("2026-09-02T11:00:00Z");
        when(service.listBundles()).thenReturn(List.of(new ApiCatalogBundleRepository.BundleSummary() {
            @Override public Long getId() { return 11L; }
            @Override public Long getVersion() { return 22L; }
            @Override public Integer getSchemaVersion() { return 3; }
            @Override public String getChecksum() { return "the-checksum"; }
            @Override public String getSigningKeyId() { return "the-key-id"; }
            @Override public String getIssuer() { return "the-issuer"; }
            @Override public Integer getApiCount() { return 444; }
            @Override public Integer getToolCount() { return 555; }
            @Override public Integer getRawBytesSize() { return 666; }
            @Override public boolean getActive() { return true; }
            @Override public Instant getImportedAt() { return imported; }
            @Override public Instant getActivatedAt() { return activated; }
        }));

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) controller.listBundles("ADMIN").getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bundles = (List<Map<String, Object>>) body.get("bundles");
        Map<String, Object> view = bundles.get(0);

        assertThat(view.keySet()).containsExactly(
                "id", "version", "schemaVersion", "checksum", "signingKeyId", "issuer",
                "apiCount", "toolCount", "rawBytesSize", "isActive", "importedAt", "activatedAt");
        assertThat(view).containsEntry("id", 11L)
                .containsEntry("version", 22L)
                .containsEntry("schemaVersion", 3)
                .containsEntry("checksum", "the-checksum")
                .containsEntry("signingKeyId", "the-key-id")
                .containsEntry("issuer", "the-issuer")
                .containsEntry("apiCount", 444)
                .containsEntry("toolCount", 555)
                .containsEntry("rawBytesSize", 666)
                .containsEntry("isActive", true)
                .containsEntry("importedAt", imported)
                .containsEntry("activatedAt", activated);
    }

    // ── Public downloads ─────────────────────────────────────────────────────

    @Test
    @DisplayName("/latest streams the active bundle with its checksum as ETag, 404 when none")
    void latestSignedBundle() {
        when(service.getActiveRawBundle()).thenReturn(Optional.of(rawBundle("cs")));

        ResponseEntity<?> ok = controller.latestSignedBundle(null);

        assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ok.getHeaders().getETag()).isEqualTo("\"cs\"");
        assertThat(ok.getBody()).isInstanceOf(StreamingResponseBody.class);

        when(service.getActiveRawBundle()).thenReturn(Optional.empty());
        assertThat(controller.latestSignedBundle(null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("A caller that sends no validator costs no identity lookup - that is every reader shipped so far")
    void noValidatorMeansNoMetadataQuery() {
        when(service.getActiveRawBundle()).thenReturn(Optional.of(rawBundle("cs")));

        controller.latestSignedBundle(null);
        controller.latestSignedBundle("   ");

        verify(service, never()).getActiveBundleMetadata();
    }

    @Test
    @DisplayName("A matching If-None-Match returns 304 WITHOUT reading the payload - the whole point of the validator")
    void matchingValidatorNeverReadsThePayload() {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta("abc", 1)));

        ResponseEntity<?> res = controller.latestSignedBundle("\"abc\"");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(res.getBody()).isNull();
        assertThat(res.getHeaders().getETag()).isEqualTo("\"abc\"");
        verify(service, never()).getActiveRawBundle();
    }

    @Test
    @DisplayName("A stale If-None-Match falls through to the full body")
    void staleValidatorDownloadsAgain() {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta("new", 1)));
        when(service.getActiveRawBundle()).thenReturn(Optional.of(rawBundle("new")));

        assertThat(controller.latestSignedBundle("\"old\"").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("If-None-Match accepts a list and a weak tag, per RFC 9110")
    void validatorParsingFollowsTheSpec() {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta("abc", 1)));

        assertThat(controller.latestSignedBundle("\"x\", W/\"abc\"").getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);
        assertThat(controller.latestSignedBundle("*").getStatusCode())
                .isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    @DisplayName("A row with no stored payload 404s even when the validator matches - a CE row is not an origin")
    void unservableRowIsNeverAnswered304() {
        // CE records applied bundles with payload_gz NULL. Answering 304 there
        // would claim "you are up to date" from an install that cannot serve
        // the bundle at all; the pre-existing contract is 404.
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta("abc", 0)));
        when(service.getActiveRawBundle()).thenReturn(Optional.empty());

        assertThat(controller.latestSignedBundle("\"abc\"").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("/{version} streams the requested version, 404 when unknown")
    void byVersion() {
        when(service.getRawBundleByVersion(42L)).thenReturn(Optional.of(rawBundle("cs")));
        when(service.getRawBundleByVersion(43L)).thenReturn(Optional.empty());

        assertThat(controller.signedBundleByVersion(42L).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.signedBundleByVersion(43L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static ApiCatalogBundleService.RawBundle rawBundle(String checksum) {
        return new ApiCatalogBundleService.RawBundle(1L, 1, checksum, "sig", "k1", "cloud",
                600, 2400, 5_000_000L, PAYLOAD_BYTES.length, () -> new java.io.ByteArrayInputStream(PAYLOAD_BYTES));
    }

    private static ApiCatalogBundleRepository.ActiveBundleMeta meta(String checksum, Integer servable) {
        return new ApiCatalogBundleRepository.ActiveBundleMeta() {
            @Override public String getChecksum() { return checksum; }
            @Override public Integer getServable() { return servable; }
            @Override public Integer getPricesStored() { return 1; }
        };
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

    /** Simple names of the binding annotation on each handler parameter. */
    private static List<String> declaredParameterKinds(Method m) {
        return Arrays.stream(m.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .map(a -> a.annotationType().getSimpleName())
                .toList();
    }

    /** Names of every {@code @RequestHeader} a handler declares. */
    private static List<String> declaredHeaderNames(Method m) {
        return Arrays.stream(m.getParameterAnnotations())
                .flatMap(Arrays::stream)
                .filter(RequestHeader.class::isInstance)
                .map(RequestHeader.class::cast)
                .map(h -> h.value().isEmpty() ? h.name() : h.value())
                .toList();
    }

    @Test
    @DisplayName("A blank or unparseable If-None-Match is treated as absent, so the caller still gets the body")
    void unusableValidatorFallsThroughToTheBody() {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta("abc", 1)));
        when(service.getActiveRawBundle()).thenReturn(Optional.of(rawBundle("abc")));

        for (String header : List.of("", "   ", ",", "W/", "\"unclosed", "not-a-tag")) {
            assertThat(controller.latestSignedBundle(header).getStatusCode())
                    .as("If-None-Match: <%s> must not be read as a match", header)
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("An unquoted tag still matches: a lenient reader costs nothing on a 64-hex checksum")
    void unquotedTagMatches() {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta("abc", 1)));

        assertThat(controller.latestSignedBundle("abc").getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED);
    }

    @Test
    @DisplayName("A row whose checksum is null can never satisfy a validator")
    void nullChecksumNeverMatches() {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta(null, 1)));
        when(service.getActiveRawBundle()).thenReturn(Optional.empty());

        assertThat(controller.latestSignedBundle("\"abc\"").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("A null servable flag is treated as not servable rather than assumed serviceable")
    void nullServableIsNotServable() {
        when(service.getActiveBundleMetadata()).thenReturn(Optional.of(meta("abc", null)));
        when(service.getActiveRawBundle()).thenReturn(Optional.empty());

        assertThat(controller.latestSignedBundle("\"abc\"").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static ApiCatalogBundleRepository.BundleSummary summary(Long id, long version) {
        return new ApiCatalogBundleRepository.BundleSummary() {
            @Override public Long getId() { return id; }
            @Override public Long getVersion() { return version; }
            @Override public Integer getSchemaVersion() { return 1; }
            @Override public String getChecksum() { return "a".repeat(64); }
            @Override public String getSigningKeyId() { return "k1"; }
            @Override public String getIssuer() { return "cloud"; }
            @Override public Integer getApiCount() { return 600; }
            @Override public Integer getToolCount() { return 2400; }
            @Override public Integer getRawBytesSize() { return 5_000_000; }
            @Override public boolean getActive() { return false; }
            @Override public java.time.Instant getImportedAt() { return Instant.now(); }
            @Override public java.time.Instant getActivatedAt() { return null; }
        };
    }
}
