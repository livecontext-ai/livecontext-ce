package com.apimarketplace.auth.web;

import com.apimarketplace.auth.ce.CeInstallStateService;
import com.apimarketplace.auth.repository.PasswordResetTokenRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.OrganizationMemberService;
import com.apimarketplace.auth.service.PasswordAuthService;
import com.apimarketplace.auth.service.PasswordResetMailer;
import com.apimarketplace.auth.service.PasswordResetService;
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
            .withBean(PasswordResetService.class, () -> mock(PasswordResetService.class))
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

    /**
     * The reset flow has to be gated the SAME way as the controller that exposes
     * it. Cloud delegates password reset to Keycloak, so a PasswordResetService
     * wired there would be a second, unreachable reset path issuing tokens for
     * accounts whose passwords Keycloak owns. The controller test above cannot
     * see this: it mocks the service, so the service's own condition is never
     * evaluated.
     */
    private final ApplicationContextRunner resetServiceRunner = new ApplicationContextRunner()
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(PasswordResetTokenRepository.class, () -> mock(PasswordResetTokenRepository.class))
            .withBean(PasswordAuthService.class, () -> mock(PasswordAuthService.class))
            .withBean(PasswordResetMailer.class, () -> mock(PasswordResetMailer.class))
            .withUserConfiguration(PasswordResetService.class);

    @Test
    @DisplayName("auth.mode=embedded wires PasswordResetService (CE is the only edition that owns passwords)")
    void embeddedModeWiresResetService() {
        resetServiceRunner
                .withPropertyValues("auth.mode=embedded")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(PasswordResetService.class);
                });
    }

    /**
     * The MAILER's gate, which nothing else evaluates: every other test supplies
     * it as a mock, so removing its condition would leave cloud instantiating a
     * mail pool for a feature it cannot run, with the suite green.
     */
    private final ApplicationContextRunner mailerRunner = new ApplicationContextRunner()
            .withBean(org.springframework.mail.javamail.JavaMailSender.class,
                    () -> mock(org.springframework.mail.javamail.JavaMailSender.class))
            .withUserConfiguration(PasswordResetMailer.class);

    @Test
    @DisplayName("auth.mode=embedded wires PasswordResetMailer, and keycloak does not")
    void mailerFollowsTheSameGate() {
        mailerRunner.withPropertyValues("auth.mode=embedded")
                .run(context -> assertThat(context).hasSingleBean(PasswordResetMailer.class));
        mailerRunner.withPropertyValues("auth.mode=keycloak")
                .run(context -> assertThat(context).doesNotHaveBean(PasswordResetMailer.class));
        mailerRunner.run(context -> assertThat(context).doesNotHaveBean(PasswordResetMailer.class));
    }

    @Test
    @DisplayName("auth.mode=keycloak does NOT wire PasswordResetService (Keycloak owns reset on Cloud)")
    void keycloakModeDoesNotWireResetService() {
        resetServiceRunner
                .withPropertyValues("auth.mode=keycloak")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PasswordResetService.class);
                });
    }

    @Test
    @DisplayName("auth.mode unset does NOT wire PasswordResetService (matchIfMissing=false default)")
    void unsetModeDoesNotWireResetService() {
        resetServiceRunner
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(PasswordResetService.class);
                });
    }
}
