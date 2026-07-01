package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CeLinkEntitlementsService;
import com.apimarketplace.auth.service.CeLinkHeartbeatService;
import com.apimarketplace.auth.service.CeLinkRewardReadService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.IpHashService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Bean-gating contract for {@link CeLinkController}: the class is decorated with
 * {@code @ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak")},
 * so the CE-link REST surface is Cloud-only. CE (auth.mode=embedded) must NOT
 * wire it. All five constructor dependencies are supplied as mocks so that the
 * keycloak-mode positive control can actually instantiate the controller, which
 * proves the gate keys on the property value rather than always omitting the bean.
 */
@DisplayName("CeLinkController bean condition (auth.mode gate)")
class CeLinkControllerBeanConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CeLinkControllerImport.class)
            .withBean(CeLinkService.class, () -> mock(CeLinkService.class))
            .withBean(CeLinkHeartbeatService.class, () -> mock(CeLinkHeartbeatService.class))
            .withBean(IpHashService.class, () -> mock(IpHashService.class))
            .withBean(CeLinkEntitlementsService.class, () -> mock(CeLinkEntitlementsService.class))
            .withBean(CeLinkRewardReadService.class, () -> mock(CeLinkRewardReadService.class));

    @Test
    @DisplayName("embeddedAuthModeOmitsCeLinkControllerBean - CE never exposes /api/ce-link")
    void embeddedAuthModeOmitsCeLinkControllerBean() {
        contextRunner
                .withPropertyValues("auth.mode=embedded")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CeLinkController.class);
                });
    }

    @Test
    @DisplayName("keycloakAuthModeWiresCeLinkControllerBean - Cloud exposes the CE-link endpoints")
    void keycloakAuthModeWiresCeLinkControllerBean() {
        contextRunner
                .withPropertyValues("auth.mode=keycloak")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CeLinkController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(CeLinkController.class)
    static class CeLinkControllerImport {
    }
}
