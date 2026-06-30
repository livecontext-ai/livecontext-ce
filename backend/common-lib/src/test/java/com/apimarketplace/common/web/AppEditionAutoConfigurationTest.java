package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that {@link AppEditionAutoConfiguration} registers the provider via
 * Spring Boot's auto-configuration import mechanism, i.e. without any service
 * needing to extend its {@code @ComponentScan} to {@code common.web}.
 */
@DisplayName("AppEditionAutoConfiguration")
class AppEditionAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AppEditionAutoConfiguration.class));

    @Test
    @DisplayName("Auto-config registers AppEditionProvider with no component scan")
    void registersProviderWithoutComponentScan() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AppEditionProvider.class);
            assertThat(context.getBean(AppEditionProvider.class).isCe()).isFalse();
        });
    }

    @Test
    @DisplayName("@ActiveProfiles(ce) resolves to CE Free")
    void ceProfileResolvesToCeFree() {
        contextRunner.withPropertyValues("spring.profiles.active=ce").run(context -> {
            AppEditionProvider provider = context.getBean(AppEditionProvider.class);
            assertThat(provider.get()).isEqualTo(AppEditionProvider.AppEdition.CE_FREE);
            assertThat(provider.isCeFree()).isTrue();
        });
    }

    @Test
    @DisplayName("app.edition=self-hosted-enterprise registers Self-Hosted Enterprise")
    void explicitSelfHostedEnterpriseEdition() {
        contextRunner
                .withPropertyValues("spring.profiles.active=ce", "app.edition=self-hosted-enterprise")
                .run(context -> {
                    AppEditionProvider provider = context.getBean(AppEditionProvider.class);
                    assertThat(provider.get()).isEqualTo(AppEditionProvider.AppEdition.SELF_HOSTED_ENTERPRISE);
                    assertThat(provider.isCe()).isFalse();
                    assertThat(provider.isSelfHostedEnterprise()).isTrue();
                });
    }

    @Test
    @DisplayName("@ConditionalOnMissingBean caller-provided AppEditionProvider takes precedence")
    void userProviderOverridesAutoConfig() {
        contextRunner
                .withBean(AppEditionProvider.class, () -> {
                    org.springframework.mock.env.MockEnvironment env =
                            new org.springframework.mock.env.MockEnvironment();
                    env.setActiveProfiles("ce");
                    return new AppEditionProvider(env);
                })
                .run(context -> {
                    assertThat(context).hasSingleBean(AppEditionProvider.class);
                    assertThat(context.getBean(AppEditionProvider.class).isCe()).isTrue();
                });
    }
}
