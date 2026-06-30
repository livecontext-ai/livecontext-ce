package com.apimarketplace.agent.config;

import com.apimarketplace.conversation.client.ConversationClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Configuration for the shared Conversation HTTP client.
 */
@Configuration
public class ConversationClientConfig {

    @Bean
    public ConversationClient conversationClient(
            RestTemplate restTemplate,
            // Bind to the standard `services.conversation-url` (helm injects SERVICES_CONVERSATION_URL
            // for every service via commonEnv). The legacy `agent.conversation.base-url` is kept as a
            // fallback for docker-compose / systemd; localhost default applies only to a single-host run.
            // On k3s (separate pods) the non-standard name resolved to localhost:8087 → connection refused
            // (broke stream register/finalize, sub-agent + task conversation create after the migration).
            @Value("${services.conversation-url:${agent.conversation.base-url:http://localhost:8087}}") String baseUrl) {
        return new ConversationClient(restTemplate, baseUrl);
    }
}
