package com.apimarketplace.conversation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for Stream services
 * Centralizes all stream-related configuration values
 */
@Configuration
public class StreamConfig {
    
    @Value("${stream.ttl.timeout-minutes:30}")
    private int timeoutMinutes;

    // Getters
    public int getTimeoutMinutes() {
        return timeoutMinutes;
    }
}
