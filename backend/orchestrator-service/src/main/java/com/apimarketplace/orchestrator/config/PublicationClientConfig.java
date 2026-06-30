package com.apimarketplace.orchestrator.config;

import com.apimarketplace.publication.client.PublicationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the Publication HTTP client.
 * Connects orchestrator-service to publication-service via HTTP.
 */
@Configuration
public class PublicationClientConfig {

    @Bean
    public PublicationClient publicationClient(
            @Value("${services.publication-url:http://localhost:8092}") String publicationServiceUrl) {
        return new PublicationClient(publicationServiceUrl);
    }
}
