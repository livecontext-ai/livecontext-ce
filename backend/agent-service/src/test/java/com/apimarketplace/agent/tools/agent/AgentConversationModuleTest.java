package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.conversation.client.ConversationClient;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentConversationModule")
@ExtendWith(MockitoExtension.class)
class AgentConversationModuleTest {

    @Mock private AgentService agentService;
    @Mock private ConversationClient conversationServiceClient;
    @Mock private RestTemplate restTemplate;

    private AgentConversationModule module;

    private static final String TENANT_ID = "tenant-1";
    private static final UUID AGENT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        module = new AgentConversationModule(agentService, conversationServiceClient,
            restTemplate, "http://localhost:8092", null, "http://localhost:3000");
    }

    private ToolExecutionContext contextWithCredentials(Map<String, Object> creds) {
        return new ToolExecutionContext(TENANT_ID, creds, Map.of(), Set.of(), null, null, null, null);
    }

    private Map<String, Object> defaultCredentials() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("conversationId", "my-conv-123");
        return creds;
    }

    private AgentEntity createAgent() {
        AgentEntity entity = new AgentEntity();
        entity.setId(AGENT_ID);
        entity.setName("Test Agent");
        entity.setIsActive(true);
        return entity;
    }

    // ==================== get_history ====================

    @Nested
    @DisplayName("get_history")
    class GetHistoryTests {

        @Test
        @DisplayName("should load sub-agent conversation history")
        void shouldLoadSubAgentHistory() {
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(createAgent()));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                .thenReturn("child-conv-1");
            when(conversationServiceClient.getConversationMessages("child-conv-1", 20, TENANT_ID))
                .thenReturn(List.of(
                    Map.of("role", "user", "content", "Hello"),
                    Map.of("role", "assistant", "content", "Hi there"),
                    Map.of("role", "system", "content", "System message"),
                    Map.of("role", "tool", "content", "Tool result")
                ));

            Map<String, Object> params = Map.of("action", "get_history", "agent_id", AGENT_ID.toString());
            var result = module.execute("get_history", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();
            assertThat(r.data().toString()).contains("Hello");
            assertThat(r.data().toString()).contains("Hi there");
            // System and tool messages should be filtered
            assertThat(r.data().toString()).doesNotContain("System message");
            assertThat(r.data().toString()).doesNotContain("Tool result");
        }

        @Test
        @DisplayName("should load own conversation history when no agent_id")
        void shouldLoadOwnHistory() {
            when(conversationServiceClient.getConversationMessages("my-conv-123", 20, TENANT_ID))
                .thenReturn(List.of(
                    Map.of("role", "user", "content", "My question"),
                    Map.of("role", "assistant", "content", "My answer")
                ));

            Map<String, Object> params = Map.of("action", "get_history");
            var result = module.execute("get_history", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().data().toString()).contains("My question");
            assertThat(result.get().data().toString()).contains("My answer");
        }

        @Test
        @DisplayName("should respect allowedAgentIds restriction")
        void shouldRespectAllowedAgentIds() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of("other-agent-id"));

            Map<String, Object> params = Map.of("action", "get_history", "agent_id", AGENT_ID.toString());
            var result = module.execute("get_history", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not in your approved agent list");
        }

        @Test
        @DisplayName("should respect namespaced allowedAgentIds restriction")
        void shouldRespectNamespacedAllowedAgentIds() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("__allowedAgentIds__", List.of("other-agent-id"));

            Map<String, Object> params = Map.of("action", "get_history", "agent_id", AGENT_ID.toString());
            var result = module.execute("get_history", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not in your approved agent list");
        }

        @Test
        @DisplayName("should fail when agent not found")
        void shouldFailAgentNotFound() {
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.empty());

            Map<String, Object> params = Map.of("action", "get_history", "agent_id", AGENT_ID.toString());
            var result = module.execute("get_history", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Agent not found");
        }

        @Test
        @DisplayName("should handle empty history")
        void shouldHandleEmptyHistory() {
            when(conversationServiceClient.getConversationMessages("my-conv-123", 20, TENANT_ID))
                .thenReturn(List.of());

            Map<String, Object> params = Map.of("action", "get_history");
            var result = module.execute("get_history", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().data().toString()).contains("No conversation history found");
        }

        @Test
        @DisplayName("should use custom limit parameter")
        void shouldUseCustomLimit() {
            when(conversationServiceClient.getConversationMessages("my-conv-123", 5, TENANT_ID))
                .thenReturn(List.of());

            Map<String, Object> params = Map.of("action", "get_history", "limit", 5);
            module.execute("get_history", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            verify(conversationServiceClient).getConversationMessages("my-conv-123", 5, TENANT_ID);
        }
    }

    // ==================== share ====================

    @Nested
    @DisplayName("share")
    class ShareTests {

        @Test
        @DisplayName("should share own conversation and return link")
        void shouldShareOwnConversation() {
            when(conversationServiceClient.enableSharing("my-conv-123", TENANT_ID, "read"))
                .thenReturn(Map.of("shareToken", "cs_abc123"));

            Map<String, Object> params = Map.of("action", "share");
            var result = module.execute("share", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();
            assertThat(r.data().toString()).contains("cs_abc123");
            assertThat(r.data().toString()).contains("http://localhost:3000/s/cs_abc123");
            assertThat(r.data().toString()).contains("SHARED");
        }

        @Test
        @DisplayName("read-only agent access rejects share before mutating conversation sharing")
        void readOnlyAgentAccessRejectsShare() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("__agentAccessMode__", "read");

            Map<String, Object> params = Map.of("action", "share");
            var result = module.execute("share", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("read-only").contains("share");
            verify(conversationServiceClient, never()).enableSharing(any(), any(), any());
        }

        @Test
        @DisplayName("should share sub-agent conversation")
        void shouldShareSubAgentConversation() {
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(createAgent()));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                .thenReturn("child-conv-1");
            when(conversationServiceClient.enableSharing("child-conv-1", TENANT_ID, "read"))
                .thenReturn(Map.of("shareToken", "cs_child456"));

            Map<String, Object> params = Map.of("action", "share", "agent_id", AGENT_ID.toString());
            var result = module.execute("share", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().data().toString()).contains("cs_child456");
            assertThat(result.get().data().toString()).contains("Test Agent");
        }

        @Test
        @DisplayName("should support readwrite share mode")
        void shouldSupportReadWriteMode() {
            when(conversationServiceClient.enableSharing("my-conv-123", TENANT_ID, "readwrite"))
                .thenReturn(Map.of("shareToken", "cs_rw789"));

            Map<String, Object> params = Map.of("action", "share", "share_mode", "readwrite");
            var result = module.execute("share", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(conversationServiceClient).enableSharing("my-conv-123", TENANT_ID, "readwrite");
        }

        @Test
        @DisplayName("should fail when sharing returns no token")
        void shouldFailNoToken() {
            when(conversationServiceClient.enableSharing(any(), any(), any()))
                .thenReturn(Map.of("error", "conversation not found"));

            Map<String, Object> params = Map.of("action", "share");
            var result = module.execute("share", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Failed to enable sharing");
        }

        @Test
        @DisplayName("should respect allowedAgentIds restriction for sub-agent share")
        void shouldRespectAllowedAgentIds() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of("other-agent-id"));

            Map<String, Object> params = Map.of("action", "share", "agent_id", AGENT_ID.toString());
            var result = module.execute("share", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not in your approved agent list");
        }

        @Test
        @DisplayName("should fail when no conversation found")
        void shouldFailNoConversation() {
            Map<String, Object> creds = new HashMap<>(); // no conversationId

            Map<String, Object> params = Map.of("action", "share");
            var result = module.execute("share", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("No conversation found");
        }
    }

    // ==================== unshare ====================

    @Nested
    @DisplayName("unshare")
    class UnshareTests {

        @Test
        @DisplayName("should unshare own conversation")
        void shouldUnshareOwnConversation() {
            Map<String, Object> params = Map.of("action", "unshare");
            var result = module.execute("unshare", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();
            assertThat(r.data().toString()).contains("UNSHARED");
            assertThat(r.data().toString()).contains("my-conv-123");

            verify(conversationServiceClient).disableSharing("my-conv-123", TENANT_ID);
        }

        @Test
        @DisplayName("should unshare sub-agent conversation")
        void shouldUnshareSubAgentConversation() {
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(createAgent()));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                .thenReturn("child-conv-1");

            Map<String, Object> params = Map.of("action", "unshare", "agent_id", AGENT_ID.toString());
            var result = module.execute("unshare", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().data().toString()).contains("UNSHARED");
            assertThat(result.get().data().toString()).contains("Test Agent");

            verify(conversationServiceClient).disableSharing("child-conv-1", TENANT_ID);
        }

        @Test
        @DisplayName("should respect allowedAgentIds restriction")
        void shouldRespectAllowedAgentIds() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of("other-agent-id"));

            Map<String, Object> params = Map.of("action", "unshare", "agent_id", AGENT_ID.toString());
            var result = module.execute("unshare", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not in your approved agent list");
        }

        @Test
        @DisplayName("should fail when no conversation found")
        void shouldFailNoConversation() {
            Map<String, Object> creds = new HashMap<>(); // no conversationId

            Map<String, Object> params = Map.of("action", "unshare");
            var result = module.execute("unshare", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("No conversation found");
        }
    }

    // ==================== refresh_share ====================

    @Nested
    @DisplayName("refresh_share")
    class RefreshShareTests {

        @Test
        @DisplayName("should refresh share link for own conversation")
        @SuppressWarnings("unchecked")
        void shouldRefreshOwnConversation() {
            // Mock finding existing shared link by resource ID
            when(restTemplate.exchange(
                eq("http://localhost:8092/api/internal/shared-links/by-resource-id/my-conv-123"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "link-uuid-1", "token", "sl_old"), HttpStatus.OK));

            // Mock regenerating token
            when(restTemplate.exchange(
                eq("http://localhost:8092/api/internal/shared-links/regenerate-token"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "link-uuid-1", "token", "sl_new123"), HttpStatus.OK));

            Map<String, Object> params = Map.of("action", "refresh_share");
            var result = module.execute("refresh_share", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();
            assertThat(r.data().toString()).contains("REFRESHED");
            assertThat(r.data().toString()).contains("sl_new123");
            assertThat(r.data().toString()).contains("http://localhost:3000/s/sl_new123");
        }

        @Test
        @DisplayName("should refresh share link for sub-agent conversation")
        @SuppressWarnings("unchecked")
        void shouldRefreshSubAgentConversation() {
            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(createAgent()));
            when(conversationServiceClient.findOrCreateAgentConversation(any(), any(), any(), any()))
                .thenReturn("child-conv-1");

            when(restTemplate.exchange(
                eq("http://localhost:8092/api/internal/shared-links/by-resource-id/child-conv-1"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "link-uuid-2", "token", "sl_old"), HttpStatus.OK));

            when(restTemplate.exchange(
                eq("http://localhost:8092/api/internal/shared-links/regenerate-token"),
                eq(HttpMethod.POST), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "link-uuid-2", "token", "sl_refreshed"), HttpStatus.OK));

            Map<String, Object> params = Map.of("action", "refresh_share", "agent_id", AGENT_ID.toString());
            var result = module.execute("refresh_share", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            assertThat(result.get().data().toString()).contains("REFRESHED");
            assertThat(result.get().data().toString()).contains("sl_refreshed");
            assertThat(result.get().data().toString()).contains("Test Agent");
        }

        @Test
        @DisplayName("should fail when no shared link exists")
        @SuppressWarnings("unchecked")
        void shouldFailNoSharedLink() {
            // Mock 404 - no shared link found
            when(restTemplate.exchange(
                eq("http://localhost:8092/api/internal/shared-links/by-resource-id/my-conv-123"),
                eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.NOT_FOUND));

            Map<String, Object> params = Map.of("action", "refresh_share");
            var result = module.execute("refresh_share", params, TENANT_ID, contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("No shared link found");
        }

        @Test
        @DisplayName("should respect allowedAgentIds restriction")
        void shouldRespectAllowedAgentIds() {
            Map<String, Object> creds = defaultCredentials();
            creds.put("allowedAgentIds", List.of("other-agent-id"));

            Map<String, Object> params = Map.of("action", "refresh_share", "agent_id", AGENT_ID.toString());
            var result = module.execute("refresh_share", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not in your approved agent list");
        }

        @Test
        @DisplayName("should fail when no conversation found")
        void shouldFailNoConversation() {
            Map<String, Object> creds = new HashMap<>(); // no conversationId

            Map<String, Object> params = Map.of("action", "refresh_share");
            var result = module.execute("refresh_share", params, TENANT_ID, contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("No conversation found");
        }
    }

    // ==================== search_messages ====================

    @Nested
    @DisplayName("search_messages")
    class SearchMessagesTests {

        private Map<String, Object> hit(String role) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("messageId", UUID.randomUUID().toString());
            h.put("role", role);
            h.put("excerpt", "matched text");
            return h;
        }

        @Test
        @DisplayName("blank query is rejected with a clear error - never reaches the backend")
        void blankQueryRejected() {
            Map<String, Object> params = Map.of("scope", "self", "query", "   ");

            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("query").contains("required");
            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("invalid scope is rejected with the list of valid scopes")
        void invalidScopeRejected() {
            Map<String, Object> params = Map.of("scope", "everything", "query", "facture");

            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Invalid scope")
                    .contains("self").contains("children").contains("all_visible");
        }

        @Test
        @DisplayName("scope=self uses the caller's conversationId only")
        void selfScopeUsesOwnConversation() {
            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("results", List.of(hit("USER"), hit("ASSISTANT")));
            backend.put("nextCursor", null);
            backend.put("hasMore", false);
            backend.put("returnedCount", 2);
            backend.put("scopeTruncated", false);

            when(conversationServiceClient.searchMessages(eq(List.of("my-conv-123")), eq("facture"),
                    isNull(), isNull(), isNull(), isNull(), eq(false), eq(20), isNull()))
                    .thenReturn(backend);

            Map<String, Object> params = Map.of("scope", "self", "query", "facture");
            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data.get("returned_count")).isEqualTo(2);
            assertThat(data.get("scope")).isEqualTo("self");
            assertThat(data.get("has_more")).isEqualTo(false);
        }

        @Test
        @DisplayName("scope=self with no conversationId in context is rejected - never reaches backend")
        void selfScopeWithoutOwnConversationRejected() {
            Map<String, Object> creds = new HashMap<>(); // no conversationId

            Map<String, Object> params = Map.of("scope", "self", "query", "facture");
            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("self").contains("conversation");
            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("scope=self rejects agent_id parameter (target is implicit)")
        void selfScopeRejectsAgentIdParam() {
            Map<String, Object> params = Map.of(
                    "scope", "self", "query", "facture", "agent_id", AGENT_ID.toString());

            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("agent_id").contains("self");
        }

        @Test
        @DisplayName("scope=children resolves allowedAgentIds → conversation IDs")
        void childrenScopeResolvesAllowlist() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "my-conv-123");
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString()));

            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(createAgent()));
            when(conversationServiceClient.findOrCreateAgentConversation(
                    eq(AGENT_ID.toString()), eq(TENANT_ID), eq("Test Agent"), any()))
                    .thenReturn("child-conv-1");

            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("results", List.of(hit("ASSISTANT")));
            backend.put("nextCursor", null);
            backend.put("hasMore", false);
            backend.put("returnedCount", 1);
            backend.put("scopeTruncated", false);
            when(conversationServiceClient.searchMessages(eq(List.of("child-conv-1")), eq("facture"),
                    isNull(), isNull(), isNull(), isNull(), eq(false), eq(20), isNull()))
                    .thenReturn(backend);

            Map<String, Object> params = Map.of("scope", "children", "query", "facture");
            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data.get("returned_count")).isEqualTo(1);
            assertThat(data.get("scope")).isEqualTo("children");
        }

        @Test
        @DisplayName("agent_id targeting an agent NOT in allowlist is rejected")
        void agentIdNotInAllowlistRejected() {
            UUID foreign = UUID.randomUUID();
            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "my-conv-123");
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString())); // foreign NOT in list

            Map<String, Object> params = Map.of(
                    "scope", "children", "query", "facture", "agent_id", foreign.toString());

            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains(foreign.toString()).contains("allowed");
            verifyNoInteractions(conversationServiceClient);
        }

        @Test
        @DisplayName("scope=all_visible unions self + children conversations")
        void allVisibleUnionsSelfAndChildren() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "my-conv-123");
            creds.put("allowedAgentIds", List.of(AGENT_ID.toString()));

            when(agentService.getAgent(AGENT_ID, TENANT_ID)).thenReturn(Optional.of(createAgent()));
            when(conversationServiceClient.findOrCreateAgentConversation(
                    eq(AGENT_ID.toString()), eq(TENANT_ID), eq("Test Agent"), any()))
                    .thenReturn("child-conv-1");

            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("results", List.of());
            backend.put("nextCursor", null);
            backend.put("hasMore", false);
            backend.put("returnedCount", 0);
            backend.put("scopeTruncated", false);
            when(conversationServiceClient.searchMessages(
                    eq(List.of("my-conv-123", "child-conv-1")),
                    eq("facture"), isNull(), isNull(), isNull(), isNull(),
                    eq(false), eq(20), isNull()))
                    .thenReturn(backend);

            Map<String, Object> params = Map.of("scope", "all_visible", "query", "facture");
            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
        }

        @Test
        @DisplayName("limit clamped to 50 max")
        void limitClampedToMax() {
            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("results", List.of());
            backend.put("nextCursor", null);
            backend.put("hasMore", false);
            backend.put("returnedCount", 0);
            backend.put("scopeTruncated", false);
            when(conversationServiceClient.searchMessages(any(), any(), any(), any(), any(), any(),
                    eq(false), eq(50), any()))
                    .thenReturn(backend);

            Map<String, Object> params = Map.of(
                    "scope", "self", "query", "facture", "limit", 999);
            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(conversationServiceClient).searchMessages(any(), any(), any(), any(), any(), any(),
                    eq(false), eq(50), any());
        }

        @Test
        @DisplayName("backend error response is surfaced to the caller as a failure")
        void backendErrorSurfacedAsFailure() {
            when(conversationServiceClient.searchMessages(any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyInt(), any()))
                    .thenReturn(Map.of("error", "{\"error\":\"query length exceeds 500 characters\"}"));

            Map<String, Object> params = Map.of("scope", "self", "query", "facture");
            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Search rejected");
        }

        @Test
        @DisplayName("scope=children with explicit empty allowlist [] returns empty result without backend call")
        void childrenScopeExplicitlyEmptyAllowlistReturnsEmpty() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "my-conv-123");
            // EXPLICIT empty list - caller has toolsConfig.agents=[] meaning "no children"
            creds.put("allowedAgentIds", List.of());

            Map<String, Object> params = Map.of("scope", "children", "query", "facture");
            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data.get("returned_count")).isEqualTo(0);
            verify(conversationServiceClient, never()).searchMessages(any(), any(), any(), any(),
                    any(), any(), anyBoolean(), anyInt(), any());
        }

        /**
         * Regression test for the god-agent fix in resolveSearchScope.
         *
         * <p>Prior bug: {@code if (allowed == null) allowed = List.of()} silently
         * downgraded a god agent (no toolsConfig restriction) to "no children",
         * inverting the system-wide convention {@code null=all} documented in
         * {@code AgentToolsProvider.java:185} and applied in
         * {@code SubAgentExecutionHandler#getAllowedAgentIds}.
         *
         * <p>After fix: a caller with no {@code allowedAgentIds} key in credentials
         * sees ALL agents in the tenant via {@code AgentService.listAllByTenant}.
         */
        @Test
        @DisplayName("REGRESSION: god agent (no allowedAgentIds) sees ALL tenant agents in scope=children")
        void godAgentSeesAllTenantAgents() {
            Map<String, Object> creds = new HashMap<>();
            creds.put("conversationId", "my-conv-123");
            // NO allowedAgentIds key at all - this is the god-agent / primary-chat case

            // 2 agents in tenant
            UUID agent1 = UUID.randomUUID();
            UUID agent2 = UUID.randomUUID();
            AgentEntity ae1 = new AgentEntity(); ae1.setId(agent1); ae1.setName("A1"); ae1.setIsActive(true);
            AgentEntity ae2 = new AgentEntity(); ae2.setId(agent2); ae2.setName("A2"); ae2.setIsActive(true);
            when(agentService.listAllByTenant(TENANT_ID)).thenReturn(List.of(ae1, ae2));
            when(agentService.getAgent(agent1, TENANT_ID)).thenReturn(Optional.of(ae1));
            when(agentService.getAgent(agent2, TENANT_ID)).thenReturn(Optional.of(ae2));
            when(conversationServiceClient.findOrCreateAgentConversation(
                    eq(agent1.toString()), eq(TENANT_ID), eq("A1"), any())).thenReturn("conv-A1");
            when(conversationServiceClient.findOrCreateAgentConversation(
                    eq(agent2.toString()), eq(TENANT_ID), eq("A2"), any())).thenReturn("conv-A2");

            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("results", List.of(hit("USER")));
            backend.put("nextCursor", null);
            backend.put("hasMore", false);
            backend.put("returnedCount", 1);
            backend.put("scopeTruncated", false);
            // Scope must include BOTH agents' conversations - this is the contract
            when(conversationServiceClient.searchMessages(eq(List.of("conv-A1", "conv-A2")),
                    eq("facture"), any(), any(), any(), any(),
                    anyBoolean(), anyInt(), any())).thenReturn(backend);

            Map<String, Object> params = Map.of("scope", "children", "query", "facture");
            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(creds));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // Verify the backend was actually called with both convs (not the empty bug path)
            verify(conversationServiceClient).searchMessages(
                    eq(List.of("conv-A1", "conv-A2")),
                    eq("facture"), any(), any(), any(), any(),
                    anyBoolean(), anyInt(), any());
        }

        @Test
        @DisplayName("filters (since/until/roles/tool_name/cursor) propagate to the backend call")
        void filtersPropagate() {
            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("results", List.of());
            backend.put("nextCursor", null);
            backend.put("hasMore", false);
            backend.put("returnedCount", 0);
            backend.put("scopeTruncated", false);
            when(conversationServiceClient.searchMessages(
                    eq(List.of("my-conv-123")), eq("facture"),
                    eq("2026-04-01T00:00:00Z"), eq("2026-04-30T23:59:59Z"),
                    eq(List.of("USER", "ASSISTANT")), eq("web_search"),
                    eq(false), eq(10), eq("v1:abc")))
                    .thenReturn(backend);

            Map<String, Object> params = new HashMap<>();
            params.put("scope", "self");
            params.put("query", "facture");
            params.put("since", "2026-04-01T00:00:00Z");
            params.put("until", "2026-04-30T23:59:59Z");
            params.put("roles", List.of("USER", "ASSISTANT"));
            params.put("tool_name", "web_search");
            params.put("limit", 10);
            params.put("cursor", "v1:abc");

            var result = module.execute("search_messages", params, TENANT_ID,
                    contextWithCredentials(defaultCredentials()));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(conversationServiceClient).searchMessages(
                    eq(List.of("my-conv-123")), eq("facture"),
                    eq("2026-04-01T00:00:00Z"), eq("2026-04-30T23:59:59Z"),
                    eq(List.of("USER", "ASSISTANT")), eq("web_search"),
                    eq(false), eq(10), eq("v1:abc"));
        }
    }
}
