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

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests that AgentLoopService.executeStreaming() propagates usage info and model
 * in the CompletionResponse passed to callback.onComplete().
 *
 * This is critical for credit consumption: without usage/model, the downstream
 * credit pipeline sees 0 tokens and charges nothing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopService Streaming Usage Propagation")
class AgentLoopServiceStreamingUsageTest {

    @Mock private LLMProviderFactory providerFactory;
    @Mock private LLMProvider provider;

    private AgentLoopService agentLoopService;

    private static final String MODEL = "gpt-4o";
    private static final String PROVIDER_NAME = "openai";

    @BeforeEach
    void setUp() {
        agentLoopService = new AgentLoopService(providerFactory, null, null, null);

        lenient().when(providerFactory.getProvider(PROVIDER_NAME)).thenReturn(provider);
        lenient().when(provider.isConfigured()).thenReturn(true);
        lenient().when(provider.getProviderName()).thenReturn(PROVIDER_NAME);
        lenient().when(provider.getDefaultModel()).thenReturn(MODEL);
    }

    @Test
    @DisplayName("should include usage and model in onComplete response when LLM completes normally")
    void shouldIncludeUsageAndModelOnNormalCompletion() throws Exception {
        // Simulate streaming: completeStreaming invokes the collector callback inline
        doAnswer(invocation -> {
            StreamingCallback collector = invocation.getArgument(1);
            collector.onChunk("Hello!");
            collector.onComplete(CompletionResponse.builder()
                    .content("Hello!")
                    .finishReason("stop")
                    .usage(UsageInfo.builder().promptTokens(100).completionTokens(50).totalTokens(150).build())
                    .model(MODEL)
                    .build());
            return null;
        }).when(provider).completeStreaming(any(CompletionRequest.class), any(StreamingCallback.class));

        AtomicReference<CompletionResponse> capturedResponse = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        StreamingCallback testCallback = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onError(String error) {}
            @Override
            public void onComplete(CompletionResponse response) {
                capturedResponse.set(response);
                latch.countDown();
            }
        };

        AgentLoopContext context = AgentLoopContext.builder()
                .userPrompt("Hi")
                .provider(PROVIDER_NAME)
                .model(MODEL)
                .maxIterations(1)
                .build();

        agentLoopService.executeStreaming(context, testCallback);
        latch.await(5, TimeUnit.SECONDS);

        CompletionResponse captured = capturedResponse.get();
        assertThat(captured).isNotNull();
        assertThat(captured.usage()).isNotNull();
        assertThat(captured.usage().promptTokens()).isEqualTo(100);
        assertThat(captured.usage().completionTokens()).isEqualTo(50);
        assertThat(captured.model()).isEqualTo(MODEL);
    }

    @Test
    @DisplayName("should include usage and model when stopped by user before iteration")
    void shouldIncludeUsageAndModelOnUserStop() throws Exception {
        AtomicReference<CompletionResponse> capturedResponse = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        StreamingCallback testCallback = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onError(String error) {}
            @Override
            public void onComplete(CompletionResponse response) {
                capturedResponse.set(response);
                latch.countDown();
            }
            @Override
            public boolean shouldStop() {
                return true; // Stop immediately
            }
        };

        AgentLoopContext context = AgentLoopContext.builder()
                .userPrompt("Hi")
                .provider(PROVIDER_NAME)
                .model(MODEL)
                .build();

        agentLoopService.executeStreaming(context, testCallback);
        latch.await(5, TimeUnit.SECONDS);

        CompletionResponse captured = capturedResponse.get();
        assertThat(captured).isNotNull();
        assertThat(captured.usage()).isNotNull();
        assertThat(captured.model()).isEqualTo(MODEL);
        assertThat(captured.finishReason()).isEqualTo("stopped_by_user");
    }

    @Test
    @DisplayName("should include usage and model on max iterations with tool calls")
    void shouldIncludeUsageAndModelOnMaxIterations() throws Exception {
        // Simulate streaming that returns tool calls (causes iteration to continue)
        // but max iterations is 1, so it stops after first iteration
        ToolCall toolCall = ToolCall.builder()
                .id("call-1")
                .toolName("test_tool")
                .arguments(Map.of())
                .index(0)
                .build();

        doAnswer(invocation -> {
            StreamingCallback collector = invocation.getArgument(1);
            collector.onToolCall(toolCall);
            collector.onComplete(CompletionResponse.builder()
                    .content("")
                    .finishReason("tool_calls")
                    .toolCalls(List.of(toolCall))
                    .usage(UsageInfo.builder().promptTokens(200).completionTokens(100).totalTokens(300).build())
                    .model(MODEL)
                    .build());
            return null;
        }).when(provider).completeStreaming(any(CompletionRequest.class), any(StreamingCallback.class));

        AtomicReference<CompletionResponse> capturedResponse = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        StreamingCallback testCallback = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall tc) {}
            @Override public void onError(String error) {}
            @Override
            public void onComplete(CompletionResponse response) {
                capturedResponse.set(response);
                latch.countDown();
            }
        };

        AgentLoopContext context = AgentLoopContext.builder()
                .userPrompt("Do something")
                .provider(PROVIDER_NAME)
                .model(MODEL)
                .maxIterations(1)
                .build();

        agentLoopService.executeStreaming(context, testCallback);
        latch.await(5, TimeUnit.SECONDS);

        CompletionResponse captured = capturedResponse.get();
        assertThat(captured).isNotNull();
        assertThat(captured.usage()).isNotNull();
        assertThat(captured.model()).isEqualTo(MODEL);
        assertThat(captured.finishReason()).isEqualTo("max_iterations");
    }

    @Test
    @DisplayName("should return zero usage when LLM provider does not report tokens")
    void shouldReturnZeroUsageWhenProviderDoesNotReportTokens() throws Exception {
        // LLM returns response without usage info
        doAnswer(invocation -> {
            StreamingCallback collector = invocation.getArgument(1);
            collector.onChunk("Hello!");
            collector.onComplete(CompletionResponse.builder()
                    .content("Hello!")
                    .finishReason("stop")
                    .build());
            return null;
        }).when(provider).completeStreaming(any(CompletionRequest.class), any(StreamingCallback.class));

        AtomicReference<CompletionResponse> capturedResponse = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        StreamingCallback testCallback = new StreamingCallback() {
            @Override public void onChunk(String content) {}
            @Override public void onToolCall(ToolCall toolCall) {}
            @Override public void onError(String error) {}
            @Override
            public void onComplete(CompletionResponse response) {
                capturedResponse.set(response);
                latch.countDown();
            }
        };

        AgentLoopContext context = AgentLoopContext.builder()
                .userPrompt("Hi")
                .provider(PROVIDER_NAME)
                .model(MODEL)
                .maxIterations(1)
                .build();

        agentLoopService.executeStreaming(context, testCallback);
        latch.await(5, TimeUnit.SECONDS);

        CompletionResponse captured = capturedResponse.get();
        assertThat(captured).isNotNull();
        // Usage should still be present (with zeros from state), not null
        assertThat(captured.usage()).isNotNull();
        assertThat(captured.usage().promptTokens()).isEqualTo(0);
        assertThat(captured.usage().completionTokens()).isEqualTo(0);
        assertThat(captured.model()).isEqualTo(MODEL);
    }
}
