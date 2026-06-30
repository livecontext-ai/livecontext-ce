package com.apimarketplace.orchestrator.config;

import com.apimarketplace.common.credit.PricingSnapshotClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link PricingSnapshotClient} bean used by orchestrator-side
 * billing helpers (currently {@code BrowserAgentModule.postProcess}, which
 * computes {@code cost.cost_usd} from the runner's per-model token counts
 * before the result reaches the chat agent / workflow node).
 *
 * <p>Mirrors {@code agent-service.CreditClientConfig#pricingSnapshotClient}:
 * single bean, 5-minute TTL, eager warm-up at startup so the first browser
 * agent run has real rates instead of a 0/0 fallback.
 */
@Configuration
public class PricingSnapshotConfig {

    private static final Logger log = LoggerFactory.getLogger(PricingSnapshotConfig.class);

    @Bean
    public PricingSnapshotClient pricingSnapshotClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl) {
        PricingSnapshotClient client = new PricingSnapshotClient(authServiceUrl);
        try {
            client.refresh();
        } catch (Exception e) {
            // Non-fatal: lazy refresh on first getRates() call. We intentionally
            // do not propagate the failure - auth-service may come up after us.
            log.warn("Initial pricing snapshot refresh failed (will retry lazily): {}",
                e.getMessage());
        }
        return client;
    }
}
