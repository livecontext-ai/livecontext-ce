package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CE link cloud-only bean conditions")
class CeLinkCloudOnlyBeanConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(IpHashServiceImport.class);

    @Test
    @DisplayName("embeddedAuthSkipsCloudIpHashBeanWithoutIpHashKey - CE boots without cloud registry secrets")
    void embeddedAuthSkipsCloudIpHashBeanWithoutIpHashKey() {
        contextRunner
                .withPropertyValues("auth.mode=embedded")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(IpHashService.class);
                });
    }

    @Test
    @DisplayName("keycloakAuthStillRequiresIpHashKey - cloud keeps fail-fast HMAC configuration")
    void keycloakAuthStillRequiresIpHashKey() {
        contextRunner
                .withPropertyValues("auth.mode=keycloak")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCause(context.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("cloud-link.ip-hash.key-v1 must be set");
                });
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Configuration(proxyBeanMethods = false)
    @Import(IpHashService.class)
    static class IpHashServiceImport {
    }
}
