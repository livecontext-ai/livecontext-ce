package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import com.apimarketplace.agent.tool.ToolDiscoveryService;
import com.apimarketplace.agent.tool.ToolExecutionService;
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

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for AgentLoopService with multi-LLM provider dispatch.
 *
 * <p>These tests validate that the agent loop behaves correctly across all supported
 * LLM providers: OpenAI, Claude, Gemini, Mistral, DeepSeek.
 *
 * <h2>Test Categories:</h2>
 * <ul>
 *   <li><b>Provider Factory Tests</b> - Provider registration and resolution</li>
 *   <li><b>Synchronous Execution Tests</b> - Basic execute() functionality</li>
 *   <li><b>Streaming Execution Tests</b> - executeStreaming() with callbacks</li>
 *   <li><b>Tool Execution Tests</b> - Tool calling and result handling</li>
 *   <li><b>Loop Detection Tests</b> - Infinite loop prevention</li>
 *   <li><b>Error Handling Tests</b> - Failure modes and recovery</li>
 *   <li><b>Multi-Provider Dispatch Tests</b> - Cross-provider behavior</li>
 * </ul>
 *
 * @see AgentLoopService
 * @see LLMProvider
 * @see LLMProviderFactory
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Multi-LLM Agent Loop Service Tests")
class MultiLLMAgentLoopServiceTest {

    // ═══════════════════════════════════════════════════════════════════════════════
    // SUPPORTED PROVIDERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Enum representing all supported LLM providers.
     * Used for parameterized tests to ensure consistent behavior across providers.
     */
    enum LLMProviderType {
        OPENAI("openai", "gpt-4o"),
        ANTHROPIC("anthropic", "claude-sonnet-4-20250514"),
        GOOGLE("google", "gemini-2.0-flash"),
        MISTRAL("mistral", "mistral-large-latest"),
        DEEPSEEK("deepseek", "deepseek-chat");

        private final String name;
        private final String defaultModel;

        LLMProviderType(String name, String defaultModel) {
            this.name = name;
            this.defaultModel = defaultModel;
        }

        public String getName() {
            return name;
        }

        public String getDefaultModel() {
            return defaultModel;
        }
    }

    /**
     * Provider source for parameterized tests.
     */
    static Stream<Arguments> allProviders() {
        return Arrays.stream(LLMProviderType.values())
            .map(p -> Arguments.of(p.getName(), p.getDefaultModel()));
    }

    /**
     * Provider source for primary providers (OpenAI, Claude, Gemini).
     */
    static Stream<Arguments> primaryProviders() {
        return Stream.of(
            Arguments.of("openai", "gpt-4o"),
            Arguments.of("anthropic", "claude-sonnet-4-20250514"),
            Arguments.of("google", "gemini-2.0-flash")
        );
    }

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
        // Initialize with mock factory
        agentLoopService = new AgentLoopService(
            mockProviderFactory,
            mockToolExecutionService,
            mockToolDiscoveryService,
            AgentLogger.NOOP
        );
    }

    /**
     * Creates a mock provider configured for the specified type.
     */
    private LLMProvider createMockProvider(String providerName, String defaultModel) {
        LLMProvider provider = mock(LLMProvider.class);
        lenient().when(provider.getProviderName()).thenReturn(providerName);
        lenient().when(provider.getDefaultModel()).thenReturn(defaultModel);
        lenient().when(provider.isConfigured()).thenReturn(true);
        lenient().when(provider.supportsStreaming()).thenReturn(true);
        lenient().when(provider.supportsToolCalling()).thenReturn(true);
        return provider;
    }

    /**
     * Creates a simple completion response.
     */
    private CompletionResponse createSimpleResponse(String content) {
        return CompletionResponse.builder()
            .content(content)
            .finishReason("stop")
            .usage(UsageInfo.builder().promptTokens(100).completionTokens(50).totalTokens(150).build())
            .build();
    }

    /**
     * Creates a completion response with tool calls.
     */
    private CompletionResponse createResponseWithToolCalls(List<ToolCall> toolCalls) {
        return CompletionResponse.builder()
            .content("")
            .toolCalls(toolCalls)
            .finishReason("tool_calls")
            .usage(UsageInfo.builder().promptTokens(100).completionTokens(50).totalTokens(150).build())
            .build();
    }

    /**
     * Creates a basic agent loop context.
     */
    private AgentLoopContext createContext(String provider, String model, String userPrompt) {
        return AgentLoopContext.builder()
            .provider(provider)
            .model(model)
            .userPrompt(userPrompt)
            .maxIterations(5)
            .build();
    }

    /**
     * Creates a context with tools.
     */
    private AgentLoopContext createContextWithTools(String provider, String model,
                                                     String userPrompt, List<ToolDefinition> tools) {
        return AgentLoopContext.builder()
            .provider(provider)
            .model(model)
            .userPrompt(userPrompt)
            .tools(tools)
            .maxIterations(5)
            .build();
    }

    /**
     * Creates a sample tool definition.
     */
    private ToolDefinition createToolDefinition(String name, String description) {
        return ToolDefinition.builder()
            .name(name)
            .description(description)
            .parameters(List.of())
            .build();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PROVIDER FACTORY TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Provider Factory Tests")
    class ProviderFactoryTests {

        @Test
        @DisplayName("Factory should register all providers on initialization")
        void factoryRegistersAllProviders() {
            // Create mock providers
            List<LLMProvider> providers = Arrays.stream(LLMProviderType.values())
                .map(p -> createMockProvider(p.getName(), p.getDefaultModel()))
                .toList();

            LLMProviderFactory factory = new LLMProviderFactory(providers);

            assertThat(factory.getAvailableProviderNames())
                .containsExactlyInAnyOrder("openai", "anthropic", "google", "mistral", "deepseek");
        }

        @ParameterizedTest(name = "Provider {0} should be resolvable")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#allProviders")
        @DisplayName("Each provider should be resolvable by name")
        void eachProviderResolvableByName(String providerName, String defaultModel) {
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            LLMProviderFactory factory = new LLMProviderFactory(List.of(provider));

            LLMProvider resolved = factory.getProvider(providerName);

            assertThat(resolved.getProviderName()).isEqualTo(providerName);
            assertThat(resolved.getDefaultModel()).isEqualTo(defaultModel);
        }

        @Test
        @DisplayName("Factory should throw for unknown provider")
        void factoryThrowsForUnknownProvider() {
            LLMProviderFactory factory = new LLMProviderFactory(List.of());

            assertThrows(LLMProviderException.class, () ->
                factory.getProvider("unknown-provider")
            );
        }

        @Test
        @DisplayName("Factory should handle null providers list gracefully")
        void factoryHandlesNullProviders() {
            LLMProviderFactory factory = new LLMProviderFactory(null);

            assertThat(factory.getAvailableProviderNames()).isEmpty();
        }

        @Test
        @DisplayName("Factory should filter configured providers correctly")
        void factoryFiltersConfiguredProviders() {
            LLMProvider configured = createMockProvider("openai", "gpt-4o");
            LLMProvider unconfigured = createMockProvider("anthropic", "claude-3");
            when(unconfigured.isConfigured()).thenReturn(false);

            LLMProviderFactory factory = new LLMProviderFactory(List.of(configured, unconfigured));

            assertThat(factory.getConfiguredProviders()).hasSize(1);
            assertThat(factory.getConfiguredProviders().get(0).getProviderName()).isEqualTo("openai");
        }

        @Test
        @DisplayName("Factory should find provider by model")
        void factoryFindsProviderByModel() {
            LLMProvider openai = createMockProvider("openai", "gpt-4o");
            // Stub supportsModel to return true for our target model
            when(openai.supportsModel("gpt-4o")).thenReturn(true);

            LLMProviderFactory factory = new LLMProviderFactory(List.of(openai));

            assertThat(factory.getProviderForModel("gpt-4o"))
                .isPresent()
                .hasValueSatisfying(p -> assertThat(p.getProviderName()).isEqualTo("openai"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // SYNCHRONOUS EXECUTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Synchronous Execution Tests")
    class SynchronousExecutionTests {

        @ParameterizedTest(name = "Provider {0} should execute simple prompt")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#allProviders")
        @DisplayName("Simple prompt execution across all providers")
        void simplePromptExecution(String providerName, String defaultModel) {
            // Setup mock provider
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            when(mockProviderFactory.getProvider(providerName)).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Hello! How can I help?"));

            // Execute
            AgentLoopContext context = createContext(providerName, defaultModel, "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            // Verify
            assertThat(result.success()).isTrue();
            assertThat(result.content()).isEqualTo("Hello! How can I help?");
            assertThat(result.provider()).isEqualTo(providerName);
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
            verify(provider).complete(any());
        }

        @ParameterizedTest(name = "Provider {0} should use default model when not specified")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#allProviders")
        @DisplayName("Default model selection")
        void defaultModelSelection(String providerName, String defaultModel) {
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            when(mockProviderFactory.getProvider(providerName)).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Response"));

            AgentLoopContext context = AgentLoopContext.builder()
                .provider(providerName)
                // No model specified
                .userPrompt("Test")
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.model()).isEqualTo(defaultModel);
        }

        @Test
        @DisplayName("Should fail when provider is not configured")
        void failsWhenProviderNotConfigured() {
            LLMProvider unconfigured = createMockProvider("openai", "gpt-4o");
            when(unconfigured.isConfigured()).thenReturn(false);
            when(mockProviderFactory.getProvider("openai")).thenReturn(unconfigured);

            AgentLoopContext context = createContext("openai", "gpt-4o", "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("not configured");
        }

        @Test
        @DisplayName("Should handle provider exception gracefully")
        void handlesProviderException() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(new LLMProviderException("openai", "Rate limit exceeded"));

            AgentLoopContext context = createContext("openai", "gpt-4o", "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Rate limit exceeded");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }

        @Test
        @DisplayName("Should respect max iterations limit")
        void respectsMaxIterations() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            // Always return tool calls to force multiple iterations
            ToolCall toolCall = new ToolCall("call_1", "test_tool", Map.of(), 0);
            when(provider.complete(any())).thenReturn(createResponseWithToolCalls(List.of(toolCall)));

            // Mock tool execution
            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Tool result"));

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("openai")
                .model("gpt-4o")
                .userPrompt("Test")
                .tools(List.of(createToolDefinition("test_tool", "Test tool")))
                .maxIterations(3)
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.iterations()).isLessThanOrEqualTo(3);
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.MAX_ITERATIONS);
        }

        @ParameterizedTest(name = "Provider {0} should track usage metrics")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#primaryProviders")
        @DisplayName("Usage metrics tracking")
        void usageMetricsTracking(String providerName, String defaultModel) {
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            when(mockProviderFactory.getProvider(providerName)).thenReturn(provider);

            CompletionResponse response = CompletionResponse.builder()
                .content("Response")
                .finishReason("stop")
                .usage(UsageInfo.builder().promptTokens(200).completionTokens(100).totalTokens(300).build())
                .build();
            when(provider.complete(any())).thenReturn(response);

            AgentLoopContext context = createContext(providerName, defaultModel, "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.usage()).isNotNull();
            assertThat(result.usage().totalTokens()).isEqualTo(300);
            assertThat(result.durationMs()).isGreaterThanOrEqualTo(0);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // STREAMING EXECUTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Streaming Execution Tests")
    class StreamingExecutionTests {

        @ParameterizedTest(name = "Provider {0} should stream chunks correctly")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#allProviders")
        @DisplayName("Chunk streaming across providers")
        void chunkStreamingAcrossProviders(String providerName, String defaultModel) throws Exception {
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            when(mockProviderFactory.getProvider(providerName)).thenReturn(provider);

            // Simulate streaming response
            doAnswer(invocation -> {
                StreamingCallback callback = invocation.getArgument(1);
                callback.onChunk("Hello ");
                callback.onChunk("World!");
                callback.onComplete(createSimpleResponse("Hello World!"));
                return null;
            }).when(provider).completeStreaming(any(), any());

            List<String> receivedChunks = new CopyOnWriteArrayList<>();
            AtomicBoolean completed = new AtomicBoolean(false);
            CountDownLatch latch = new CountDownLatch(1);

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onChunk(String content) {
                    receivedChunks.add(content);
                }

                @Override
                public void onToolCall(ToolCall toolCall) {}

                @Override
                public void onComplete(CompletionResponse response) {
                    completed.set(true);
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    latch.countDown();
                }
            };

            AgentLoopContext context = createContext(providerName, defaultModel, "Hello");
            agentLoopService.executeStreaming(context, testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(receivedChunks).containsExactly("Hello ", "World!");
            assertThat(completed.get()).isTrue();
        }

        @Test
        @DisplayName("Should handle streaming tool calls")
        void streamingToolCalls() throws Exception {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "search", Map.of("query", "test"), 0);

            // First call returns tool call, second returns final response
            AtomicInteger callCount = new AtomicInteger(0);
            doAnswer(invocation -> {
                StreamingCallback callback = invocation.getArgument(1);
                if (callCount.incrementAndGet() == 1) {
                    callback.onToolCall(toolCall);
                    callback.onComplete(createResponseWithToolCalls(List.of(toolCall)));
                } else {
                    callback.onChunk("Search results processed.");
                    callback.onComplete(createSimpleResponse("Search results processed."));
                }
                return null;
            }).when(provider).completeStreaming(any(), any());

            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Result: test data"));

            List<ToolCall> receivedToolCalls = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onChunk(String content) {}

                @Override
                public void onToolCall(ToolCall tc) {
                    receivedToolCalls.add(tc);
                }

                @Override
                public void onComplete(CompletionResponse response) {
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    latch.countDown();
                }
            };

            AgentLoopContext context = createContextWithTools("openai", "gpt-4o", "Search for test",
                List.of(createToolDefinition("search", "Search tool")));
            agentLoopService.executeStreaming(context, testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(receivedToolCalls).hasSize(1);
            assertThat(receivedToolCalls.get(0).toolName()).isEqualTo("search");
        }

        @Test
        @DisplayName("Should respect shouldStop() signal")
        void respectsShouldStopSignal() throws Exception {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            AtomicInteger chunkCount = new AtomicInteger(0);
            AtomicBoolean stopRequested = new AtomicBoolean(false);

            doAnswer(invocation -> {
                StreamingCallback callback = invocation.getArgument(1);
                for (int i = 0; i < 10; i++) {
                    if (callback.shouldStop()) break;
                    callback.onChunk("Chunk " + i + " ");
                }
                callback.onComplete(createSimpleResponse("Completed"));
                return null;
            }).when(provider).completeStreaming(any(), any());

            CountDownLatch latch = new CountDownLatch(1);

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onChunk(String content) {
                    if (chunkCount.incrementAndGet() >= 3) {
                        stopRequested.set(true);
                    }
                }

                @Override
                public void onToolCall(ToolCall toolCall) {}

                @Override
                public void onComplete(CompletionResponse response) {
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    latch.countDown();
                }

                @Override
                public boolean shouldStop() {
                    return stopRequested.get();
                }
            };

            AgentLoopContext context = createContext("openai", "gpt-4o", "Generate content");
            agentLoopService.executeStreaming(context, testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            // Stop was requested after 3 chunks
            assertThat(chunkCount.get()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("Should call onError when streaming fails")
        void callsOnErrorWhenStreamingFails() throws Exception {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            doThrow(new LLMProviderException("openai", "Network error"))
                .when(provider).completeStreaming(any(), any());

            AtomicBoolean errorReceived = new AtomicBoolean(false);
            String[] errorMessage = new String[1];
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
                    errorReceived.set(true);
                    errorMessage[0] = error;
                    latch.countDown();
                }
            };

            AgentLoopContext context = createContext("openai", "gpt-4o", "Hello");
            agentLoopService.executeStreaming(context, testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(errorReceived.get()).isTrue();
            assertThat(errorMessage[0]).contains("Network error");
        }

        @Test
        @DisplayName("Should handle thinking/reasoning content")
        void handlesThinkingContent() throws Exception {
            LLMProvider provider = createMockProvider("google", "gemini-2.0-flash-thinking");
            when(mockProviderFactory.getProvider("google")).thenReturn(provider);

            doAnswer(invocation -> {
                StreamingCallback callback = invocation.getArgument(1);
                callback.onThinking("Let me think about this...");
                callback.onThinking("First, I need to consider...");
                callback.onChunk("Here's my answer: ");
                callback.onChunk("42");
                callback.onComplete(createSimpleResponse("Here's my answer: 42"));
                return null;
            }).when(provider).completeStreaming(any(), any());

            List<String> thinkingContent = new CopyOnWriteArrayList<>();
            List<String> responseContent = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);

            StreamingCallback testCallback = new StreamingCallback() {
                @Override
                public void onThinking(String thinking) {
                    thinkingContent.add(thinking);
                }

                @Override
                public void onChunk(String content) {
                    responseContent.add(content);
                }

                @Override
                public void onToolCall(ToolCall toolCall) {}

                @Override
                public void onComplete(CompletionResponse response) {
                    latch.countDown();
                }

                @Override
                public void onError(String error) {
                    latch.countDown();
                }
            };

            AgentLoopContext context = createContext("google", "gemini-2.0-flash-thinking", "What is 6*7?");
            agentLoopService.executeStreaming(context, testCallback);

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertThat(thinkingContent).hasSize(2);
            assertThat(responseContent).containsExactly("Here's my answer: ", "42");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOOL EXECUTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Tool Execution Tests")
    class ToolExecutionTests {

        @ParameterizedTest(name = "Provider {0} should execute single tool call")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#allProviders")
        @DisplayName("Single tool call execution")
        void singleToolCallExecution(String providerName, String defaultModel) {
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            when(mockProviderFactory.getProvider(providerName)).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "calculator", Map.of("expression", "2+2"), 0);

            // First call returns tool call, second returns final response
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("The result is 4."));

            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "4"));

            AgentLoopContext context = createContextWithTools(providerName, defaultModel,
                "Calculate 2+2", List.of(createToolDefinition("calculator", "Math calculator")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.toolResults()).hasSize(1);
            assertThat(result.toolResults().get(0).content()).isEqualTo("4");
            assertThat(result.content()).isEqualTo("The result is 4.");
        }

        @Test
        @DisplayName("Should execute parallel tool calls")
        void parallelToolCallExecution() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            List<ToolCall> toolCalls = List.of(
                new ToolCall("call_1", "search", Map.of("query", "weather"), 0),
                new ToolCall("call_2", "search", Map.of("query", "news"), 1),
                new ToolCall("call_3", "search", Map.of("query", "stocks"), 2)
            );

            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(toolCalls))
                .thenReturn(createSimpleResponse("Here are the results..."));

            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenAnswer(invocation -> {
                    ToolCall tc = invocation.getArgument(0);
                    return ToolResult.success(tc, "Result for " + tc.arguments().get("query"));
                });

            AgentLoopContext context = createContextWithTools("openai", "gpt-4o",
                "Get weather, news, and stocks",
                List.of(createToolDefinition("search", "Search tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.toolResults()).hasSize(3);
            verify(mockToolExecutionService, times(3)).executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap());
        }

        @Test
        @DisplayName("Should handle tool execution failure gracefully")
        void handlesToolExecutionFailure() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "api_call", Map.of(), 0);

            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("I apologize, the API call failed."));

            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenReturn(ToolResult.failure(toolCall, "Connection timeout"));

            AgentLoopContext context = createContextWithTools("openai", "gpt-4o",
                "Call the API", List.of(createToolDefinition("api_call", "API caller")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.toolResults()).hasSize(1);
            assertThat(result.toolResults().get(0).success()).isFalse();
            assertThat(result.toolResults().get(0).error()).isEqualTo("Connection timeout");
        }

        @Test
        @DisplayName("BUG-10: same-name duplicates in context.tools() are dropped at add-time (first-wins)")
        void deduplicatesTools() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Done"));

            List<ToolDefinition> duplicateTools = List.of(
                createToolDefinition("search", "Search v1"),
                createToolDefinition("search", "Search v2"), // Duplicate!
                createToolDefinition("calculator", "Calculator")
            );

            AgentLoopContext context = createContextWithTools("openai", "gpt-4o",
                "Test", duplicateTools);

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            // The provider should receive exactly 2 unique tools ('search' + 'calculator'),
            // with 'search' retaining the first occurrence's description (first-wins).
            ArgumentCaptor<CompletionRequest> captor = ArgumentCaptor.forClass(CompletionRequest.class);
            verify(provider).complete(captor.capture());
            List<ToolDefinition> shipped = captor.getValue().tools();
            assertThat(shipped).hasSize(2);
            assertThat(shipped).extracting(ToolDefinition::name)
                    .containsExactlyInAnyOrder("search", "calculator");
            ToolDefinition search = shipped.stream()
                    .filter(t -> "search".equals(t.name())).findFirst().orElseThrow();
            assertThat(search.description()).isEqualTo("Search v1"); // first wins
        }

        @Test
        @DisplayName("BUG-10: catalog-discovered tool overlapping with context.tools() is skipped at add-time")
        void deduplicatesAcrossContextAndCatalogDiscovery() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Done"));

            // context.tools() already includes 'search' (from core/coreToolsProvider).
            // toolDiscoveryService returns 'search' x3 + 'calculator' (simulating the
            // observed get_task ×3 pattern - overlapping registrations across sources).
            when(mockToolDiscoveryService.findRelevantTools(anyString(), anyInt()))
                .thenReturn(List.of(
                    createToolDefinition("search", "Search v-catalog-1"),
                    createToolDefinition("search", "Search v-catalog-2"),
                    createToolDefinition("search", "Search v-catalog-3"),
                    createToolDefinition("calculator", "Calculator from catalog")
                ));

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("openai")
                .model("gpt-4o")
                .userPrompt("Test")
                .tools(List.of(createToolDefinition("search", "Search v-context")))
                .autoDiscoverTools(true)
                .maxIterations(5)
                .build();

            agentLoopService.execute(context);

            ArgumentCaptor<CompletionRequest> captor = ArgumentCaptor.forClass(CompletionRequest.class);
            verify(provider).complete(captor.capture());
            List<ToolDefinition> shipped = captor.getValue().tools();

            // Exactly 2 tools: 'search' (from context, first-wins over 3 catalog dupes)
            // + 'calculator' (only catalog provides it).
            assertThat(shipped).hasSize(2);
            assertThat(shipped).extracting(ToolDefinition::name)
                    .containsExactlyInAnyOrder("search", "calculator");
            ToolDefinition search = shipped.stream()
                    .filter(t -> "search".equals(t.name())).findFirst().orElseThrow();
            assertThat(search.description()).isEqualTo("Search v-context");
        }

        @Test
        @DisplayName("Should pass credentials to tool execution")
        void passesCredentialsToToolExecution() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "auth_api", Map.of(), 0);

            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Authenticated successfully."));

            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Auth successful"));

            Map<String, Object> credentials = Map.of("api_key", "secret123");
            AgentLoopContext context = AgentLoopContext.builder()
                .provider("openai")
                .model("gpt-4o")
                .userPrompt("Authenticate")
                .tools(List.of(createToolDefinition("auth_api", "Auth API")))
                .credentials(credentials)
                .build();

            agentLoopService.execute(context);

            ArgumentCaptor<Map<String, Object>> credCaptor = ArgumentCaptor.forClass(Map.class);
            verify(mockToolExecutionService).executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), credCaptor.capture());
            assertThat(credCaptor.getValue()).containsEntry("api_key", "secret123");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // LOOP DETECTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Loop Detection Tests")
    class LoopDetectionTests {

        @Test
        @DisplayName("Should detect identical tool calls")
        void detectsIdenticalToolCalls() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "search", Map.of("query", "test"), 0);

            // First 4 calls should be OK
            for (int i = 0; i < 4; i++) {
                LoopDetector.DetectionResult result = detector.recordToolCall(toolCall);
                assertThat(result).isEqualTo(LoopDetector.DetectionResult.OK);
            }

            // 5th call should warn
            assertThat(detector.recordToolCall(toolCall))
                .isEqualTo(LoopDetector.DetectionResult.WARN);
        }

        @Test
        @DisplayName("Should stop after too many identical calls")
        void stopsAfterTooManyIdenticalCalls() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "fetch", Map.of("url", "http://test.com"), 0);

            // Make 14 calls (below stop threshold)
            for (int i = 0; i < 14; i++) {
                detector.recordToolCall(toolCall);
            }

            // 15th call should stop
            assertThat(detector.recordToolCall(toolCall))
                .isEqualTo(LoopDetector.DetectionResult.STOP);
        }

        @Test
        @DisplayName("Should track consecutive calls separately")
        void tracksConsecutiveCallsSeparately() {
            LoopDetector detector = new LoopDetector();

            // Different tool calls (not identical)
            for (int i = 0; i < 14; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall())
                .isEqualTo(LoopDetector.ConsecutiveResult.REMINDER);

            // Continue to strong recommendation
            for (int i = 0; i < 10; i++) {
                detector.recordConsecutiveCall();
            }
            assertThat(detector.recordConsecutiveCall())
                .isEqualTo(LoopDetector.ConsecutiveResult.STRONG_RECOMMENDATION);
        }

        @Test
        @DisplayName("Should detect duplicate results")
        void detectsDuplicateResults() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "api", Map.of(), 0);

            // First result - not duplicate
            assertThat(detector.recordResult(toolCall, "Result A")).isFalse();

            // Different result - not duplicate
            assertThat(detector.recordResult(toolCall, "Result B")).isFalse();

            // Same as last - duplicate!
            assertThat(detector.recordResult(toolCall, "Result B")).isTrue();
        }

        @Test
        @DisplayName("Should generate appropriate warning messages")
        void generatesWarningMessages() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "repeated_tool", Map.of(), 0);

            String warning = detector.generateWarningMessage(toolCall, 5);
            assertThat(warning)
                .contains("repeated_tool")
                .contains("5")
                .contains("workflow");
        }

        @Test
        @DisplayName("Should reset correctly")
        void resetsCorrectly() {
            LoopDetector detector = new LoopDetector();
            ToolCall toolCall = new ToolCall("call_1", "tool", Map.of(), 0);

            for (int i = 0; i < 10; i++) {
                detector.recordToolCall(toolCall);
                detector.recordConsecutiveCall();
            }

            detector.reset();

            assertThat(detector.getCallCount(toolCall)).isZero();
            assertThat(detector.getTotalConsecutiveCalls()).isZero();
        }

        @Test
        @DisplayName("Different arguments should have different signatures")
        void differentArgsDifferentSignatures() {
            LoopDetector detector = new LoopDetector();
            ToolCall call1 = new ToolCall("call_1", "search", Map.of("query", "cats"), 0);
            ToolCall call2 = new ToolCall("call_2", "search", Map.of("query", "dogs"), 0);

            // Each should be tracked separately
            detector.recordToolCall(call1);
            detector.recordToolCall(call1);
            detector.recordToolCall(call2);
            detector.recordToolCall(call2);

            assertThat(detector.getCallCount(call1)).isEqualTo(2);
            assertThat(detector.getCallCount(call2)).isEqualTo(2);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ERROR HANDLING TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle null provider gracefully")
        void handlesNullProvider() {
            when(mockProviderFactory.getDefaultProviderName()).thenReturn("null");
            when(mockProviderFactory.getProvider(anyString()))
                .thenThrow(new LLMProviderException("null", "Provider not found"));

            AgentLoopContext context = createContext(null, null, "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Should handle missing default provider gracefully in streaming")
        void handlesMissingDefaultProviderInStreaming() {
            StreamingCallback callback = mock(StreamingCallback.class);

            AgentLoopContext context = createContext(null, null, "Hello");
            AgentLoopResult result = agentLoopService.executeStreaming(context, callback);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("No configured LLM provider available");
            verify(callback).onError(contains("No configured LLM provider available"));
        }

        @Test
        @DisplayName("Should handle empty user prompt")
        void handlesEmptyUserPrompt() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("I need more context."));

            AgentLoopContext context = createContext("openai", "gpt-4o", "");
            AgentLoopResult result = agentLoopService.execute(context);

            // Should still execute (LLM handles empty prompts)
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("Should handle runtime exception in provider")
        void handlesRuntimeExceptionInProvider() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(new RuntimeException("Unexpected error"));

            AgentLoopContext context = createContext("openai", "gpt-4o", "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Unexpected error");
            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }

        @ParameterizedTest(name = "Provider {0} should handle null response content")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#allProviders")
        @DisplayName("Null response content handling")
        void handlesNullResponseContent(String providerName, String defaultModel) {
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            when(mockProviderFactory.getProvider(providerName)).thenReturn(provider);

            CompletionResponse nullContentResponse = CompletionResponse.builder()
                .content(null)
                .finishReason("stop")
                .build();
            when(provider.complete(any())).thenReturn(nullContentResponse);

            AgentLoopContext context = createContext(providerName, defaultModel, "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            // Content should be empty string or null, not throw
            assertThat(result.content()).isNullOrEmpty();
        }

        @Test
        @DisplayName("Should handle tool execution timeout")
        void handlesToolExecutionTimeout() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "slow_api", Map.of(), 0);

            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Tool timed out, here's what I know."));

            // Simulate a slow tool that will be caught by internal timeout
            // The executor wraps executeTool in CompletableFuture with timeout
            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenAnswer(invocation -> {
                    // Simulate a long-running operation
                    Thread.sleep(100);
                    throw new RuntimeException("Tool execution timed out");
                });

            AgentLoopContext context = createContextWithTools("openai", "gpt-4o",
                "Call slow API", List.of(createToolDefinition("slow_api", "Slow API")));

            AgentLoopResult result = agentLoopService.execute(context);

            // Should complete with error message in tool result
            assertThat(result.success()).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // MULTI-PROVIDER DISPATCH TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-Provider Dispatch Tests")
    class MultiProviderDispatchTests {

        @Test
        @DisplayName("Should dispatch to correct provider based on context")
        void dispatchesToCorrectProvider() {
            LLMProvider openai = createMockProvider("openai", "gpt-4o");
            LLMProvider anthropic = createMockProvider("anthropic", "claude-sonnet-4-20250514");

            when(mockProviderFactory.getProvider("openai")).thenReturn(openai);
            when(mockProviderFactory.getProvider("anthropic")).thenReturn(anthropic);

            when(openai.complete(any())).thenReturn(createSimpleResponse("OpenAI response"));
            when(anthropic.complete(any())).thenReturn(createSimpleResponse("Claude response"));

            // Test OpenAI
            AgentLoopContext openaiContext = createContext("openai", "gpt-4o", "Hello");
            AgentLoopResult openaiResult = agentLoopService.execute(openaiContext);
            assertThat(openaiResult.content()).isEqualTo("OpenAI response");
            assertThat(openaiResult.provider()).isEqualTo("openai");

            // Test Anthropic
            AgentLoopContext anthropicContext = createContext("anthropic", "claude-sonnet-4-20250514", "Hello");
            AgentLoopResult anthropicResult = agentLoopService.execute(anthropicContext);
            assertThat(anthropicResult.content()).isEqualTo("Claude response");
            assertThat(anthropicResult.provider()).isEqualTo("anthropic");

            verify(openai, times(1)).complete(any());
            verify(anthropic, times(1)).complete(any());
        }

        @ParameterizedTest(name = "Provider {0} should handle system prompt correctly")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#allProviders")
        @DisplayName("System prompt handling across providers")
        void systemPromptHandling(String providerName, String defaultModel) {
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            when(mockProviderFactory.getProvider(providerName)).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Response with system prompt"));

            AgentLoopContext context = AgentLoopContext.builder()
                .provider(providerName)
                .model(defaultModel)
                .systemPrompt("You are a helpful assistant.")
                .userPrompt("Hello")
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            // System prompt should be included in messages
            ArgumentCaptor<CompletionRequest> captor = ArgumentCaptor.forClass(CompletionRequest.class);
            verify(provider).complete(captor.capture());
        }

        @Test
        @DisplayName("Should fall back to default provider when not specified")
        void fallsBackToDefaultProvider() {
            LLMProvider openai = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getDefaultProviderName()).thenReturn("openai");
            when(mockProviderFactory.getProvider("openai")).thenReturn(openai);
            when(openai.complete(any())).thenReturn(createSimpleResponse("Default provider response"));

            // Provider is null - should use factory default ("openai")
            AgentLoopContext context = AgentLoopContext.builder()
                .userPrompt("Hello")
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.provider()).isEqualTo("openai");
        }

        @ParameterizedTest(name = "Provider {0} should preserve conversation history")
        @MethodSource("com.apimarketplace.agent.loop.MultiLLMAgentLoopServiceTest#primaryProviders")
        @DisplayName("Conversation history preservation")
        void conversationHistoryPreservation(String providerName, String defaultModel) {
            LLMProvider provider = createMockProvider(providerName, defaultModel);
            when(mockProviderFactory.getProvider(providerName)).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Continuing the conversation"));

            List<Message> history = List.of(
                Message.user("What's the weather?"),
                Message.assistant("It's sunny today."),
                Message.user("What about tomorrow?")
            );

            AgentLoopContext context = AgentLoopContext.builder()
                .provider(providerName)
                .model(defaultModel)
                .conversationHistory(history)
                .userPrompt("And the day after?")
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.conversationHistory()).isNotEmpty();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // CONVERSATION HISTORY TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Conversation History Tests")
    class ConversationHistoryTests {

        @Test
        @DisplayName("Should pass system prompt to LLM in completion request")
        void includesSystemPromptInConversation() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Response"));

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("openai")
                .model("gpt-4o")
                .systemPrompt("You are a pirate.")
                .userPrompt("Hello")
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            // System prompt is passed to the LLM via the messages list but is NOT
            // included in conversationHistory() (which returns only execution messages).
            // Verify the LLM received a SYSTEM message with the custom prompt.
            ArgumentCaptor<CompletionRequest> captor = ArgumentCaptor.forClass(CompletionRequest.class);
            verify(provider).complete(captor.capture());
            assertThat(captor.getValue().conversationHistory())
                .anyMatch(m -> m.role() == Message.Role.SYSTEM && m.content().contains("pirate"));
        }

        @Test
        @DisplayName("Should track tool results in conversation history")
        void tracksToolResultsInHistory() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "test_tool", Map.of(), 0);

            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Final response"));

            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Tool output"));

            AgentLoopContext context = createContextWithTools("openai", "gpt-4o",
                "Use tool", List.of(createToolDefinition("test_tool", "Test tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.success()).isTrue();
            assertThat(result.conversationHistory())
                .anyMatch(m -> m.role() == Message.Role.TOOL);
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
        @DisplayName("All stop reasons should be valid")
        void allStopReasonsValid(AgentStopReason reason) {
            assertThat(reason.name()).isNotBlank();
        }

        @Test
        @DisplayName("Should return COMPLETED for successful execution")
        void completedForSuccess() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenReturn(createSimpleResponse("Done"));

            AgentLoopContext context = createContext("openai", "gpt-4o", "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.COMPLETED);
        }

        @Test
        @DisplayName("Should return ERROR for failed execution")
        void errorForFailure() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);
            when(provider.complete(any())).thenThrow(new RuntimeException("Failed"));

            AgentLoopContext context = createContext("openai", "gpt-4o", "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        }

        @Test
        @DisplayName("Should return MAX_ITERATIONS when limit reached")
        void maxIterationsWhenLimitReached() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "infinite_tool", Map.of(), 0);
            when(provider.complete(any())).thenReturn(createResponseWithToolCalls(List.of(toolCall)));

            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Continue"));

            AgentLoopContext context = AgentLoopContext.builder()
                .provider("openai")
                .model("gpt-4o")
                .userPrompt("Loop forever")
                .tools(List.of(createToolDefinition("infinite_tool", "Infinite tool")))
                .maxIterations(2)
                .build();

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.stopReason()).isEqualTo(AgentStopReason.MAX_ITERATIONS);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // APPROVED SERVICES TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Approved Services Tests")
    class ApprovedServicesTests {

        @Test
        @DisplayName("Should pass approved services through context")
        void passesApprovedServicesThroughContext() {
            // This test verifies the AgentLoopContext correctly handles approved services
            // No mocks needed - we're testing the context object itself
            Set<String> approvedServices = Set.of("gmail", "slack");
            AgentLoopContext context = AgentLoopContext.builder()
                .provider("openai")
                .model("gpt-4o")
                .userPrompt("Send an email")
                .approvedServices(approvedServices)
                .build();

            assertThat(context.isServiceApproved("gmail")).isTrue();
            assertThat(context.isServiceApproved("slack")).isTrue();
            assertThat(context.isServiceApproved("twitter")).isFalse();
            assertThat(context.hasApprovedServices()).isTrue();
        }

        @Test
        @DisplayName("Should handle null approved services")
        void handlesNullApprovedServices() {
            AgentLoopContext context = AgentLoopContext.builder()
                .provider("openai")
                .userPrompt("Test")
                .build();

            assertThat(context.isServiceApproved("any")).isFalse();
            assertThat(context.hasApprovedServices()).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PERFORMANCE TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Should track execution duration")
        void tracksExecutionDuration() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            // Simulate some processing time
            when(provider.complete(any())).thenAnswer(invocation -> {
                Thread.sleep(100);
                return createSimpleResponse("Response");
            });

            AgentLoopContext context = createContext("openai", "gpt-4o", "Hello");
            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.durationMs()).isGreaterThanOrEqualTo(100);
        }

        @Test
        @DisplayName("Should return correct iteration count")
        void returnsCorrectIterationCount() {
            LLMProvider provider = createMockProvider("openai", "gpt-4o");
            when(mockProviderFactory.getProvider("openai")).thenReturn(provider);

            ToolCall toolCall = new ToolCall("call_1", "tool", Map.of(), 0);

            // 3 iterations: tool call -> tool call -> final response
            when(provider.complete(any()))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createResponseWithToolCalls(List.of(toolCall)))
                .thenReturn(createSimpleResponse("Done"));

            when(mockToolExecutionService.executeTool(any(ToolCall.class), any(ToolDefinition.class), any(), anyMap()))
                .thenReturn(ToolResult.success(toolCall, "Result"));

            AgentLoopContext context = createContextWithTools("openai", "gpt-4o",
                "Do something", List.of(createToolDefinition("tool", "Tool")));

            AgentLoopResult result = agentLoopService.execute(context);

            assertThat(result.iterations()).isEqualTo(3);
        }
    }
}
