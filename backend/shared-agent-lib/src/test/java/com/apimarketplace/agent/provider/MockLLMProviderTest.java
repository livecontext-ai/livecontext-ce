package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.ratelimit.ProviderRateLimiter;
import com.apimarketplace.agent.streaming.StreamingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit coverage for {@link MockLLMProvider} - the no-cost in-process provider that
 * lets agent tests run without a real API key. One behavior per test.
 */
class MockLLMProviderTest {

    private MockLLMProvider newProvider(ProviderRateLimiter rl) {
        return new MockLLMProvider(rl, "ok", 0L, 50, "mock-model");
    }

    private CompletionRequest request() {
        return CompletionRequest.builder()
                .tenantId("t1")
                .model("mock-model")
                .systemPrompt("be a stub")
                .userPrompt("say ok")
                .build();
    }

    @Test
    @DisplayName("complete() returns the canned reply with finishReason=stop and the configured completion tokens")
    void completeReturnsCannedReplyAndUsage() {
        MockLLMProvider provider = newProvider(mock(ProviderRateLimiter.class));

        CompletionResponse r = provider.complete(request());

        assertThat(r.content()).isEqualTo("ok");
        assertThat(r.finishReason()).isEqualTo("stop");
        assertThat(r.isComplete()).isTrue();
        assertThat(r.model()).isEqualTo("mock-model");
        assertThat(r.usage().completionTokens()).isEqualTo(50);
        assertThat(r.usage().getTotal()).isEqualTo(r.usage().promptTokens() + 50);
    }

    @Test
    @DisplayName("complete() runs through the rate limiter (checkRateLimit before, recordRequest after) for provider 'mock'")
    void completeExercisesRateLimiter() {
        ProviderRateLimiter rl = mock(ProviderRateLimiter.class);
        MockLLMProvider provider = newProvider(rl);

        provider.complete(request());

        verify(rl).checkRateLimit(eq("mock"), eq("mock-model"), eq("t1"), anyInt());
        verify(rl).recordRequest(eq("mock"), eq("mock-model"), eq("t1"), anyInt());
    }

    @Test
    @DisplayName("complete() tolerates a null rate limiter (direct-construction unit tests)")
    void completeWorksWithoutRateLimiter() {
        MockLLMProvider provider = newProvider(null);

        CompletionResponse r = provider.complete(request());

        assertThat(r.content()).isEqualTo("ok");
    }

    @Test
    @DisplayName("streamReactive() emits a content chunk then a completed event")
    void streamReactiveEmitsContentThenCompleted() {
        MockLLMProvider provider = newProvider(mock(ProviderRateLimiter.class));

        List<StreamingEvent> events = provider.streamReactive(request()).collectList().block();

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(StreamingEvent.ContentChunk.class);
        assertThat(events.get(1)).isInstanceOf(StreamingEvent.CompletedEvent.class);
        assertThat(((StreamingEvent.ContentChunk) events.get(0)).content()).isEqualTo("ok");
    }

    @Test
    @DisplayName("provider identity: name='mock', configured without a key, no tool calling")
    void providerIdentity() {
        MockLLMProvider provider = newProvider(mock(ProviderRateLimiter.class));

        assertThat(provider.getProviderName()).isEqualTo("mock");
        assertThat(provider.isConfigured()).isTrue();
        assertThat(provider.supportsToolCalling()).isFalse();
        assertThat(provider.supportsStreaming()).isTrue();
        assertThat(provider.getSupportedModels()).isEqualTo(List.of("mock-model"));
        assertThat(provider.getDefaultModel()).isEqualTo("mock-model");
    }
}
