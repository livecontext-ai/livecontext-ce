package com.apimarketplace.orchestrator.services.state.elide;

import com.apimarketplace.orchestrator.services.flag.TenantFlagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Integration test for the {@link TenantElideFlagResolver} bean precedence
 * (audit B round-2 MUST-FIX + audit A round-2 NICE-TO-HAVE).
 *
 * <p>The contract: when both {@link DefaultTenantElideFlagResolver} (default-OFF
 * fallback) and {@link TenantFlagBackedElideResolver} (@Primary, depends on
 * {@link TenantFlagService}) are on the classpath, Spring MUST inject the
 * primary one. A misconfigured wiring would silently leave production on
 * default-OFF - no flag flip would ever take effect.
 *
 * <p>Pattern: spin up a minimal {@code @SpringBootTest} that scans both
 * resolver classes, mock {@link TenantFlagService} (so we don't need DB),
 * verify the autowired {@link TenantElideFlagResolver} is the backed one.
 */
@SpringBootTest(classes = ElideResolverPrecedenceTest.TestApp.class)
@DisplayName("ElideResolver bean precedence - @Primary wins over default")
class ElideResolverPrecedenceTest {

    @Configuration
    @ComponentScan(
            basePackageClasses = TenantFlagBackedElideResolver.class,
            // Exclude StateSnapshotMapperConfig - it depends on the default Spring
            // ObjectMapper bean which isn't provided by this minimal test context.
            // We're only testing resolver-bean precedence here, not the mapper wiring
            // (covered by EpochStateRunningElideSerializerTest + integration tests).
            excludeFilters = @ComponentScan.Filter(
                    type = FilterType.ASSIGNABLE_TYPE,
                    classes = StateSnapshotMapperConfig.class))
    static class TestApp {
    }

    @MockBean private TenantFlagService flagService;
    @Autowired private TenantElideFlagResolver resolver;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("@Primary TenantFlagBackedElideResolver wins over @ConditionalOnMissingBean DefaultTenantElideFlagResolver")
    void primaryResolverWins() {
        assertThat(resolver).isInstanceOf(TenantFlagBackedElideResolver.class);
    }

    @Test
    @DisplayName("DefaultTenantElideFlagResolver is excluded by @ConditionalOnMissingBean when the backed bean is present")
    void defaultBeanExcluded() {
        // The @ConditionalOnMissingBean(TenantElideFlagResolver.class) on the
        // default bean: when ANY other bean of TenantElideFlagResolver is
        // already registered, the default doesn't load. Spring's bean catalog
        // must therefore have exactly ONE TenantElideFlagResolver (the backed one).
        var beans = context.getBeansOfType(TenantElideFlagResolver.class);
        assertThat(beans).hasSize(1);
        assertThat(beans.values()).allMatch(b -> b instanceof TenantFlagBackedElideResolver);
    }

    @Test
    @DisplayName("Resolver delegates to TenantFlagService.getValue (default ON when no row)")
    void resolverDelegatesToFlagService() {
        when(flagService.getValue("state-snapshot.elide-running-nodes", "tenant-T1"))
                .thenReturn(java.util.Optional.empty());

        // Default-ON contract since 2026-05-08: missing row → elide enabled.
        assertThat(resolver.isElideEnabled("tenant-T1")).isTrue();
    }
}
