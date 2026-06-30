package com.apimarketplace.common.storage.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring test for the k3s regression: {@code storage.mapping.catalog-base-url} used to default to a
 * hardcoded {@code localhost:8081} bound to NO env, so on k3s (separate pods) the orchestrator's
 * StorageMappingResolverService hit {@code localhost} → {@code Connection refused}. The effective URL now
 * inherits the standard {@code services.catalog-url} (which the helm injects as {@code SERVICES_CATALOG_URL}
 * and the CE profile sets to the monolith). These cases exercise the REAL Spring binding - the
 * "platform url only" case fails on the pre-fix code and passes on the post-fix code.
 */
@DisplayName("StorageMappingConfig - Spring binding / catalog URL wiring")
class StorageMappingConfigWiringTest {

    private final ApplicationContextRunner runner =
        new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("inherits services.catalog-url (in-cluster DNS) when no explicit override - k3s regression guard")
    void inheritsPlatformCatalogUrlFromEnv() {
        runner.withPropertyValues("services.catalog-url=http://livecontext-livecontext-catalog:8081")
            .run(ctx -> assertThat(ctx.getBean(StorageMappingConfig.class).getCatalogBaseUrl())
                .isEqualTo("http://livecontext-livecontext-catalog:8081"));
    }

    @Test
    @DisplayName("CE profile: inherits services.catalog-url pointing at the monolith (localhost:8080)")
    void inheritsCeMonolithCatalogUrl() {
        runner.withPropertyValues("services.catalog-url=http://localhost:8080")
            .run(ctx -> assertThat(ctx.getBean(StorageMappingConfig.class).getCatalogBaseUrl())
                .isEqualTo("http://localhost:8080"));
    }

    // The literal prod mechanism: Helm injects the env var SERVICES_CATALOG_URL; @Value("${services.catalog-url}")
    // resolves it through the system-environment property source's UPPER_UNDERSCORE relaxed lookup. This pins the
    // env-var → property-key contract that the kebab-case cases above don't exercise.
    @Test
    @DisplayName("resolves from the SERVICES_CATALOG_URL env var (Helm-injected) via relaxed binding")
    void resolvesFromServicesCatalogUrlEnvVar() {
        new ApplicationContextRunner()
            .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    Collections.singletonMap(
                        "SERVICES_CATALOG_URL", "http://livecontext-livecontext-catalog:8081"))))
            .withUserConfiguration(TestConfig.class)
            .run(ctx -> assertThat(ctx.getBean(StorageMappingConfig.class).getCatalogBaseUrl())
                .isEqualTo("http://livecontext-livecontext-catalog:8081"));
    }

    @Test
    @DisplayName("explicit storage.mapping.catalog-base-url wins over services.catalog-url")
    void explicitOverrideWinsOverPlatformUrl() {
        runner.withPropertyValues(
                "services.catalog-url=http://livecontext-livecontext-catalog:8081",
                "storage.mapping.catalog-base-url=http://explicit-override:9999")
            .run(ctx -> assertThat(ctx.getBean(StorageMappingConfig.class).getCatalogBaseUrl())
                .isEqualTo("http://explicit-override:9999"));
    }

    @Test
    @DisplayName("falls back to localhost:8081 when neither property is set (plain single-host dev)")
    void fallsBackToLocalhostWhenNothingSet() {
        runner.run(ctx -> assertThat(ctx.getBean(StorageMappingConfig.class).getCatalogBaseUrl())
            .isEqualTo("http://localhost:8081"));
    }

    @EnableConfigurationProperties(StorageMappingConfig.class)
    static class TestConfig {
    }
}
