package com.apimarketplace.auth.metrics;

import com.apimarketplace.auth.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AuthMetrics")
class AuthMetricsTest {

    private SimpleMeterRegistry registry;
    private AuthMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        UserRepository repo = mock(UserRepository.class);
        when(repo.count()).thenReturn(0L);
        metrics = new AuthMetrics(registry, repo);
    }

    private Counter counter(String name, String... tags) {
        return registry.find(name).tags(tags).counter();
    }

    @Test
    @DisplayName("counters are pre-registered at value 0 (no Grafana 'no data')")
    void counters_preRegistered() {
        // login success per provider
        for (String provider : new String[]{"keycloak", "google", "github", "local"}) {
            Counter c = counter(AuthMetrics.LOGIN_TOTAL, "result", "success", "provider", provider);
            assertThat(c).as("login success/" + provider).isNotNull();
            assertThat(c.count()).isZero();
        }
        // login failure per provider × reason
        Counter c = counter(AuthMetrics.LOGIN_TOTAL,
                "result", "failure", "provider", "keycloak", "reason", "invalid_credentials");
        assertThat(c).isNotNull();
        assertThat(c.count()).isZero();

        // signup
        assertThat(counter(AuthMetrics.SIGNUP_TOTAL, "provider", "google", "first_user", "false")).isNotNull();
        assertThat(counter(AuthMetrics.SIGNUP_TOTAL, "provider", "google", "first_user", "true")).isNotNull();

        // refresh / password / logout / rate limit
        assertThat(counter(AuthMetrics.TOKEN_REFRESH_TOTAL, "result", "success")).isNotNull();
        assertThat(counter(AuthMetrics.PASSWORD_CHANGE_TOTAL, "result", "failure")).isNotNull();
        assertThat(counter(AuthMetrics.LOGOUT_TOTAL, "scope", "all")).isNotNull();
        assertThat(counter(AuthMetrics.RATE_LIMIT_TOTAL, "endpoint", "login")).isNotNull();
        assertThat(registry.find(AuthMetrics.TOKEN_REUSE_DETECTED_TOTAL).counter()).isNotNull();
    }

    @Test
    @DisplayName("loginSuccess increments the SAME pre-registered counter (not a duplicate)")
    void loginSuccess_incrementsPreregistered() {
        Counter pre = counter(AuthMetrics.LOGIN_TOTAL, "result", "success", "provider", "google");
        double before = pre.count();
        metrics.loginSuccess("google");
        metrics.loginSuccess("google");
        assertThat(pre.count()).isEqualTo(before + 2.0);

        // and there is exactly one such meter (no cardinality leak)
        long matching = registry.getMeters().stream()
                .filter(m -> m.getId().getName().equals(AuthMetrics.LOGIN_TOTAL))
                .filter(m -> "success".equals(m.getId().getTag("result")))
                .filter(m -> "google".equals(m.getId().getTag("provider")))
                .count();
        assertThat(matching).isEqualTo(1);
    }

    @Test
    @DisplayName("safe() lowercases free-form provider tags")
    void safe_lowercases() {
        metrics.loginSuccess("GoOgLe");
        Counter c = counter(AuthMetrics.LOGIN_TOTAL, "result", "success", "provider", "google");
        assertThat(c.count()).isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("safe() truncates oversize tag values to 32 chars")
    void safe_truncates() {
        String huge = "x".repeat(100);
        metrics.loginFailure("keycloak", huge);
        // The actual reason tag should be 32 chars max
        boolean found = registry.getMeters().stream()
                .map(Meter::getId)
                .filter(id -> AuthMetrics.LOGIN_TOTAL.equals(id.getName()))
                .map(id -> id.getTag("reason"))
                .filter(r -> r != null && r.length() == 32 && r.equals("x".repeat(32)))
                .findAny().isPresent();
        assertThat(found).isTrue();
    }

    @Test
    @DisplayName("safe() maps null/blank to 'unknown'")
    void safe_nullOrBlank() {
        metrics.loginSuccess(null);
        metrics.loginSuccess("");
        Counter c = counter(AuthMetrics.LOGIN_TOTAL, "result", "success", "provider", "unknown");
        assertThat(c).isNotNull();
        assertThat(c.count()).isGreaterThanOrEqualTo(2.0);
    }

    @Test
    @DisplayName("tokenRefreshed(success) increments active token gauge")
    void activeTokenGauge_tracking() {
        metrics.tokenRefreshed("success");
        metrics.tokenRefreshed("success");
        metrics.logout("single");
        Double v = registry.find(AuthMetrics.ACTIVE_REFRESH_TOKENS_GAUGE).gauge().value();
        assertThat(v).isEqualTo(1.0);
    }
}
