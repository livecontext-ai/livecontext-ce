package com.apimarketplace.publication.config;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.HttpCreditDeadLetterHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class CreditClientConfig {

    @Bean
    public CreditConsumptionClient creditConsumptionClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authServiceUrl,
            @Value("${gateway.filter.secret-key:${GATEWAY_SECRET_KEY:}}") String gatewaySecretKey) {
        CreditConsumptionClient client = new CreditConsumptionClient(authServiceUrl, gatewaySecretKey);
        client.setDeadLetterHandler(
                new HttpCreditDeadLetterHandler(new RestTemplate(), authServiceUrl));
        return client;
    }
}
