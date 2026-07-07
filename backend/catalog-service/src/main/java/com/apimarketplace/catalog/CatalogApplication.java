package com.apimarketplace.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Catalog service.
 */
@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {
    "com.apimarketplace.catalog",
    "com.apimarketplace.common.mapping",
    "com.apimarketplace.common.security",
    "com.apimarketplace.common.billing",  // CatalogBillingDispatcher (shared with orchestrator-service)
    "com.apimarketplace.common.credit",   // CreditClientAutoConfig → CreditConsumptionClient bean
    "com.apimarketplace.agent.billing",   // ImageGenerationBillingStrategy (lives in agent-common)
    "com.apimarketplace.auth.client",     // AuthClientConfig -> AuthClient bean (CE catalog relay link/entitlements gates)
    "com.apimarketplace.sse"
})
public class CatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}


