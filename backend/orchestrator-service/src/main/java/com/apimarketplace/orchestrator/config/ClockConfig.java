package com.apimarketplace.orchestrator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Clock bean for testability.
 * Production: Clock.systemUTC().
 * Tests: inject Clock.fixed(...) or MutableClock.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
