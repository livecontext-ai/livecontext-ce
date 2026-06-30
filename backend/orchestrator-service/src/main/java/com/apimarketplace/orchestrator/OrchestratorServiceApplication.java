package com.apimarketplace.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(
    basePackages = {
        "com.apimarketplace.orchestrator",
        "com.apimarketplace.common.storage",
        "com.apimarketplace.common.security",
        "com.apimarketplace.common.billing",  // CatalogBillingDispatcher (shared with catalog-service)
        "com.apimarketplace.common.credit",   // CreditClientAutoConfig (was orchestrator's CreditClientConfig)
        "com.apimarketplace.agent",  // Full scan of shared-agent-lib (incl. ImageGenerationBillingStrategy)
        "com.apimarketplace.auth.client"
    },
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
    }
)
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.orchestrator.persistence",
    "com.apimarketplace.orchestrator.repository",
    "com.apimarketplace.common.storage.repository"
})
@EntityScan(basePackages = {
    "com.apimarketplace.orchestrator.domain"
},
    basePackageClasses = {
        com.apimarketplace.common.storage.domain.StorageEntity.class,
        com.apimarketplace.common.storage.domain.TenantStorageQuota.class
    })
@EnableScheduling
public class OrchestratorServiceApplication {

    private static final Logger logger = LoggerFactory.getLogger(OrchestratorServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(OrchestratorServiceApplication.class, args);
        logger.info("Orchestrator Service started successfully");
    }
}




