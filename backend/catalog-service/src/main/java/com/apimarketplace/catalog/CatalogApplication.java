package com.apimarketplace.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
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
    "com.apimarketplace.common.credit",   // CreditClientAutoConfig → CreditConsumptionClient bean
    "com.apimarketplace.auth.client",     // AuthClientConfig -> AuthClient bean (CE catalog relay link/entitlements gates)
    "com.apimarketplace.sse"
},
    // A @ComponentScan declared here replaces the one @SpringBootApplication carries, and with it
    // Boot's TypeExcludeFilter: a @SpringBootTest context would then scan the test classes too (and a
    // slice test such as @WebMvcTest would load every component). Same filter as
    // ConversationServiceApplication; inert at runtime.
    excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class)
    }
)
public class CatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}


