package com.apimarketplace.auth.web;

import com.apimarketplace.auth.ce.CeInstallStateService;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.service.OrganizationMemberService;
import com.apimarketplace.auth.service.OrganizationMemberService.InvitationInfo;
import com.apimarketplace.auth.service.PasswordAuthService;
import com.apimarketplace.auth.service.PasswordAuthService.TokenPair;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for embedded email+password authentication (CE mode).
 * Provides registration, login, token refresh, and logout endpoints.
 *
 * Activated by: auth.mode=embedded
 *
 * Endpoints:
 *   POST /api/auth/register  - Create account (email + password)
 *   POST /api/auth/login     - Authenticate and get tokens
 *   POST /api/auth/refresh   - Refresh access token
 *   POST /api/auth/logout    - Revoke refresh token
 *   GET  /api/auth/openid-configuration - OIDC discovery (minimal)
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(name = "auth.mode", havingValue = "embedded")
@CrossOrigin(origins = "*")
public class EmbeddedAuthController {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedAuthController.class);

    private final PasswordAuthService passwordAuthService;
    private final CeInstallStateService installStateService;
    private final OrganizationMemberService organizationMemberService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.audit.AuditLogger auditLogger;

    @Value("${auth.jwt.issuer:livecontext}")
    private String issuer;

    @Value("${server.port:8083}")
    private int serverPort;

    public EmbeddedAuthController(PasswordAuthService passwordAuthService,
                                  CeInstallStateService installStateService,
                                  OrganizationMemberService organizationMemberService) {
        this.passwordAuthService = passwordAuthService;
        this.installStateService = installStateService;
        this.organizationMemberService = organizationMemberService;
    }

    /**
     * POST /api/auth/register
     * Body: { "email": "...", "password": "...", "firstName": "...", "lastName": "..." }
     */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        String email = body.get("email");
        String invitationToken = body.get("invitationToken");

        // Invite-by-link bypass: a brand-new invitee can register through a CE
        // admin's invite link even when public registration is closed - but ONLY
        // when the token resolves to a PENDING, non-expired invitation AND the
        // submitted email matches the invitation email. A bogus / expired /
        // mismatched token must NEVER open the closed door, so we fall through to
        // the normal gate below in that case.
        boolean invitedBypass = false;
        if (invitationToken != null && !invitationToken.isBlank() && email != null) {
            InvitationInfo info = organizationMemberService.getInvitationInfo(invitationToken);
            invitedBypass = info.valid()
                    && info.email() != null
                    && info.email().equalsIgnoreCase(email.trim());
        }

        // Door check: an admin may have closed public registration after wizard
        // completion. Cloud doesn't reach this code path (controller is gated on
        // auth.mode=embedded), so this only fires in CE. A valid matching invite
        // link is the only way past a closed door.
        if (!invitedBypass && !installStateService.isRegistrationOpen()) {
            logger.warn("Registration attempt rejected: door closed (CE install bootstrapped)");
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "registration_closed");
            err.put("message", "Public registration is closed on this installation. Contact the administrator.");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(err);
        }
        try {
            String password = body.get("password");
            String firstName = body.get("firstName");
            String lastName = body.get("lastName");

            User user = passwordAuthService.register(email, password, firstName, lastName);

            // Auto-accept the invitation so the new user joins the org with the
            // invited role. acceptInvitation re-validates token state + email
            // match (defence in depth) and is a no-op for an already-resolved
            // token. Best-effort: a join hiccup must not fail the registration
            // the user just completed - they can still accept via the inbox.
            if (invitedBypass) {
                try {
                    organizationMemberService.acceptInvitation(invitationToken, user.getId());
                    logger.info("Invite-link registration auto-joined user {} via invitation token", user.getId());
                } catch (Exception acceptEx) {
                    logger.warn("Invite-link auto-accept failed for new user {}: {}",
                            user.getId(), acceptEx.getMessage());
                }
            }

            TokenPair tokens = passwordAuthService.generateTokenPair(
                    user, request.getHeader("User-Agent"), getClientIp(request));

            Map<String, Object> response = buildTokenResponse(user, tokens);
            logger.info("User registered: id={}, email={}", user.getId(), user.getEmail());
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.SIGNUP_SUCCESS, request)
                        .user(user.getId()).success().detail("provider", "local").write();
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(response);

        } catch (IllegalArgumentException e) {
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.SIGNUP_FAILURE, request)
                        .warn().failure("invalid_input").write();
            }
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("Registration failed", e);
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.SIGNUP_FAILURE, request)
                        .warn().failure("internal_error").write();
            }
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Registration failed");
        }
    }

    /**
     * POST /api/auth/login
     * Body: { "email": "...", "password": "..." }
     */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        try {
            String email = body.get("email");
            String password = body.get("password");

            User user = passwordAuthService.login(email, password);
            TokenPair tokens = passwordAuthService.generateTokenPair(
                    user, request.getHeader("User-Agent"), getClientIp(request));

            Map<String, Object> response = buildTokenResponse(user, tokens);
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.LOGIN_SUCCESS, request)
                        .user(user.getId()).success().detail("provider", "local").write();
            }
            return ResponseEntity.ok(response);

        } catch (PasswordAuthService.AuthenticationException e) {
            if (auditLogger != null) {
                String reason = e.getMessage() != null && e.getMessage().contains("rate") ? "rate_limited" : "invalid_credentials";
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.LOGIN_FAILURE, request)
                        .warn().failure(reason).detail("provider", "local").write();
            }
            return errorResponse(HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (Exception e) {
            logger.error("Login failed", e);
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.LOGIN_FAILURE, request)
                        .warn().failure("internal_error").write();
            }
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Login failed");
        }
    }

    /**
     * POST /api/auth/refresh
     * Body: { "refreshToken": "..." }
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @RequestBody Map<String, String> body,
            HttpServletRequest request) {
        try {
            String refreshToken = body.get("refreshToken");

            TokenPair tokens = passwordAuthService.refresh(
                    refreshToken, request.getHeader("User-Agent"), getClientIp(request));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("accessToken", tokens.accessToken());
            response.put("refreshToken", tokens.refreshToken());
            response.put("expiresIn", tokens.accessTokenExpiresInSeconds());
            response.put("refreshExpiresIn", tokens.refreshTokenExpiresInSeconds());
            response.put("tokenType", "Bearer");
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.TOKEN_REFRESHED, request)
                        .success().write();
            }
            return ResponseEntity.ok(response);

        } catch (PasswordAuthService.AuthenticationException e) {
            if (auditLogger != null) {
                String type = e.getMessage() != null && e.getMessage().toLowerCase().contains("revoked")
                        ? com.apimarketplace.auth.audit.AuditEventTypes.TOKEN_REUSE_DETECTED
                        : com.apimarketplace.auth.audit.AuditEventTypes.TOKEN_REFRESHED;
                auditLogger.eventFromRequest(type, request).critical().failure("invalid_token").write();
            }
            return errorResponse(HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (Exception e) {
            logger.error("Token refresh failed", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Token refresh failed");
        }
    }

    /**
     * POST /api/auth/logout
     * Body: { "refreshToken": "..." }
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(@RequestBody Map<String, String> body,
                                                      HttpServletRequest request) {
        try {
            String refreshToken = body.get("refreshToken");
            passwordAuthService.logout(refreshToken);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.LOGOUT, request)
                        .success().write();
            }
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Logout failed", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Logout failed");
        }
    }

    /**
     * POST /api/auth/change-password
     * Body: { "currentPassword": "...", "newPassword": "..." }
     * Requires X-User-ID header (gateway-injected).
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestBody Map<String, String> body,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            HttpServletRequest request) {
        try {
            if (userIdHeader == null || userIdHeader.isBlank()) {
                return errorResponse(HttpStatus.UNAUTHORIZED, "Authentication required");
            }

            Long userId = Long.parseLong(userIdHeader);
            String currentPassword = body.get("currentPassword");
            String newPassword = body.get("newPassword");

            passwordAuthService.changePassword(userId, currentPassword, newPassword);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Password changed. Please login again.");
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.PASSWORD_CHANGED, request)
                        .user(userId).success().write();
            }
            return ResponseEntity.ok(response);

        } catch (PasswordAuthService.AuthenticationException e) {
            if (auditLogger != null) {
                auditLogger.eventFromRequest(com.apimarketplace.auth.audit.AuditEventTypes.PASSWORD_CHANGE_FAILED, request)
                        .warn().failure("invalid_current_password").write();
            }
            return errorResponse(HttpStatus.UNAUTHORIZED, e.getMessage());
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            logger.error("Password change failed", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Password change failed");
        }
    }

    /**
     * GET /api/auth/openid-configuration
     * Minimal OIDC discovery document for CE mode.
     */
    @GetMapping("/openid-configuration")
    public ResponseEntity<Map<String, Object>> openidConfiguration() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("issuer", issuer);
        config.put("jwks_uri", "/.well-known/jwks.json");
        config.put("authorization_endpoint", "/api/auth/login");
        config.put("token_endpoint", "/api/auth/login");
        config.put("userinfo_endpoint", "/api/me");
        config.put("end_session_endpoint", "/api/auth/logout");
        config.put("grant_types_supported", new String[]{"password", "refresh_token"});
        config.put("response_types_supported", new String[]{"token"});
        config.put("subject_types_supported", new String[]{"public"});
        config.put("id_token_signing_alg_values_supported", new String[]{"RS256"});
        config.put("token_endpoint_auth_methods_supported", new String[]{"none"});
        return ResponseEntity.ok(config);
    }

    private Map<String, Object> buildTokenResponse(User user, TokenPair tokens) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("accessToken", tokens.accessToken());
        response.put("refreshToken", tokens.refreshToken());
        response.put("expiresIn", tokens.accessTokenExpiresInSeconds());
        response.put("refreshExpiresIn", tokens.refreshTokenExpiresInSeconds());
        response.put("tokenType", "Bearer");

        Map<String, Object> userInfo = new LinkedHashMap<>();
        userInfo.put("id", user.getId());
        userInfo.put("email", user.getEmail());
        userInfo.put("username", user.getUsername());
        userInfo.put("firstName", user.getFirstName());
        userInfo.put("lastName", user.getLastName());
        userInfo.put("emailVerified", user.isEmailVerified());
        userInfo.put("roles", user.getRoles() != null ? user.getRoles() : Set.of("USER"));
        response.put("user", userInfo);

        return response;
    }

    private ResponseEntity<Map<String, Object>> errorResponse(HttpStatus status, String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("error", true);
        error.put("message", message);
        return ResponseEntity.status(status).body(error);
    }

    private String getClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
