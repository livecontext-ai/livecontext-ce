package com.apimarketplace.agent.service.credit;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.HttpCreditDeadLetterHandler;
import com.apimarketplace.common.credit.PricingSnapshotClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration that creates the unified CreditConsumptionClient bean
 * from common-lib with agent-service-specific settings.
 *
 * Wires the {@link HttpCreditDeadLetterHandler} so that failed synchronous
 * credit consumption calls (in AgentObservabilityService) are persisted
 * to the auth-service dead-letter table for later reconciliation.
 *
 * Also creates the {@link PricingSnapshotClient} bean that caches model
 * pricing from auth-service. This is the single source of truth for
 * per-model rates used by budget guards and cost calculators.
 */
@Configuration
public class CreditClientConfig {

    @Bean
    public CreditConsumptionClient creditConsumptionClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl,
            @Value("${credit.consumption.enabled:true}") boolean enabled,
            @Value("${gateway.filter.secret-key:${GATEWAY_SECRET_KEY:}}") String gatewaySecretKey) {
        CreditConsumptionClient client = new CreditConsumptionClient(authServiceUrl, enabled, gatewaySecretKey);
        client.setDeadLetterHandler(
                new HttpCreditDeadLetterHandler(new RestTemplate(), authServiceUrl));
        return client;
    }

    @Bean
    public PricingSnapshotClient pricingSnapshotClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl) {
        PricingSnapshotClient client = new PricingSnapshotClient(authServiceUrl);
        // Eagerly load the snapshot at startup so the first budget check has real rates.
        try {
            client.refresh();
        } catch (Exception e) {
            // Non-fatal: lazy refresh will retry on first getRates() call.
        }
        return client;
    }
}
