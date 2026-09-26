package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.common.web.BundleEtags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the API-catalog bundle system. Mirrors the LLM model
 * bundle's {@code agent-service CatalogBundleController}.
 *
 * <p>Admin endpoints live under {@code /api/catalog/bundles} (gated by
 * {@link AdminRoleGuard} via the gateway-injected {@code X-User-Roles}
 * header; the existing gateway route for {@code /api/catalog/**} applies and
 * the prefix is NOT in the gateway's public allowlist, so a user JWT is
 * required).
 *
 * <p>CE download endpoints live under {@code /api/catalog/public/bundles/*}.
 * The gateway already treats {@code /api/catalog/public} as a public prefix
 * ({@code GatewayConstants.PUBLIC_ENDPOINTS}) - no user JWT. CE instances
 * authenticate the CONTENT, not the transport: they verify the Ed25519
 * signature against their pinned trust list offline.
 */
@Slf4j
@RestController
public class ApiCatalogBundleController {

    /**
     * {@code /latest} may be served from a shared cache (Cloudflare, once a cache rule covers the
     * path) for five minutes. That is what keeps a growing fleet off the origin: every install
     * that downloads the 32 MB payload in full, including old releases that can never send a
     * validator, is then answered by the edge, and the origin serves it about once per edge
     * location per five minutes. The cost is up to five minutes of delay after an activation,
     * nothing next to a 15-minute poll. The ETag still lets the edge answer a 304.
     */
    static final CacheControl LATEST_CACHE = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    /** A numbered version's bytes never change once built, so it may be cached for a day. */
    static final CacheControl VERSION_CACHE = CacheControl.maxAge(Duration.ofDays(1)).cachePublic().immutable();

    private final ApiCatalogBundleService bundleService;
    private final ApiCatalogBundleSigner signer;
    private final ApiCatalogBundleSyncStatusRepository syncStatusRepo;
    /** Present only on CE instances where {@code api-catalog.bundle.sync.enabled=true}. */
    private final ObjectProvider<ApiCatalogBundleSyncScheduler> schedulerProvider;

    public ApiCatalogBundleController(
            ApiCatalogBundleService bundleService,
            ApiCatalogBundleSigner signer,
            ApiCatalogBundleSyncStatusRepository syncStatusRepo,
            ObjectProvider<ApiCatalogBundleSyncScheduler> schedulerProvider) {
        this.bundleService = bundleService;
        this.signer = signer;
        this.syncStatusRepo = syncStatusRepo;
        this.schedulerProvider = schedulerProvider;
    }

    /** Admin: build a new bundle (is_active=false) from the current catalog. */
    @PostMapping("/api/catalog/bundles")
    public ResponseEntity<?> buildBundle(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        try {
            ApiCatalogBundleEntity saved = bundleService.buildBundle();
            return ResponseEntity.ok(toAdminView(saved));
        } catch (IllegalStateException | java.io.UncheckedIOException e) {
            // UncheckedIOException is caught alongside because serialising or compressing the
            // payload can fail, and letting it escape hands the admin the same opaque HTTP 500
            // this area was fixed to stop producing. The message names what actually failed.
            log.warn("API catalog bundle build rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    /** Admin: flip a bundle to is_active=true (deactivates the previous one). */
    @PostMapping("/api/catalog/bundles/{id}/activate")
    public ResponseEntity<?> activateBundle(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable Long id) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        try {
            ApiCatalogBundleEntity activated = bundleService.activateBundle(id);
            return ResponseEntity.ok(toAdminView(activated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (DataIntegrityViolationException e) {
            // Concurrent activate raced against the partial unique index
            // idx_api_catalog_bundles_one_active. Surface as 409, not 500.
            log.warn("Concurrent API catalog bundle activate rejected (id={}): {}",
                    id, e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "another bundle was activated concurrently - refresh and retry"));
        }
    }

    /** Admin: list all bundles (newest first). */
    @GetMapping("/api/catalog/bundles")
    public ResponseEntity<?> listBundles(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        // Already newest-first from the query, and payload-free.
        List<Map<String, Object>> view = bundleService.listBundles().stream()
                .map(this::toAdminView)
                .toList();
        // LinkedHashMap: publicKeyBase64() is null on envs without a signing
        // key (local dev) and Map.of rejects null values.
        LinkedHashMap<String, Object> body = new LinkedHashMap<>();
        body.put("bundles", view);
        body.put("signingKeyId", signer.keyId());
        body.put("publicKeyBase64", signer.publicKeyBase64());
        return ResponseEntity.ok(body);
    }

    /**
     * CE admin: last fetch/apply outcome so the operator UI surfaces failures
     * without tailing logs. Always 200; fields null before the first tick.
     */
    @GetMapping("/api/catalog/bundles/sync-status")
    public ResponseEntity<?> syncStatus(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        ApiCatalogBundleSyncStatusEntity row = syncStatusRepo
                .findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(ApiCatalogBundleSyncStatusEntity::new);
        return ResponseEntity.ok(toSyncStatusView(row));
    }

    /**
     * CE admin: force a sync tick immediately. Returns the updated sync-status
     * row. 503 on cloud instances (no scheduler bean -
     * {@code api-catalog.bundle.sync.enabled} is false).
     */
    @PostMapping("/api/catalog/bundles/sync-now")
    public ResponseEntity<?> syncNow(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        ApiCatalogBundleSyncScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "api-catalog.bundle.sync.enabled=false on this instance"));
        }
        boolean ran = true;
        try {
            ran = scheduler.tick();
        } catch (Exception e) {
            // Same guarantee as the scheduled tick: the endpoint must not 500
            // because downstream apply failed - the failure is already
            // persisted on the sync-status row.
            log.warn("API catalog syncNow() caught exception (already persisted): {}", e.getMessage());
        }
        ApiCatalogBundleSyncStatusEntity row = syncStatusRepo
                .findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(ApiCatalogBundleSyncStatusEntity::new);
        Map<String, Object> view = toSyncStatusView(row);
        // A sync was already running, so this click did nothing: say so, rather than let the row
        // (about to be updated by that other sync) pass for the result of this one.
        view.put("syncInProgress", !ran);
        return ResponseEntity.ok(view);
    }

    /**
     * CE download (public): the currently active signed bundle. 404 if none.
     *
     * <p><b>Conditional GET.</b> A CE instance polls this every 15 minutes and
     * the bundle changes far less often, so a client that sends
     * {@code If-None-Match} with the checksum it already holds gets a bodiless
     * {@code 304} - answered from a payload-free projection, so the ~24 MB
     * gzip is never read. Clients that send no validator keep getting the full
     * {@code 200} body exactly as before.
     */
    @GetMapping("/api/catalog/public/bundles/latest")
    public ResponseEntity<StreamingResponseBody> latestSignedBundle(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        // Only look up the identity when the caller actually sent a validator:
        // a client that sends none (every CE reader shipped so far) would
        // otherwise pay an extra query and transaction per poll for an answer
        // that cannot change the response.
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            Optional<ApiCatalogBundleRepository.ActiveBundleMeta> meta = bundleService.getActiveBundleMetadata();
            // Only a row that still carries its payload is servable; a CE-side
            // applied row must 404 here exactly as it did before, never 304.
            if (meta.isPresent() && isServable(meta.get()) && BundleEtags.matches(ifNoneMatch, meta.get().getChecksum())) {
                return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                        .eTag(BundleEtags.quoted(meta.get().getChecksum()))
                        .cacheControl(LATEST_CACHE)
                        .build();
            }
        }
        return streamOrNotFound(bundleService.getActiveRawBundle(), LATEST_CACHE);
    }

    /** CE download (public): a specific version (replay / diagnostics). */
    @GetMapping("/api/catalog/public/bundles/{version}")
    public ResponseEntity<StreamingResponseBody> signedBundleByVersion(@PathVariable long version) {
        return streamOrNotFound(bundleService.getRawBundleByVersion(version), VERSION_CACHE);
    }

    /**
     * 200 + streamed envelope, or 404. The body is written by
     * {@link ApiCatalogBundleJsonWriter} so the base64 is encoded into the
     * response instead of being built in heap first.
     *
     * <p>The return type names {@link StreamingResponseBody} explicitly, and
     * must keep naming it: Spring selects
     * {@code StreamingResponseBodyReturnValueHandler} from the handler's
     * DECLARED generic. Widen this to {@code ResponseEntity<?>} and the generic
     * resolves to null, the handler declines, and Jackson serialises the lambda
     * as {@code {}} - a 200 carrying a valid ETag and an empty body, with no
     * exception and no log line.
     */
    private ResponseEntity<StreamingResponseBody> streamOrNotFound(
            Optional<ApiCatalogBundleService.RawBundle> bundle, CacheControl cacheControl) {
        if (bundle.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ApiCatalogBundleService.RawBundle b = bundle.get();
        StreamingResponseBody body = out -> ApiCatalogBundleJsonWriter.write(b, out);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .eTag(BundleEtags.quoted(b.checksum()))
                .cacheControl(cacheControl)
                .body(body);
    }

    private static boolean isServable(ApiCatalogBundleRepository.ActiveBundleMeta meta) {
        Integer servable = meta.getServable();
        return servable != null && servable == 1;
    }

    /**
     * CE trust bootstrap (public): the cloud's current Ed25519 public key -
     * pinned into {@code catalog.bundle.trusted-keys} by the CE operator.
     * Same keypair as the LLM model bundle (shared trust root).
     */
    @GetMapping("/api/catalog/public/bundles/signing-key")
    public ResponseEntity<?> signingKey() {
        String pub = signer.publicKeyBase64();
        if (pub == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "no signing key configured"));
        }
        return ResponseEntity.ok(Map.of(
                "keyId", signer.keyId(),
                "issuer", signer.issuer(),
                "publicKeyBase64", pub,
                "algorithm", "Ed25519"));
    }

    private Map<String, Object> toSyncStatusView(ApiCatalogBundleSyncStatusEntity row) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("lastAppliedVersion", row.getLastAppliedVersion());
        out.put("lastAppliedAt", row.getLastAppliedAt());
        out.put("lastFetchAt", row.getLastFetchAt());
        out.put("lastFetchStatus", row.getLastFetchStatus());
        out.put("lastFetchError", row.getLastFetchError());
        out.put("consecutiveFailures", row.getConsecutiveFailures());
        // Poll backoff: why the scheduled sync may be quiet for a while after failures. A manual
        // sync ignores it. backoffLevel 0 and a null nextAttemptAt mean no wait.
        out.put("backoffLevel", row.getBackoffLevel());
        out.put("nextAttemptAt", row.getNextAttemptAt());
        out.put("updatedAt", row.getUpdatedAt());
        out.put("schedulerEnabled", schedulerProvider.getIfAvailable() != null);
        return out;
    }

    /** Admin view of a freshly built or activated bundle (entity in hand). */
    private Map<String, Object> toAdminView(ApiCatalogBundleEntity e) {
        return adminView(e.getId(), e.getVersion(), e.getSchemaVersion(), e.getChecksum(),
                e.getSigningKeyId(), e.getIssuer(), e.getApiCount(), e.getToolCount(),
                e.getRawBytesSize(), e.isActive(), e.getImportedAt(), e.getActivatedAt());
    }

    /** Admin view of a listed bundle (payload-free projection). */
    private Map<String, Object> toAdminView(ApiCatalogBundleRepository.BundleSummary s) {
        return adminView(s.getId(), s.getVersion(), s.getSchemaVersion(), s.getChecksum(),
                s.getSigningKeyId(), s.getIssuer(), s.getApiCount(), s.getToolCount(),
                s.getRawBytesSize(), s.getActive(), s.getImportedAt(), s.getActivatedAt());
    }

    /**
     * The admin JSON shape, defined once. The two callers above only forward
     * fields, so the entity path and the projection path cannot drift apart.
     */
    private static Map<String, Object> adminView(
            Long id, Long version, Integer schemaVersion, String checksum, String signingKeyId,
            String issuer, Integer apiCount, Integer toolCount, Integer rawBytesSize,
            boolean active, Instant importedAt, Instant activatedAt) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("version", version);
        out.put("schemaVersion", schemaVersion);
        out.put("checksum", checksum);
        out.put("signingKeyId", signingKeyId);
        out.put("issuer", issuer);
        out.put("apiCount", apiCount);
        out.put("toolCount", toolCount);
        out.put("rawBytesSize", rawBytesSize);
        out.put("isActive", active);
        out.put("importedAt", importedAt);
        out.put("activatedAt", activatedAt);
        return out;
    }
}
