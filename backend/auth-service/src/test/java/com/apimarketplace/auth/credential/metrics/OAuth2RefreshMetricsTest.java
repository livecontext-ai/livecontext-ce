package com.apimarketplace.auth.credential.metrics;

import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OAuth2RefreshMetrics")
class OAuth2RefreshMetricsTest {

    private SimpleMeterRegistry registry;
    private CredentialRepository repository;
    private OAuth2RefreshMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        repository = mock(CredentialRepository.class);
        metrics = new OAuth2RefreshMetrics(registry, repository);
    }

    /**
     * The {@code oauth2_credentials_in_error_state} gauge reads from the repository on every
     * scrape. Registering it once at construction is the whole contract - subsequent DB
     * updates flow through the supplier automatically.
     */
    @Test
    @DisplayName("registers credentials-in-error gauge that reads from repository on scrape")
    void gaugeReadsFromRepositoryOnScrape() {
        when(repository.countOAuth2CredentialsInTerminalState()).thenReturn(7L);

        Gauge gauge = registry.find(OAuth2RefreshMetrics.CREDENTIALS_IN_ERROR_GAUGE).gauge();

        assertThat(gauge).isNotNull();
        assertThat(gauge.value()).isEqualTo(7.0);
    }

    /**
     * Gauge failure mode: a DB outage during scrape must not break the /metrics endpoint.
     * We swallow the exception and return 0 - alerts on the gauge should pair with a
     * database-up check to avoid false negatives during outages.
     */
    @Test
    @DisplayName("gauge returns 0 when repository throws - /metrics stays green during DB outage")
    void gaugeSurvivesRepositoryFailure() {
        when(repository.countOAuth2CredentialsInTerminalState())
                .thenThrow(new RuntimeException("db down"));

        Gauge gauge = registry.find(OAuth2RefreshMetrics.CREDENTIALS_IN_ERROR_GAUGE).gauge();

        assertThat(gauge.value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("recordSuccess increments oauth2_refresh_total{result=success,provider=<x>}")
    void recordSuccessTagsResult() {
        metrics.recordSuccess("gmail");
        metrics.recordSuccess("gmail");

        Counter c = registry.find(OAuth2RefreshMetrics.REFRESH_TOTAL)
                .tag("result", "success")
                .tag("provider", "gmail")
                .counter();

        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordTerminal emits both refresh_total and terminal_total with reason tag")
    void recordTerminalEmitsBothCounters() {
        metrics.recordTerminal("gmail", RefreshErrorBucket.TERMINAL_USER, "invalid_grant");

        Counter refreshCounter = registry.find(OAuth2RefreshMetrics.REFRESH_TOTAL)
                .tag("result", "terminal")
                .tag("provider", "gmail")
                .counter();
        Counter terminalCounter = registry.find(OAuth2RefreshMetrics.TERMINAL_TOTAL)
                .tag("reason", "terminal_user")
                .tag("provider", "gmail")
                .tag("provider_code", "invalid_grant")
                .counter();

        assertThat(refreshCounter.count()).isEqualTo(1.0);
        assertThat(terminalCounter.count()).isEqualTo(1.0);
    }

    /**
     * TERMINAL_CONFIG maps to reason=terminal_config (admin must fix), not terminal_user.
     * Paging logic differs: user-scope alerts go to support, config-scope alerts go to ops.
     */
    @Test
    @DisplayName("TERMINAL_CONFIG bucket tags reason=terminal_config, not terminal_user")
    void terminalConfigUsesCorrectReason() {
        metrics.recordTerminal("slack", RefreshErrorBucket.TERMINAL_CONFIG, "invalid_scope");

        Counter terminalConfig = registry.find(OAuth2RefreshMetrics.TERMINAL_TOTAL)
                .tag("reason", "terminal_config")
                .tag("provider", "slack")
                .tag("provider_code", "invalid_scope")
                .counter();
        assertThat(terminalConfig).isNotNull();
        assertThat(terminalConfig.count()).isEqualTo(1.0);

        // And there must be NO terminal_user series emitted.
        assertThat(registry.find(OAuth2RefreshMetrics.TERMINAL_TOTAL)
                .tag("reason", "terminal_user")
                .tag("provider", "slack")
                .counter()).isNull();
    }

    /**
     * Providers with a null or blank integration field (legacy rows, migration artifacts)
     * must not NPE - they fold into a single {@code unknown} series so the metric remains
     * valid and bounded.
     */
    @Test
    @DisplayName("null/blank provider is normalized to 'unknown'")
    void nullProviderIsNormalized() {
        metrics.recordSuccess(null);
        metrics.recordSuccess("");
        metrics.recordSuccess("   ");

        Counter unknown = registry.find(OAuth2RefreshMetrics.REFRESH_TOTAL)
                .tag("result", "success")
                .tag("provider", "unknown")
                .counter();

        assertThat(unknown).isNotNull();
        assertThat(unknown.count()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("recordTransient / InProgress / Unsupported / Failed each tag distinct result values")
    void resultLabelsAreDistinct() {
        metrics.recordTransient("gmail");
        metrics.recordInProgress("gmail");
        metrics.recordUnsupported("gmail");
        metrics.recordFailed("gmail");

        for (String result : new String[]{"transient_retry", "in_progress", "unsupported", "failed"}) {
            Counter c = registry.find(OAuth2RefreshMetrics.REFRESH_TOTAL)
                    .tag("result", result)
                    .tag("provider", "gmail")
                    .counter();
            assertThat(c).as("result=%s should have been emitted", result).isNotNull();
            assertThat(c.count()).isEqualTo(1.0);
        }
    }
}
