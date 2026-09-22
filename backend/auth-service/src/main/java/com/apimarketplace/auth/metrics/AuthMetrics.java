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
    /**
     * The {@code reason} tag value carried by a SUCCESSFUL login.
     *
     * <p>A success has no reason, so the obvious shape is to leave the tag off it. That shape
     * does not survive the scrape: Prometheus requires every meter sharing a name to share the
     * same tag KEYS, and Micrometer enforces it by refusing the second shape with a WARN. The
     * refusal is quiet in the worst way: the caller still gets a live counter that increments
     * in process and still appears in {@code getMeters()}, so every in-JVM assertion about it
     * passes. It is only never EXPORTED. Successes were registered first, so for as long as the
     * two shapes differed it was every FAILURE series that vanished at the scrape -
     * {@code auth_login_total{result="failure"}} did not exist in Prometheus at all, which is
     * not a flat line an operator can read but an absence that five alert rules
     * ({@code AuthLoginFailureSpike}, {@code AuthLoginFailureRatioHigh},
     * {@code AuthLoginFailureRatioCritical}, {@code AuthInvalidCredentialsBurst},
     * {@code AuthDisabledAccountAccessAttempt}) and two dashboard panels resolved to "no data",
     * i.e. to nothing firing and nothing shown.
     *
     * <p>So the tag is always present and a success spells it {@code none}. Every existing query
     * keeps working ({@code {result="failure"}}, {@code {result="failure",reason="..."}} and the
     * bare total all still select what they selected), and the two shapes can no longer diverge.
     */
    public static final String LOGIN_REASON_NONE = "none";
    public static final String SIGNUP_TOTAL = "auth_signup_total";
    public static final String TOKEN_REFRESH_TOTAL = "auth_token_refresh_total";
    public static final String LOGOUT_TOTAL = "auth_logout_total";
    public static final String PASSWORD_CHANGE_TOTAL = "auth_password_change_total";
    public static final String RATE_LIMIT_TOTAL = "auth_rate_limit_total";
    public static final String TOKEN_REUSE_DETECTED_TOTAL = "auth_token_reuse_detected_total";
    /**
     * Tokens that arrived WITH a signature we trust but WITHOUT the OIDC {@code auth_time}
     * claim that decides what a login is.
     *
     * <p>Exists so that "we counted no logins" and "nobody logged in" stop looking the
     * same. Any non-zero rate here means the login counter and the login audit trail are
     * blind for that provider, which is a configuration fault on the identity provider,
     * not an absence of users.
     *
     * <p>Tagged {@code reason} because the two causes send an operator to different places:
     * {@code absent} is the provider's token configuration, {@code future} is its clock.
     * Reporting both under one label would page with the wrong diagnosis half the time.
     */
    public static final String AUTH_TIME_CLAIM_MISSING_TOTAL = "auth_auth_time_claim_missing_total";
    /** The claim was not in the token at all. */
    public static final String AUTH_TIME_ABSENT = "absent";
    /** The claim was there, dated far enough ahead that storing it would mute the account. */
    public static final String AUTH_TIME_FUTURE = "future";

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
            Counter.builder(LOGIN_TOTAL).tags("result","success","provider",provider,"reason",LOGIN_REASON_NONE).register(registry);
            // Exactly the reasons a producer can actually emit, and all of them. Adding
            // cross_provider_conflict closes the hole this whole class is about (it is recorded
            // by UserResolutionService but was absent here, so its series did not exist until
            // the first occurrence). Dropping invalid_jwt / integrity_violation /
            // provisioning_race closes the mirror hole: no code path emits them, so they were
            // permanently flat zeros, and a flat zero reads as "watched and healthy" rather
            // than as "nothing is watching". Same argument the auth_time counter below makes
            // for leaving "local" out.
            for (String reason : new String[]{"invalid_credentials","rate_limited","disabled","internal_error","no_jwt","user_not_found","cross_provider_conflict"}) {
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
        // Three providers, not the four above. The only producer derives its tag from a
        // token it could parse, and a self-hosted "local" token is recognised earlier and
        // never reaches the counter, so a pre-registered local series would be a permanently
        // flat line that an operator would read as healthy rather than as unreachable.
        for (String provider : new String[]{"keycloak", "google", "github"}) {
            for (String reason : new String[]{AUTH_TIME_ABSENT, AUTH_TIME_FUTURE}) {
                Counter.builder(AUTH_TIME_CLAIM_MISSING_TOTAL)
                        .tags("provider", provider, "reason", reason).register(registry);
            }
        }
    }

    // ----- login -----

    public void loginSuccess(String provider) {
        Counter.builder(LOGIN_TOTAL)
                .tags(Tags.of("result", "success", "provider", safe(provider), "reason", LOGIN_REASON_NONE))
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

    /**
     * A trusted token carried no {@code auth_time}. See {@link #AUTH_TIME_CLAIM_MISSING_TOTAL}.
     * Not a login failure: nothing was refused, we simply cannot tell a sign-in from a
     * refresh for that provider any more.
     */
    public void authTimeClaimMissing(String provider, String reason) {
        Counter.builder(AUTH_TIME_CLAIM_MISSING_TOTAL)
                .tags(Tags.of("provider", safe(provider), "reason", safe(reason)))
                .description("Trusted tokens whose authentication instant is unusable (logins uncountable)")
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
     * Defensive normalisation of a tag value: lowercase, blank to {@code unknown}, and a hard
     * 32-character truncation.
     *
     * <p>It does NOT clamp to an allow-list, and the difference matters: a caller passing a
     * free-form string still creates a new series, it is merely a short lowercase one. The
     * bound on cardinality comes from the callers, all of which pass a literal, and from
     * {@code preRegisteredReasonsMatchTheProducers}, which fails the build when the set of
     * literals and the pre-registered set diverge.
     */
    private static String safe(String v) {
        if (v == null || v.isBlank()) return "unknown";
        if (v.length() > 32) return v.substring(0, 32);
        return v.toLowerCase();
    }
}
