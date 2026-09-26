package com.apimarketplace.auth.lifecycle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Resend client of the lifecycle emails. The bean always exists (so nothing has to
 * null-check it) and is INERT unless {@code lifecycle.resend.enabled=true} AND an api key is
 * set, which only the cloud deployment does. The CE monolith never sets either property, so
 * the defaults below keep it a no-op there.
 */
@Configuration
public class LifecycleEmailConfig {

    @Bean
    public ResendClient lifecycleResendClient(
            @Value("${lifecycle.resend.enabled:false}") boolean enabled,
            @Value("${lifecycle.resend.api-key:}") String apiKey,
            @Value("${lifecycle.resend.base-url:" + ResendClient.DEFAULT_BASE_URL + "}") String baseUrl) {
        return new ResendClient(enabled, apiKey, baseUrl);
    }
}
