package com.apimarketplace.conversation.service.credit;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.HttpCreditDeadLetterHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration that creates the unified CreditConsumptionClient bean
 * from common-lib with conversation-specific settings.
 *
 * Dead-letter events are forwarded to auth-service via HTTP
 * (auth-service owns the dead-letter table and retry job).
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
}
