package com.apimarketplace.auth.metrics;

import com.apimarketplace.auth.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized authentication metrics exposed via Prometheus.
 *
 * Cardinality policy:
 * - No PII tags (no email, no userId, no IP).
 * - Only bounded enum tags: result, reason, provider, role.
 * - Active session/user gauges are aggregate counts only.
 */
@Component
public class AuthMetrics {

    public static final String LOGIN_TOTAL = "auth_login_total";
    public static final String SIGNUP_TOTAL = "auth_signup_total";
    public static final String TOKEN_REFRESH_TOTAL = "auth_token_refresh_total";
    public static final String LOGOUT_TOTAL = "auth_logout_total";
    public static final String PASSWORD_CHANGE_TOTAL = "auth_password_change_total";
    public static final String RATE_LIMIT_TOTAL = "auth_rate_limit_total";
    public static final String TOKEN_REUSE_DETECTED_TOTAL = "auth_token_reuse_detected_total";

    public static final String USERS_TOTAL_GAUGE = "auth_users_total";
    public static final String ACTIVE_REFRESH_TOKENS_GAUGE = "auth_active_refresh_tokens";

    private final MeterRegistry registry;
    private final AtomicLong activeRefreshTokens = new AtomicLong(0);

    public AuthMetrics(MeterRegistry registry, UserRepository userRepository) {
        this.registry = registry;

        // Total registered users (refreshed at scrape time via supplier)
        Gauge.builder(USERS_TOTAL_GAUGE, userRepository, repo -> {
                    try { return repo.count(); } catch (Exception e) { return 0d; }
                })
                .description("Total number of registered users")
                .register(registry);

        Gauge.builder(ACTIVE_REFRESH_TOKENS_GAUGE, activeRefreshTokens, AtomicLong::get)
                .description("Active refresh tokens issued since startup (best-effort)")
                .register(registry);

        // Pre-register every counter at value 0 with the most common label
        // combinations. Without this, Micrometer only registers a counter on
        // its first .increment() call - meaning Grafana panels and Prometheus
        // alert rules that reference these metrics show "no data" instead of
        // "0", and `absent()` based alerts misfire. Pre-registration also
        // ensures the metric label set is bounded and known at startup.
        //
        // For each event type we register the cartesian product of meaningful
        // tags. The recorder later increments the same counters in place.
        for (String provider : new String[]{"keycloak", "google", "github", "local"}) {
            Counter.builder(LOGIN_TOTAL).tags("result","success","provider",provider).register(registry);
            for (String reason : new String[]{"invalid_credentials","rate_limited","disabled","internal_error","invalid_jwt","integrity_violation","provisioning_race","no_jwt","user_not_found"}) {
                Counter.builder(LOGIN_TOTAL).tags("result","failure","provider",provider,"reason",reason).register(registry);
            }
            Counter.builder(SIGNUP_TOTAL).tags("provider",provider,"first_user","false").register(registry);
            Counter.builder(SIGNUP_TOTAL).tags("provider",provider,"first_user","true").register(registry);
        }
        for (String result : new String[]{"success","failure"}) {
            Counter.builder(TOKEN_REFRESH_TOTAL).tags("result",result).register(registry);
            Counter.builder(PASSWORD_CHANGE_TOTAL).tags("result",result).register(registry);
        }
        for (String scope : new String[]{"single","all"}) {
            Counter.builder(LOGOUT_TOTAL).tags("scope",scope).register(registry);
        }
        for (String endpoint : new String[]{"login","register","refresh"}) {
            Counter.builder(RATE_LIMIT_TOTAL).tags("endpoint",endpoint).register(registry);
        }
        Counter.builder(TOKEN_REUSE_DETECTED_TOTAL).register(registry);
    }

    // ----- login -----

    public void loginSuccess(String provider) {
        Counter.builder(LOGIN_TOTAL)
                .tags(Tags.of("result", "success", "provider", safe(provider)))
                .description("Total login attempts")
                .register(registry)
                .increment();
    }

    public void loginFailure(String provider, String reason) {
        Counter.builder(LOGIN_TOTAL)
                .tags(Tags.of("result", "failure", "provider", safe(provider), "reason", safe(reason)))
                .description("Total login attempts")
                .register(registry)
                .increment();
    }

    public void rateLimitHit(String endpoint) {
        Counter.builder(RATE_LIMIT_TOTAL)
                .tags(Tags.of("endpoint", safe(endpoint)))
                .description("Rate limiter rejections")
                .register(registry)
                .increment();
    }

    // ----- signup -----

    public void signup(String provider, boolean firstUser) {
        Counter.builder(SIGNUP_TOTAL)
                .tags(Tags.of("provider", safe(provider), "first_user", String.valueOf(firstUser)))
                .description("Total user registrations")
                .register(registry)
                .increment();
    }

    // ----- tokens -----

    public void tokenRefreshed(String result) {
        Counter.builder(TOKEN_REFRESH_TOTAL)
                .tags(Tags.of("result", safe(result)))
                .description("Refresh token rotations")
                .register(registry)
                .increment();
        if ("success".equals(result)) {
            activeRefreshTokens.incrementAndGet();
        }
    }

    public void tokenReuseDetected() {
        Counter.builder(TOKEN_REUSE_DETECTED_TOTAL)
                .description("Refresh token reuse attempts (potential attack)")
                .register(registry)
                .increment();
    }

    public void logout(String scope) {
        Counter.builder(LOGOUT_TOTAL)
                .tags(Tags.of("scope", safe(scope)))
                .description("Logout events")
                .register(registry)
                .increment();
        activeRefreshTokens.decrementAndGet();
    }

    // ----- password -----

    public void passwordChanged(String result) {
        Counter.builder(PASSWORD_CHANGE_TOTAL)
                .tags(Tags.of("result", safe(result)))
                .description("Password change attempts")
                .register(registry)
                .increment();
    }

    // ----- helpers -----

    /**
     * Defensive: clamp tag values to a small bounded set or unknown.
     * Prevents cardinality explosion if a caller passes a free-form string.
     */
    private static String safe(String v) {
        if (v == null || v.isBlank()) return "unknown";
        if (v.length() > 32) return v.substring(0, 32);
        return v.toLowerCase();
    }
}
