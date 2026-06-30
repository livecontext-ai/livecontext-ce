package com.apimarketplace.conversation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Configuration to enable scheduled tasks.
 * Used for heartbeat cleanup and TTL management.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
