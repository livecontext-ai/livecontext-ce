package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.OrganizationMemberQuotaLimit;
import com.apimarketplace.auth.service.MemberQuotaAdminService;
import com.apimarketplace.auth.service.MemberQuotaAdminService.Outcome;
import com.apimarketplace.auth.service.MemberQuotaAdminService.Result;
import com.apimarketplace.auth.web.dto.MemberQuotaDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PR11c - REST surface for OWNER/ADMIN-configurable per-member quota
 * caps. Backs the {@code MemberQuotaDialog.tsx} frontend component.
 *
 * <p>Authorization is enforced by {@link MemberQuotaAdminService}: the
 * actor must be OWNER or ADMIN of the path-param {@code orgId} (NOT
 * the active-workspace org via X-Organization-Role - managing caps on
 * other orgs should not require switching active workspace first).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /api/organizations/{orgId}/members/{userId}/quota}
 *       → 200 with cap row, 200 with empty body when no cap configured,
 *       403/404 per outcome.</li>
 *   <li>{@code PUT /api/organizations/{orgId}/members/{userId}/quota}
 *       → 200 with upserted row + ORG_QUOTA_CAP_SET audit event.</li>
 *   <li>{@code DELETE /api/organizations/{orgId}/members/{userId}/quota}
 *       → 204 (idempotent) + ORG_QUOTA_CAP_REMOVED audit event when
 *       a row was actually deleted.</li>
 *   <li>{@code GET /api/organizations/{orgId}/quotas} → 200 with array
 *       of cap rows for admin-panel listing.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}")
public class OrganizationMemberQuotaController {

    private final MemberQuotaAdminService adminService;

    public OrganizationMemberQuotaController(MemberQuotaAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/members/{userId}/quota")
    public ResponseEntity<?> get(@PathVariable UUID orgId,
                                  @PathVariable Long userId,
                                  @RequestHeader(value = "X-User-ID", required = false) Long actorUserId) {
        if (actorUserId == null) return ResponseEntity.status(401).body(error("UNAUTH", "Missing X-User-ID"));
        Result<Optional<OrganizationMemberQuotaLimit>> r = adminService.get(actorUserId, orgId, userId);
        if (!r.success()) return mapOutcome(r.outcome());
        return r.value()
                .map(row -> ResponseEntity.ok((Object) MemberQuotaDto.from(row)))
                .orElseGet(() -> ResponseEntity.ok((Object) Map.of()));
    }

    @PutMapping("/members/{userId}/quota")
    public ResponseEntity<?> upsert(@PathVariable UUID orgId,
                                     @PathVariable Long userId,
                                     @RequestHeader(value = "X-User-ID", required = false) Long actorUserId,
                                     @RequestBody UpsertRequest body) {
        if (actorUserId == null) return ResponseEntity.status(401).body(error("UNAUTH", "Missing X-User-ID"));
        if (body == null) return ResponseEntity.badRequest().body(error("BAD_BODY", "Request body required"));
        // Round-2 audit fix (SHOULD-FIX #2): reject all-null PUT to avoid
        // creating a useless row + emitting a noisy ORG_QUOTA_CAP_SET event
        // with all-null new values. Admin should DELETE the row instead.
        if (body.periodCredits == null && body.periodStorageBytes == null && body.periodLlmTokens == null) {
            return ResponseEntity.badRequest().body(error("EMPTY_CAP",
                    "At least one cap dimension must be set. Use DELETE to remove the cap row entirely."));
        }
        // Positive-only sanity (mirrors V199 CHECK constraints). NULL is
        // explicitly accepted = "clear cap on this dimension".
        if (body.periodCredits != null && body.periodCredits.signum() <= 0) {
            return ResponseEntity.badRequest().body(error("INVALID_CAP",
                    "periodCredits must be > 0 (use null to clear, DELETE to remove the row)"));
        }
        if (body.periodStorageBytes != null && body.periodStorageBytes <= 0) {
            return ResponseEntity.badRequest().body(error("INVALID_CAP", "periodStorageBytes must be > 0"));
        }
        if (body.periodLlmTokens != null && body.periodLlmTokens <= 0) {
            return ResponseEntity.badRequest().body(error("INVALID_CAP", "periodLlmTokens must be > 0"));
        }
        Result<OrganizationMemberQuotaLimit> r = adminService.upsert(
                actorUserId, orgId, userId,
                body.periodCredits, body.periodStorageBytes, body.periodLlmTokens);
        if (!r.success()) return mapOutcome(r.outcome());
        return ResponseEntity.ok(MemberQuotaDto.from(r.value()));
    }

    @DeleteMapping("/members/{userId}/quota")
    public ResponseEntity<?> remove(@PathVariable UUID orgId,
                                     @PathVariable Long userId,
                                     @RequestHeader(value = "X-User-ID", required = false) Long actorUserId) {
        if (actorUserId == null) return ResponseEntity.status(401).body(error("UNAUTH", "Missing X-User-ID"));
        Result<Integer> r = adminService.remove(actorUserId, orgId, userId);
        if (!r.success()) return mapOutcome(r.outcome());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/quotas")
    public ResponseEntity<?> list(@PathVariable UUID orgId,
                                   @RequestHeader(value = "X-User-ID", required = false) Long actorUserId) {
        if (actorUserId == null) return ResponseEntity.status(401).body(error("UNAUTH", "Missing X-User-ID"));
        Result<List<OrganizationMemberQuotaLimit>> r = adminService.list(actorUserId, orgId);
        if (!r.success()) return mapOutcome(r.outcome());
        return ResponseEntity.ok(r.value().stream().map(MemberQuotaDto::from).toList());
    }

    /** Maps service-layer Outcome → HTTP status. Keeps the controller dumb. */
    private ResponseEntity<Map<String, Object>> mapOutcome(Outcome outcome) {
        return switch (outcome) {
            case FORBIDDEN -> ResponseEntity.status(403).body(error("FORBIDDEN",
                    "Only OWNER or ADMIN of the target organization can manage quotas."));
            case ORG_NOT_FOUND -> ResponseEntity.status(404).body(error("ORG_NOT_FOUND",
                    "Organization not found or has been deleted."));
            case TARGET_NOT_MEMBER -> ResponseEntity.status(404).body(error("TARGET_NOT_MEMBER",
                    "Target user is not a member of this organization."));
            case CONFLICT_TARGET_IS_OWNER -> ResponseEntity.status(409).body(error("TARGET_IS_OWNER",
                    "Cannot set a quota on the organization's owner - caps are for members."));
            case OK -> ResponseEntity.ok().body(Map.of());
        };
    }

    private static Map<String, Object> error(String code, String message) {
        return Map.of("code", code, "message", message);
    }

    /** PUT body shape - partial-write semantics, all fields nullable. */
    public record UpsertRequest(
            BigDecimal periodCredits,
            Long periodStorageBytes,
            Long periodLlmTokens
    ) {}
}
