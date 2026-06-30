package com.apimarketplace.agent.service.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conditionally creates SubAgentBridgeClient bean when bridge is enabled.
 * Mirrors conversation-service's BridgeClientConfig pattern.
 * Feature flag: conversation.bridge.enabled=true
 */
@Configuration
public class SubAgentBridgeClientConfig {

    @Bean
    @ConditionalOnProperty(name = "conversation.bridge.enabled", havingValue = "true")
    public SubAgentBridgeClient subAgentBridgeClient(
            @Value("${conversation.bridge.url:http://localhost:8093}") String bridgeUrl) {
        return new SubAgentBridgeClient(bridgeUrl);
    }
}
