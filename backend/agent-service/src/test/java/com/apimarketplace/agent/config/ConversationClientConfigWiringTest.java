package com.apimarketplace.agent.config;

import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the k3s conversation-service wiring incident (2026-06-08).
 *
 * <p>agent-service's {@link ConversationClient} must read the standard
 * {@code services.conversation-url} (helm injects {@code SERVICES_CONVERSATION_URL} for every
 * service). The original code read the non-standard {@code agent.conversation.base-url}, which
 * helm never injected, so on k3s it fell back to {@code http://localhost:8087} - a different
 * pod - and stream register/finalize plus sub-agent / task conversation creation failed with
 * "Connection refused" after the migration.
 *
 * <p>The legacy property is kept as a fallback so docker-compose / systemd keep working.
 * Pre-fix the {@code services.conversation-url} case fails (the property was ignored).
 */
@DisplayName("agent-service → conversation-service URL wiring")
class ConversationClientConfigWiringTest {

    private static final String CLUSTER_URL = "http://livecontext-conversation:8087";
    private static final String LEGACY_URL = "http://legacy-host:8087";
    private static final String DEFAULT_URL = "http://localhost:8087";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RestTemplate.class, RestTemplate::new)
            .withUserConfiguration(ConversationClientConfig.class);

    @Test
    @DisplayName("binds services.conversation-url (helm-injected) when present")
    void usesServicesUrl() {
        runner.withPropertyValues("services.conversation-url=" + CLUSTER_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationClient.class).getBaseUrl())
                        .isEqualTo(CLUSTER_URL));
    }

    @Test
    @DisplayName("falls back to legacy agent.conversation.base-url (compose/systemd)")
    void fallsBackToLegacy() {
        runner.withPropertyValues("agent.conversation.base-url=" + LEGACY_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationClient.class).getBaseUrl())
                        .isEqualTo(LEGACY_URL));
    }

    @Test
    @DisplayName("defaults to localhost only when no URL is configured")
    void defaults() {
        runner.run(ctx -> assertThat(ctx.getBean(ConversationClient.class).getBaseUrl())
                .isEqualTo(DEFAULT_URL));
    }

    @Test
    @DisplayName("services.conversation-url wins over the legacy property")
    void servicesUrlWinsOverLegacy() {
        runner.withPropertyValues(
                        "services.conversation-url=" + CLUSTER_URL,
                        "agent.conversation.base-url=" + LEGACY_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationClient.class).getBaseUrl())
                        .isEqualTo(CLUSTER_URL));
    }
}
