package com.apimarketplace.trigger.config;

import com.apimarketplace.notification.client.NotificationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the cross-service notification client. Producers in
 * {@link com.apimarketplace.trigger.service.TriggerLifecycleManager}
 * emit {@code WEBHOOK_TRIGGER_DISABLED} when a user-actionable suspend
 * fires (USER_DISABLED / WORKFLOW_UNPINNED / NO_PRODUCTION_RUN /
 * TTL_EXPIRED / MAX_EXEC_REACHED / PLAN_TRIGGER_REMOVED).
 *
 * <p>The client carries mandatory 2s/5s timeouts internally - see
 * {@link NotificationClient} javadoc for the cascade-prevention rationale.
 */
@Configuration
public class NotificationClientConfig {

    @Bean
    public NotificationClient notificationClient(
            @Value("${services.orchestrator-url:http://localhost:8099}") String orchestratorUrl) {
        return new NotificationClient(orchestratorUrl);
    }
}
