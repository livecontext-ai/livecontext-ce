package com.apimarketplace.auth.audit;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.metrics.AuthMetrics;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Single source of truth for emitting authentication metrics + audit events.
 *
 * Before this class existed, every service (UserResolutionService,
 * OAuthUserProcessor, EmbeddedAuthController, PasswordAuthService) duplicated the same
 * triplet of helper methods (~135 lines of copy-paste). Worse, OAuthUserProcessor
 * had no AuditLogger injected at all, so OAuth audit events were silently dropped.
 *
 * All callers go through this recorder; both AuthMetrics and AuditLogger are
 * autowired with required=false so unit tests that don't construct them still
 * compile and pass (the recorder no-ops).
 */
@Component
public class AuthEventRecorder {

    @Autowired(required = false)
    private AuthMetrics authMetrics;

    @Autowired(required = false)
    private AuditLogger auditLogger;

    /** Bounded enum-like provider tag. Never returns null or free-form strings. */
    public String providerTag(AuthProvider p) {
        if (p == null) return "keycloak";
        return switch (p) {
            case GOOGLE -> "google";
            case GITHUB -> "github";
            case LOCAL -> "local";
            default -> "keycloak";
        };
    }

    /** Best-effort fetch of the current servlet request (null outside MVC). */
    private HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        } catch (Exception ignored) {}
        return null;
    }

    private AuditLogger.Builder builder(String eventType) {
        HttpServletRequest req = currentRequest();
        return req != null
                ? auditLogger.eventFromRequest(eventType, req)
                : auditLogger.event(eventType);
    }

    /** Login success - increments counter + emits LOGIN_SUCCESS audit. */
    public void recordLoginSuccess(Long userId, String providerTag) {
        try {
            if (authMetrics != null) authMetrics.loginSuccess(providerTag);
            if (auditLogger != null) {
                builder(AuditEventTypes.LOGIN_SUCCESS)
                        .user(userId)
                        .success()
                        .detail("provider", providerTag)
                        .write();
            }
        } catch (Exception ignored) {}
    }

    /** New user signup followed by their first login. */
    public void recordSignupAndLogin(Long userId, String providerTag, boolean firstUser) {
        try {
            if (authMetrics != null) {
                authMetrics.signup(providerTag, firstUser);
                authMetrics.loginSuccess(providerTag);
            }
            if (auditLogger != null) {
                builder(AuditEventTypes.SIGNUP_SUCCESS)
                        .user(userId)
                        .success()
                        .detail("provider", providerTag)
                        .write();
                builder(AuditEventTypes.LOGIN_SUCCESS)
                        .user(userId)
                        .success()
                        .detail("provider", providerTag)
                        .write();
            }
        } catch (Exception ignored) {}
    }

    /** Login failure with a bounded reason code. */
    public void recordLoginFailure(String providerTag, String reason) {
        try {
            if (authMetrics != null) authMetrics.loginFailure(providerTag, reason);
            if (auditLogger != null) {
                builder(AuditEventTypes.LOGIN_FAILURE)
                        .warn()
                        .failure(reason)
                        .detail("provider", providerTag)
                        .write();
            }
        } catch (Exception ignored) {}
    }
}
