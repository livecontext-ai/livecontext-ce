package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.RemoteMarketplaceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link RemoteMarketplaceController}.
 *
 * <p>The controller is annotated
 * {@code @ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")} with NO
 * {@code matchIfMissing}, so it defaults to false: the CE-side remote-marketplace acquire/proxy
 * surface is wired ONLY when {@code marketplace.mode=remote} (the cloud-linked CE monolith). On a
 * cloud install or a plain CE (property unset, or {@code marketplace.mode=local}) the controller
 * MUST NOT exist, otherwise its routes would forward marketplace reads/acquires through a cloud
 * link that is not configured. These tests pin that contract so a regression in the condition
 * (flipping {@code matchIfMissing} or renaming the property) is caught.
 */
@DisplayName("RemoteMarketplaceController - marketplace.mode=remote bean gating")
class RemoteMarketplaceControllerBeanGatingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RemoteMarketplaceService.class, () -> mock(RemoteMarketplaceService.class))
            .withUserConfiguration(RemoteMarketplaceControllerImport.class);

    @Test
    @DisplayName("Property unset - controller bean is ABSENT (matchIfMissing defaults to false)")
    void beanAbsentWhenPropertyUnset() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(RemoteMarketplaceController.class);
        });
    }

    @Test
    @DisplayName("marketplace.mode=local - controller bean is ABSENT (no remote proxy on a non-remote install)")
    void beanAbsentWhenModeLocal() {
        contextRunner
                .withPropertyValues("marketplace.mode=local")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(RemoteMarketplaceController.class);
                });
    }

    @Test
    @DisplayName("marketplace.mode=remote - controller bean IS wired exactly once (cloud-linked CE monolith)")
    void beanPresentWhenModeRemote() {
        contextRunner
                .withPropertyValues("marketplace.mode=remote")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RemoteMarketplaceController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RemoteMarketplaceController.class)
    static class RemoteMarketplaceControllerImport {
    }
}
