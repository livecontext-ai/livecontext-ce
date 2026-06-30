package com.apimarketplace.conversation.service.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Conditionally creates BridgeClient bean when bridge is enabled.
 * Feature flag: conversation.bridge.enabled=true
 */
@Configuration
public class BridgeClientConfig {

    @Bean
    @ConditionalOnProperty(name = "conversation.bridge.enabled", havingValue = "true")
    public BridgeClient bridgeClient(
            @Value("${conversation.bridge.url:http://localhost:8093}") String bridgeUrl) {
        return new BridgeClient(bridgeUrl);
    }
}
