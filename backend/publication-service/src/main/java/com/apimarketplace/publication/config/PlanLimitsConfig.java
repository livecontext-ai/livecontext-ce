package com.apimarketplace.publication.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Initializes plan-limit flags from application properties.
 * In CE mode, plan-limits.enabled=false disables all quota enforcement.
 */
@Configuration
public class PlanLimitsConfig {

    private static final Logger logger = LoggerFactory.getLogger(PlanLimitsConfig.class);

    @Value("${plan-limits.enabled:true}")
    private boolean planLimitsEnabled;

    @PostConstruct
    void init() {
        SharedLinkPlanLimits.setEnabled(planLimitsEnabled);
        logger.info("Plan limits enforcement: {}", planLimitsEnabled ? "ENABLED" : "DISABLED (CE mode - all resources unlimited)");
    }
}
