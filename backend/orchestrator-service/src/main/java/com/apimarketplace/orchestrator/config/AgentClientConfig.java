package com.apimarketplace.orchestrator.config;

import com.apimarketplace.agent.client.AgentClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for the Agent HTTP client.
 * Connects orchestrator-service to agent-service via HTTP.
 */
@Configuration
public class AgentClientConfig {

    @Bean
    public AgentClient agentClient(
            @Value("${services.agent-url:http://localhost:8090}") String agentServiceUrl) {
        return new AgentClient(agentServiceUrl);
    }
}
