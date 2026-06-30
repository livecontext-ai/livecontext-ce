package com.apimarketplace.orchestrator.controllers.org;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.dto.OrgRestrictionDto;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for managing org-level resource access restrictions.
 * Only OWNER/ADMIN can manage restrictions.
 */
@RestController
@RequestMapping("/api/org-access")
public class OrgAccessController {

    private static final Set<String> ADMIN_ROLES = Set.of("OWNER", "ADMIN");

    private final OrgAccessGuard orgAccessService;
    private final TenantResolver tenantResolver;

    public OrgAccessController(OrgAccessGuard orgAccessService, TenantResolver tenantResolver) {
        this.orgAccessService = orgAccessService;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Get all restrictions for a member in an org.
     */
    @GetMapping("/{orgId}/members/{userId}/restrictions")
    public ResponseEntity<List<OrgRestrictionDto>> getMemberRestrictions(
            @PathVariable String orgId,
            @PathVariable String userId,
            HttpServletRequest request) {
        if (!isAdminCaller(request, orgId)) {
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(orgAccessService.getMemberRestrictions(orgId, userId));
    }

    /**
     * Bulk set restrictions for a member + resource type.
     * Body: { "resourceType": "workflow", "restrictedResourceIds": ["id1", "id2"] }
     */
    @PutMapping("/{orgId}/members/{userId}/restrictions")
    public ResponseEntity<Void> setRestrictions(
            @PathVariable String orgId,
            @PathVariable String userId,
            @RequestBody SetRestrictionsRequest body,
            HttpServletRequest request) {
        String callerId = tenantResolver.resolve(request);
        if (!isAdminCaller(request, orgId)) {
            return ResponseEntity.status(403).build();
        }
        Map<String, String> permissions = body.permissions() != null ? body.permissions() : Map.of();
        orgAccessService.setRestrictions(orgId, userId, body.resourceType(),
                Set.copyOf(body.restrictedResourceIds()), permissions, callerId);
        return ResponseEntity.ok().build();
    }

    /**
     * Restrict a single resource for a member.
     * Body: { "resourceType": "workflow", "resourceId": "abc-123" }
     */
    @PostMapping("/{orgId}/members/{userId}/restrict")
    public ResponseEntity<Void> restrictSingle(
            @PathVariable String orgId,
            @PathVariable String userId,
            @RequestBody RestrictRequest body,
            HttpServletRequest request) {
        String callerId = tenantResolver.resolve(request);
        if (!isAdminCaller(request, orgId)) {
            return ResponseEntity.status(403).build();
        }
        orgAccessService.restrictAccess(orgId, userId, body.resourceType(), body.resourceId(), callerId);
        return ResponseEntity.ok().build();
    }

    /**
     * Remove a restriction (grant access back).
     */
    @DeleteMapping("/{orgId}/members/{userId}/restrict/{type}/{resourceId}")
    public ResponseEntity<Void> grantSingle(
            @PathVariable String orgId,
            @PathVariable String userId,
            @PathVariable String type,
            @PathVariable String resourceId,
            HttpServletRequest request) {
        if (!isAdminCaller(request, orgId)) {
            return ResponseEntity.status(403).build();
        }
        orgAccessService.grantAccess(orgId, userId, type, resourceId);
        return ResponseEntity.ok().build();
    }

    private boolean isAdminCaller(HttpServletRequest request, String orgId) {
        String callerOrgId = tenantResolver.resolveOrganizationId(request).orElse(null);
        String callerRole = tenantResolver.resolveOrganizationRole(request).orElse(null);
        // Caller must be in the same org and have OWNER/ADMIN role
        return orgId.equals(callerOrgId) && callerRole != null && ADMIN_ROLES.contains(callerRole);
    }

    // Request DTOs
    /**
     * @param permissions optional per-resource level ({@code resourceId -> "DENY"|"READ"});
     *                    ids omitted from the map default to DENY.
     */
    public record SetRestrictionsRequest(String resourceType, List<String> restrictedResourceIds,
                                         Map<String, String> permissions) {}
    public record RestrictRequest(String resourceType, String resourceId) {}
}
