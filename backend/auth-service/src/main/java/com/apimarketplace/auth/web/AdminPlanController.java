package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.AdminPlanService;
import com.apimarketplace.auth.service.AdminPlanService.AssignPlanResult;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Cloud-only admin endpoint to grant a user a complimentary subscription plan tier
 * (FREE/STARTER/PRO/TEAM). Sibling of {@link AdminCreditController}; both back the admin
 * "Grant Credits / Assign Plan" settings page.
 *
 * <p><b>URL prefix:</b> mounted under {@code /api/admin/credits} (same base as
 * {@link AdminCreditController}) so it reuses the existing {@code auth-admin-credits}
 * gateway route - requests go through the full gateway authentication + HMAC filter and
 * are NOT covered by the {@code /api/credits/} public-path exception. Endpoint:
 * {@code POST /api/admin/credits/plan}.
 *
 * <p>The actual mutation lives in {@link AdminPlanService#assignPlan}: it changes ONLY the
 * subscription's plan tier (capabilities + storage quota) and grants the standard 5K base
 * credits - never the plan's larger allowance. It refuses to clobber an active Stripe
 * subscription. See that service for the full contract.
 *
 * <p>Every call is written to the audit log ({@link AuditEventTypes#PLAN_GRANTED}) with the
 * admin's user ID, target user ID, requested plan, and IP/UA hashes, on success and failure.
 */
@RestController
@RequestMapping("/api/admin/credits")
public class AdminPlanController {

    private static final Logger log = LoggerFactory.getLogger(AdminPlanController.class);

    private final AdminPlanService adminPlanService;
    private final UserRepository userRepository;
    private final AuditLogger auditLogger;
    private final boolean unlimited;

    public AdminPlanController(
            AdminPlanService adminPlanService,
            UserRepository userRepository,
            AuditLogger auditLogger,
            @Value("${credit.unlimited:false}") boolean unlimited
    ) {
        this.adminPlanService = adminPlanService;
        this.userRepository = userRepository;
        this.auditLogger = auditLogger;
        this.unlimited = unlimited;
    }

    /**
     * Assign a comp plan to a target user. Admin-only, cloud-only.
     *
     * <p>The target is identified by EXACTLY ONE of {@code target_user_id} OR
     * {@code target_email}. {@code plan_code} must be one of FREE/STARTER/PRO/TEAM
     * (case-insensitive); FREE reverts a comp account to free.
     *
     * <p>Responses:
     * <ul>
     *   <li>{@code 200 OK} - assigned; returns previous + new plan codes</li>
     *   <li>{@code 400 Bad Request} - missing/ambiguous target, missing/unsupported plan</li>
     *   <li>{@code 403 Forbidden} - caller lacks the ADMIN role</li>
     *   <li>{@code 404 Not Found} - target email/user doesn't exist</li>
     *   <li>{@code 409 Conflict} - target has an active PAID (Stripe) subscription
     *       (manage via Stripe instead)</li>
     *   <li>{@code 503 Service Unavailable} - running in CE/unlimited mode</li>
     * </ul>
     */
    @PostMapping("/plan")
    public ResponseEntity<Map<String, Object>> assignPlan(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @RequestBody(required = false) AdminAssignPlanRequest request,
            HttpServletRequest httpRequest
    ) {
        // Layer 1: ADMIN role check (gateway-injected header).
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) {
            AuditLogger.Builder denyEvent = auditLogger.event(AuditEventTypes.PLAN_GRANTED)
                    .user(adminUserId)
                    .ip(extractClientIp(httpRequest))
                    .userAgent(httpRequest.getHeader("User-Agent"))
                    .warn()
                    .failure("forbidden_non_admin")
                    .detail("caller_roles", roles == null ? "" : roles);
            if (request != null) {
                if (request.targetUserId() != null) {
                    denyEvent.detail("attempted_target_user_id", request.targetUserId());
                }
                if (request.targetEmail() != null && !request.targetEmail().isBlank()) {
                    denyEvent.detail("attempted_target_email", truncate(request.targetEmail().trim(), 128));
                }
                if (request.planCode() != null) {
                    denyEvent.detail("attempted_plan", truncate(request.planCode().trim(), 64));
                }
            }
            denyEvent.write();
            log.warn("Unauthorized plan grant attempt: caller userId={} roles='{}'", adminUserId, roles);
            return denied;
        }

        // Layer 2: cloud-only gate. Plan grants make no sense in CE (unlimited credits, no tiers).
        if (unlimited) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "not_available_in_ce",
                    "message", "Plan grants are disabled in Community Edition (credit.unlimited=true)"
            ));
        }

        // Layer 3: input validation - exactly-one-of {user_id, email} + a plan code.
        Map<String, Object> validationError = validate(request);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationError);
        }

        // Layer 4: resolve target user (by ID or email -> ID).
        Long resolvedUserId;
        String resolvedEmail;
        if (request.targetUserId() != null) {
            resolvedUserId = request.targetUserId();
            resolvedEmail = null;
        } else {
            Optional<User> userOpt = userRepository.findByEmail(request.targetEmail().trim());
            if (userOpt.isEmpty()) {
                log.warn("Admin {} attempted plan grant to non-existent email", adminUserId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "user_not_found",
                        "message", "No user with that email address"
                ));
            }
            resolvedUserId = userOpt.get().getId();
            resolvedEmail = userOpt.get().getEmail();
        }

        AssignPlanResult result = adminPlanService.assignPlan(
                resolvedUserId, request.planCode(), adminUserId);

        return writeAuditAndRespond(result, request, adminUserId, resolvedUserId, resolvedEmail, httpRequest);
    }

    // ─────────────────────── private helpers ───────────────────────

    private Map<String, Object> validate(AdminAssignPlanRequest request) {
        if (request == null) {
            return Map.of("error", "missing_body", "message", "Request body is required");
        }

        boolean hasId = request.targetUserId() != null;
        boolean hasEmail = request.targetEmail() != null && !request.targetEmail().isBlank();
        if (hasId && hasEmail) {
            return Map.of("error", "ambiguous_target",
                    "message", "Provide either target_user_id or target_email, not both");
        }
        if (!hasId && !hasEmail) {
            return Map.of("error", "missing_target",
                    "message", "Either target_user_id or target_email is required");
        }
        if (hasId && request.targetUserId() <= 0) {
            return Map.of("error", "invalid_target_user_id",
                    "message", "target_user_id must be a positive integer");
        }
        if (hasEmail && !request.targetEmail().contains("@")) {
            return Map.of("error", "invalid_target_email",
                    "message", "target_email must be a valid email address");
        }

        if (request.planCode() == null || request.planCode().isBlank()) {
            return Map.of("error", "missing_plan", "message", "plan_code is required");
        }
        String normalized = request.planCode().trim().toUpperCase();
        if (!AdminPlanService.ALLOWED_PLAN_CODES.contains(normalized)) {
            return Map.of("error", "unsupported_plan",
                    "message", "plan_code must be one of FREE, STARTER, PRO, TEAM");
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> writeAuditAndRespond(
            AssignPlanResult result,
            AdminAssignPlanRequest request,
            Long adminUserId,
            Long resolvedUserId,
            String resolvedEmail,
            HttpServletRequest httpRequest
    ) {
        String requestedPlan = request.planCode().trim().toUpperCase();
        AuditLogger.Builder auditEvent = auditLogger.event(AuditEventTypes.PLAN_GRANTED)
                .user(adminUserId)
                .ip(extractClientIp(httpRequest))
                .userAgent(httpRequest.getHeader("User-Agent"))
                .detail("target_user_id", resolvedUserId)
                .detail("requested_plan", requestedPlan)
                .detail("target_lookup", resolvedEmail != null ? "email:" + resolvedEmail : "id");

        if (result.success()) {
            auditEvent.detail("previous_plan", result.previousPlanCode() == null ? "" : result.previousPlanCode())
                    .success().write();
            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("target_user_id", resolvedUserId);
            if (resolvedEmail != null) {
                body.put("target_email", resolvedEmail);
            }
            body.put("plan_code", result.newPlanCode());
            body.put("previous_plan_code", result.previousPlanCode());
            log.info("Admin {} assigned plan {} (was {}) to user {} ({})",
                    adminUserId, result.newPlanCode(), result.previousPlanCode(), resolvedUserId,
                    resolvedEmail != null ? resolvedEmail : "by id");
            return ResponseEntity.ok(body);
        }

        String reason = result.error() != null ? result.error() : "unknown";
        auditEvent.failure(reason).write();
        log.warn("Admin {} FAILED to assign plan {} to user {}: {}",
                adminUserId, requestedPlan, resolvedUserId, reason);

        // "has_paid_subscription" is a conflict with Stripe state, not a not-found.
        HttpStatus status = switch (reason) {
            case "has_paid_subscription" -> HttpStatus.CONFLICT;
            case "user_not_found" -> HttpStatus.NOT_FOUND;
            case "unsupported_plan", "plan_not_found", "missing_target" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return ResponseEntity.status(status).body(Map.of(
                "error", reason,
                "message", messageFor(reason)
        ));
    }

    private static String messageFor(String reason) {
        return switch (reason) {
            case "has_paid_subscription" ->
                    "User has an active paid subscription - change it through Stripe, not a comp grant";
            case "user_not_found" -> "Target user not found";
            case "unsupported_plan" -> "plan_code must be one of FREE, STARTER, PRO, TEAM";
            case "plan_not_found" -> "Requested plan is not configured on this deployment";
            default -> "Plan assignment failed: " + reason;
        };
    }

    private static String extractClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) return real.trim();
        return req.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Request DTO. The target is identified by EXACTLY ONE of {@code target_user_id} or
     * {@code target_email}. {@code plan_code} is one of FREE/STARTER/PRO/TEAM.
     */
    public record AdminAssignPlanRequest(
            @JsonProperty("target_user_id") Long targetUserId,
            @JsonProperty("target_email") String targetEmail,
            @JsonProperty("plan_code") String planCode
    ) {}
}
