package com.apimarketplace.conversation.config;

import com.apimarketplace.agent.client.AgentClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for AgentClient bean.
 * Conversation-service routes LLM execution through agent-service.
 */
@Configuration
public class AgentClientConfig {

    private static final Logger log = LoggerFactory.getLogger(AgentClientConfig.class);

    @Bean
    public AgentClient agentClient(@Value("${services.agent-service.url:http://localhost:8090}") String agentServiceUrl) {
        log.info("Creating AgentClient for remote execution via {}", agentServiceUrl);
        return new AgentClient(agentServiceUrl);
    }
}
