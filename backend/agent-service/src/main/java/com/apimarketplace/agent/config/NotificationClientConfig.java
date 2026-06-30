package com.apimarketplace.agent.config;

import com.apimarketplace.notification.client.NotificationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the cross-service notification client. Producer in agent-service:
 * {@link com.apimarketplace.agent.service.AgentTaskService#assignTask}
 * emits {@code AGENT_TASK_ASSIGNED} when a task is assigned to a sub-agent.
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
