package com.apimarketplace.auth.web;

import com.apimarketplace.auth.ce.CeInstallStateService;
import com.apimarketplace.auth.service.OrganizationMemberService;
import com.apimarketplace.auth.service.PasswordAuthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contract test for the CE/Cloud gating of {@link EmbeddedAuthController}.
 *
 * <p>The controller is annotated {@code @ConditionalOnProperty(name = "auth.mode",
 * havingValue = "embedded")}, so the embedded email+password endpoints
 * (/api/auth/register, /login, ...) must exist ONLY in CE (auth.mode=embedded)
 * and must NEVER be wired in Cloud (auth.mode=keycloak) or when the property is
 * unset (matchIfMissing defaults to false). The functional
 * {@link EmbeddedAuthControllerTest} instantiates the controller directly with
 * mocks and therefore cannot observe this Spring conditional; this test pins it
 * with an {@link ApplicationContextRunner}, mirroring
 * {@code CeLinkCloudOnlyBeanConditionTest}.
 */
@DisplayName("EmbeddedAuthController @ConditionalOnProperty(auth.mode=embedded) bean wiring")
class EmbeddedAuthControllerConditionalWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PasswordAuthService.class, () -> mock(PasswordAuthService.class))
            .withBean(CeInstallStateService.class, () -> mock(CeInstallStateService.class))
            .withBean(OrganizationMemberService.class, () -> mock(OrganizationMemberService.class))
            .withUserConfiguration(EmbeddedAuthController.class);

    @Test
    @DisplayName("auth.mode=embedded wires the controller (CE email+password endpoints active)")
    void embeddedModeWiresController() {
        contextRunner
                .withPropertyValues("auth.mode=embedded")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EmbeddedAuthController.class);
                });
    }

    @Test
    @DisplayName("auth.mode=keycloak does NOT wire the controller (Cloud must not expose CE register/login)")
    void keycloakModeDoesNotWireController() {
        contextRunner
                .withPropertyValues("auth.mode=keycloak")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmbeddedAuthController.class);
                });
    }

    @Test
    @DisplayName("auth.mode unset does NOT wire the controller (matchIfMissing=false default)")
    void unsetModeDoesNotWireController() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(EmbeddedAuthController.class);
                });
    }

}
