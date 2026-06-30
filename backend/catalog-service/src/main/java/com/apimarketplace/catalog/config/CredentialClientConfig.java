package com.apimarketplace.catalog.config;

import com.apimarketplace.credential.client.CredentialClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for CredentialClient bean in catalog-service.
 * Routes credential operations to auth-service via HTTP.
 */
@Configuration
public class CredentialClientConfig {

    @Bean
    public CredentialClient credentialClient(
            @Value("${services.auth-service.url:http://localhost:8083}") String authUrl,
            @Value("${gateway.filter.secret-key:${GATEWAY_SECRET_KEY:}}") String gatewaySecretKey) {
        return new CredentialClient(authUrl, gatewaySecretKey);
    }
}
