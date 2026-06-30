package com.apimarketplace.monolith.config;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.service.ai.ConversationToolExecutionService;
import com.apimarketplace.conversation.service.ai.ToolServiceRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("MonolithToolExecutionService")
class MonolithToolExecutionServiceTest {

    private final ToolExecutionService delegate = mock(ToolExecutionService.class);
    private final ToolExecutionService conversationLocalTools = mock(ToolExecutionService.class);
    private final MonolithToolExecutionService service =
        new MonolithToolExecutionService(delegate, conversationLocalTools);

    @Test
    @DisplayName("routes every conversation-local tool to the local conversation service")
    void routesEveryConversationLocalToolLocally() {
        for (String toolName : java.util.List.of("set_conversation_title", "get_tool_result", "request_credential")) {
            ToolCall call = ToolCall.builder()
                .id("call-" + toolName)
                .toolName(toolName)
                .arguments(Map.of())
                .build();
            ToolDefinition definition = ToolDefinition.builder().name(toolName).build();
            when(conversationLocalTools.executeTool(eq(call), eq(definition), eq("tenant-1"), any()))
                .thenReturn(ToolResult.success(call, "local"));

            ToolResult result = service.executeTool(call, definition, "tenant-1", Map.of("conversationId", "conv-1"));

            assertThat(result.success()).isTrue();
            verify(conversationLocalTools).executeTool(eq(call), eq(definition), eq("tenant-1"), any());
        }

        verify(delegate, never()).executeTool(any(), any(), any(), any());
    }

    @Test
    @DisplayName("delegates non-conversation tools to the remote execution service")
    void delegatesNonConversationTools() {
        ToolCall call = ToolCall.builder()
            .id("call-workflow")
            .toolName("workflow")
            .arguments(Map.of("action", "list"))
            .build();
        ToolDefinition definition = ToolDefinition.builder().name("workflow").build();
        when(delegate.executeTool(eq(call), eq(definition), eq("tenant-1"), any()))
            .thenReturn(ToolResult.success(call, "remote"));

        ToolResult result = service.executeTool(call, definition, "tenant-1", Map.of());

        assertThat(result.content()).isEqualTo("remote");
        verify(delegate).executeTool(eq(call), eq(definition), eq("tenant-1"), any());
        verify(conversationLocalTools, never()).executeTool(any(), any(), any(), any());
    }

    @Test
    @DisplayName("forwards conversation credentials unchanged to the local conversation service")
    void forwardsCredentialsUnchanged() {
        ToolCall call = ToolCall.builder()
            .id("call-credential")
            .toolName("request_credential")
            .arguments(Map.of("services", java.util.List.of("gmail")))
            .build();
        ToolDefinition definition = ToolDefinition.builder().name("request_credential").build();
        when(conversationLocalTools.executeTool(eq(call), eq(definition), eq("tenant-1"), any()))
            .thenReturn(ToolResult.success(call, "local"));

        service.executeTool(call, definition, "tenant-1", Map.of(
            "conversationId", "conv-1",
            "__streamId__", "stream-1",
            "__orgId__", "org-1"
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> credentials = ArgumentCaptor.forClass(Map.class);
        verify(conversationLocalTools).executeTool(eq(call), eq(definition), eq("tenant-1"), credentials.capture());
        assertThat(credentials.getValue())
            .containsEntry("conversationId", "conv-1")
            .containsEntry("__streamId__", "stream-1")
            .containsEntry("__orgId__", "org-1");
    }

    @Test
    @DisplayName("set_conversation_title publishes a title_updated event to the CE WebSocket channel")
    void setConversationTitlePublishesCeWebSocketTitleEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        ConversationHistoryService historyService = mock(ConversationHistoryService.class);
        ToolResultService toolResultService = mock(ToolResultService.class);
        ToolServiceRouter toolServiceRouter = mock(ToolServiceRouter.class);
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.convertAndSend(eq("ws:conversation:conv-1"), any()))
            .thenReturn(1L);

        var ceStreamPubSub = new NoOpStreamPubSubService(objectMapper, redisTemplate);
        var conversationTools = new ConversationToolExecutionService(
            historyService,
            toolResultService,
            ceStreamPubSub,
            toolServiceRouter
        );
        var ceService = new MonolithToolExecutionService(delegate, conversationTools);
        ToolCall call = ToolCall.builder()
            .id("call-title")
            .toolName("set_conversation_title")
            .arguments(Map.of("title", "Live CE Title"))
            .build();
        ToolDefinition definition = ToolDefinition.builder().name("set_conversation_title").build();

        ToolResult result = ceService.executeTool(call, definition, "tenant-1", Map.of(
            "conversationId", "conv-1",
            "__streamId__", "stream-1",
            "__orgId__", "org-1"
        ));

        assertThat(result.success()).isTrue();
        verify(historyService).updateConversationTitle("conv-1", "tenant-1", "org-1", "Live CE Title");
        verifyNoInteractions(delegate);

        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(redisTemplate).convertAndSend(eq("ws:conversation:conv-1"), payloadCaptor.capture());
        var payload = objectMapper.readTree(String.valueOf(payloadCaptor.getValue()));
        assertThat(payload.get("type").asText()).isEqualTo("title_updated");
        assertThat(payload.get("streamId").asText()).isEqualTo("stream-1");
        assertThat(payload.get("conversationId").asText()).isEqualTo("conv-1");
        assertThat(payload.get("title").asText()).isEqualTo("Live CE Title");
    }
}
