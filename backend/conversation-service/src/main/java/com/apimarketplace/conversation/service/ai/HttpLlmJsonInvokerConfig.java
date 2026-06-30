package com.apimarketplace.conversation.service.ai;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Stage 2 follow-up (#51) - builds the dedicated {@link RestTemplate} used by
 * {@link HttpLlmJsonInvoker} to reach agent-service's json-completion
 * endpoint. Lives in its own bean method (rather than inline in the invoker
 * constructor) so the invoker has a single unambiguous constructor Spring
 * can auto-wire, and so the timeouts are visible and tunable in one place.
 *
 * <p>The {@code @Qualifier}-tagged bean avoids clobbering any default
 * {@code RestTemplate} other components might register - cold-summary
 * latency budgets (10s connect, 90s read) are intentionally looser than a
 * typical microservice hop.
 */
@Configuration
public class HttpLlmJsonInvokerConfig {

    @Bean(name = "llmJsonInvokerRestTemplate")
    public RestTemplate llmJsonInvokerRestTemplate() {
        return new RestTemplateBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(90))
                .build();
    }
}
