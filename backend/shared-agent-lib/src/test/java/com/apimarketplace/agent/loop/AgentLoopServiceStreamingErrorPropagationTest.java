package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.streaming.StreamingCallback;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * Pins the contract: when {@link com.apimarketplace.agent.provider.AbstractLLMProvider#completeStreaming}
 * surfaces an error via {@code callback.onError(...)} (silent-return paths - provider not configured,
 * rate-limit denial, transport failure caught by the provider's own try/catch), the verbatim error
 * string MUST appear in {@link AgentLoopResult#error()}.
 *
 * <p>Regression source: prod {@code agent:analyze_emails} on the Daily Email Digest workflow always
 * failed with the orchestrator's generic fallback {@code "Async agent execution failed"}. The agent
 * actually died at {@code AbstractLLMProvider.completeStreaming} line ~291 with a real error string,
 * but {@link AgentLoopExecutor.IterationResult#error()} was a no-arg signal and
 * {@code AgentLoopService.executeLoop} built an {@link AgentLoopResult} without setting {@code .error}.
 * The string went only to the SSE channel and was lost from the orchestrator's view.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopService - streaming error propagation into AgentLoopResult.error")
class AgentLoopServiceStreamingErrorPropagationTest {

    @Mock private LLMProviderFactory providerFactory;
    @Mock private LLMProvider provider;

    private AgentLoopService agentLoopService;

    private static final String MODEL = "deepseek-chat";
    private static final String PROVIDER_NAME = "deepseek";

    @BeforeEach
    void setUp() {
        agentLoopService = new AgentLoopService(providerFactory, null, null, null);
        lenient().when(providerFactory.getProvider(PROVIDER_NAME)).thenReturn(provider);
        // Outer isConfigured check at AgentLoopService.execute passes - failure happens
        // INSIDE processIteration's streaming call.
        lenient().when(provider.isConfigured()).thenReturn(true);
        lenient().when(provider.getProviderName()).thenReturn(PROVIDER_NAME);
        lenient().when(provider.getDefaultModel()).thenReturn(MODEL);
    }

    @Test
    @DisplayName("execute() with non-null callback: streaming callback.onError surfaces in AgentLoopResult.error")
    void executeWithCallbackPropagatesStreamingError() {
        String expectedError = "Provider is not configured. API key is missing.";
        doAnswer(invocation -> {
            StreamingCallback collector = invocation.getArgument(1);
            collector.onError(expectedError);
            return null;
        }).when(provider).completeStreaming(any(CompletionRequest.class), any(StreamingCallback.class));

        StreamingCallback noopCallback = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onError(String error) {}
            @Override public void onComplete(CompletionResponse response) {}
        };

        AgentLoopContext context = AgentLoopContext.builder()
                .userPrompt("hello")
                .provider(PROVIDER_NAME)
                .model(MODEL)
                .maxIterations(1)
                .build();

        AgentLoopResult result = agentLoopService.execute(context, noopCallback);

        assertThat(result.success()).isFalse();
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        assertThat(result.error())
                .as("Streaming error string must propagate from collector → IterationResult → AgentLoopResult.error")
                .isEqualTo(expectedError);
    }

    @Test
    @DisplayName("executeStreaming(): streaming callback.onError also surfaces in AgentLoopResult.error")
    void executeStreamingPropagatesError() {
        String expectedError = "Streaming error: connection refused";
        doAnswer(invocation -> {
            StreamingCallback collector = invocation.getArgument(1);
            collector.onError(expectedError);
            return null;
        }).when(provider).completeStreaming(any(CompletionRequest.class), any(StreamingCallback.class));

        StreamingCallback noopCallback = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onError(String error) {}
            @Override public void onComplete(CompletionResponse response) {}
        };

        AgentLoopContext context = AgentLoopContext.builder()
                .userPrompt("hello")
                .provider(PROVIDER_NAME)
                .model(MODEL)
                .maxIterations(1)
                .build();

        AgentLoopResult result = agentLoopService.executeStreaming(context, noopCallback);

        assertThat(result.success()).isFalse();
        assertThat(result.stopReason()).isEqualTo(AgentStopReason.ERROR);
        assertThat(result.error())
                .as("executeStreaming must propagate the streaming-collector error into AgentLoopResult.error")
                .isEqualTo(expectedError);
    }

    @Test
    @DisplayName("StreamingCollector exposes the last error string for IterationResult.error(message)")
    void streamingCollectorCapturesLastError() {
        // White-box: the StreamingCollector inside AgentLoopExecutor must store the
        // verbatim onError(...) string so processIteration can hand it to
        // IterationResult.error(message). Without this, the no-arg error() factory
        // would still ship and AgentLoopResult.error stays null (the prod regression).
        StreamingCallback delegate = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onError(String error) {}
            @Override public void onComplete(CompletionResponse response) {}
        };
        LoopExecutionState state = new LoopExecutionState(null, 5, 30000L);
        AgentLoopExecutor.StreamingCollector collector = new AgentLoopExecutor.StreamingCollector(delegate, state);

        collector.onError("HTTP 401: Unauthorized");

        assertThat(collector.hasError()).isTrue();
        assertThat(collector.getLastError()).isEqualTo("HTTP 401: Unauthorized");
    }

    @Test
    @DisplayName("StreamingCollector falls back to exception.getMessage() when 2-arg onError passes a null string")
    void streamingCollectorFallsBackToExceptionMessage() {
        // Defensive: if a future provider calls onError(null, exception) we still
        // want lastError populated from the exception. Otherwise IterationResult.error(null)
        // ships and the orchestrator falls back to "Async agent execution failed".
        StreamingCallback delegate = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onError(String error) {}
            @Override public void onComplete(CompletionResponse response) {}
        };
        LoopExecutionState state = new LoopExecutionState(null, 5, 30000L);
        AgentLoopExecutor.StreamingCollector collector = new AgentLoopExecutor.StreamingCollector(delegate, state);

        collector.onError(null, new RuntimeException("connection reset"));

        assertThat(collector.hasError()).isTrue();
        assertThat(collector.getLastError()).isEqualTo("connection reset");
    }
}
