package com.apimarketplace.common.analytics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;

/**
 * Auto-registers a single shared {@link PostHogAnalyticsClient} into every
 * service that depends on common-lib (registered via
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}).
 *
 * <p>The bean is ALWAYS created so injection points never need null-checks; it is
 * internally a no-op unless {@code posthog.enabled=true} AND {@code posthog.api-key}
 * is non-blank. Defaults are off (so CE / local / un-configured deployments emit
 * nothing); Cloud turns it on via each service's {@code application.yml}.</p>
 */
@AutoConfiguration
public class PostHogAutoConfiguration {

    @Bean
    public PostHogAnalyticsClient postHogAnalyticsClient(
            @Value("${posthog.enabled:false}") boolean enabled,
            @Value("${posthog.api-key:}") String apiKey,
            @Value("${posthog.host:https://eu.i.posthog.com}") String host) {
        return new PostHogAnalyticsClient(enabled, apiKey, host);
    }
}
