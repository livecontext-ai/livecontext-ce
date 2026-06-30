package com.apimarketplace.monolith;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.Set;

/**
 * Community Edition monolith entry point.
 *
 * Combines all microservices into a single Spring Boot application.
 * Uses fully-qualified bean names to avoid conflicts between services
 * that have duplicate class names in different packages.
 *
 * Activated with profile: ce
 * Key properties: deployment.mode=monolith, auth.mode=embedded, storage.type=s3
 */
@SpringBootApplication(exclude = {
    org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class,
    org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
})
@EnableScheduling
@EnableAsync
@ComponentScan(
    basePackages = {
        "com.apimarketplace.monolith",
        "com.apimarketplace.orchestrator",
        "com.apimarketplace.auth",
        "com.apimarketplace.agent",
        "com.apimarketplace.conversation",
        "com.apimarketplace.catalog",
        "com.apimarketplace.datasource",
        "com.apimarketplace.interfaces",
        "com.apimarketplace.trigger",
        "com.apimarketplace.publication",
        "com.apimarketplace.storage",
        "com.apimarketplace.common.security",
        "com.apimarketplace.common.event",
        "com.apimarketplace.common.storage",
        // shared-sse-lib: WebClientSseConsumer (@Component) is consumed by
        // catalog-service's StreamingResponseHandler. Microservice mode picks
        // it up via CatalogApplication's scan; monolith must register it
        // explicitly here (webflux is on the classpath via catalog-service).
        "com.apimarketplace.sse"
    },
    excludeFilters = {
        // Exclude individual service Application classes to avoid multiple @SpringBootApplication
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\..+\\..+Application"),
        // Exclude gateway (WebFlux-based, not compatible with monolith servlet container)
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.gateway\\..*"),
        // Exclude cloud/WebFlux conversation streaming auto-scan. CE wires the small
        // Redis-backed adapters it needs explicitly in MonolithAdapterConfig.
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.conversation\\.streaming\\..*"),
        // Exclude only conversation controllers that depend on reactive stream state.
        // AttachmentController stays mounted in CE because it uses the regular storage service.
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.conversation\\.controller\\.v3\\.(ChatControllerV3|StreamControllerV3)"),
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.conversation\\.controller\\.internal\\..*"),
        // Exclude conversation Redis config. CE defines servlet-safe Redis adapters explicitly.
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.conversation\\.config\\.RedisConfig"),
        // Exclude storage-service SecurityConfig (MonolithSecurityConfig handles all security)
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.storage\\.config\\.SecurityConfig"),
        // Exclude auth-service SecurityConfig (MonolithSecurityConfig handles all security)
        @ComponentScan.Filter(type = FilterType.REGEX,
            pattern = "com\\.apimarketplace\\.auth\\.config\\.SecurityConfig")
    },
    nameGenerator = MonolithApplication.FullyQualifiedBeanNameGenerator.class
)
@EntityScan(basePackages = {
    "com.apimarketplace.orchestrator",
    "com.apimarketplace.auth.domain",
    "com.apimarketplace.auth.credential.domain",
    "com.apimarketplace.auth.ce",  // CeInstallState entity (V121)
    "com.apimarketplace.agent",
    "com.apimarketplace.conversation",
    "com.apimarketplace.catalog",
    "com.apimarketplace.datasource",
    "com.apimarketplace.interfaces",
    "com.apimarketplace.trigger",
    "com.apimarketplace.publication",
    "com.apimarketplace.common.storage",
    "com.apimarketplace.storage"
})
@EnableJpaRepositories(basePackages = {
    "com.apimarketplace.orchestrator",
    "com.apimarketplace.auth.repository",
    "com.apimarketplace.auth.credential.repository",
    "com.apimarketplace.auth.ce",  // CeInstallStateRepository
    "com.apimarketplace.agent",
    "com.apimarketplace.conversation",
    "com.apimarketplace.catalog",
    "com.apimarketplace.datasource",
    "com.apimarketplace.interfaces",
    "com.apimarketplace.trigger",
    "com.apimarketplace.publication",
    "com.apimarketplace.common.storage",
    "com.apimarketplace.storage"
})
public class MonolithApplication {

    private static final Set<String> PGVECTOR_REPAIRABLE_FAILED_VERSIONS = Set.of("74", "75");

    public static void main(String[] args) {
        System.setProperty("spring.profiles.active", "ce");
        SpringApplication.run(MonolithApplication.class, args);
    }

    @Bean
    FlywayMigrationStrategy repairKnownPgvectorFailureThenMigrate() {
        return flyway -> {
            if (hasFailedPgvectorMigrationHistory(flyway)) {
                flyway.repair();
            }
            flyway.migrate();
        };
    }

    boolean hasFailedPgvectorMigrationHistory(Flyway flyway) {
        for (MigrationInfo migration : flyway.info().all()) {
            if (migration.getVersion() == null || migration.getState() == null) {
                continue;
            }
            String version = migration.getVersion().toString();
            String state = migration.getState().name();
            if (PGVECTOR_REPAIRABLE_FAILED_VERSIONS.contains(version) && state.contains("FAILED")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bean name generator that uses fully-qualified class names.
     * This avoids ConflictingBeanDefinitionException when multiple services
     * have identically-named classes (e.g. CreditClientConfig, RedisConfig).
     */
    public static class FullyQualifiedBeanNameGenerator extends AnnotationBeanNameGenerator {
        @Override
        protected String buildDefaultBeanName(BeanDefinition definition, BeanDefinitionRegistry registry) {
            return definition.getBeanClassName();
        }
    }
}
