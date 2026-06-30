package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.domain.CatalogBundleEntity;
import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.web.AdminRoleGuard;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the cloud-side catalog-bundle publisher.
 *
 * <p>Admin endpoints live under {@code /api/model-config/bundles} (gated by
 * {@link AdminRoleGuard} via {@code X-User-Roles}, routed through the gateway
 * by the existing {@code agent-model-config} rule).
 *
 * <p>CE download endpoints live under {@code /api/catalog-bundles/*} and are
 * GATED behind an active cloud link: the gateway validates the CE's cloud-link
 * bearer token (injecting {@code X-User-ID}) and the handler additionally checks
 * {@code authClient.userOwnsActiveCeLink} on the {@code X-LiveContext-Install-Id}
 * header - mirroring the LLM relay ({@code CloudLlmRelayController}). Catalog
 * freshness is therefore a benefit of being cloud-linked: an UNLINKED install gets
 * 401/403, never the bundle. Only {@code /api/catalog-bundles/signing-key} stays
 * public (trust bootstrap of the Ed25519 public key). Trust is defence-in-depth:
 * the bearer gates WHO may fetch, the signature (verified offline against the
 * operator-pinned key) proves WHAT was fetched. A dedicated
 * {@code agent-catalog-bundles} route in {@code SimpleGatewayConfig} wires the
 * prefix to agent-service.
 */
@Slf4j
@RestController
public class CatalogBundleController {

    /** Same header the LLM relay uses to carry the CE install id (see CloudLlmRelayController). */
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private final CatalogBundleService bundleService;
    private final CatalogBundleSigner signer;
    private final CatalogBundleSyncStatusRepository syncStatusRepo;
    /** Present only on CE instances where {@code catalog.bundle.sync.enabled=true}. */
    private final ObjectProvider<CatalogBundleSyncScheduler> schedulerProvider;
    /** Cloud-link entitlement check (shared with the LLM relay). */
    private final AuthClient authClient;

    public CatalogBundleController(
            CatalogBundleService bundleService,
            CatalogBundleSigner signer,
            CatalogBundleSyncStatusRepository syncStatusRepo,
            ObjectProvider<CatalogBundleSyncScheduler> schedulerProvider,
            AuthClient authClient) {
        this.bundleService = bundleService;
        this.signer = signer;
        this.syncStatusRepo = syncStatusRepo;
        this.schedulerProvider = schedulerProvider;
        this.authClient = authClient;
    }

    /**
     * Bundle-download entitlement gate: the caller must present a validated
     * cloud-link identity (gateway-injected {@code X-User-ID}) that owns an ACTIVE
     * CE link for the given install id. Returns the error response to send, or
     * {@code null} when the caller is allowed. Mirrors
     * {@code CloudLlmRelayController.validate} so the catalog and the relay share
     * one entitlement model.
     */
    private ResponseEntity<?> denyIfNotLinked(String cloudUserId, String installId) {
        if (cloudUserId == null || cloudUserId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "AUTHENTICATION_REQUIRED"));
        }
        if (!authClient.userOwnsActiveCeLink(cloudUserId, installId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "CE_LINK_NOT_ACTIVE"));
        }
        return null;
    }

    /** Admin: build a new bundle (is_active=false) from the current catalog. */
    @PostMapping("/api/model-config/bundles")
    public ResponseEntity<?> buildBundle(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        try {
            CatalogBundleEntity saved = bundleService.buildBundle();
            return ResponseEntity.ok(toAdminView(saved));
        } catch (IllegalStateException e) {
            log.warn("Bundle build rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin: flip a bundle to is_active=true (deactivates the previous one). */
    @PostMapping("/api/model-config/bundles/{id}/activate")
    public ResponseEntity<?> activateBundle(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable Long id) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        try {
            CatalogBundleEntity activated = bundleService.activateBundle(id);
            return ResponseEntity.ok(toAdminView(activated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (DataIntegrityViolationException e) {
            // Concurrent activate raced against the partial unique index
            // idx_catalog_bundles_one_active. DB correctly rejected the loser;
            // surface as 409 so the admin refreshes rather than seeing a 500.
            log.warn("Concurrent activate rejected by unique index (id={}): {}",
                    id, e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "another bundle was activated concurrently - refresh and retry"));
        }
    }

    /** Admin: list all bundles (newest first). */
    @GetMapping("/api/model-config/bundles")
    public ResponseEntity<?> listBundles(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        List<Map<String, Object>> view = bundleService.listBundles().stream()
                .sorted((a, b) -> Long.compare(b.getVersion(), a.getVersion()))
                .map(this::toAdminView)
                .toList();
        // Use LinkedHashMap: Map.of rejects null values and publicKeyBase64() is
        // null on envs with no signing key (local dev) - the admin UI still
        // needs to render bundle history in that case.
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("bundles", view);
        body.put("signingKeyId", signer.keyId());
        body.put("publicKeyBase64", signer.publicKeyBase64());
        return ResponseEntity.ok(body);
    }

    /**
     * CE admin: report the last fetch / apply outcome so the operator UI can
     * surface failures ("signature invalid", "connection refused") without
     * having to tail logs. Always returns 200 with the singleton row - fields
     * are {@code null} before the first tick.
     */
    @GetMapping("/api/model-config/bundles/sync-status")
    public ResponseEntity<?> syncStatus(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        CatalogBundleSyncStatusEntity row = syncStatusRepo
                .findById(CatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(CatalogBundleSyncStatusEntity::new);
        return ResponseEntity.ok(toSyncStatusView(row));
    }

    /**
     * CE admin: force a sync tick immediately (instead of waiting for the next
     * cron firing). Returns the updated sync-status row. 503 on cloud
     * instances where the scheduler bean was not created (i.e. {@code
     * catalog.bundle.sync.enabled} is false).
     */
    @PostMapping("/api/model-config/bundles/sync-now")
    public ResponseEntity<?> syncNow(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        CatalogBundleSyncScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "catalog.bundle.sync.enabled=false on this instance"));
        }
        try {
            // Call tick() via the injected proxy bean reference so the
            // @SchedulerLock annotation actually fires (AOP does not intercept
            // self-invocation). This shares the lock with the scheduled tick,
            // preventing a concurrent click + cron firing from racing on
            // bundleRepo.deactivateAll() + save(active=true) against the
            // partial unique index idx_catalog_bundles_one_active.
            scheduler.tick();
        } catch (Exception e) {
            // Same belt-and-braces guarantee as the scheduled tick: the
            // endpoint must not 500 because downstream apply failed. The
            // failure is already persisted on the sync-status row.
            log.warn("syncNow() caught exception (already persisted): {}", e.getMessage());
        }
        CatalogBundleSyncStatusEntity row = syncStatusRepo
                .findById(CatalogBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(CatalogBundleSyncStatusEntity::new);
        return ResponseEntity.ok(toSyncStatusView(row));
    }

    /**
     * CE download: returns the currently active signed bundle. Requires an active
     * cloud link (401 if no validated cloud identity, 403 if the install is not
     * linked). 404 if no bundle has been activated yet.
     */
    @GetMapping("/api/catalog-bundles/latest")
    public ResponseEntity<?> latestSignedBundle(
            @RequestHeader(value = "X-User-ID", required = false) String cloudUserId,
            @RequestHeader(value = INSTALL_HEADER, required = false) String installId) {
        ResponseEntity<?> denied = denyIfNotLinked(cloudUserId, installId);
        if (denied != null) return denied;
        Optional<SignedBundle> bundle = bundleService.getActiveSignedBundle();
        return bundle.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * CE download: a specific version (for replay / diagnostics). Same active-link
     * gate as {@link #latestSignedBundle}.
     */
    @GetMapping("/api/catalog-bundles/{version}")
    public ResponseEntity<?> signedBundleByVersion(
            @RequestHeader(value = "X-User-ID", required = false) String cloudUserId,
            @RequestHeader(value = INSTALL_HEADER, required = false) String installId,
            @PathVariable long version) {
        ResponseEntity<?> denied = denyIfNotLinked(cloudUserId, installId);
        if (denied != null) return denied;
        Optional<SignedBundle> bundle = bundleService.getSignedBundleByVersion(version);
        return bundle.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * CE trust bootstrap: returns the cloud's current Ed25519 public key.
     * Served over HTTPS and pinned into {@code catalog-seed.trust-keys} on the
     * CE side at first boot. Key rotation = republish this + push new bundles
     * signed with the new key; old bundles remain verifiable with the old key
     * as long as CE retains it in its trust list.
     */
    @GetMapping("/api/catalog-bundles/signing-key")
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

    private Map<String, Object> toSyncStatusView(CatalogBundleSyncStatusEntity row) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
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

    private Map<String, Object> toAdminView(CatalogBundleEntity e) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", e.getId());
        out.put("version", e.getVersion());
        out.put("schemaVersion", e.getSchemaVersion());
        out.put("checksum", e.getChecksum());
        out.put("signingKeyId", e.getSigningKeyId());
        out.put("issuer", e.getIssuer());
        out.put("modelCount", e.getModelCount());
        out.put("rawBytesSize", e.getRawBytesSize());
        out.put("isActive", e.isActive());
        out.put("importedAt", e.getImportedAt());
        out.put("activatedAt", e.getActivatedAt());
        return out;
    }
}
