package com.apimarketplace.publication.controller;

import com.apimarketplace.common.web.GatewayFilterProperties;
import com.apimarketplace.publication.service.PublicationHighlightService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Cloud-only bean gating for {@link PublicationHighlightAdminController}.
 *
 * <p>The controller is annotated
 * {@code @ConditionalOnProperty(name = "marketplace.curation.admin-enabled", havingValue = "true",
 * matchIfMissing = false)}. CE distributions leave the property unset, so the admin curation
 * endpoint must NOT be wired (its GET/PUT return 404). Cloud sets it to {@code true} to expose the
 * curation surface. These tests pin that contract so a regression in the condition (e.g. flipping
 * {@code matchIfMissing} or renaming the property) is caught.
 */
@DisplayName("PublicationHighlightAdminController cloud-only bean gating")
class PublicationHighlightAdminControllerBeanConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PublicationHighlightService.class, () -> mock(PublicationHighlightService.class))
            .withBean(GatewayFilterProperties.class, GatewayFilterProperties::new)
            .withUserConfiguration(AdminControllerImport.class);

    @Test
    @DisplayName("CE default (property missing) does not wire the admin curation controller")
    void absentWhenPropertyMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PublicationHighlightAdminController.class);
        });
    }

    @Test
    @DisplayName("explicit marketplace.curation.admin-enabled=false does not wire the controller")
    void absentWhenPropertyFalse() {
        contextRunner
                .withPropertyValues("marketplace.curation.admin-enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PublicationHighlightAdminController.class);
                });
    }

    @Test
    @DisplayName("cloud marketplace.curation.admin-enabled=true wires the controller exactly once")
    void presentWhenPropertyTrue() {
        contextRunner
                .withPropertyValues("marketplace.curation.admin-enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PublicationHighlightAdminController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(PublicationHighlightAdminController.class)
    static class AdminControllerImport {
    }
}
