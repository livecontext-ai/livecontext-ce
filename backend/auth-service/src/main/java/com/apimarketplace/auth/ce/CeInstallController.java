package com.apimarketplace.auth.ce;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.common.web.AdminRoleGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CE install-state REST.
 *
 * <ul>
 *   <li>{@code GET /api/ce/status} - <b>public</b> (gateway-allowlisted in
 *       {@code GatewayConstants.PUBLIC_ENDPOINTS}) so the frontend guard can
 *       read the flag before the user logs in. No {@code adminUserId} is
 *       returned to avoid leaking internal ids to anonymous callers.</li>
 *   <li>{@code POST /api/ce/complete} - <b>admin-only</b>. Enforced via the
 *       {@code X-User-Roles} header from the gateway (auth-service's
 *       {@code SecurityConfig} is {@code anyRequest().permitAll()}, so
 *       Spring's {@code @PreAuthorize} would not work - the header-based
 *       {@link AdminRoleGuard} pattern from {@code BridgeAccessController} is
 *       the canonical one in this codebase).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ce")
public class CeInstallController {

    private static final Logger log = LoggerFactory.getLogger(CeInstallController.class);

    private final CeInstallStateService service;

    @Autowired(required = false)
    private AuditLogger auditLogger;

    public CeInstallController(CeInstallStateService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public ResponseEntity<CeStatusView> status() {
        return ResponseEntity.ok(service.getStatus());
    }

    @PostMapping("/complete")
    public ResponseEntity<?> complete(
            @RequestHeader(value = "X-User-ID", defaultValue = "") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            HttpServletRequest request) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        Long adminId = parseUserIdOrNull(userId);
        CeStatusView prev = service.getStatus();
        CeStatusView view = service.markBootstrapped(adminId);

        // Wizard completion implicitly closes the public-registration door.
        // Audit-log the close so operators can correlate first-bootstrap with
        // the security boundary change. Only on first call (state transition).
        if (auditLogger != null && !prev.bootstrapped() && view.bootstrapped()) {
            auditLogger.eventFromRequest(AuditEventTypes.CE_REGISTRATION_CLOSED, request)
                    .user(adminId).success()
                    .detail("reason", "wizard_complete")
                    .write();
        }
        return ResponseEntity.ok(view);
    }

    /**
     * {@code PUT /api/ce/registration { "open": boolean }} - admin-only.
     * Toggles whether {@code /api/auth/register} accepts new signups. Useful
     * after the wizard closes the door automatically (and the admin wants to
     * re-open temporarily to invite team members) or to defensively close
     * registration on an already-bootstrapped install.
     */
    @PutMapping("/registration")
    public ResponseEntity<?> setRegistration(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", defaultValue = "") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            HttpServletRequest request) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        Object openValue = body == null ? null : body.get("open");
        if (!(openValue instanceof Boolean open)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_request",
                    "message", "Body must contain boolean field 'open'"));
        }

        Long adminId = parseUserIdOrNull(userId);
        CeStatusView prev = service.getStatus();
        CeStatusView view = service.setRegistrationOpen(open);
        log.info("[CE] registration toggled to {} by admin {}", open, userId);

        // Audit-log only on actual state transition. No-op toggles (same value)
        // are silent to avoid log noise on idempotent calls.
        if (auditLogger != null && prev.registrationOpen() != view.registrationOpen()) {
            String eventType = view.registrationOpen()
                    ? AuditEventTypes.CE_REGISTRATION_OPENED
                    : AuditEventTypes.CE_REGISTRATION_CLOSED;
            auditLogger.eventFromRequest(eventType, request)
                    .user(adminId).success()
                    .detail("previous", prev.registrationOpen())
                    .detail("new", view.registrationOpen())
                    .write();
        }
        return ResponseEntity.ok(view);
    }

    /**
     * The gateway injects {@code X-User-ID} as a numeric auth-service user id
     * for cloud mode and as a string uuid for embedded mode in some flows. We
     * only want numeric ids for the audit column; anything unparseable is
     * stored as null. Never throws - this is audit-only metadata.
     */
    private Long parseUserIdOrNull(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            log.debug("X-User-ID not numeric ({}), storing null admin id", header);
            return null;
        }
    }
}
