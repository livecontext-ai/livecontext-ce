package com.apimarketplace.agent.webhook;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentWebhookDispatchService")
class AgentWebhookDispatchServiceTest {

    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TOKEN = "token-prod";
    private static final String TENANT_ID = "tenant-webhook";
    private static final String ORGANIZATION_ID = "org-webhook";
    private static final String CONVERSATION_ID = "conv-webhook";

    @Mock
    private AgentWebhookTokenService tokenService;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private ConversationClient conversationClient;

    private AgentWebhookDispatchService service;

    @BeforeEach
    void setUp() {
        service = new AgentWebhookDispatchService(
            tokenService, agentRepository, conversationClient, new ObjectMapper());
    }

    @Test
    @DisplayName("Memory webhook reuses the org-scoped agent conversation and dispatches source=WEBHOOK with org")
    void memoryWebhookForwardsOrganizationToConversationAndSyncDispatch() {
        AgentWebhookTokenEntity token = token(true);
        AgentEntity agent = agent();

        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        when(conversationClient.findOrCreateAgentConversation(
            AGENT_ID.toString(), TENANT_ID, "Webhook Bot", ORGANIZATION_ID))
            .thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID)))
            .thenReturn(Map.of("success", true, "content", "ok"));

        AgentWebhookResponse response = service.dispatch(TOKEN, Map.of("event", "created"), true);

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        verify(conversationClient).findOrCreateAgentConversation(
            AGENT_ID.toString(), TENANT_ID, "Webhook Bot", ORGANIZATION_ID);
        verify(conversationClient).sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID));
    }

    @Test
    @DisplayName("Memory-off webhook creates the isolated conversation in the agent org before queued dispatch")
    void memoryOffWebhookForwardsOrganizationToIsolatedConversationAndSyncDispatch() {
        AgentWebhookTokenEntity token = token(false);
        AgentEntity agent = agent();

        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        when(conversationClient.createConversation(
            eq(TENANT_ID), startsWith("Webhook: Webhook Bot"),
            eq("deepseek-chat"), eq("deepseek"), eq(AGENT_ID.toString()), eq(false), eq(ORGANIZATION_ID)))
            .thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID)))
            .thenReturn(Map.of("success", true, "content", "ok"));

        AgentWebhookResponse response = service.dispatch(TOKEN, Map.of("event", "created"), true);

        assertThat(response.status()).isEqualTo("success");
        verify(conversationClient).createConversation(
            eq(TENANT_ID), startsWith("Webhook: Webhook Bot"),
            eq("deepseek-chat"), eq("deepseek"), eq(AGENT_ID.toString()), eq(false), eq(ORGANIZATION_ID));
        verify(conversationClient).sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID));
    }

    private AgentWebhookTokenEntity token(boolean memoryEnabled) {
        AgentWebhookTokenEntity token = new AgentWebhookTokenEntity(AGENT_ID, TOKEN);
        token.setMemoryEnabled(memoryEnabled);
        token.setIsActive(true);
        return token;
    }

    private AgentEntity agent() {
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setTenantId(TENANT_ID);
        agent.setOrganizationId(ORGANIZATION_ID);
        agent.setName("Webhook Bot");
        agent.setModelProvider("deepseek");
        agent.setModelName("deepseek-chat");
        agent.setIsActive(true);
        return agent;
    }
}
