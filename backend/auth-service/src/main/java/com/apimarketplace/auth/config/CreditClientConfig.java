package com.apimarketplace.auth.config;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration that creates the CreditConsumptionClient bean for auth-service.
 * Auth-service calls itself for credit consumption retries (dead-letter handler
 * retries go through the same credit consumption API hosted here).
 */
@Configuration
public class CreditClientConfig {

    @Bean
    public CreditConsumptionClient creditConsumptionClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl,
            @Value("${credit.consumption.enabled:true}") boolean enabled,
            @Value("${gateway.filter.secret-key:${GATEWAY_SECRET_KEY:}}") String gatewaySecretKey) {
        return new CreditConsumptionClient(authServiceUrl, enabled, gatewaySecretKey);
    }
}
