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

    /**
     * Product-analytics emitter (PostHog). Optional so hand-built test instances and
     * analytics-less deployments are untouched; a null field emits nothing.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics;

    /** Bounded enum-like provider tag. Never returns null or free-form strings. */
    public String providerTag(AuthProvider p) {
        if (p == null) return "keycloak";
        return switch (p) {
            case GOOGLE -> "google";
            case GITHUB -> "github";
            case LOCAL -> "local";
            case SAML -> "saml";
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
            if (analytics != null) analytics.loginSucceeded(userId, providerTag);
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
            if (analytics != null) {
                analytics.registered(userId, providerTag, firstUser);
                analytics.loginSucceeded(userId, providerTag);
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

    /**
     * A trusted token could not date its own authentication, so a sign-in can no longer be
     * told apart from a token refresh for that provider. {@code reason} says which of the
     * two causes it was (see {@code AuthMetrics.AUTH_TIME_ABSENT} / {@code AUTH_TIME_FUTURE}).
     *
     * <p>Metric only, no audit row: nothing happened to the account, and writing an audit
     * event per request would drown the trail it is meant to keep readable.
     */
    public void recordAuthTimeClaimMissing(String providerTag, String reason) {
        try {
            if (authMetrics != null) authMetrics.authTimeClaimMissing(providerTag, reason);
        } catch (Exception ignored) {}
    }

    /**
     * A login attempt refused by the rate limiter.
     *
     * <p><b>Call this once per lockout, not once per refused request.</b> Once the limiter
     * is armed, every further attempt is refused for the rest of the window, and an
     * attacker chooses that rate; signing an audit row for each of them is write
     * amplification on the very path meant to contain abuse. It is the same argument
     * {@link #recordAuthTimeClaimMissing} is metric-only for, and it applies here too: the
     * trail has to stay readable to be worth keeping. The caller owns that suppression
     * (see {@code PasswordAuthService.loginAttempts} / {@code rateLimitAudited}).
     *
     * <p>Counted as BOTH a rate-limit hit and a login failure, because the two answer
     * different questions ("is the limiter working" and "what share of sign-ins fail"),
     * and both dashboards existed before this method did. What was missing is the audit
     * row: {@code AuditEventTypes.LOGIN_RATE_LIMITED} had been declared and never written
     * by anybody, so a burst of refusals left no trail at all.
     *
     * <p><b>What the row can and cannot say.</b> It is keyed by SOURCE, not by account: the
     * limiter fires before any user lookup, so no user id exists to attach, and the
     * identifying detail is the pseudonymised IP and user-agent that {@code eventFromRequest}
     * supplies. Reading this trail therefore answers "repeated refusals from one source",
     * not "against one account". Attaching the attempted address would answer the second
     * question and is deliberately not done: it would write an unverified, attacker-chosen
     * e-mail into the audit log on every refused attempt, which turns the trail into a place
     * to plant strings and, for a real address, records a non-event about someone who never
     * tried to sign in.
     */
    public void recordLoginRateLimited(String providerTag) {
        try {
            if (authMetrics != null) {
                authMetrics.rateLimitHit("login");
                authMetrics.loginFailure(providerTag, "rate_limited");
            }
            if (auditLogger != null) {
                builder(AuditEventTypes.LOGIN_RATE_LIMITED)
                        .warn()
                        .failure("rate_limited")
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
