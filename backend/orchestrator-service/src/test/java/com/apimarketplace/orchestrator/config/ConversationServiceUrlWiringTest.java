package com.apimarketplace.orchestrator.config;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import com.apimarketplace.orchestrator.trigger.ChatDispatchService;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression for the k3s "scheduled agent never launches" incident (Nova, 2026-06-08).
 *
 * <p>Every orchestrator → conversation-service client MUST read the standard
 * {@code services.conversation-url} property. Helm injects {@code SERVICES_CONVERSATION_URL}
 * for every service via commonEnv (same convention as {@code services.agent-url},
 * {@code services.trigger-url}, …). The original code read the non-standard
 * {@code orchestrator.conversation.base-url}, which helm never injected, so on k3s it fell
 * back to {@code http://localhost:8087} - a different pod - and every call failed with
 * "Connection refused". The schedule advanced but {@code findOrCreateAgentConversation}
 * returned null, so the agent was never dispatched (and no bridge access check ever ran).
 *
 * <p>Each binding also keeps the legacy property as a fallback so docker-compose / systemd,
 * which still set {@code orchestrator.conversation.base-url}, keep working unchanged.
 *
 * <p>Pre-fix these tests fail on the {@code services.conversation-url} cases (the property was
 * ignored, the bean used the localhost default).
 */
@DisplayName("orchestrator → conversation-service URL wiring")
class ConversationServiceUrlWiringTest {

    private static final String CLUSTER_URL = "http://livecontext-conversation:8087";
    private static final String LEGACY_URL = "http://legacy-host:8087";
    private static final String DEFAULT_URL = "http://localhost:8087";

    // ---- ConversationClient bean - the schedule-agent dispatch path that broke Nova ----

    private final ApplicationContextRunner clientRunner = new ApplicationContextRunner()
            .withBean(RestTemplate.class, RestTemplate::new)
            .withUserConfiguration(ConversationClientConfig.class);

    @Test
    @DisplayName("ConversationClient binds services.conversation-url (helm-injected) when present")
    void conversationClientUsesServicesUrl() {
        clientRunner.withPropertyValues("services.conversation-url=" + CLUSTER_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationClient.class).getBaseUrl())
                        .isEqualTo(CLUSTER_URL));
    }

    @Test
    @DisplayName("ConversationClient falls back to legacy orchestrator.conversation.base-url (compose/systemd)")
    void conversationClientFallsBackToLegacy() {
        clientRunner.withPropertyValues("orchestrator.conversation.base-url=" + LEGACY_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationClient.class).getBaseUrl())
                        .isEqualTo(LEGACY_URL));
    }

    @Test
    @DisplayName("ConversationClient defaults to localhost only when no URL is configured")
    void conversationClientDefaults() {
        clientRunner.run(ctx -> assertThat(ctx.getBean(ConversationClient.class).getBaseUrl())
                .isEqualTo(DEFAULT_URL));
    }

    @Test
    @DisplayName("ConversationClient: services.conversation-url wins over the legacy property")
    void conversationClientServicesUrlWinsOverLegacy() {
        clientRunner.withPropertyValues(
                        "services.conversation-url=" + CLUSTER_URL,
                        "orchestrator.conversation.base-url=" + LEGACY_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationClient.class).getBaseUrl())
                        .isEqualTo(CLUSTER_URL));
    }

    // ---- ConversationStorageClient - daily storage-usage accounting ----

    private final ApplicationContextRunner storageRunner = new ApplicationContextRunner()
            .withBean(ConversationStorageClient.class);

    @Test
    @DisplayName("ConversationStorageClient binds services.conversation-url when present")
    void storageClientUsesServicesUrl() {
        storageRunner.withPropertyValues("services.conversation-url=" + CLUSTER_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationStorageClient.class).getBaseUrl())
                        .isEqualTo(CLUSTER_URL));
    }

    @Test
    @DisplayName("ConversationStorageClient falls back to the legacy property")
    void storageClientFallsBackToLegacy() {
        storageRunner.withPropertyValues("orchestrator.conversation.base-url=" + LEGACY_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationStorageClient.class).getBaseUrl())
                        .isEqualTo(LEGACY_URL));
    }

    @Test
    @DisplayName("ConversationStorageClient defaults to localhost only when no URL is configured")
    void storageClientDefaults() {
        storageRunner.run(ctx -> assertThat(ctx.getBean(ConversationStorageClient.class).getBaseUrl())
                .isEqualTo(DEFAULT_URL));
    }

    @Test
    @DisplayName("ConversationStorageClient: services.conversation-url wins over the legacy property")
    void storageClientServicesUrlWinsOverLegacy() {
        storageRunner.withPropertyValues(
                        "services.conversation-url=" + CLUSTER_URL,
                        "orchestrator.conversation.base-url=" + LEGACY_URL)
                .run(ctx -> assertThat(ctx.getBean(ConversationStorageClient.class).getBaseUrl())
                        .isEqualTo(CLUSTER_URL));
    }

    // ---- ChatDispatchService - chat-trigger dispatch path ----

    /** Context with every ChatDispatchService dependency mocked, so only the @Value URL binding matters. */
    private ApplicationContextRunner chatDispatchRunner() {
        return new ApplicationContextRunner()
                .withBean(TriggerClient.class, () -> mock(TriggerClient.class))
                .withBean(RedisTemplate.class, () -> mock(RedisTemplate.class))
                .withBean(RestTemplate.class, () -> mock(RestTemplate.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(WorkflowRepository.class, () -> mock(WorkflowRepository.class))
                .withBean(WorkflowRunRepository.class, () -> mock(WorkflowRunRepository.class))
                .withBean(ReusableTriggerService.class, () -> mock(ReusableTriggerService.class))
                .withBean(ProductionRunResolver.class, () -> mock(ProductionRunResolver.class))
                .withBean(CreditConsumptionClient.class, () -> mock(CreditConsumptionClient.class))
                .withBean(AgentDefaultsConfig.class, () -> mock(AgentDefaultsConfig.class))
                .withBean(AgentClient.class, () -> mock(AgentClient.class))
                .withBean(RunContextService.class, () -> mock(RunContextService.class))
                .withBean(ChatDispatchService.class);
    }

    @Test
    @DisplayName("ChatDispatchService binds services.conversation-url when present")
    void chatDispatchUsesServicesUrl() {
        chatDispatchRunner().withPropertyValues("services.conversation-url=" + CLUSTER_URL)
                .run(ctx -> assertThat(ctx.getBean(ChatDispatchService.class).getConversationServiceUrl())
                        .isEqualTo(CLUSTER_URL));
    }

    @Test
    @DisplayName("ChatDispatchService falls back to the legacy property")
    void chatDispatchFallsBackToLegacy() {
        chatDispatchRunner().withPropertyValues("orchestrator.conversation.base-url=" + LEGACY_URL)
                .run(ctx -> assertThat(ctx.getBean(ChatDispatchService.class).getConversationServiceUrl())
                        .isEqualTo(LEGACY_URL));
    }

    @Test
    @DisplayName("ChatDispatchService defaults to localhost only when no URL is configured")
    void chatDispatchDefaults() {
        chatDispatchRunner()
                .run(ctx -> assertThat(ctx.getBean(ChatDispatchService.class).getConversationServiceUrl())
                        .isEqualTo(DEFAULT_URL));
    }

    @Test
    @DisplayName("ChatDispatchService: services.conversation-url wins over the legacy property")
    void chatDispatchServicesUrlWinsOverLegacy() {
        chatDispatchRunner().withPropertyValues(
                        "services.conversation-url=" + CLUSTER_URL,
                        "orchestrator.conversation.base-url=" + LEGACY_URL)
                .run(ctx -> assertThat(ctx.getBean(ChatDispatchService.class).getConversationServiceUrl())
                        .isEqualTo(CLUSTER_URL));
    }
}
