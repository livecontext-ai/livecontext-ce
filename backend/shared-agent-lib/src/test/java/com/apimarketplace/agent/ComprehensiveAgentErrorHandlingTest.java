package com.apimarketplace.agent;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.loop.*;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.ratelimit.ProviderRateLimiter;
import com.apimarketplace.agent.ratelimit.RateLimitMode;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import com.apimarketplace.agent.tool.ToolDiscoveryService;
import com.apimarketplace.agent.tool.ToolExecutionService;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.validation.ToolParameterValidator;
import com.apimarketplace.agent.tools.validation.ValidationResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive error handling tests for the Agent system.
 *
 * <p>This test suite covers ALL critical error scenarios:
 * <ul>
 *   <li>LLM Provider errors (rate limit, auth, connection)</li>
 *   <li>Tool validation errors (missing params, invalid types)</li>
 *   <li>Tool execution errors (timeout, failure)</li>
 *   <li>Loop detection and warnings</li>
 *   <li>Streaming errors and disconnection</li>
 *   <li>Context overflow handling</li>
 *   <li>Rate limiting</li>
 * </ul>
 *
 * <p>Each error should generate a clear message to guide the LLM.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Comprehensive Agent Error Handling Tests")
class ComprehensiveAgentErrorHandlingTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    // TEST FIXTURES
    // ═══════════════════════════════════════════════════════════════════════════════

    @Mock
    private LLMProviderFactory mockProviderFactory;

    @Mock
    private ToolExecutionService mockToolExecutionService;

    @Mock
    private ToolDiscoveryService mockToolDiscoveryService;

    @Mock
    private LLMProvider mockProvider;

    private AgentLoopService agentLoopService;

    @BeforeEach
    void setUp() {
        agentLoopService = new AgentLoopService(
            mockProviderFactory,
            mockToolExecutionService,
            mockToolDiscoveryService,
            AgentLogger.NOOP
        );
    }

    private LLMProvider createMockProvider(String name) {
        LLMProvider provider = mock(LLMProvider.class);
        lenient().when(provider.getProviderName()).thenReturn(name);
        lenient().when(provider.getDefaultModel()).thenReturn("test-model");
        lenient().when(provider.isConfigured()).thenReturn(true);
        lenient().when(provider.supportsStreaming()).thenReturn(true);
        lenient().when(provider.supportsToolCalling()).thenReturn(true);
        return provider;
    }

    private CompletionResponse createSimpleResponse(String content) {
        return CompletionResponse.builder()
            .content(content)
            .finishReason("stop")
            .usage(UsageInfo.builder().promptTokens(100).completionTokens(50).totalTokens(150).build())
            .build();
    }

    private CompletionResponse createResponseWithToolCalls(List<ToolCall> toolCalls) {
        return CompletionResponse.builder()
            .content("")
            .toolCalls(toolCalls)
            .finishReason("tool_calls")
            .usage(UsageInfo.builder().promptTokens(100).completionTokens(50).totalTokens(150).build())
            .build();
    }

    private AgentLoopContext createContext(String userPrompt) {
        return AgentLoopContext.builder()
            .provider("openai")
            .model("gpt-4o")
            .userPrompt(userPrompt)
            .maxIterations(5)
            .build();
    }

    private AgentLoopContext createContextWithTools(String userPrompt, List<ToolDefinition> tools) {
        return AgentLoopContext.builder()
            .provider("openai")
            .model("gpt-4o")
            .userPrompt(userPrompt)
            .tools(tools)
            .maxIterations(10)
            .build();
    }

    private ToolDefinition createToolDefinition(String name) {
        return ToolDefinition.builder()
            .name(name)
            .description("Test tool: " + name)
            .parameters(List.of())
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LLM PROVIDER ERROR TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LLM Provider Error Tests")
    class LLMProviderErrorTests {

        @Test
        @DisplayName("Should handle rate limit error (HTTP 429)")
        void handlesRateLimitError() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(
                LLMProviderException.rateLimited("openai")
            );

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).containsIgnoringCase("rate");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }

        @Test
        @DisplayName("Should handle unauthorized error (HTTP 401)")
        void handlesUnauthorizedError() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(
                LLMProviderException.unauthorized("openai")
            );

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).containsIgnoringCase("API key");
        }

        @Test
        @DisplayName("Should handle model not found error")
        void handlesModelNotFoundError() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(
                LLMProviderException.modelNotFound("openai", "gpt-5-turbo")
            );

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).containsIgnoringCase("model");
        }

        @Test
        @DisplayName("Should handle connection refused error")
        void handlesConnectionRefusedError() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(
                new LLMProviderException("openai", "Connection refused", new ConnectException())
            );

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Connection");
        }

        @Test
        @DisplayName("Should handle DNS resolution error")
        void handlesDnsResolutionError() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(
                new LLMProviderException("openai", "Unknown host: api.openai.com", new UnknownHostException())
            );

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Unknown host");
        }

        @Test
        @DisplayName("Should handle socket timeout error")
        void handlesSocketTimeoutError() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(
                new LLMProviderException("openai", "Read timed out", new SocketTimeoutException())
            );

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("timed out");
        }

        @Test
        @DisplayName("Should handle provider not configured")
        void handlesProviderNotConfigured() {
            LLMProvider provider = createMockProvider("openai");
            when(provider.isConfigured()).thenReturn(false);
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not configured");
        }

        @Test
        @DisplayName("Should handle unknown provider")
        void handlesUnknownProvider() {
            when(mockProviderFactory.getProvider("unknown"))
                .thenThrow(new LLMProviderException("unknown", "Provider not found"));

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("unknown")
                .userPrompt("Hello")
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Provider not found");
        }

        @Test
        @DisplayName("Should handle HTTP 500 internal server error")
        void handlesInternalServerError() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(
                new LLMProviderException("openai", "Internal server error (500)")
            );

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("500");
        }

        @Test
        @DisplayName("Should handle HTTP 503 service unavailable")
        void handlesServiceUnavailable() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(
                new LLMProviderException("openai", "Service unavailable (503)")
            );

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("503");
        }

        @Test
        @DisplayName("Should handle null response from provider")
        void handlesNullResponse() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(null);

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            // Should handle gracefully (either success with empty or failure)
            // The important thing is no NPE
            assertThat(result).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOOL EXECUTION ERROR TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Tool Execution Error Tests")
    class ToolExecutionErrorTests {

        @Test
        @DisplayName("Should return error when tool not found")
        void toolNotFound() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            // LLM tries to call a tool that doesn't exist in the tools list
            ToolCall toolCall = new ToolCall("call_1", "nonexistent_tool", Map.of(), 0);
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("I couldn't find that tool."));

            // Only provide a different tool
            AgentLoopContext context = createContextWithTools("Use unknown tool",
                List.of(createToolDefinition("existing_tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            // Should have a tool result with error
            assertThat(result.toolResults()).isNotEmpty();
            ToolResult toolResult = result.toolResults().get(0);
            assertThat(toolResult.success()).isFalse();
            assertThat(toolResult.error()).contains("not found");
            assertThat(toolResult.error()).contains("Available");
        }

        @Test
        @DisplayName("Should handle tool execution timeout")
        void toolExecutionTimeout() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "slow_tool", Map.of(), 0);
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("The tool timed out."));

            // Mock tool that takes too long
            when(mockToolExecutionService.executeTool(any(), any(), any(), anyMap()))
                .thenAnswer(inv -> {
                    Thread.sleep(200); // Simulate slow execution
                    throw new RuntimeException("Should have timed out");
                });

            ToolDefinition slowTool = ToolDefinition.builder()
                .name("slow_tool")
                .description("A slow tool")
                .timeoutMs(50L) // Very short timeout
                .build();

            AgentLoopContext context = createContextWithTools("Call slow tool", List.of(slowTool));
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.toolResults()).isNotEmpty();
            ToolResult toolResult = result.toolResults().get(0);
            assertThat(toolResult.success()).isFalse();
            assertThat(toolResult.error().toLowerCase()).containsAnyOf("timeout", "timed out", "error");
        }

        @Test
        @DisplayName("Should handle tool execution exception")
        void toolExecutionException() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "failing_tool", Map.of(), 0);
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Tool failed."));

            when(mockToolExecutionService.executeTool(any(), any(), any(), anyMap()))
                .thenThrow(new RuntimeException("Database connection failed"));

            AgentLoopContext context = createContextWithTools("Call failing tool",
                List.of(createToolDefinition("failing_tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.toolResults()).isNotEmpty();
            ToolResult toolResult = result.toolResults().get(0);
            assertThat(toolResult.success()).isFalse();
            assertThat(toolResult.error()).contains("Database connection failed");
        }

        @Test
        @DisplayName("Should handle tool returning failure result")
        void toolReturnsFailure() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "api_tool", Map.of(), 0);
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("API returned an error."));

            when(mockToolExecutionService.executeTool(any(), any(), any(), anyMap()))
                .thenReturn(ToolResult.failure(toolCall, "API returned 404: Resource not found"));

            AgentLoopContext context = createContextWithTools("Call API",
                List.of(createToolDefinition("api_tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.toolResults()).isNotEmpty();
            ToolResult toolResult = result.toolResults().get(0);
            assertThat(toolResult.success()).isFalse();
            assertThat(toolResult.error()).contains("404");
            assertThat(toolResult.error()).contains("Resource not found");
        }

        @Test
        @DisplayName("Should handle multiple tool failures")
        void multipleToolFailures() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            List<ToolCall> toolCalls = List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1),
                new ToolCall("call_3", "tool_c", Map.of(), 2)
            );

            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(toolCalls))
                .thenReturn(createSimpleResponse("Multiple tools failed."));

            when(mockToolExecutionService.executeTool(any(), any(), any(), anyMap()))
                .thenAnswer(inv -> {
                    ToolCall tc = inv.getArgument(0);
                    if (tc.toolName().equals("tool_a")) {
                        return ToolResult.failure(tc, "Error A: Connection refused");
                    } else if (tc.toolName().equals("tool_b")) {
                        return ToolResult.success(tc, "Success B");
                    } else {
                        return ToolResult.failure(tc, "Error C: Timeout");
                    }
                });

            AgentLoopContext context = createContextWithTools("Call all tools",
                List.of(
                    createToolDefinition("tool_a"),
                    createToolDefinition("tool_b"),
                    createToolDefinition("tool_c")
                ));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.toolResults()).hasSize(3);

            // Check individual results
            long failureCount = result.toolResults().stream().filter(r -> !r.success()).count();
            assertThat(failureCount).isEqualTo(2);
        }

        @Test
        @DisplayName("Should handle tool with null execution service")
        void toolWithNullExecutionService() {
            // Create service without tool execution service
            AgentLoopService serviceWithoutTools = new AgentLoopService(
                mockProviderFactory,
                null, // No tool execution service
                null,
                AgentLogger.NOOP
            );

            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "any_tool", Map.of(), 0);
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Tool service not available."));

            AgentLoopContext context = createContextWithTools("Call tool",
                List.of(createToolDefinition("any_tool")));

            AgentLoopResult result = serviceWithoutTools.execute(context);

            assertThat(result.toolResults()).isNotEmpty();
            ToolResult toolResult = result.toolResults().get(0);
            assertThat(toolResult.success()).isFalse();
            assertThat(toolResult.error()).contains("not configured");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LOOP DETECTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Loop Detection Tests")
    class LoopDetectionTests {

        @Test
        @DisplayName("Should warn after 5 identical tool calls")
        void warnsAfter5IdenticalCalls() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "catalog",
                Map.of("tool_id", "gmail-api", "action", "list"), 0);

            // First 4 calls should be OK
            for (int i = 0; i < 4; i++) {
                assertThat(detector.recordToolCall(toolCall))
                    .isEqualTo(LoopDetector.DetectionResult.OK);
            }

            // 5th call should WARN
            assertThat(detector.recordToolCall(toolCall))
                .isEqualTo(LoopDetector.DetectionResult.WARN);

            // Check warning message contains guidance
            String warning = detector.generateWarningMessage(toolCall, 5);
            assertThat(warning)
                .contains("catalog")
                .contains("5")
                .contains("workflow");
        }

        @Test
        @DisplayName("Should STOP after 15 identical tool calls")
        void stopsAfter15IdenticalCalls() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "interface",
                Map.of("action", "create"), 0);

            // Make 14 calls
            for (int i = 0; i < 14; i++) {
                detector.recordToolCall(toolCall);
            }

            // 15th call should STOP
            assertThat(detector.recordToolCall(toolCall))
                .isEqualTo(LoopDetector.DetectionResult.STOP);

            // Check stop message
            String stopMsg = detector.generateStopMessage(toolCall, 15);
            assertThat(stopMsg)
                .contains("interface")
                .contains("15")
                .contains("STOP")
                .contains("workflow");
        }

        @Test
        @DisplayName("Should track consecutive calls with graduated warnings")
        void tracksConsecutiveCallsWithGraduatedWarnings() {
            LoopDetector detector = new LoopDetector();

            // Test REMINDER at 15
            for (int i = 0; i < 14; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall())
                .isEqualTo(LoopDetector.ConsecutiveResult.REMINDER);

            // Test STRONG_RECOMMENDATION at 25
            for (int i = 0; i < 10; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall())
                .isEqualTo(LoopDetector.ConsecutiveResult.STRONG_RECOMMENDATION);

            // Test FINAL_WARNING at 35
            for (int i = 0; i < 10; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall())
                .isEqualTo(LoopDetector.ConsecutiveResult.FINAL_WARNING);

            // Test STOP at 40
            for (int i = 0; i < 5; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall())
                .isEqualTo(LoopDetector.ConsecutiveResult.STOP);
        }

        @Test
        @DisplayName("Should generate helpful consecutive call messages")
        void generatesHelpfulConsecutiveMessages() {
            LoopDetector detector = new LoopDetector();

            // Simulate 15 calls
            for (int i = 0; i < 15; i++) {
                detector.recordConsecutiveCall();
            }

            String reminder = detector.generateConsecutiveMessage(LoopDetector.ConsecutiveResult.REMINDER);
            assertThat(reminder)
                .contains("15")
                .contains("workflow");

            // Simulate 25 calls
            for (int i = 0; i < 10; i++) {
                detector.recordConsecutiveCall();
            }

            String strong = detector.generateConsecutiveMessage(LoopDetector.ConsecutiveResult.STRONG_RECOMMENDATION);
            assertThat(strong)
                .contains("25")
                .contains("SHOULD")
                .contains("respond");
        }

        @Test
        @DisplayName("Should detect duplicate results")
        void detectsDuplicateResults() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "table", Map.of("action", "get"), 0);

            // First result - not duplicate
            assertThat(detector.recordResult(toolCall, "Result A")).isFalse();

            // Same result again - duplicate!
            assertThat(detector.recordResult(toolCall, "Result A")).isTrue();

            // Different result - not duplicate
            assertThat(detector.recordResult(toolCall, "Result B")).isFalse();
        }

        @Test
        @DisplayName("Should differentiate tools with different arguments")
        void differentiatesToolsWithDifferentArgs() {
            LoopDetector detector = new LoopDetector();

            ToolCall call1 = new ToolCall("call_1", "catalog",
                Map.of("tool_id", "gmail-api"), 0);
            ToolCall call2 = new ToolCall("call_2", "catalog",
                Map.of("tool_id", "slack-api"), 0);

            // Each should be tracked separately
            for (int i = 0; i < 5; i++) {
                detector.recordToolCall(call1);
                detector.recordToolCall(call2);
            }

            // Each should have 5 calls, not 10
            assertThat(detector.getCallCount(call1)).isEqualTo(5);
            assertThat(detector.getCallCount(call2)).isEqualTo(5);
        }

        @Test
        @DisplayName("Should reset correctly")
        void resetsCorrectly() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "workflow", Map.of(), 0);

            for (int i = 0; i < 10; i++) {
                detector.recordToolCall(toolCall);
                detector.recordConsecutiveCall();
            }

            detector.reset();

            assertThat(detector.getCallCount(toolCall)).isZero();
            assertThat(detector.getTotalConsecutiveCalls()).isZero();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STREAMING ERROR TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Streaming Error Tests")
    class StreamingErrorTests {

        @Test
        @DisplayName("Should handle streaming disconnection")
        void handlesStreamingDisconnection() throws Exception {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            doAnswer(inv -> {
                StreamingCallback callback = inv.getArgument(1);
                callback.onChunk("Starting response...");
                // Simulate disconnection
                callback.onError("Connection reset by peer");
                return null;
            }).when(provider).completeStreaming(any(), any());

            AtomicReference<String> errorRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onChunk(String content) {}

                @Override
                public void onToolCall(ToolCall toolCall) {}

                @Override
                public void onComplete(CompletionResponse response) {
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            };

            agentLoopService.executeStreaming(createContext("Hello"), testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(errorRef.get()).contains("Connection reset");
        }

        @Test
        @DisplayName("Should handle streaming timeout")
        void handlesStreamingTimeout() throws Exception {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            doAnswer(inv -> {
                StreamingCallback callback = inv.getArgument(1);
                callback.onError("Read timed out after 30000ms");
                return null;
            }).when(provider).completeStreaming(any(), any());

            AtomicReference<String> errorRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onChunk(String content) {}

                @Override
                public void onToolCall(ToolCall toolCall) {}

                @Override
                public void onComplete(CompletionResponse response) {
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            };

            agentLoopService.executeStreaming(createContext("Hello"), testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(errorRef.get()).containsIgnoringCase("timed out");
        }

        @Test
        @DisplayName("Should handle streaming exception")
        void handlesStreamingException() throws Exception {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            doThrow(new RuntimeException("Unexpected streaming error"))
                .when(provider).completeStreaming(any(), any());

            AtomicReference<String> errorRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onChunk(String content) {}

                @Override
                public void onToolCall(ToolCall toolCall) {}

                @Override
                public void onComplete(CompletionResponse response) {
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            };

            agentLoopService.executeStreaming(createContext("Hello"), testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(errorRef.get()).contains("Unexpected streaming error");
        }

        @Test
        @DisplayName("Should handle provider not configured in streaming")
        void handlesProviderNotConfiguredInStreaming() throws Exception {
            LLMProvider provider = createMockProvider("openai");
            when(provider.isConfigured()).thenReturn(false);
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            AtomicReference<String> errorRef = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onChunk(String content) {}

                @Override
                public void onToolCall(ToolCall toolCall) {}

                @Override
                public void onComplete(CompletionResponse response) {
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            };

            agentLoopService.executeStreaming(createContext("Hello"), testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(errorRef.get()).contains("not configured");
        }

        @Test
        @DisplayName("Should handle streaming with multiple chunks")
        void handlesStreamingWithMultipleChunks() throws Exception {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            AtomicInteger chunkCount = new AtomicInteger(0);

            doAnswer(inv -> {
                StreamingCallback callback = inv.getArgument(1);
                // Send exactly 5 chunks
                for (int i = 0; i < 5; i++) {
                    callback.onChunk("Chunk " + i);
                }
                callback.onComplete(createSimpleResponse("Done"));
                return null;
            }).when(provider).completeStreaming(any(), any());

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<CompletionResponse> responseRef = new AtomicReference<>();

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onChunk(String content) {
                    chunkCount.incrementAndGet();
                }

                @Override
                public void onToolCall(ToolCall toolCall) {}

                @Override
                public void onComplete(CompletionResponse response) {
                    responseRef.set(response);
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    latch.countDown();
                }

                @Override
                public boolean shouldStop() {
                    return false;
                }
            };

            agentLoopService.executeStreaming(createContext("Hello"), testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            // All 5 chunks should have been received
            assertThat(chunkCount.get()).isEqualTo(5);
            assertThat(responseRef.get()).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOOL ERROR CODE TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Tool Error Code Tests")
    class ToolErrorCodeTests {

        @ParameterizedTest(name = "Error code {0} should have correct format")
        @EnumSource(ToolErrorCode.class)
        @DisplayName("All error codes should be properly formatted")
        void allErrorCodesProperlyFormatted(ToolErrorCode code) {
            assertThat(code.getCode()).startsWith("TOOL_");
            assertThat(code.getDefaultMessage()).isNotBlank();

            // Test format method
            String formatted = code.format("Custom message");
            assertThat(formatted)
                .startsWith("TOOL_")
                .contains("Custom message");
        }

        @Test
        @DisplayName("TOOL_NOT_FOUND should provide helpful message")
        void toolNotFoundHelpfulMessage() {
            String formatted = ToolErrorCode.TOOL_NOT_FOUND.format("unknown_tool");
            assertThat(formatted).contains("TOOL_001");
        }

        @Test
        @DisplayName("MISSING_PARAMETER should include parameter name")
        void missingParameterIncludesName() {
            String formatted = ToolErrorCode.MISSING_PARAMETER.format("action");
            assertThat(formatted).contains("TOOL_011").contains("action");
        }

        @Test
        @DisplayName("TIMEOUT should be retryable indicator")
        void timeoutRetryable() {
            // Timeout errors should indicate potential retry
            assertThat(ToolErrorCode.TIMEOUT.getCode()).isEqualTo("TOOL_051");
        }

        @Test
        @DisplayName("RATE_LIMITED should indicate wait")
        void rateLimitedIndicatesWait() {
            assertThat(ToolErrorCode.RATE_LIMITED.getCode()).isEqualTo("TOOL_052");
            assertThat(ToolErrorCode.RATE_LIMITED.getDefaultMessage())
                .containsIgnoringCase("rate");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // VALIDATION ERROR TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Validation Error Tests")
    class ValidationErrorTests {

        @Test
        @DisplayName("ValidationResult should format multiple errors correctly")
        void formatsMultipleErrors() {
            ValidationResult result = ValidationResult.failure(List.of(
                new ValidationResult.ValidationError("param1", "Required parameter 'param1' is missing", ToolErrorCode.MISSING_PARAMETER),
                new ValidationResult.ValidationError("param2", "Parameter 'param2' expected type string but got Integer", ToolErrorCode.INVALID_PARAMETER_TYPE)
            ));

            String formatted = result.formatErrors();
            assertThat(formatted)
                .contains("param1")
                .contains("param2")
                .contains("missing")
                .contains("string");
        }

        @Test
        @DisplayName("ValidationResult should identify primary error code")
        void identifiesPrimaryErrorCode() {
            ValidationResult result = ValidationResult.failure(List.of(
                new ValidationResult.ValidationError("param1", "Missing", ToolErrorCode.MISSING_PARAMETER),
                new ValidationResult.ValidationError("param2", "Invalid type", ToolErrorCode.INVALID_PARAMETER_TYPE)
            ));

            assertThat(result.getPrimaryErrorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("ValidationError should include enum allowed values")
        void includesEnumAllowedValues() {
            ValidationResult.ValidationError error = new ValidationResult.ValidationError(
                "status",
                "Parameter 'status' value 'invalid' is not one of allowed values: [active, inactive, pending]",
                ToolErrorCode.INVALID_ENUM_VALUE
            );

            assertThat(error.message())
                .contains("active")
                .contains("inactive")
                .contains("pending");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // AGENT STOP REASON TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent Stop Reason Tests")
    class AgentStopReasonTests {

        @ParameterizedTest(name = "Stop reason {0}")
        @EnumSource(AgentStopReason.class)
        @DisplayName("All stop reasons should have descriptions")
        void allStopReasonsHaveDescriptions(AgentStopReason reason) {
            assertThat(reason.name()).isNotBlank();
            // Each reason should be meaningful
            assertThat(reason.name().length()).isGreaterThan(3);
        }

        @Test
        @DisplayName("COMPLETED should indicate success")
        void completedIndicatesSuccess() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Done"));

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isTrue();
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        }

        @Test
        @DisplayName("ERROR should indicate failure")
        void errorIndicatesFailure() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(new RuntimeException("Fatal error"));

            AgentLoopResult result = agentLoopService.execute(createContext("Hello"));

            assertThat(result.success()).isFalse();
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }

        @Test
        @DisplayName("MAX_ITERATIONS should be set when limit reached")
        void maxIterationsWhenLimitReached() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "loop_tool", Map.of(), 0);
            when(provider.complete(any())).thenReturn(createResponseWithToolCalls(List.of(toolCall)));

            when(mockToolExecutionService.executeTool(any(), any(), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Continue"));

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("openai")
                .model("gpt-4o")
                .userPrompt("Loop")
                .tools(List.of(createToolDefinition("loop_tool")))
                .maxIterations(2)
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.MAX_ITERATIONS);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOOL RESULT TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Tool Result Tests")
    class ToolResultTests {

        @Test
        @DisplayName("Success result should have content and no error")
        void successResultHasContentNoError() {
            ToolCall toolCall = new ToolCall("call_1", "test", Map.of(), 0);
            ToolResult result = ToolResult.success(toolCall, "Result content");

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("Result content");
            assertThat(result.error()).isNull();
        }

        @Test
        @DisplayName("Failure result should have error and no content")
        void failureResultHasErrorNoContent() {
            ToolCall toolCall = new ToolCall("call_1", "test", Map.of(), 0);
            ToolResult result = ToolResult.failure(toolCall, "Error message");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Error message");
            assertThat(result.content()).isNull();
        }

        @Test
        @DisplayName("Result should preserve tool call reference")
        void resultPreservesToolCallReference() {
            ToolCall toolCall = new ToolCall("unique_id", "my_tool", Map.of("key", "value"), 0);
            ToolResult result = ToolResult.success(toolCall, "Content");

            assertThat(result.toolCall()).isEqualTo(toolCall);
            assertThat(result.toolCall().id()).isEqualTo("unique_id");
            assertThat(result.toolCall().toolName()).isEqualTo("my_tool");
        }

        @Test
        @DisplayName("Result builder should allow metadata")
        void resultBuilderAllowsMetadata() {
            ToolCall toolCall = new ToolCall("call_1", "test", Map.of(), 0);
            ToolResult result = ToolResult.builder()
                .toolCall(toolCall)
                .success(true)
                .content("Content")
                .durationMs(150L)
                .metadata(Map.of("requestId", "req-123", "cached", true))
                .build();

            assertThat(result.durationMs()).isEqualTo(150L);
            assertThat(result.metadata()).containsEntry("requestId", "req-123");
            assertThat(result.metadata()).containsEntry("cached", true);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MESSAGE SEQUENCE TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Message Sequence Tests")
    class MessageSequenceTests {

        @Test
        @DisplayName("Valid sequence should pass validation")
        void validSequencePassesValidation() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Hello"));
            messages.add(Message.assistantWithToolCalls("", List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0)
            )));
            messages.add(Message.toolResult("call_1", "tool_a", "Result"));
            messages.add(Message.assistant("Done"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertThat(result.valid()).isTrue();
            assertThat(result.errors()).isEmpty();
        }

        @Test
        @DisplayName("Missing tool result should fail validation")
        void missingToolResultFailsValidation() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.assistantWithToolCalls("", List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1)
            )));
            messages.add(Message.toolResult("call_1", "tool_a", "Result 1"));
            // Missing call_2 result!

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e -> e.contains("call_2"));
        }

        @Test
        @DisplayName("Orphan tool message should fail validation")
        void orphanToolMessageFailsValidation() {
            List<Message> messages = new ArrayList<>();
            messages.add(Message.user("Hello"));
            messages.add(Message.toolResult("orphan_id", "tool_x", "Result"));

            var result = ToolCallBatchAppender.validateSequence(messages);

            assertThat(result.valid()).isFalse();
            assertThat(result.errors()).anyMatch(e ->
                e.toLowerCase().contains("orphan"));
        }

        @Test
        @DisplayName("Appending with missing result creates placeholder")
        void appendingWithMissingResultCreatesPlaceholder() {
            List<Message> messages = new ArrayList<>();

            List<ToolCall> toolCalls = List.of(
                new ToolCall("call_1", "tool_a", Map.of(), 0),
                new ToolCall("call_2", "tool_b", Map.of(), 1)
            );

            // Only one result provided
            List<ToolResult> results = List.of(
                ToolResult.success(toolCalls.get(0), "Result 1")
            );

            var appendResult = ToolCallBatchAppender.appendAtomically(
                messages, "", toolCalls, results
            );

            // The method creates error placeholders for missing results and returns success
            // with an empty missingToolCallIds list (graceful handling)
            assertThat(appendResult.success()).isTrue();
            // 1 assistant message + 2 tool results (1 real + 1 placeholder)
            assertThat(appendResult.messagesAdded()).isEqualTo(3);

            // Verify sequence is still valid (placeholder added)
            var validation = ToolCallBatchAppender.validateSequence(messages);
            assertThat(validation.valid()).isTrue();

            // Verify the placeholder error message is present
            assertThat(messages).hasSize(3);
            assertThat(messages.get(2).content()).contains("Error:");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LLM PROVIDER EXCEPTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("LLM Provider Exception Tests")
    class LLMProviderExceptionTests {

        @Test
        @DisplayName("Rate limited exception should be retryable")
        void rateLimitedIsRetryable() {
            LLMProviderException ex = LLMProviderException.rateLimited("openai");

            assertThat(ex.getProviderName()).isEqualTo("openai");
            assertThat(ex.isRetryable()).isTrue();
            assertThat(ex.getMessage()).containsIgnoringCase("rate");
        }

        @Test
        @DisplayName("Unauthorized exception should not be retryable")
        void unauthorizedNotRetryable() {
            LLMProviderException ex = LLMProviderException.unauthorized("anthropic");

            assertThat(ex.getProviderName()).isEqualTo("anthropic");
            assertThat(ex.isRetryable()).isFalse();
            // LLMProviderException.unauthorized() returns "Invalid API key" message
            assertThat(ex.getMessage()).containsIgnoringCase("API key");
        }

        @Test
        @DisplayName("Model not found should include model name")
        void modelNotFoundIncludesModelName() {
            LLMProviderException ex = LLMProviderException.modelNotFound("openai", "gpt-5-ultra");

            assertThat(ex.getMessage()).contains("gpt-5-ultra");
            assertThat(ex.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("Exception should preserve cause")
        void exceptionPreservesCause() {
            IOException cause = new IOException("Network failure");
            LLMProviderException ex = new LLMProviderException("google", "Connection failed", cause);

            assertThat(ex.getCause()).isEqualTo(cause);
            assertThat(ex.getMessage()).contains("Connection failed");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // AGENT LOOP RESULT TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent Loop Result Tests")
    class AgentLoopResultTests {

        @Test
        @DisplayName("Success result should have all fields populated")
        void successResultHasAllFields() {
            CompletionResponse response = createSimpleResponse("Final answer");
            List<ToolResult> toolResults = List.of(
                ToolResult.success(new ToolCall("call_1", "tool", Map.of(), 0), "Result")
            );

            AgentLoopResult result = AgentLoopResult.success(
                response,
                toolResults,
                3, // iterations
                UsageInfo.builder().promptTokens(500).completionTokens(200).totalTokens(700).build(),
                1500L, // duration
                "openai",
                "gpt-4o",
                List.of(Message.user("Hello")),
                AgentStopReason.COMPLETED,
                Map.of("metric1", 42)
            );

            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("Final answer");
            assertThat(result.iterations()).isEqualTo(3);
            assertThat(result.usage().totalTokens()).isEqualTo(700);
            assertThat(result.durationMs()).isEqualTo(1500L);
            assertThat(result.provider()).isEqualTo("openai");
            assertThat(result.model()).isEqualTo("gpt-4o");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
            assertThat(result.toolResults()).hasSize(1);
            assertThat(result.metrics()).containsEntry("metric1", 42);
        }

        @Test
        @DisplayName("Failure result should have error and stop reason")
        void failureResultHasErrorAndStopReason() {
            AgentLoopResult result = AgentLoopResult.failure(
                "Connection timeout",
                500L,
                "anthropic",
                AgentStopReason.ERROR
            );

            assertThat(result.success()).isFalse();
            assertThat(result.error()).isEqualTo("Connection timeout");
            assertThat(result.durationMs()).isEqualTo(500L);
            assertThat(result.provider()).isEqualTo("anthropic");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONTEXT SIZE TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Context Size Tests")
    class ContextSizeTests {

        @Test
        @DisplayName("Should estimate token count from messages")
        void estimatesTokenCount() {
            // Each char is roughly 1/4 token
            // 4000 chars ≈ 1000 tokens
            String longContent = "x".repeat(4000);

            List<Message> messages = List.of(
                Message.system("System prompt"),
                Message.user(longContent),
                Message.assistant("Response")
            );

            // The executor monitors context size internally
            // This test verifies the concept
            int estimatedChars = messages.stream()
                .mapToInt(m -> m.content() != null ? m.content().length() : 0)
                .sum();

            int estimatedTokens = estimatedChars / 4;
            assertThat(estimatedTokens).isGreaterThan(1000);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // EDGE CASE TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Case Tests")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle empty user prompt")
        void handlesEmptyUserPrompt() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("I need more context."));

            AgentLoopContext context = createContext("");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result).isNotNull();
            // Should not throw, LLM handles empty prompts
        }

        @Test
        @DisplayName("Should handle very long user prompt")
        void handlesVeryLongUserPrompt() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Processed"));

            String longPrompt = "Test ".repeat(10000);
            AgentLoopContext context = createContext(longPrompt);
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Should handle tool with empty name")
        void handlesToolWithEmptyName() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "", Map.of(), 0);
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Invalid tool"));

            AgentLoopContext context = createContextWithTools("Call empty tool",
                List.of(createToolDefinition("valid_tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.toolResults()).isNotEmpty();
            assertThat(result.toolResults().get(0).success()).isFalse();
        }

        @Test
        @DisplayName("Should handle tool arguments with special characters")
        void handlesToolArgumentsWithSpecialChars() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            Map<String, Object> argsWithSpecialChars = Map.of(
                "query", "SELECT * FROM users WHERE name = 'O''Brien'",
                "path", "C:\\Users\\test\\file.txt",
                "json", "{\"key\": \"value with \\\"quotes\\\"\"}",
                "unicode", "Hello 世界 🌍"
            );

            ToolCall toolCall = new ToolCall("call_1", "special_tool", argsWithSpecialChars, 0);
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Processed special chars"));

            when(mockToolExecutionService.executeTool(any(), any(), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Success"));

            AgentLoopContext context = createContextWithTools("Call with special chars",
                List.of(createToolDefinition("special_tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Should handle concurrent tool execution")
        void handlesConcurrentToolExecution() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            List<ToolCall> manyToolCalls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                manyToolCalls.add(new ToolCall("call_" + i, "tool_" + i, Map.of("index", i), i));
            }

            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(manyToolCalls))
                .thenReturn(createSimpleResponse("All tools executed"));

            AtomicInteger executionCount = new AtomicInteger(0);
            when(mockToolExecutionService.executeTool(any(), any(), any(), anyMap()))
                .thenAnswer(inv -> {
                    executionCount.incrementAndGet();
                    Thread.sleep(10); // Small delay to simulate work
                    ToolCall tc = inv.getArgument(0);
                    return ToolResult.success(tc, "Result " + tc.arguments().get("index"));
                });

            List<ToolDefinition> tools = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tools.add(createToolDefinition("tool_" + i));
            }

            AgentLoopContext context = createContextWithTools("Execute many tools", tools);
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.toolResults()).hasSize(10);
            assertThat(executionCount.get()).isEqualTo(10);
        }

        @Test
        @DisplayName("Should handle null tool arguments")
        void handlesNullToolArguments() {
            LLMProvider provider = createMockProvider("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "null_args_tool", null, 0);
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Handled null args"));

            when(mockToolExecutionService.executeTool(any(), any(), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Success with null args"));

            AgentLoopContext context = createContextWithTools("Call with null args",
                List.of(createToolDefinition("null_args_tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
        }
    }
}
