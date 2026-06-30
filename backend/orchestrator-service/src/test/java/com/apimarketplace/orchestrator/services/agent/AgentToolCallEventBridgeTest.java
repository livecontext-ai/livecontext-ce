package com.apimarketplace.orchestrator.services.agent;

import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import com.apimarketplace.orchestrator.services.streaming.events.AgentToolCallPhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentToolCallEventBridge")
@ExtendWith(MockitoExtension.class)
class AgentToolCallEventBridgeTest {

    @Mock
    private WorkflowEventPublisher eventPublisher;

    @Mock
    private ConversationEventPublisher conversationPublisher;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Nested
    @DisplayName("5-arg constructor (workflow only)")
    class WorkflowOnlyBridge {

        @Test
        @DisplayName("onToolCall should emit to workflow channel only")
        void onToolCallEmitsToWorkflow() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher
            );

            ToolCall toolCall = new ToolCall("tc-001", "catalog", Map.of("query", "test"), null);
            bridge.onToolCall(toolCall);

            verify(eventPublisher).emitAgentToolCall(
                eq("run-1"), eq("agent:test"), eq("catalog"), eq("tc-001"),
                eq(AgentToolCallPhase.CALLING), any(Map.class), eq(0), isNull()
            );
            verifyNoInteractions(conversationPublisher);
        }

        @Test
        @DisplayName("onToolResult should emit to workflow channel only")
        void onToolResultEmitsToWorkflow() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher
            );

            ToolCall toolCall = new ToolCall("tc-001", "catalog", null, null);
            ToolResult result = new ToolResult(toolCall, true, "result content", null, 100L, null);
            bridge.onToolResult(result);

            verify(eventPublisher).emitAgentToolCall(
                eq("run-1"), eq("agent:test"), eq("catalog"), eq("tc-001"),
                eq(AgentToolCallPhase.COMPLETED), any(Map.class), eq(0), isNull()
            );
            verifyNoInteractions(conversationPublisher);
        }

        @Test
        @DisplayName("onChunk should do nothing without conversation publisher")
        void onChunkNoOp() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher
            );

            bridge.onChunk("content chunk");

            verifyNoInteractions(conversationPublisher);
        }
    }

    @Nested
    @DisplayName("8-arg constructor (workflow + conversation)")
    class DualPublishBridge {

        @Test
        @DisplayName("onChunk should publish to conversation channel")
        void onChunkPublishesToConversation() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher,
                conversationPublisher, "conv-1", "stream-1"
            );

            bridge.onChunk("Hello");

            verify(conversationPublisher).publishContent("conv-1", "stream-1", "Hello");
        }

        @Test
        @DisplayName("onToolCall should emit to both channels")
        void onToolCallEmitsToBoth() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, 2, eventPublisher,
                conversationPublisher, "conv-1", "stream-1"
            );

            ToolCall toolCall = new ToolCall("tc-001", "workflow", Map.of("action", "run"), null);
            bridge.onToolCall(toolCall);

            // Workflow channel
            verify(eventPublisher).emitAgentToolCall(
                eq("run-1"), eq("agent:test"), eq("workflow"), eq("tc-001"),
                eq(AgentToolCallPhase.CALLING), any(Map.class), eq(0), eq(2)
            );
            // Conversation channel
            verify(conversationPublisher).publishToolCall(
                eq("conv-1"), eq("stream-1"), eq("workflow"), eq("tc-001"), any(Map.class)
            );
        }

        @Test
        @DisplayName("onToolResult should emit to both channels")
        void onToolResultEmitsToBoth() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher,
                conversationPublisher, "conv-1", "stream-1"
            );

            ToolCall toolCall = new ToolCall("tc-001", "catalog", null, null);
            ToolResult result = new ToolResult(toolCall, true, "data here", null, 250L, null);
            bridge.onToolResult(result);

            // Workflow channel
            verify(eventPublisher).emitAgentToolCall(
                eq("run-1"), eq("agent:test"), eq("catalog"), eq("tc-001"),
                eq(AgentToolCallPhase.COMPLETED), any(Map.class), eq(0), isNull()
            );
            // Conversation channel
            verify(conversationPublisher).publishToolResult(
                eq("conv-1"), eq("stream-1"),
                eq("tc-001"), eq("catalog"),
                eq(true), eq(250L), eq("data here")
            );
        }

        @Test
        @DisplayName("failed tool result should emit FAILED phase")
        void failedToolResultEmitsFailedPhase() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher,
                conversationPublisher, "conv-1", "stream-1"
            );

            ToolCall toolCall = new ToolCall("tc-001", "catalog", null, null);
            ToolResult result = new ToolResult(toolCall, false, null, "timeout", 5000L, null);
            bridge.onToolResult(result);

            verify(eventPublisher).emitAgentToolCall(
                eq("run-1"), eq("agent:test"), eq("catalog"), eq("tc-001"),
                eq(AgentToolCallPhase.FAILED), any(Map.class), eq(0), isNull()
            );
        }
    }

    @Nested
    @DisplayName("Null safety")
    class NullSafety {

        @Test
        @DisplayName("onToolCall should ignore null toolCall")
        void onToolCallIgnoresNull() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher
            );

            bridge.onToolCall(null);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("onToolResult should ignore null result")
        void onToolResultIgnoresNullResult() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher
            );

            bridge.onToolResult(null);

            verifyNoInteractions(eventPublisher);
        }

        @Test
        @DisplayName("onToolResult should ignore result with null toolCall")
        void onToolResultIgnoresNullToolCall() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher
            );

            ToolResult result = new ToolResult(null, true, "content", null, null, null);
            bridge.onToolResult(result);

            verifyNoInteractions(eventPublisher);
        }
    }

    @Nested
    @DisplayName("shouldStop()")
    class ShouldStopTests {

        @Test
        @DisplayName("should return false when no Redis template is provided")
        void shouldReturnFalseWithoutRedis() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher
            );

            assertThat(bridge.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should detect workflow cancel signal via runId")
        void shouldDetectWorkflowCancelSignal() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher,
                conversationPublisher, "conv-1", "stream-1",
                null, null, null, null, redisTemplate
            );

            when(redisTemplate.hasKey("workflow:cancel:run-1")).thenReturn(true);

            assertThat(bridge.shouldStop()).isTrue();
            verify(redisTemplate).hasKey("workflow:cancel:run-1");
        }

        @Test
        @DisplayName("should detect conversation cancel signal via streamId")
        void shouldDetectConversationCancelSignal() {
            // No runId, only streamId
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                null, "agent:test", 0, null, null,
                conversationPublisher, "conv-1", "stream-42",
                null, null, null, null, redisTemplate
            );

            when(redisTemplate.hasKey("agent:cancel:stream-42")).thenReturn(true);

            assertThat(bridge.shouldStop()).isTrue();
            verify(redisTemplate).hasKey("agent:cancel:stream-42");
        }

        @Test
        @DisplayName("should return false when no cancel keys exist")
        void shouldReturnFalseWhenNoCancelKeys() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher,
                conversationPublisher, "conv-1", "stream-1",
                null, null, null, null, redisTemplate
            );

            when(redisTemplate.hasKey("workflow:cancel:run-1")).thenReturn(false);
            when(redisTemplate.hasKey("agent:cancel:stream-1")).thenReturn(false);

            assertThat(bridge.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should cache stop signal locally after first detection")
        void shouldCacheStopSignalLocally() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher,
                conversationPublisher, "conv-1", "stream-1",
                null, null, null, null, redisTemplate
            );

            when(redisTemplate.hasKey("workflow:cancel:run-1")).thenReturn(true);

            // First call detects cancel
            assertThat(bridge.shouldStop()).isTrue();
            // Second call uses cached value
            assertThat(bridge.shouldStop()).isTrue();

            // Redis should only have been queried once
            verify(redisTemplate, times(1)).hasKey("workflow:cancel:run-1");
        }

        @Test
        @DisplayName("should handle Redis errors gracefully (fail-open)")
        void shouldHandleRedisErrorsGracefully() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher,
                conversationPublisher, "conv-1", "stream-1",
                null, null, null, null, redisTemplate
            );

            when(redisTemplate.hasKey("workflow:cancel:run-1"))
                .thenThrow(new RuntimeException("Redis connection error"));

            assertThat(bridge.shouldStop()).isFalse();
        }

        @Test
        @DisplayName("should check workflow cancel before conversation cancel")
        void shouldCheckWorkflowCancelFirst() {
            AgentToolCallEventBridge bridge = new AgentToolCallEventBridge(
                "run-1", "agent:test", 0, null, eventPublisher,
                conversationPublisher, "conv-1", "stream-1",
                null, null, null, null, redisTemplate
            );

            when(redisTemplate.hasKey("workflow:cancel:run-1")).thenReturn(true);

            assertThat(bridge.shouldStop()).isTrue();

            // Should not check agent:cancel since workflow:cancel already matched
            verify(redisTemplate, never()).hasKey("agent:cancel:stream-1");
        }
    }
}
