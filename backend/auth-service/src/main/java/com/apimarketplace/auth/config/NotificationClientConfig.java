package com.apimarketplace.auth.config;

import com.apimarketplace.notification.client.NotificationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the cross-service notification client. Producers in auth-service
 * (currently {@link com.apimarketplace.auth.credential.service.OAuth2RefreshScheduler}
 * for {@code CRED_EXPIRED}) emit via this client.
 *
 * <p>The client carries mandatory 2s/5s timeouts internally - see
 * {@link NotificationClient} javadoc for the cascade-prevention rationale.
 * Critical here: the OAuth2 refresh scheduler runs on a 5-minute tick with
 * a 4-minute ShedLock - a hung HTTP call to orchestrator would burn the
 * tick budget and starve other credentials waiting in the same sweep.
 */
@Configuration
public class NotificationClientConfig {

    @Bean
    public NotificationClient notificationClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        return new NotificationClient(orchestratorUrl);
    }
}
