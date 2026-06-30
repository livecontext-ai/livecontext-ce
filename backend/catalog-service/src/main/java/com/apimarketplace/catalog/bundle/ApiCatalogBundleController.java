package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleSyncStatusRepository;
import com.apimarketplace.common.web.AdminRoleGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

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
        } catch (IllegalStateException e) {
            log.warn("API catalog bundle build rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
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

        List<Map<String, Object>> view = bundleService.listBundles().stream()
                .sorted((a, b) -> Long.compare(b.getVersion(), a.getVersion()))
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
        try {
            scheduler.tick();
        } catch (Exception e) {
            // Same guarantee as the scheduled tick: the endpoint must not 500
            // because downstream apply failed - the failure is already
            // persisted on the sync-status row.
            log.warn("API catalog syncNow() caught exception (already persisted): {}", e.getMessage());
        }
        ApiCatalogBundleSyncStatusEntity row = syncStatusRepo
                .findById(ApiCatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(ApiCatalogBundleSyncStatusEntity::new);
        return ResponseEntity.ok(toSyncStatusView(row));
    }

    /** CE download (public): the currently active signed bundle. 404 if none. */
    @GetMapping("/api/catalog/public/bundles/latest")
    public ResponseEntity<?> latestSignedBundle() {
        Optional<ApiCatalogSignedBundle> bundle = bundleService.getActiveSignedBundle();
        return bundle.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** CE download (public): a specific version (replay / diagnostics). */
    @GetMapping("/api/catalog/public/bundles/{version}")
    public ResponseEntity<?> signedBundleByVersion(@PathVariable long version) {
        Optional<ApiCatalogSignedBundle> bundle = bundleService.getSignedBundleByVersion(version);
        return bundle.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
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
        out.put("updatedAt", row.getUpdatedAt());
        out.put("schedulerEnabled", schedulerProvider.getIfAvailable() != null);
        return out;
    }

    private Map<String, Object> toAdminView(ApiCatalogBundleEntity e) {
        LinkedHashMap<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.getId());
        out.put("version", e.getVersion());
        out.put("schemaVersion", e.getSchemaVersion());
        out.put("checksum", e.getChecksum());
        out.put("signingKeyId", e.getSigningKeyId());
        out.put("issuer", e.getIssuer());
        out.put("apiCount", e.getApiCount());
        out.put("toolCount", e.getToolCount());
        out.put("rawBytesSize", e.getRawBytesSize());
        out.put("isActive", e.isActive());
        out.put("importedAt", e.getImportedAt());
        out.put("activatedAt", e.getActivatedAt());
        return out;
    }
}
