package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.VerifiedAccountService;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Cloud-only admin endpoint that grants or revokes a user's verified badge - the blue
 * check shown next to their name on their profile, their marketplace listings, their
 * reviews and their messages.
 *
 * <p>Platform admins already carry the badge by virtue of their role and need no row
 * here (see {@link VerifiedAccountService}). This endpoint exists for everyone else:
 * the publishers and creators verified one at a time as the platform grows.
 *
 * <p>Every call is written to the audit log ({@link AuditEventTypes#ACCOUNT_VERIFIED})
 * with the admin's user id, the target, and the direction - including denied attempts,
 * which are the ones worth alerting on.
 *
 * <p>NOTE on URL prefix: {@code /api/admin/} is deliberately outside the
 * {@code gateway.filter.public-paths} of auth-service, so this endpoint is only
 * reachable through the gateway and the {@code X-User-Roles} header it injects cannot
 * be forged by anyone who can reach auth-service:8083 directly. This mirrors
 * {@link AdminCreditController} - read its class Javadoc before moving this mapping.
 */
@RestController
@RequestMapping("/api/admin/verified-accounts")
public class AdminVerifiedAccountController {

    private static final Logger log = LoggerFactory.getLogger(AdminVerifiedAccountController.class);
    private static final int EMAIL_LOG_MAX_LENGTH = 128;

    private final VerifiedAccountService verifiedAccountService;
    private final UserRepository userRepository;
    private final AuditLogger auditLogger;

    public AdminVerifiedAccountController(VerifiedAccountService verifiedAccountService,
                                          UserRepository userRepository,
                                          AuditLogger auditLogger) {
        this.verifiedAccountService = verifiedAccountService;
        this.userRepository = userRepository;
        this.auditLogger = auditLogger;
    }

    /**
     * Set the verified badge on a target user.
     *
     * <p>Endpoint: {@code POST /api/admin/verified-accounts/set}. The target is named by
     * EITHER {@code target_user_id} OR {@code target_email} - exactly one, since passing
     * both is ambiguous and probably a caller bug.
     *
     * <p>Request body:
     * <pre>{@code
     * { "target_email": "alice@example.com", "verified": true }
     * }</pre>
     *
     * <p>Responses:
     * <ul>
     *   <li>{@code 200 OK} - stored, returns the resolved state including
     *       {@code verifiedByRole} (true when the target is a platform admin, whose badge
     *       a revoke here cannot take away), {@code profileWithdrawn} (the target set
     *       their profile to PRIVATE, so the grant is stored but dormant) and
     *       {@code effectivelyVerified} (what the target's readers will actually see,
     *       after both of those)</li>
     *   <li>{@code 400 Bad Request} - missing body, both or neither target field, missing
     *       {@code verified}</li>
     *   <li>{@code 403 Forbidden} - caller lacks the ADMIN role</li>
     *   <li>{@code 404 Not Found} - no such user</li>
     *   <li>{@code 503 Service Unavailable} - self-hosted deployment, where the badge
     *       does not exist</li>
     * </ul>
     */
    @PostMapping("/set")
    public ResponseEntity<Map<String, Object>> setVerified(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @RequestBody(required = false) AdminVerifyRequest request,
            HttpServletRequest httpRequest) {

        // Layer 1: ADMIN role (gateway-injected header).
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) {
            AuditLogger.Builder denyEvent = auditLogger.event(AuditEventTypes.ACCOUNT_VERIFIED)
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
                    denyEvent.detail("attempted_target_email",
                            truncate(request.targetEmail().trim(), EMAIL_LOG_MAX_LENGTH));
                }
            }
            denyEvent.write();
            log.warn("Unauthorized verified-badge change attempt: caller userId={} roles='{}'",
                    adminUserId, roles);
            return denied;
        }

        // Layer 2: managed-cloud gate. Fail loudly rather than storing a flag no surface
        // would ever render on a self-hosted install.
        if (!verifiedAccountService.isFeatureEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "not_available_self_hosted",
                    "message", "Verified badges are a managed-cloud feature"));
        }

        // Layer 3: input validation.
        Map<String, Object> validationError = validate(request);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationError);
        }

        // Layer 4: resolve the target (by id, or by email -> id).
        Long targetUserId;
        if (request.targetUserId() != null) {
            targetUserId = request.targetUserId();
        } else {
            Optional<User> userOpt = userRepository.findByEmail(request.targetEmail().trim());
            if (userOpt.isEmpty()) {
                log.warn("Admin {} attempted to verify a non-existent email", adminUserId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "user_not_found",
                        "message", "No user with that email address"));
            }
            targetUserId = userOpt.get().getId();
        }

        boolean verified = Boolean.TRUE.equals(request.verified());
        Optional<VerifiedAccountService.VerificationState> stateOpt =
                verifiedAccountService.setManualVerified(targetUserId, verified, adminUserId);
        if (stateOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "user_not_found",
                    "message", "No user with that id"));
        }

        VerifiedAccountService.VerificationState state = stateOpt.get();
        auditLogger.event(AuditEventTypes.ACCOUNT_VERIFIED)
                .user(adminUserId)
                .ip(extractClientIp(httpRequest))
                .userAgent(httpRequest.getHeader("User-Agent"))
                .success()
                .detail("target_user_id", state.userId())
                .detail("verified", state.manuallyVerified())
                .detail("verified_by_role", state.verifiedByRole())
                .write();
        log.info("Admin {} set verified={} on user {}", adminUserId, verified, state.userId());

        Map<String, Object> body = new HashMap<>();
        body.put("userId", state.userId());
        body.put("email", state.email());
        body.put("verified", state.manuallyVerified());
        body.put("verifiedByRole", state.verifiedByRole());
        body.put("effectivelyVerified", state.effectivelyVerified());
        body.put("profileWithdrawn", state.profileWithdrawn());
        return ResponseEntity.ok(body);
    }

    // --------------------------- private helpers ---------------------------

    private static Map<String, Object> validate(AdminVerifyRequest request) {
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
        if (request.verified() == null) {
            // Not defaulted to true: "grant" and "revoke" are different enough that a
            // caller who omitted the field has not said which one they meant.
            return Map.of("error", "missing_verified",
                    "message", "verified is required (true to grant, false to revoke)");
        }
        return null;
    }

    private static String extractClientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        String real = req.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return req.getRemoteAddr();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Admin request body. Snake-case on the wire, matching the sibling admin endpoints. */
    public record AdminVerifyRequest(
            @JsonProperty("target_user_id") Long targetUserId,
            @JsonProperty("target_email") String targetEmail,
            @JsonProperty("verified") Boolean verified
    ) {}
}
