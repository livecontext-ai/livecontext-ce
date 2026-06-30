package com.apimarketplace.auth.credential.metrics;

import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Prometheus metrics for the OAuth2 refresh pipeline.
 *
 * <p>Three series:
 * <ul>
 *   <li>{@value #REFRESH_TOTAL}{@code {result, provider}} - per-attempt counter. One increment
 *       per refresh call, regardless of outcome. Label {@code result} ∈ {@code success,
 *       terminal, transient_retry, in_progress, unsupported, failed}.</li>
 *   <li>{@value #TERMINAL_TOTAL}{@code {reason, provider}} - terminal-only breakdown so
 *       alerting can page on {@code terminal_user} (user re-auth needed) vs.
 *       {@code terminal_config} (admin must fix template).</li>
 *   <li>{@value #CREDENTIALS_IN_ERROR_GAUGE} - aggregate count of OAuth2 credentials stuck in
 *       {@code error} or {@code needs_reauth} state. Scraped on demand via a bounded COUNT(*)
 *       query; the repository should keep this query cheap with a partial index on status.</li>
 * </ul>
 *
 * <p><strong>Cardinality policy.</strong> The {@code provider} label is bounded to integration
 * slugs (≤ ~600 entries in practice). Combined with the six {@code result} values that caps at
 * ~3 600 series - comfortable for Prometheus. We intentionally do <em>not</em> tag user/tenant
 * IDs. A compromised credential should surface in the {@code terminal_total{reason="..."}}
 * counter, not blow up our metric cardinality.
 *
 * <p>Metric alert floor: pair any {@code terminal_rate_5m > X} rule with an absolute-count
 * floor (e.g. {@code AND terminal_rate_5m > 3}) so low-volume environments don't page on a
 * single expected re-auth.
 */
@Component
public class OAuth2RefreshMetrics {

    private static final Logger log = LoggerFactory.getLogger(OAuth2RefreshMetrics.class);

    public static final String REFRESH_TOTAL = "oauth2_refresh_total";
    public static final String TERMINAL_TOTAL = "oauth2_refresh_terminal_total";
    public static final String CREDENTIALS_IN_ERROR_GAUGE = "oauth2_credentials_in_error_state";

    /**
     * Unknown provider slug - defensive default so a credential row with a null
     * {@code integration} column still emits a valid metric.
     */
    private static final String UNKNOWN_PROVIDER = "unknown";

    private final MeterRegistry registry;

    public OAuth2RefreshMetrics(MeterRegistry registry, CredentialRepository credentialRepository) {
        this.registry = registry;

        // Scrape-time gauge: a cheap COUNT(*) query on an indexed column. Wrapped in try/catch
        // so a DB outage can't break the /metrics endpoint for the rest of the pod.
        Gauge.builder(CREDENTIALS_IN_ERROR_GAUGE, credentialRepository, repo -> {
                    try {
                        return repo.countOAuth2CredentialsInTerminalState();
                    } catch (Exception e) {
                        log.debug("Failed to count terminal credentials for gauge: {}", e.getMessage());
                        return 0d;
                    }
                })
                .description("OAuth2 credentials currently stuck in error or needs_reauth status")
                .register(registry);
    }

    public void recordSuccess(String provider) {
        increment("success", provider);
    }

    public void recordTerminal(String provider, RefreshErrorBucket bucket, String providerCode) {
        String resolvedProvider = resolve(provider);
        increment("terminal", resolvedProvider);
        String reason = bucket == RefreshErrorBucket.TERMINAL_USER ? "terminal_user" : "terminal_config";
        Counter.builder(TERMINAL_TOTAL)
                .tag("reason", reason)
                .tag("provider", resolvedProvider)
                .tag("provider_code", providerCode != null ? providerCode : "unknown")
                .register(registry)
                .increment();
    }

    public void recordTransient(String provider) {
        increment("transient_retry", provider);
    }

    public void recordInProgress(String provider) {
        increment("in_progress", provider);
    }

    public void recordUnsupported(String provider) {
        increment("unsupported", provider);
    }

    public void recordFailed(String provider) {
        increment("failed", provider);
    }

    private void increment(String result, String provider) {
        Counter.builder(REFRESH_TOTAL)
                .tag("result", result)
                .tag("provider", resolve(provider))
                .register(registry)
                .increment();
    }

    private static String resolve(String provider) {
        return (provider == null || provider.isBlank()) ? UNKNOWN_PROVIDER : provider;
    }
}
