package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.CreditService;
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

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Cloud-only admin endpoint to grant credits to a user. Gated behind the platform ADMIN role
 * AND the cloud deployment mode (disabled when {@code credit.unlimited=true}, i.e. in CE).
 *
 * <p>This is the ONLY supported way to manually adjust a user's credit balance: it calls the
 * canonical {@link CreditService#grantCredits} which locks the subscription, updates
 * {@code auth.subscription.remaining_credits}, and inserts a {@code MANUAL_ADJUSTMENT} row in
 * {@code auth.credit_ledger} - all inside a single transaction. Never run
 * {@code UPDATE auth.subscription} or {@code INSERT INTO auth.credit_ledger} by hand in psql;
 * doing so will desync the two tables (the ledger is history, the subscription is truth) and
 * the balance shown to the user will not match the ledger - that exact regression happened on
 * 2026-04-09 and led to this endpoint.
 *
 * <p>Every call is written to the audit log ({@link AuditEventTypes#CREDIT_GRANTED}) with the
 * admin's user ID, target user ID, amount, and IP/UA hashes, regardless of success or failure.
 */
/**
 * NOTE on URL prefix: this controller lives under {@code /api/admin/credits} on purpose,
 * NOT under {@code /api/credits/admin}. The {@code /api/credits/} prefix is listed in
 * {@code auth-service/application.yml gateway.filter.public-paths}, which means it bypasses
 * {@link com.apimarketplace.common.web.GatewayAuthenticationFilter}. Any endpoint under
 * {@code /api/credits/*} can be reached without a valid {@code X-Gateway-Secret} header, so
 * an attacker with network access to auth-service:8083 could forge {@code X-User-Roles:ADMIN}
 * and bypass the role check. Moving the admin endpoint to {@code /api/admin/credits} puts it
 * back under gateway verification (the {@code /api/admin/} prefix is NOT in public-paths).
 */
@RestController
@RequestMapping("/api/admin/credits")
public class AdminCreditController {

    private static final Logger log = LoggerFactory.getLogger(AdminCreditController.class);

    /**
     * Hard cap to prevent fat-finger mistakes. An admin who genuinely needs to grant more than
     * this in a single call can split the request - a second grant is a second audit entry,
     * which is a feature, not a bug.
     */
    private static final BigDecimal MAX_GRANT_AMOUNT = new BigDecimal("1000000");

    private static final String SOURCE_TYPE = "MANUAL_ADJUSTMENT";
    private static final int DESCRIPTION_MAX_LENGTH = 500;

    private final CreditService creditService;
    private final UserRepository userRepository;
    private final AuditLogger auditLogger;
    private final boolean unlimited;

    public AdminCreditController(
            CreditService creditService,
            UserRepository userRepository,
            AuditLogger auditLogger,
            @Value("${credit.unlimited:false}") boolean unlimited
    ) {
        this.creditService = creditService;
        this.userRepository = userRepository;
        this.auditLogger = auditLogger;
        this.unlimited = unlimited;
    }

    /**
     * Grant credits to a target user. Admin-only, cloud-only.
     *
     * <p>Endpoint: {@code POST /api/admin/credits/grant}. The {@code /api/admin/} prefix is
     * intentional so the call is NOT covered by the {@code /api/credits/} public-path
     * exception - see the class Javadoc.
     *
     * <p>The target can be identified by EITHER {@code target_user_id} OR {@code target_email}
     * - exactly one must be present. Email is the common case for admin operators who don't
     * have internal user IDs memorized; it's resolved to a user ID via
     * {@link UserRepository#findByEmail(String)} and then fed through the same code path.
     *
     * <p>Request body (ID form):
     * <pre>{@code
     * { "target_user_id": 42, "amount": "500", "description": "..." }
     * }</pre>
     *
     * <p>Request body (email form - recommended):
     * <pre>{@code
     * { "target_email": "alice@example.com", "amount": "500", "description": "..." }
     * }</pre>
     *
     * <p>Responses:
     * <ul>
     *   <li>{@code 200 OK} - grant succeeded, returns new balance + resolved user ID</li>
     *   <li>{@code 400 Bad Request} - missing fields, both or neither of {user_id/email},
     *       non-positive amount, or amount > cap</li>
     *   <li>{@code 403 Forbidden} - caller lacks the ADMIN role</li>
     *   <li>{@code 404 Not Found} - target email/user doesn't exist, OR target has no active
     *       subscription</li>
     *   <li>{@code 503 Service Unavailable} - running in CE/unlimited mode</li>
     * </ul>
     */
    @PostMapping("/grant")
    public ResponseEntity<Map<String, Object>> grantCredits(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @RequestBody AdminGrantRequest request,
            HttpServletRequest httpRequest
    ) {
        // Layer 1: ADMIN role check (gateway-injected header).
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) {
            // Always audit denied admin attempts - critical for detecting brute-force
            // admin-bypass probes in prod. The Javadoc promises auditing "regardless of
            // success or failure"; this closes that loop for the 403 path specifically.
            // Capture the attempted target (id or email) so ops can correlate probes
            // across attempts even if the caller is spraying random accounts.
            AuditLogger.Builder denyEvent = auditLogger.event(AuditEventTypes.CREDIT_GRANTED)
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
                            truncate(request.targetEmail().trim(), 128));
                }
                if (request.amount() != null) {
                    denyEvent.detail("attempted_amount", request.amount().toPlainString());
                }
            }
            denyEvent.write();
            log.warn("Unauthorized credit grant attempt: caller userId={} roles='{}'",
                    adminUserId, roles);
            return denied;
        }

        // Layer 2: cloud-only gate. In CE, CreditService.grantCredits is a no-op - we prefer
        // to fail loudly so admins realize the feature doesn't apply.
        if (unlimited) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "error", "not_available_in_ce",
                    "message", "Credit grants are disabled in Community Edition (credit.unlimited=true)"
            ));
        }

        // Layer 3: input validation - amount bounds + exactly-one-of {user_id, email}.
        Map<String, Object> validationError = validate(request);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(validationError);
        }

        // Layer 4: resolve target user (by ID or email → ID).
        Long resolvedUserId;
        String resolvedEmail;
        if (request.targetUserId() != null) {
            resolvedUserId = request.targetUserId();
            resolvedEmail = null; // not fetched to avoid an unnecessary DB hit
        } else {
            // Email path - look up the user first so we can fail fast with 404 before touching
            // the subscription table.
            Optional<User> userOpt = userRepository.findByEmail(request.targetEmail().trim());
            if (userOpt.isEmpty()) {
                log.warn("Admin {} attempted credit grant to non-existent email", adminUserId);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "error", "user_not_found",
                        "message", "No user with that email address"
                ));
            }
            resolvedUserId = userOpt.get().getId();
            resolvedEmail = userOpt.get().getEmail();
        }

        // Description: use the caller's if provided, else a default citing the admin user ID.
        String description = (request.description() != null && !request.description().isBlank())
                ? truncate(request.description().trim(), DESCRIPTION_MAX_LENGTH)
                : "Admin grant by user " + adminUserId;

        // Unique source_id so an accidental double-submit is caught by the ledger's UNIQUE
        // index on source_id instead of double-crediting.
        String sourceId = "admin-grant-" + UUID.randomUUID();

        CreditService.CreditConsumeResult result = creditService.grantCredits(
                resolvedUserId,
                request.amount(),
                SOURCE_TYPE,
                sourceId,
                description
        );

        return writeAuditAndRespond(result, request, adminUserId, resolvedUserId,
                resolvedEmail, sourceId, description, httpRequest);
    }

    // ─────────────────────── private helpers ───────────────────────

    private Map<String, Object> validate(AdminGrantRequest request) {
        if (request == null) {
            return Map.of("error", "missing_body", "message", "Request body is required");
        }

        // Exactly one of {target_user_id, target_email} must be provided. Passing both is
        // ambiguous and probably a bug in the caller.
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

        // Shallow email shape check - final validity is decided by the DB lookup. This is just
        // a fat-finger guard so "alice" or "alice@" returns 400 instead of a generic 404.
        if (hasEmail && !request.targetEmail().contains("@")) {
            return Map.of("error", "invalid_target_email",
                    "message", "target_email must be a valid email address");
        }

        if (request.amount() == null) {
            return Map.of("error", "invalid_amount", "message", "amount is required");
        }
        if (request.amount().signum() <= 0) {
            return Map.of("error", "invalid_amount",
                    "message", "amount must be strictly positive (use a separate deduct endpoint for removal)");
        }
        if (request.amount().compareTo(MAX_GRANT_AMOUNT) > 0) {
            return Map.of("error", "amount_exceeds_cap",
                    "message", "amount exceeds the per-call cap of " + MAX_GRANT_AMOUNT
                            + " - split the grant into smaller calls");
        }
        return null;
    }

    private ResponseEntity<Map<String, Object>> writeAuditAndRespond(
            CreditService.CreditConsumeResult result,
            AdminGrantRequest request,
            Long adminUserId,
            Long resolvedUserId,
            String resolvedEmail,
            String sourceId,
            String description,
            HttpServletRequest httpRequest
    ) {
        String clientIp = extractClientIp(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        AuditLogger.Builder auditEvent = auditLogger.event(AuditEventTypes.CREDIT_GRANTED)
                .user(adminUserId)
                .ip(clientIp)
                .userAgent(userAgent)
                .detail("target_user_id", resolvedUserId)
                .detail("amount", request.amount().toPlainString())
                .detail("source_id", sourceId)
                .detail("description", description)
                // Keep track of WHICH identifier the admin used - if they passed email, record
                // the resolved email too (it's the one the admin actually sees), otherwise
                // flag the request as ID-based so the audit trail is self-explanatory.
                .detail("target_lookup",
                        resolvedEmail != null ? "email:" + resolvedEmail : "id");

        if (result.success()) {
            auditEvent.success().write();
            Map<String, Object> body = new HashMap<>();
            body.put("success", true);
            body.put("target_user_id", resolvedUserId);
            if (resolvedEmail != null) {
                body.put("target_email", resolvedEmail);
            }
            body.put("amount_granted", request.amount());
            body.put("new_balance", result.remainingCredits());
            body.put("source_id", sourceId);
            log.info("Admin {} granted {} credits to user {} ({}) (source_id={}, new_balance={})",
                    adminUserId, request.amount(), resolvedUserId,
                    resolvedEmail != null ? resolvedEmail : "by id", sourceId,
                    result.remainingCredits());
            return ResponseEntity.ok(body);
        }

        // Failure: most commonly "no active subscription" on the target user.
        String reason = result.error() != null ? result.error() : "unknown";
        auditEvent.failure(reason).write();
        log.warn("Admin {} FAILED to grant {} credits to user {}: {}",
                adminUserId, request.amount(), resolvedUserId, reason);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "grant_failed",
                "message", reason
        ));
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
     * {@code target_email} - passing both or neither returns 400. Description is optional -
     * defaults to {@code "Admin grant by user <adminId>"}.
     */
    public record AdminGrantRequest(
            @JsonProperty("target_user_id") Long targetUserId,
            @JsonProperty("target_email") String targetEmail,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("description") String description
    ) {}
}
