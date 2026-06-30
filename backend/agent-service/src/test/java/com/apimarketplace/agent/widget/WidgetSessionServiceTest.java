package com.apimarketplace.agent.widget;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WidgetSessionService Tests")
class WidgetSessionServiceTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ConversationClient conversationClient;

    private WidgetSessionService service;

    private static final UUID AGENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TENANT_ID = "tenant-123";
    private static final String ORGANIZATION_ID = "org-widget";
    private static final String CONVERSATION_ID = "conv-456";

    @BeforeEach
    void setUp() {
        service = new WidgetSessionService(redisTemplate, conversationClient);
        lenient().when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    private AgentEntity createAgent(String name, String model, String provider) {
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setTenantId(TENANT_ID);
        agent.setName(name);
        agent.setModelName(model);
        agent.setModelProvider(provider);
        agent.setOrganizationId(ORGANIZATION_ID);
        return agent;
    }

    // ---------------------------------------------------------------------------
    // createSession
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("createSession()")
    class CreateSessionTests {

        @Test
        @DisplayName("resolves the agent's unique conversation via findOrCreateAgentConversation (one agent = one conversation)")
        void shouldResolveAgentConversation() {
            AgentEntity agent = createAgent("TestBot", "claude-sonnet-4-20250514", "anthropic");
            when(conversationClient.findOrCreateAgentConversation(
                    eq(AGENT_ID.toString()), eq(TENANT_ID), eq("TestBot"), eq(ORGANIZATION_ID)))
                    .thenReturn(CONVERSATION_ID);

            WidgetSessionService.WidgetSession session = service.createSession(agent, "127.0.0.1", "Mozilla/5.0");

            assertThat(session).isNotNull();
            assertThat(session.sessionId()).isNotBlank();
            assertThat(session.conversationId()).isEqualTo(CONVERSATION_ID);
            assertThat(session.agentId()).isEqualTo(AGENT_ID);
            assertThat(session.tenantId()).isEqualTo(TENANT_ID);
            assertThat(session.organizationId()).isEqualTo(ORGANIZATION_ID);
            assertThat(session.ipAddress()).isEqualTo("127.0.0.1");

            verify(conversationClient).findOrCreateAgentConversation(
                    AGENT_ID.toString(), TENANT_ID, "TestBot", ORGANIZATION_ID);
            verify(hashOperations).putAll(startsWith("widget:session:"), anyMap());
            verify(redisTemplate).expire(startsWith("widget:session:"), any());
        }

        @Test
        @DisplayName("must NEVER call createConversation - that would violate one-agent-one-conversation")
        void shouldNeverBlindCreateConversation() {
            AgentEntity agent = createAgent("TestBot", "claude-sonnet-4-20250514", "anthropic");
            when(conversationClient.findOrCreateAgentConversation(anyString(), anyString(), any(), any()))
                    .thenReturn(CONVERSATION_ID);

            service.createSession(agent, "127.0.0.1", "UA");

            verify(conversationClient, never()).createConversation(
                    anyString(), anyString(), any(), any(), anyString(), any());
        }

        @Test
        @DisplayName("two back-to-back sessions for the same agent share the same conversationId")
        void twoSessionsShareSameConversation() {
            AgentEntity agent = createAgent("TestBot", "model-x", "zai");
            when(conversationClient.findOrCreateAgentConversation(
                    AGENT_ID.toString(), TENANT_ID, "TestBot", ORGANIZATION_ID))
                    .thenReturn(CONVERSATION_ID);

            WidgetSessionService.WidgetSession first = service.createSession(agent, "127.0.0.1", "UA-A");
            WidgetSessionService.WidgetSession second = service.createSession(agent, "127.0.0.2", "UA-B");

            assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
            assertThat(first.conversationId())
                    .as("different sessions must reuse the same conversation for the same agent")
                    .isEqualTo(second.conversationId())
                    .isEqualTo(CONVERSATION_ID);
            verify(conversationClient, times(2)).findOrCreateAgentConversation(
                    AGENT_ID.toString(), TENANT_ID, "TestBot", ORGANIZATION_ID);
        }

        @Test
        @DisplayName("should throw when conversation resolution returns null")
        void shouldThrowOnNull() {
            AgentEntity agent = createAgent("TestBot", null, null);
            when(conversationClient.findOrCreateAgentConversation(anyString(), anyString(), any(), any()))
                    .thenReturn(null);

            assertThatThrownBy(() -> service.createSession(agent, "127.0.0.1", "UA"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Failed to resolve conversation");
        }
    }

    // ---------------------------------------------------------------------------
    // sendMessage
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("sendMessage()")
    class SendMessageTests {

        @Test
        @DisplayName("should delegate to ConversationClient.sendChatSync with source=WIDGET")
        void shouldCallSendChatSync() {
            AgentEntity agent = createAgent("TestBot", "claude-sonnet-4-20250514", "anthropic");
            Map<String, Object> expected = Map.of("success", true, "content", "Hello!");
            when(conversationClient.sendChatSync(
                    eq(TENANT_ID), eq(CONVERSATION_ID), eq("Hi"),
                    eq(AGENT_ID.toString()), eq("claude-sonnet-4-20250514"), eq("anthropic"), eq("WIDGET"),
                    isNull(), eq(ORGANIZATION_ID)))
                    .thenReturn(expected);

            Map<String, Object> result = service.sendMessage(TENANT_ID, CONVERSATION_ID, "Hi", agent);

            assertThat(result).containsEntry("success", true);
            assertThat(result).containsEntry("content", "Hello!");
            verify(conversationClient).sendChatSync(
                    TENANT_ID, CONVERSATION_ID, "Hi",
                    AGENT_ID.toString(), "claude-sonnet-4-20250514", "anthropic", "WIDGET",
                    null, ORGANIZATION_ID);
        }

        @Test
        @DisplayName("should return error map when ConversationClient fails")
        void shouldReturnErrorOnFailure() {
            AgentEntity agent = createAgent("TestBot", null, null);
            Map<String, Object> errorResult = Map.of("success", false, "error", "timeout");
            when(conversationClient.sendChatSync(anyString(), anyString(), anyString(),
                    anyString(), any(), any(), anyString(), isNull(), eq(ORGANIZATION_ID)))
                    .thenReturn(errorResult);

            Map<String, Object> result = service.sendMessage(TENANT_ID, CONVERSATION_ID, "Hi", agent);

            assertThat(result).containsEntry("success", false);
            assertThat(result).containsEntry("error", "timeout");
        }
    }

    // ---------------------------------------------------------------------------
    // getHistory
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("getHistory()")
    class GetHistoryTests {

        @Test
        @DisplayName("should delegate to ConversationClient.getConversationMessages")
        void shouldFetchHistory() {
            List<Map<String, Object>> messages = List.of(
                    Map.of("role", "user", "content", "Hello"),
                    Map.of("role", "assistant", "content", "Hi there!")
            );
            when(conversationClient.getConversationMessages(CONVERSATION_ID, 100, TENANT_ID, ORGANIZATION_ID))
                    .thenReturn(messages);

            List<Map<String, Object>> result = service.getHistory(TENANT_ID, CONVERSATION_ID, ORGANIZATION_ID);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).containsEntry("role", "user");
            assertThat(result.get(1)).containsEntry("role", "assistant");
            verify(conversationClient).getConversationMessages(CONVERSATION_ID, 100, TENANT_ID, ORGANIZATION_ID);
        }

        @Test
        @DisplayName("should return empty list when no messages")
        void shouldReturnEmptyList() {
            when(conversationClient.getConversationMessages(CONVERSATION_ID, 100, TENANT_ID, ORGANIZATION_ID))
                    .thenReturn(List.of());

            List<Map<String, Object>> result = service.getHistory(TENANT_ID, CONVERSATION_ID, ORGANIZATION_ID);

            assertThat(result).isEmpty();
        }
    }

    // ---------------------------------------------------------------------------
    // getSession / validateSession
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("getSession()")
    class GetSessionTests {

        @Test
        @DisplayName("should return null for blank sessionId")
        void shouldReturnNullForBlank() {
            assertThat(service.getSession("")).isNull();
            assertThat(service.getSession(null)).isNull();
        }

        @Test
        @DisplayName("should return null when Redis has no data")
        void shouldReturnNullWhenMissing() {
            when(hashOperations.entries("widget:session:abc")).thenReturn(Map.of());

            assertThat(service.getSession("abc")).isNull();
        }

        @Test
        @DisplayName("should reconstruct session from Redis data")
        void shouldReconstructFromRedis() {
            Map<Object, Object> data = new HashMap<>();
            data.put("sessionId", "sess-1");
            data.put("conversationId", CONVERSATION_ID);
            data.put("agentId", AGENT_ID.toString());
            data.put("tenantId", TENANT_ID);
            data.put("organizationId", ORGANIZATION_ID);
            data.put("ipAddress", "10.0.0.1");
            data.put("userAgent", "TestAgent");
            data.put("createdAt", "2025-01-01T00:00:00Z");
            when(hashOperations.entries("widget:session:sess-1")).thenReturn(data);

            WidgetSessionService.WidgetSession session = service.getSession("sess-1");

            assertThat(session).isNotNull();
            assertThat(session.sessionId()).isEqualTo("sess-1");
            assertThat(session.conversationId()).isEqualTo(CONVERSATION_ID);
            assertThat(session.agentId()).isEqualTo(AGENT_ID);
            assertThat(session.tenantId()).isEqualTo(TENANT_ID);
            assertThat(session.organizationId()).isEqualTo(ORGANIZATION_ID);
        }
    }

    @Nested
    @DisplayName("validateSession()")
    class ValidateSessionTests {

        @Test
        @DisplayName("should return false for non-existent session")
        void shouldRejectMissing() {
            when(hashOperations.entries("widget:session:bad")).thenReturn(Map.of());

            assertThat(service.validateSession("bad", "10.0.0.1")).isFalse();
        }

        @Test
        @DisplayName("should accept matching IP")
        void shouldAcceptMatchingIp() {
            Map<Object, Object> data = sessionData("10.0.0.1");
            when(hashOperations.entries("widget:session:sess-1")).thenReturn(data);

            assertThat(service.validateSession("sess-1", "10.0.0.1")).isTrue();
            verify(redisTemplate).expire(eq("widget:session:sess-1"), any());
        }

        @Test
        @DisplayName("should reject mismatched IP")
        void shouldRejectMismatchedIp() {
            Map<Object, Object> data = sessionData("10.0.0.1");
            when(hashOperations.entries("widget:session:sess-1")).thenReturn(data);

            assertThat(service.validateSession("sess-1", "192.168.0.1")).isFalse();
        }

        @Test
        @DisplayName("should accept when session has no IP (lenient)")
        void shouldAcceptWhenSessionHasNoIp() {
            Map<Object, Object> data = sessionData("");
            when(hashOperations.entries("widget:session:sess-1")).thenReturn(data);

            assertThat(service.validateSession("sess-1", "10.0.0.1")).isTrue();
        }

        private Map<Object, Object> sessionData(String ip) {
            Map<Object, Object> data = new HashMap<>();
            data.put("sessionId", "sess-1");
            data.put("conversationId", CONVERSATION_ID);
            data.put("agentId", AGENT_ID.toString());
            data.put("tenantId", TENANT_ID);
            data.put("organizationId", ORGANIZATION_ID);
            data.put("ipAddress", ip);
            data.put("userAgent", "UA");
            data.put("createdAt", "2025-01-01T00:00:00Z");
            return data;
        }
    }
}
