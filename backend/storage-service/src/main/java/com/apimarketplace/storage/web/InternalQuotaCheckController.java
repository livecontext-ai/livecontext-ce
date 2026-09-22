package com.apimarketplace.storage.web;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.service.QuotaService;
import com.apimarketplace.common.web.TenantResolver;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only probe: "could this scope store N more bytes?", answered without writing anything.
 *
 * <p>It exists for callers that SPEND before they store. Media generation charges the customer
 * inside the provider call and only then makes the asset durable, so an account with no room pays
 * for every generation and keeps none. Every other quota interaction is a write that discovers
 * the refusal too late to prevent the charge; this is a read taken before the money moves.
 *
 * <p><b>Why its own class rather than a third method on {@link InternalQuotaController}:</b> that
 * controller is gated to {@code deployment.mode=microservice}, correctly, because its two
 * limit-writing endpoints exist only so auth-service can reach storage-service over HTTP, which
 * is pointless in the monolith where auth calls {@code QuotaService} in process. This probe has
 * the opposite requirement: CE points {@code services.storage-url} at the monolith itself, so
 * catalog-service reaches it over HTTP-to-self exactly as it reaches the upload endpoints, and
 * gating it to microservice would 404 in CE, fail open on every generation and protect nobody.
 *
 * <p>No gateway auth (internal endpoint on the private VLAN), same as its sibling.
 */
@RestController
@RequestMapping("/api/internal/storage/quota")
public class InternalQuotaCheckController {

    private final QuotaService quotaService;

    public InternalQuotaCheckController(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /**
     * Answer whether {@code bytes} more would be accepted, deciding it exactly as the WRITE will.
     *
     * <p>Mirroring the write is the whole requirement: a probe that reads the personal quota while
     * the upload lands in a workspace would wave through a caller whose workspace is full. So this
     * reproduces {@code S3FileStorageService.validateQuota} step for step, and the two must be
     * changed together:
     * <ul>
     *   <li>the active workspace comes from {@link TenantResolver#currentRequestOrganizationId()},
     *       the same call the write makes, with an explicit {@code organizationId} overriding it
     *       for async and daemon callers that carry no request context;</li>
     *   <li>system tenants ({@code _publications} and friends, any id starting with an underscore)
     *       are not real accounts and are never quota-checked on write, so they are allowed here
     *       rather than refused, which would block a path the write would have accepted.</li>
     * </ul>
     *
     * <p>"Read-only" describes the quota figures, not the table: like every quota decision, this
     * goes through {@code getOrCreate…Quota} and will materialise a missing row. That is not a
     * side effect worth avoiding here, because the write this probe precedes by milliseconds
     * would create the very same row with the very same values.
     *
     * @param bytes how much is about to be added. Probe with 1 to ask "is there ANY room left":
     *              a 0-byte probe passes even at exactly the ceiling, because the quota compares
     *              {@code used <= hardLimit}.
     */
    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> check(
            @RequestParam String tenantId,
            @RequestParam(required = false) String organizationId,
            @RequestParam(defaultValue = "1") long bytes) {

        if (tenantId != null && tenantId.startsWith("_")) {
            return answer(true, QuotaStatus.OK, tenantId, null);
        }

        String scopeOrgId = (organizationId != null && !organizationId.isBlank())
                ? organizationId
                : TenantResolver.currentRequestOrganizationId();

        QuotaStatus status = quotaService.checkQuotaForScope(tenantId, scopeOrgId, Math.max(0L, bytes));
        return answer(status != QuotaStatus.HARD_LIMIT_REACHED, status, tenantId, scopeOrgId);
    }

    private ResponseEntity<Map<String, Object>> answer(
            boolean allowed, QuotaStatus status, String tenantId, String orgId) {
        return ResponseEntity.ok(Map.of(
                "allowed", allowed,
                "status", status.name(),
                "tenantId", tenantId == null ? "" : tenantId,
                "organizationId", orgId == null ? "" : orgId));
    }
}
