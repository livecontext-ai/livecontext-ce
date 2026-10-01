package com.apimarketplace.publication.config;

import com.apimarketplace.notification.client.NotificationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the cross-service notification client. Producer in publication-service:
 * {@link com.apimarketplace.publication.service.CreatorFollowNotifier} emits
 * {@code CREATOR_PUBLISHED} to a creator's followers when a new listing goes live.
 *
 * <p>Same bean name as the agent/auth/trigger configs: in the CE monolith the
 * definitions override each other (all build the same client on the same URL).
 */
@Configuration
public class NotificationClientConfig {

    @Bean
    public NotificationClient notificationClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        return new NotificationClient(orchestratorUrl);
    }
}
