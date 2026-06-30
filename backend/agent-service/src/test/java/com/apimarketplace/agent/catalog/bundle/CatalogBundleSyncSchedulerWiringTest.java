package com.apimarketplace.agent.catalog.bundle;

import com.apimarketplace.agent.repository.CatalogBundleSyncStatusRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.annotation.UserConfigurations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating regression for {@link CatalogBundleSyncScheduler}'s
 * {@code @ConditionalOnProperty(name = "catalog.bundle.sync.enabled", havingValue = "true")}.
 *
 * <p>The scheduler is CE-only: cloud instances must NEVER run it. The class-level
 * condition is the single line that keeps a cloud pod from periodically fetching and
 * applying a model-catalog bundle. If a refactor drops or weakens that annotation the
 * unit tests in {@link CatalogBundleSyncSchedulerTest} (which instantiate the bean
 * directly) would still pass, so the absence of the bean is proven here against a real
 * (minimal) Spring context.
 *
 * <p>The collaborators are registered as mocks so the bean is constructible whenever the
 * condition matches - the assertions are purely about presence/absence of the scheduler
 * bean, never its behavior.
 */
@DisplayName("CatalogBundleSyncScheduler - @ConditionalOnProperty bean gating (CE-only)")
class CatalogBundleSyncSchedulerWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(CatalogBundleFetcher.class, () -> mock(CatalogBundleFetcher.class))
            .withBean(CatalogBundleVerifier.class, () -> mock(CatalogBundleVerifier.class))
            .withBean(CatalogBundleApplier.class, () -> mock(CatalogBundleApplier.class))
            .withBean(CatalogBundleSyncStatusRepository.class,
                    () -> mock(CatalogBundleSyncStatusRepository.class))
            .withBean(TrustedKeyRegistry.class, () -> mock(TrustedKeyRegistry.class))
            .withBean(CatalogBundleTrustBootstrap.class, () -> mock(CatalogBundleTrustBootstrap.class))
            .withConfiguration(UserConfigurations.of(CatalogBundleSyncScheduler.class));

    @Test
    @DisplayName("catalog.bundle.sync.enabled=true → scheduler bean IS wired (CE install)")
    void wiredWhenEnabledTrue() {
        runner.withPropertyValues("catalog.bundle.sync.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(CatalogBundleSyncScheduler.class));
    }

    @Test
    @DisplayName("property absent (cloud default) → scheduler bean is NOT wired")
    void notWiredWhenPropertyAbsent() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(CatalogBundleSyncScheduler.class));
    }

    @Test
    @DisplayName("catalog.bundle.sync.enabled=false → scheduler bean is NOT wired")
    void notWiredWhenEnabledFalse() {
        runner.withPropertyValues("catalog.bundle.sync.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CatalogBundleSyncScheduler.class));
    }

    @Test
    @DisplayName("non-true value (catalog.bundle.sync.enabled=yes) → scheduler bean is NOT wired")
    void notWiredWhenEnabledNonTrueValue() {
        runner.withPropertyValues("catalog.bundle.sync.enabled=yes")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(CatalogBundleSyncScheduler.class));
    }
}
