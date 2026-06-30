package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("AgentObservabilityClient - buildRequestBody")
@ExtendWith(MockitoExtension.class)
class AgentObservabilityClientTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private com.apimarketplace.common.credit.CreditConsumptionClient creditClient;

    private AgentObservabilityClient client;

    @BeforeEach
    void setUp() {
        client = new AgentObservabilityClient(restTemplate, creditClient);
    }

    private AgentConfigProvider.AgentConfig agentConfig(Double temp, Integer maxTokens, Integer maxIter) {
        return new AgentConfigProvider.AgentConfig(
            "agent-id", "Test Agent", "system prompt", "anthropic", "claude-3",
            temp, maxTokens, maxIter, null, null, null,
            null, null, null
        );
    }

    // ==========================================================================
    // Tool results flattening (the bug fix)
    // ==========================================================================

    @Nested
    @DisplayName("toolResults flattening")
    class ToolResultsFlattening {

        @Test
        @DisplayName("should flatten nested toolCall structure to flat fields")
        void flattenNestedToolCall() {
            // This is the EXACT structure that Jackson produces from ToolResult records in agent-service
            Map<String, Object> toolCall = new LinkedHashMap<>();
            toolCall.put("id", "call_abc123");
            toolCall.put("toolName", "web_search");
            toolCall.put("arguments", Map.of("query", "test query"));
            toolCall.put("index", 0);

            Map<String, Object> toolResult = new LinkedHashMap<>();
            toolResult.put("toolCall", toolCall);
            toolResult.put("success", true);
            toolResult.put("content", "Search results here");
            toolResult.put("error", null);
            toolResult.put("durationMs", 450L);
            toolResult.put("metadata", Map.of("source", "web"));

            var response = new AgentExecutionResponseDto(
                true, "Final answer", "content",
                List.of(toolResult), 1,
                Map.of("promptTokens", 100, "completionTokens", 50, "totalTokens", 150),
                null, 1200L, "anthropic", "claude-3",
                List.of(), "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("toolResults");
            assertThat(results).hasSize(1);

            Map<String, Object> flat = results.get(0);
            // Flattened fields from toolCall
            assertThat(flat.get("toolCallId")).isEqualTo("call_abc123");
            assertThat(flat.get("toolName")).isEqualTo("web_search");
            assertThat(flat.get("arguments")).isEqualTo(Map.of("query", "test query"));
            // No nested toolCall anymore
            assertThat(flat).doesNotContainKey("toolCall");
            // Passthrough fields
            assertThat(flat.get("success")).isEqualTo(true);
            assertThat(flat.get("content")).isEqualTo("Search results here");
            assertThat(flat.get("error")).isNull();
            assertThat(flat.get("durationMs")).isEqualTo(450L);
            assertThat(flat.get("metadata")).isEqualTo(Map.of("source", "web"));
        }

        @Test
        @DisplayName("should handle null toolCall gracefully")
        void handleNullToolCall() {
            Map<String, Object> toolResult = new LinkedHashMap<>();
            toolResult.put("toolCall", null);
            toolResult.put("success", false);
            toolResult.put("content", null);
            toolResult.put("error", "No tool call");
            toolResult.put("durationMs", null);
            toolResult.put("metadata", null);

            var response = new AgentExecutionResponseDto(
                false, null, null,
                List.of(toolResult), 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                "error", 500L, "anthropic", "claude-3",
                null, "error", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("toolResults");
            assertThat(results).hasSize(1);

            Map<String, Object> flat = results.get(0);
            assertThat(flat.get("toolCallId")).isNull();
            assertThat(flat.get("toolName")).isEqualTo("unknown");
            assertThat(flat.get("arguments")).isNull();
            assertThat(flat.get("success")).isEqualTo(false);
            assertThat(flat.get("error")).isEqualTo("No tool call");
        }

        @Test
        @DisplayName("should flatten multiple tool results")
        void flattenMultipleToolResults() {
            Map<String, Object> tc1 = Map.of("id", "call_1", "toolName", "search", "arguments", Map.of("q", "a"));
            Map<String, Object> tc2 = Map.of("id", "call_2", "toolName", "fetch", "arguments", Map.of("url", "http://x"));

            Map<String, Object> tr1 = new LinkedHashMap<>();
            tr1.put("toolCall", tc1);
            tr1.put("success", true);
            tr1.put("content", "result1");
            tr1.put("error", null);
            tr1.put("durationMs", 100L);
            tr1.put("metadata", null);

            Map<String, Object> tr2 = new LinkedHashMap<>();
            tr2.put("toolCall", tc2);
            tr2.put("success", false);
            tr2.put("content", null);
            tr2.put("error", "timeout");
            tr2.put("durationMs", 5000L);
            tr2.put("metadata", null);

            var response = new AgentExecutionResponseDto(
                true, "done", null,
                List.of(tr1, tr2), 1,
                Map.of("promptTokens", 200, "completionTokens", 100, "totalTokens", 300),
                null, 5100L, "openai", "gpt-4",
                null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) body.get("toolResults");
            assertThat(results).hasSize(2);
            assertThat(results.get(0).get("toolCallId")).isEqualTo("call_1");
            assertThat(results.get(0).get("toolName")).isEqualTo("search");
            assertThat(results.get(1).get("toolCallId")).isEqualTo("call_2");
            assertThat(results.get(1).get("toolName")).isEqualTo("fetch");
            assertThat(results.get(1).get("error")).isEqualTo("timeout");
        }
    }

    // ==========================================================================
    // Token usage mapping
    // ==========================================================================

    @Nested
    @DisplayName("token usage mapping")
    class TokenUsage {

        @Test
        @DisplayName("should map all token fields from totalUsage")
        void mapAllTokenFields() {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("promptTokens", 500);
            usage.put("completionTokens", 200);
            usage.put("totalTokens", 700);
            usage.put("cacheCreationInputTokens", 100);
            usage.put("cacheReadInputTokens", 50);
            usage.put("cachedTokens", 80);
            usage.put("reasoningTokens", 30);

            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1, usage, null, 100L,
                "anthropic", "claude-3", null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body.get("totalPromptTokens")).isEqualTo(500);
            assertThat(body.get("totalCompletionTokens")).isEqualTo(200);
            assertThat(body.get("totalTokens")).isEqualTo(700);
            assertThat(body.get("totalCacheCreationTokens")).isEqualTo(100);
            assertThat(body.get("totalCacheReadTokens")).isEqualTo(50);
            assertThat(body.get("totalCachedTokens")).isEqualTo(80);
            assertThat(body.get("totalReasoningTokens")).isEqualTo(30);
        }

        @Test
        @DisplayName("should default to 0 when totalUsage is null")
        void nullUsageDefaults() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1, null, null, 100L,
                "anthropic", "claude-3", null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body.get("totalPromptTokens")).isEqualTo(0);
            assertThat(body.get("totalCompletionTokens")).isEqualTo(0);
            assertThat(body.get("totalTokens")).isEqualTo(0);
        }
    }

    // ==========================================================================
    // Tool stats
    // ==========================================================================

    @Nested
    @DisplayName("tool stats")
    class ToolStats {

        @Test
        @DisplayName("should compute tool stats from toolResults")
        void computeToolStats() {
            Map<String, Object> tc1 = Map.of("id", "c1", "toolName", "search", "arguments", Map.of());
            Map<String, Object> tc2 = Map.of("id", "c2", "toolName", "search", "arguments", Map.of());
            Map<String, Object> tc3 = Map.of("id", "c3", "toolName", "fetch", "arguments", Map.of());

            List<Map<String, Object>> toolResults = List.of(
                Map.of("toolCall", tc1, "success", true, "content", "r1"),
                Map.of("toolCall", tc2, "success", true, "content", "r2"),
                Map.of("toolCall", tc3, "success", false, "content", "")
            );

            var response = new AgentExecutionResponseDto(
                true, "ok", null, toolResults, 2,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 300L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body.get("totalToolCalls")).isEqualTo(3);
            assertThat(body.get("successfulToolCalls")).isEqualTo(2);
            assertThat(body.get("failedToolCalls")).isEqualTo(1);
            assertThat(body.get("toolSequence")).isEqualTo("search,search,fetch");
            assertThat(body.get("distinctTools")).isEqualTo(List.of("search", "fetch"));
        }

        @Test
        @DisplayName("should handle null toolResults")
        void nullToolResults() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body.get("totalToolCalls")).isEqualTo(0);
            assertThat(body.get("successfulToolCalls")).isEqualTo(0);
            assertThat(body.get("failedToolCalls")).isEqualTo(0);
            assertThat(body).doesNotContainKey("toolSequence");
            assertThat(body).doesNotContainKey("distinctTools");
        }
    }

    // ==========================================================================
    // Loop detection
    // ==========================================================================

    @Nested
    @DisplayName("loop detection")
    class LoopDetection {

        @Test
        @DisplayName("should extract loop fields from metrics")
        void loopDetected() {
            Map<String, Object> metrics = Map.of(
                "loopDetected", true,
                "loopType", "INFINITE",
                "loopToolName", "web_search"
            );

            var response = new AgentExecutionResponseDto(
                false, null, null, null, 5,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                "loop detected", 10000L, "anthropic", "claude-3",
                null, "max_iterations", metrics, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body.get("loopDetected")).isEqualTo(true);
            assertThat(body.get("loopType")).isEqualTo("INFINITE");
            assertThat(body.get("loopToolName")).isEqualTo("web_search");
        }

        @Test
        @DisplayName("should set loopDetected=false when metrics is null")
        void noLoopWhenNoMetrics() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);
            assertThat(body.get("loopDetected")).isEqualTo(false);
        }
    }

    // ==========================================================================
    // Passthrough fields
    // ==========================================================================

    @Nested
    @DisplayName("passthrough fields")
    class Passthrough {

        @Test
        @DisplayName("should pass conversationHistory as-is")
        void conversationHistoryPassthrough() {
            List<Map<String, Object>> history = List.of(
                Map.of("role", "USER", "content", "hello"),
                Map.of("role", "ASSISTANT", "content", "hi there")
            );

            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                history, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);
            assertThat(body.get("conversationHistory")).isSameAs(history);
        }

        @Test
        @DisplayName("should pass usagePerIteration as-is")
        void usagePerIterationPassthrough() {
            List<Map<String, Object>> usagePerIter = List.of(
                Map.of("promptTokens", 50, "completionTokens", 25)
            );

            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 50, "completionTokens", 25, "totalTokens", 75),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, usagePerIter, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);
            assertThat(body.get("usagePerIteration")).isSameAs(usagePerIter);
        }

        @Test
        @DisplayName("should pass iterationDurations and finishReasonsPerIteration")
        void iterationDurationsAndFinishReasons() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 2,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 2000L, "anthropic", "claude-3",
                null, "end_turn", null, null,
                List.of(800L, 1200L), List.of("tool_use", "end_turn"), null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body.get("iterationDurations")).isEqualTo(List.of(800L, 1200L));
            assertThat(body.get("finishReasonsPerIteration")).isEqualTo(List.of("tool_use", "end_turn"));
        }

        @Test
        @DisplayName("should extract toolCallsPerIteration from metrics")
        void toolCallsPerIteration() {
            Map<String, Object> metrics = Map.of(
                "toolCallsPerIteration", List.of(2, 1)
            );

            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 2,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", metrics, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);
            assertThat(body.get("toolCallsPerIteration")).isEqualTo(List.of(2, 1));
        }
    }

    // ==========================================================================
    // Agent config
    // ==========================================================================

    @Nested
    @DisplayName("agent config")
    class AgentConfigMapping {

        @Test
        @DisplayName("should include agent config fields when present")
        void agentConfigPresent() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            var config = agentConfig(0.7, 4096, 10);
            Map<String, Object> body = client.buildRequestBody("agent-id", response, "my prompt", null, "conv-1", config);

            assertThat(body.get("temperature")).isEqualTo(0.7);
            assertThat(body.get("maxTokens")).isEqualTo(4096);
            assertThat(body.get("maxIterations")).isEqualTo(10);
        }

        @Test
        @DisplayName("should not include agent config fields when config is null")
        void agentConfigNull() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body).doesNotContainKey("temperature");
            assertThat(body).doesNotContainKey("maxTokens");
            assertThat(body).doesNotContainKey("maxIterations");
        }
    }

    // ==========================================================================
    // Header fields
    // ==========================================================================

    @Nested
    @DisplayName("header fields")
    class HeaderFields {

        @Test
        @DisplayName("should map all header fields from response")
        void allHeaderFields() {
            var response = new AgentExecutionResponseDto(
                true, "Final answer", null, null, 3,
                Map.of("promptTokens", 100, "completionTokens", 50, "totalTokens", 150),
                null, 5000L, "openai", "gpt-4o",
                List.of(Map.of("role", "USER", "content", "hi")),
                "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("my-agent", response, "sys prompt", "user question", "conv-99", null);

            assertThat(body.get("agentEntityId")).isEqualTo("my-agent");
            assertThat(body.get("provider")).isEqualTo("openai");
            assertThat(body.get("model")).isEqualTo("gpt-4o");
            assertThat(body.get("success")).isEqualTo(true);
            assertThat(body.get("stopReason")).isEqualTo("end_turn");
            assertThat(body.get("errorMessage")).isNull();
            assertThat(body.get("durationMs")).isEqualTo(5000L);
            assertThat(body.get("iterationCount")).isEqualTo(3);
            assertThat(body.get("conversationId")).isEqualTo("conv-99");
            assertThat(body.get("systemPrompt")).isEqualTo("sys prompt");
            assertThat(body.get("userPrompt")).isEqualTo("user question");
            assertThat(body.get("messageCount")).isEqualTo(1);
        }
    }

    // ==========================================================================
    // Edge cases
    // ==========================================================================

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should not include empty lists in body")
        void emptyListsNotIncluded() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, List.of(), 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                List.of(), "end_turn", null, List.of(), List.of(), List.of(), null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body).doesNotContainKey("toolResults");
            assertThat(body).doesNotContainKey("conversationHistory");
            assertThat(body).doesNotContainKey("usagePerIteration");
            assertThat(body).doesNotContainKey("iterationDurations");
            assertThat(body).doesNotContainKey("finishReasonsPerIteration");
        }

        @Test
        @DisplayName("should handle null conversationHistory and null metrics")
        void nullHistoryAndMetrics() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody("agent-id", response, "prompt", null, "conv-1", null);

            assertThat(body).doesNotContainKey("conversationHistory");
            assertThat(body).doesNotContainKey("toolCallsPerIteration");
            assertThat(body.get("loopDetected")).isEqualTo(false);
        }
    }

    // ==========================================================================
    // General chat (null agentId) recording
    // ==========================================================================

    @Nested
    @DisplayName("general chat recording (null agentId)")
    class GeneralChatRecording {

        @Test
        @DisplayName("should send request when agentId is null (general chat)")
        @SuppressWarnings("unchecked")
        void recordAsyncSendsWhenAgentIdNull() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");

            var response = new AgentExecutionResponseDto(
                true, "Hello!", null, null, 1,
                Map.of("promptTokens", 50, "completionTokens", 25, "totalTokens", 75),
                null, 800L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            // Should NOT skip - previously this would return early
            client.recordAsync("tenant-1", null, null, response, "system prompt", null, "conv-1", null, null, null, null);

            verify(restTemplate).exchange(
                eq("http://localhost:8099/api/internal/agent-observability/record"),
                eq(HttpMethod.POST),
                argThat(entity -> {
                    Map<String, Object> body = (Map<String, Object>) entity.getBody();
                    return body != null && body.get("agentEntityId") == null
                        && "conv-1".equals(body.get("conversationId"));
                }),
                eq(Map.class)
            );
        }

        @Test
        @DisplayName("buildRequestBody should set agentEntityId to null for general chat")
        void buildBodyNullAgentId() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            Map<String, Object> body = client.buildRequestBody(null, response, "prompt", null, "conv-1", null);

            assertThat(body.get("agentEntityId")).isNull();
            assertThat(body.containsKey("agentEntityId")).isTrue();
            assertThat(body.get("conversationId")).isEqualTo("conv-1");
            assertThat(body).doesNotContainKey("temperature");
            assertThat(body).doesNotContainKey("maxTokens");
        }

        @Test
        @DisplayName("should still skip when response is null")
        void skipWhenResponseNull() {
            client.recordAsync("tenant-1", null, null, null, "prompt", null, "conv-1", null, null, null, null);
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("should still skip when tenantId is null")
        void skipWhenTenantIdNull() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            client.recordAsync(null, null, null, response, "prompt", null, "conv-1", null, null, null, null);
            verifyNoInteractions(restTemplate);
        }
    }

    // ==========================================================================
    // Fallback to direct credit consumption when observability HTTP fails
    // ==========================================================================

    @Nested
    @DisplayName("Credit fallback when observability fails")
    class CreditFallback {

        @Test
        @DisplayName("should fall back to creditClient.consumeCreditsAsync when HTTP fails")
        void fallbackToCreditClientOnHttpFailure() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 800, "completionTokens", 400, "totalTokens", 1200),
                null, 2000L, "anthropic", "claude-3-sonnet",
                null, "end_turn", null, null, null, null, null, null
            , null);

            // Simulate observability HTTP call failing (agent-service down)
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

            client.recordAsync("tenant-42", null, "agent-1", response, "prompt", null, "conv-99", null, null, null, null);

            // Fallback: consumeCreditsAsync called with correct token counts
            verify(creditClient).consumeCreditsAsync(
                    eq("tenant-42"),
                    eq("CHAT_CONVERSATION"),
                    eq("conv-99"),
                    eq("anthropic"),
                    eq("claude-3-sonnet"),
                    eq(800),
                    eq(400)
            );
        }

        @Test
        @DisplayName("fallback credit debit runs inside the captured org scope")
        void fallbackCreditDebitRunsInsideCapturedOrgScope() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 800, "completionTokens", 400, "totalTokens", 1200),
                null, 2000L, "anthropic", "claude-3-sonnet",
                null, "end_turn", null, null, null, null, null, null,
                null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));
            doAnswer(invocation -> {
                assertThat(TenantResolver.currentRequestOrganizationId()).isEqualTo("org-credit-fallback");
                return null;
            }).when(creditClient).consumeCreditsAsync(
                    eq("tenant-42"),
                    eq("CHAT_CONVERSATION"),
                    eq("conv-99"),
                    eq("anthropic"),
                    eq("claude-3-sonnet"),
                    eq(800),
                    eq(400)
            );

            client.recordAsync("tenant-42", "org-credit-fallback", "agent-1", response,
                    "prompt", null, "conv-99", null, null, null, null);

            assertThat(TenantResolver.currentRequestOrganizationId())
                    .as("Fallback org scope must not leak after the debit call.")
                    .isNull();
        }

        @Test
        @DisplayName("should NOT call fallback when observability HTTP succeeds")
        void noFallbackWhenObservabilityOk() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 500, "completionTokens", 200, "totalTokens", 700),
                null, 1500L, "openai", "gpt-4",
                null, "end_turn", null, null, null, null, null, null
            , null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(Map.of("success", true), HttpStatus.OK));

            client.recordAsync("tenant-42", null, "agent-1", response, "prompt", null, "conv-99", null, null, null, null);

            verifyNoInteractions(creditClient);
        }

        @Test
        @DisplayName("fallback uses agentId when conversationId is null")
        void fallbackUsesAgentIdWhenConvIdNull() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 100, "completionTokens", 50, "totalTokens", 150),
                null, 1000L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RuntimeException("503 Service Unavailable"));

            client.recordAsync("tenant-42", null, "agent-id-7", response, "prompt", null, null, null, null, null, null);

            verify(creditClient).consumeCreditsAsync(
                    eq("tenant-42"),
                    eq("CHAT_CONVERSATION"),
                    eq("agent-id-7"),  // falls back to agentId
                    any(), any(), anyInt(), anyInt()
            );
        }

        @Test
        @DisplayName("fallback swallows credit client errors (no exception propagation)")
        void fallbackSwallowsCreditClientErrors() {
            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 100, "completionTokens", 50, "totalTokens", 150),
                null, 1000L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null
            , null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                    .thenThrow(new RuntimeException("HTTP failure"));
            doThrow(new RuntimeException("Credit client also failed"))
                    .when(creditClient).consumeCreditsAsync(any(), any(), any(), any(), any(), anyInt(), anyInt());

            // Must not throw - final defense
            org.assertj.core.api.Assertions.assertThatCode(() ->
                client.recordAsync("tenant-42", null, "agent-1", response, "prompt", null, "conv-99", null, null, null, null)
            ).doesNotThrowAnyException();
        }
    }

    // ==========================================================================
    // PR20 - X-Organization-ID propagation on outbound observability call
    // ==========================================================================

    /**
     * Regression guards for the very seam Reviewer C round-1 caught: the outbound
     * HTTP call from conversation-service to orchestrator must carry
     * {@code X-Organization-ID} when the chat was driven from an org workspace.
     * Pre-PR20 round-2 the headers block only set {@code X-User-ID}, dropping the
     * org context before it could reach agent-service. Without these tests a future
     * refactor that removes the {@code headers.set("X-Organization-ID", orgId)} line
     * would ship green at this layer and silently re-introduce the bug.
     */
    @Nested
    @DisplayName("PR20 - outbound X-Organization-ID header propagation")
    class OutboundOrgHeader {

        @Test
        @DisplayName("orgId non-null → X-Organization-ID set on outbound headers")
        void orgIdSetOnOutboundHeaders() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");

            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null,
                null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            client.recordAsync("tenant-42", "org-acme", "agent-1", response,
                "system", "user", "conv-1", null, null, null, null);

            org.mockito.ArgumentCaptor<HttpEntity<?>> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
            HttpHeaders sent = entityCaptor.getValue().getHeaders();
            assertThat(sent.getFirst("X-User-ID"))
                .as("Tenant header must still be set alongside the org header.")
                .isEqualTo("tenant-42");
            assertThat(sent.getFirst("X-Organization-ID"))
                .as("PR20 round-2 - the outbound observability hop MUST forward the workspace "
                  + "identity. A regression that drops this header would silently re-introduce "
                  + "the 'team-workspace chat agent history empty' bug class.")
                .isEqualTo("org-acme");
        }

        @Test
        @DisplayName("orgId null → X-Organization-ID absent from outbound headers (personal scope)")
        void nullOrgIdOmitsHeader() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");

            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null,
                null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            client.recordAsync("tenant-42", null, "agent-1", response,
                "system", "user", "conv-1", null, null, null, null);

            org.mockito.ArgumentCaptor<HttpEntity<?>> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
            HttpHeaders sent = entityCaptor.getValue().getHeaders();
            assertThat(sent.containsKey("X-Organization-ID"))
                .as("Personal-scope chat MUST NOT send X-Organization-ID - receiver routes "
                  + "null-or-absent header as personal scope; a stray non-null value would "
                  + "misroute the row to an org workspace.")
                .isFalse();
        }

        @Test
        @DisplayName("orgId blank → X-Organization-ID absent (blank-string normalised to personal scope)")
        void blankOrgIdOmitsHeader() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");

            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null,
                null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            client.recordAsync("tenant-42", "   ", "agent-1", response,
                "system", "user", "conv-1", null, null, null, null);

            org.mockito.ArgumentCaptor<HttpEntity<?>> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
            assertThat(entityCaptor.getValue().getHeaders().containsKey("X-Organization-ID")).isFalse();
        }

        @Test
        @DisplayName("legacy 9-arg overload (pre-PR20 callers) → no X-Organization-ID on outbound")
        void legacyOverloadOmitsHeader() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");

            var response = new AgentExecutionResponseDto(
                true, "ok", null, null, 1,
                Map.of("promptTokens", 10, "completionTokens", 5, "totalTokens", 15),
                null, 100L, "anthropic", "claude-3",
                null, "end_turn", null, null, null, null, null, null,
                null);

            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            // 9-arg overload delegates with orgId=null - personal scope.
            client.recordAsync("tenant-42", null, "agent-1", response,
                "system", "user", "conv-1", null, null, null, null);

            org.mockito.ArgumentCaptor<HttpEntity<?>> entityCaptor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
            assertThat(entityCaptor.getValue().getHeaders().containsKey("X-Organization-ID")).isFalse();
        }
    }

    // ==========================================================================
    // recordFailureAsync - pins the body shape POSTed when sync chat short-circuits
    // before reaching the agent loop (402 insufficient credits, transport null, etc.)
    // ==========================================================================

    @Nested
    @DisplayName("recordFailureAsync - failure-only execution row")
    class RecordFailureAsync {

        @Test
        @DisplayName("POSTs minimal failure body with success=false, 0 counters, BUDGET_EXHAUSTED on 402 path")
        @SuppressWarnings("unchecked")
        void recordFailureSendsMinimalBodyOnBudgetExhausted() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            client.recordFailureAsync("tenant-1", "org-1", "agent-1",
                    "SCHEDULE", "conv-1", "BUDGET_EXHAUSTED", "Insufficient credits",
                    "scheduled prompt body", "[Error] Insufficient credits",
                    "claude-code", "claude-opus-4-7");

            org.mockito.ArgumentCaptor<HttpEntity<?>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                eq("http://localhost:8099/api/internal/agent-observability/record"),
                eq(HttpMethod.POST), captor.capture(), eq(Map.class));

            Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
            assertThat(body).containsEntry("agentEntityId", "agent-1")
                    .containsEntry("success", false)
                    .containsEntry("stopReason", "BUDGET_EXHAUSTED")
                    .containsEntry("errorMessage", "Insufficient credits")
                    .containsEntry("conversationId", "conv-1")
                    .containsEntry("source", "SCHEDULE")
                    .containsEntry("totalPromptTokens", 0)
                    .containsEntry("totalCompletionTokens", 0)
                    .containsEntry("totalTokens", 0)
                    .containsEntry("iterationCount", 0)
                    .containsEntry("totalToolCalls", 0)
                    .containsEntry("loopDetected", false)
                    .containsEntry("userPrompt", "scheduled prompt body")
                    .containsEntry("messageCount", 2)
                    // provider+model carried through so AgentMetricsQueryService.
                    // getModelStatsByAgent's WHERE model IS NOT NULL filter includes
                    // this row in the per-model aggregate (the 2026-05-22 chip
                    // visibility regression that prompted the threading fix).
                    .containsEntry("provider", "claude-code")
                    .containsEntry("model", "claude-opus-4-7");
            // No temperature/maxTokens/maxIterations (no agentConfig at 402 time)
            assertThat(body).doesNotContainKey("temperature")
                    .doesNotContainKey("maxTokens")
                    .doesNotContainKey("maxIterations");

            // conversationHistory threaded so the Agent Performance execution detail
            // view shows the schedule prompt + the [Error] line in agent_execution_messages
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("conversationHistory");
            assertThat(history).hasSize(2);
            assertThat(history.get(0)).containsEntry("role", "USER")
                    .containsEntry("content", "scheduled prompt body");
            assertThat(history.get(1)).containsEntry("role", "ASSISTANT")
                    .containsEntry("content", "[Error] Insufficient credits");

            assertThat(captor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-1");
            assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
        }

        @Test
        @DisplayName("Skips when agentId is null - dashboard rows are keyed on agentEntityId")
        void recordFailureSkipsWhenAgentIdNull() {
            client.recordFailureAsync("tenant-1", "org-1", null,
                    "SCHEDULE", "conv-1", "BUDGET_EXHAUSTED", "Insufficient credits",
                    null, null, null, null);
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Skips when tenantId is null")
        void recordFailureSkipsWhenTenantIdNull() {
            client.recordFailureAsync(null, "org-1", "agent-1",
                    "SCHEDULE", "conv-1", "BUDGET_EXHAUSTED", "Insufficient credits",
                    null, null, null, null);
            verifyNoInteractions(restTemplate);
        }

        @Test
        @DisplayName("Defaults stopReason to FAILED when caller passes null")
        @SuppressWarnings("unchecked")
        void recordFailureDefaultsStopReasonToFailed() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            client.recordFailureAsync("tenant-1", null, "agent-1",
                    "WEBHOOK", "conv-1", null, "Bridge crashed", null, null, null, null);

            org.mockito.ArgumentCaptor<HttpEntity<?>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
            Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
            assertThat(body).containsEntry("stopReason", "FAILED");
            // No X-Organization-ID when orgId is null (personal scope)
            assertThat(captor.getValue().getHeaders().containsKey("X-Organization-ID")).isFalse();
        }

        @Test
        @DisplayName("Body omits conversationHistory + sets messageCount=0 when BOTH prompts are null (truth-table corner: false,false)")
        @SuppressWarnings("unchecked")
        void recordFailureOmitsHistoryWhenNoPrompts() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            client.recordFailureAsync("tenant-1", "org-1", "agent-1",
                    "SCHEDULE", "conv-1", "BUDGET_EXHAUSTED", "Insufficient credits",
                    null, null, null, null);

            org.mockito.ArgumentCaptor<HttpEntity<?>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
            Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
            assertThat(body).containsEntry("messageCount", 0);
            assertThat(body).doesNotContainKey("conversationHistory");
            assertThat(body).doesNotContainKey("userPrompt");
        }

        @Test
        @DisplayName("Body carries ASSISTANT-only history when userPrompt is null but assistantContent is set (corner: false,true)")
        @SuppressWarnings("unchecked")
        void recordFailureAssistantOnlyHistory() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            client.recordFailureAsync("tenant-1", "org-1", "agent-1",
                    "SCHEDULE", "conv-1", "FAILED", "bridge crashed",
                    null, "[Error] bridge crashed", null, null);

            org.mockito.ArgumentCaptor<HttpEntity<?>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
            Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
            assertThat(body).containsEntry("messageCount", 1);
            assertThat(body).doesNotContainKey("userPrompt");

            List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("conversationHistory");
            assertThat(history).hasSize(1);
            assertThat(history.get(0)).containsEntry("role", "ASSISTANT")
                    .containsEntry("content", "[Error] bridge crashed");
        }

        @Test
        @DisplayName("Body carries USER-only history when assistantContent is null but userPrompt is set (corner: true,false)")
        @SuppressWarnings("unchecked")
        void recordFailureUserOnlyHistory() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of()));

            client.recordFailureAsync("tenant-1", "org-1", "agent-1",
                    "WEBHOOK", "conv-1", "FAILED", "no agent context",
                    "user prompt", null, null, null);

            org.mockito.ArgumentCaptor<HttpEntity<?>> captor =
                org.mockito.ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(Map.class));
            Map<String, Object> body = (Map<String, Object>) captor.getValue().getBody();
            assertThat(body).containsEntry("messageCount", 1)
                    .containsEntry("userPrompt", "user prompt");

            List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("conversationHistory");
            assertThat(history).hasSize(1);
            assertThat(history.get(0)).containsEntry("role", "USER")
                    .containsEntry("content", "user prompt");
        }

        @Test
        @DisplayName("HTTP failure swallowed - best-effort, never throws to caller")
        void recordFailureSwallowsHttpFailure() {
            ReflectionTestUtils.setField(client, "orchestratorUrl", "http://localhost:8099");
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("orchestrator down"));

            // Must NOT throw - the schedule daemon already returned its verdict.
            client.recordFailureAsync("tenant-1", "org-1", "agent-1",
                    "SCHEDULE", "conv-1", "BUDGET_EXHAUSTED", "Insufficient credits",
                    "scheduled prompt body", "[Error] Insufficient credits",
                    null, null);
        }
    }
}
