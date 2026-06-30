package com.apimarketplace.orchestrator.config;

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
            // for every service via commonEnv), like every other orchestrator client
            // (services.agent-url, services.trigger-url, …). The legacy `orchestrator.conversation.base-url`
            // is kept as a fallback for docker-compose / systemd which still set it; the localhost
            // default only applies to a single-host run. Pre-k3s everything was on localhost so the
            // non-standard name worked; on k3s (separate pods) it resolved to localhost:8087 → refused.
            @Value("${services.conversation-url:${orchestrator.conversation.base-url:http://localhost:8087}}") String baseUrl) {
        return new ConversationClient(restTemplate, baseUrl);
    }
}
