package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.service.QuotaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal API for storage QUOTA LIMIT updates, owned by storage-service (the service that
 * owns the {@code storage} schema and its read caches).
 *
 * <p>Why this exists: a plan change (admin comp-plan grant or Stripe upgrade) must push the new
 * {@code included_storage_bytes} into {@code tenant_storage_quota} / {@code organization_storage_quota}.
 * auth-service used to do that via an in-process {@code QuotaService} call inside a
 * {@code TransactionSynchronization.afterCommit()} hook - which (a) silently failed to commit
 * (the @Transactional write joined the already-committed auth transaction) and (b) could not evict
 * storage-service's in-JVM read cache. Routing the update through this endpoint fixes both: the
 * write runs in storage-service's own request transaction (commits) and {@code QuotaService}'s
 * {@code @CacheEvict} clears the local cache in the SAME JVM that serves the read.
 *
 * <p>No gateway auth (internal endpoint, bypasses the gateway filter on the private VLAN); disabled
 * in monolith mode (auth calls {@code QuotaService} in-process there - same JVM, no HTTP needed).
 */
@RestController
@RequestMapping("/api/internal/storage/quota")
@ConditionalOnProperty(name = "deployment.mode", havingValue = "microservice", matchIfMissing = true)
public class InternalQuotaController {

    private static final Logger logger = LoggerFactory.getLogger(InternalQuotaController.class);

    private final QuotaService quotaService;

    public InternalQuotaController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * Set a tenant's storage limit. Runs in storage-service's own transaction (commits) and
     * evicts the tenant-quota cache in this JVM.
     *
     * @param tenantId  the tenant (auth user id as string)
     * @param maxBytes  the new max bytes (plan's included_storage_bytes)
     * @param softRatio soft-limit ratio (default 0.8)
     */
    @PostMapping("/tenant/{tenantId}/limits")
    public ResponseEntity<Map<String, Object>> setTenantLimits(
            @PathVariable String tenantId,
            @RequestParam long maxBytes,
            @RequestParam(defaultValue = "0.8") double softRatio) {
        if (maxBytes <= 0) {
            // Defence-in-depth: never let a stray 0/negative nuke a tenant's quota to nothing.
            return ResponseEntity.badRequest().body(Map.of("error", "maxBytes must be positive", "maxBytes", maxBytes));
        }
        quotaService.updateLimits(tenantId, maxBytes, softRatio);
        logger.info("Internal: tenant {} storage limit set to {} bytes (soft {}%)",
                tenantId, maxBytes, softRatio * 100);
        return ResponseEntity.ok(Map.of("tenantId", tenantId, "maxBytes", maxBytes));
    }

    /**
     * Set an organization's storage limit. Runs in storage-service's own transaction (commits)
     * and evicts the org-quota cache in this JVM.
     */
    @PostMapping("/org/{organizationId}/limits")
    public ResponseEntity<Map<String, Object>> setOrganizationLimits(
            @PathVariable String organizationId,
            @RequestParam long maxBytes,
            @RequestParam(defaultValue = "0.8") double softRatio) {
        if (maxBytes <= 0) {
            return ResponseEntity.badRequest().body(Map.of("error", "maxBytes must be positive", "maxBytes", maxBytes));
        }
        quotaService.updateOrganizationLimits(organizationId, maxBytes, softRatio);
        logger.info("Internal: org {} storage limit set to {} bytes (soft {}%)",
                organizationId, maxBytes, softRatio * 100);
        return ResponseEntity.ok(Map.of("organizationId", organizationId, "maxBytes", maxBytes));
    }
}
