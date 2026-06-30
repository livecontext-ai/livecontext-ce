package com.apimarketplace.agent.skill.bundle;

import com.apimarketplace.agent.catalog.bundle.CatalogBundleSigner;
import com.apimarketplace.agent.domain.SkillBundleEntity;
import com.apimarketplace.agent.domain.SkillBundleSyncStatusEntity;
import com.apimarketplace.agent.repository.SkillBundleSyncStatusRepository;
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
 * REST surface for the cloud-side skill-bundle publisher, sibling of
 * {@code com.apimarketplace.agent.catalog.bundle.CatalogBundleController}. Reuses the
 * shared Ed25519 {@link CatalogBundleSigner} and the cloud-link entitlement model.
 *
 * <p>Admin endpoints under {@code /api/model-config/skill-bundles} (gated by
 * {@link AdminRoleGuard} via {@code X-User-Roles}; routed by the existing
 * {@code agent-model-config} gateway rule). CE downloads under {@code /api/skill-bundles/*}
 * are GATED behind an active cloud link (gateway validates the cloud-link bearer ->
 * {@code X-User-ID}; the handler checks {@code authClient.userOwnsActiveCeLink} on
 * {@code X-LiveContext-Install-Id}). Only {@code /api/skill-bundles/signing-key} is public
 * (trust bootstrap); signature verification stays as defence-in-depth.
 */
@Slf4j
@RestController
public class SkillBundleController {

    /** Same header the LLM relay + catalog bundle use to carry the CE install id. */
    static final String INSTALL_HEADER = "X-LiveContext-Install-Id";

    private final SkillBundleService bundleService;
    private final CatalogBundleSigner signer;
    private final SkillBundleSyncStatusRepository syncStatusRepo;
    /** Present only on CE instances where {@code skill.bundle.sync.enabled=true}. */
    private final ObjectProvider<SkillBundleSyncScheduler> schedulerProvider;
    private final AuthClient authClient;

    public SkillBundleController(
            SkillBundleService bundleService,
            CatalogBundleSigner signer,
            SkillBundleSyncStatusRepository syncStatusRepo,
            ObjectProvider<SkillBundleSyncScheduler> schedulerProvider,
            AuthClient authClient) {
        this.bundleService = bundleService;
        this.signer = signer;
        this.syncStatusRepo = syncStatusRepo;
        this.schedulerProvider = schedulerProvider;
        this.authClient = authClient;
    }

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

    /** Admin: build a new bundle (is_active=false) from the current global skills. */
    @PostMapping("/api/model-config/skill-bundles")
    public ResponseEntity<?> buildBundle(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            SkillBundleEntity saved = bundleService.buildBundle();
            return ResponseEntity.ok(toAdminView(saved));
        } catch (IllegalStateException e) {
            log.warn("Skill bundle build rejected: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Admin: flip a bundle to is_active=true (deactivates the previous one). */
    @PostMapping("/api/model-config/skill-bundles/{id}/activate")
    public ResponseEntity<?> activateBundle(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @PathVariable Long id) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        try {
            SkillBundleEntity activated = bundleService.activateBundle(id);
            return ResponseEntity.ok(toAdminView(activated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (DataIntegrityViolationException e) {
            log.warn("Concurrent skill-bundle activate rejected by unique index (id={}): {}",
                    id, e.getMostSpecificCause().getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "another bundle was activated concurrently - refresh and retry"));
        }
    }

    /** Admin: list all bundles (newest first). */
    @GetMapping("/api/model-config/skill-bundles")
    public ResponseEntity<?> listBundles(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        List<Map<String, Object>> view = bundleService.listBundles().stream()
                .sorted((a, b) -> Long.compare(b.getVersion(), a.getVersion()))
                .map(this::toAdminView)
                .toList();
        java.util.LinkedHashMap<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("bundles", view);
        body.put("signingKeyId", signer.keyId());
        body.put("publicKeyBase64", signer.publicKeyBase64());
        return ResponseEntity.ok(body);
    }

    /** CE admin: last fetch / apply outcome. Always 200 with the singleton row. */
    @GetMapping("/api/model-config/skill-bundles/sync-status")
    public ResponseEntity<?> syncStatus(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        SkillBundleSyncStatusEntity row = syncStatusRepo
                .findById(SkillBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(SkillBundleSyncStatusEntity::new);
        return ResponseEntity.ok(toSyncStatusView(row));
    }

    /** CE admin: force a sync tick now. 503 on instances where the scheduler bean is absent. */
    @PostMapping("/api/model-config/skill-bundles/sync-now")
    public ResponseEntity<?> syncNow(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        SkillBundleSyncScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "skill.bundle.sync.enabled=false on this instance"));
        }
        try {
            // Call via the proxy bean so @SchedulerLock fires (shares the lock with the cron tick).
            scheduler.tick();
        } catch (Exception e) {
            log.warn("Skill bundle syncNow() caught exception (already persisted): {}", e.getMessage());
        }
        SkillBundleSyncStatusEntity row = syncStatusRepo
                .findById(SkillBundleSyncStatusEntity.SINGLETON_ID)
                .orElseGet(SkillBundleSyncStatusEntity::new);
        return ResponseEntity.ok(toSyncStatusView(row));
    }

    /** CE download: currently active signed bundle. Requires an active cloud link. 404 if none. */
    @GetMapping("/api/skill-bundles/latest")
    public ResponseEntity<?> latestSignedBundle(
            @RequestHeader(value = "X-User-ID", required = false) String cloudUserId,
            @RequestHeader(value = INSTALL_HEADER, required = false) String installId) {
        ResponseEntity<?> denied = denyIfNotLinked(cloudUserId, installId);
        if (denied != null) return denied;
        Optional<SignedSkillBundle> bundle = bundleService.getActiveSignedBundle();
        return bundle.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** CE download: a specific version. Same active-link gate. */
    @GetMapping("/api/skill-bundles/{version}")
    public ResponseEntity<?> signedBundleByVersion(
            @RequestHeader(value = "X-User-ID", required = false) String cloudUserId,
            @RequestHeader(value = INSTALL_HEADER, required = false) String installId,
            @PathVariable long version) {
        ResponseEntity<?> denied = denyIfNotLinked(cloudUserId, installId);
        if (denied != null) return denied;
        Optional<SignedSkillBundle> bundle = bundleService.getSignedBundleByVersion(version);
        return bundle.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** CE trust bootstrap: the cloud's current Ed25519 public key (public endpoint). */
    @GetMapping("/api/skill-bundles/signing-key")
    public ResponseEntity<?> signingKey() {
        String pub = signer.publicKeyBase64();
        if (pub == null) {
            return ResponseEntity.status(503).body(Map.of("error", "no signing key configured"));
        }
        return ResponseEntity.ok(Map.of(
                "keyId", signer.keyId(),
                "issuer", signer.issuer(),
                "publicKeyBase64", pub,
                "algorithm", "Ed25519"));
    }

    private Map<String, Object> toSyncStatusView(SkillBundleSyncStatusEntity row) {
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

    private Map<String, Object> toAdminView(SkillBundleEntity e) {
        java.util.LinkedHashMap<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", e.getId());
        out.put("version", e.getVersion());
        out.put("schemaVersion", e.getSchemaVersion());
        out.put("checksum", e.getChecksum());
        out.put("signingKeyId", e.getSigningKeyId());
        out.put("issuer", e.getIssuer());
        out.put("skillCount", e.getSkillCount());
        out.put("rawBytesSize", e.getRawBytesSize());
        out.put("isActive", e.isActive());
        out.put("importedAt", e.getImportedAt());
        out.put("activatedAt", e.getActivatedAt());
        return out;
    }
}
