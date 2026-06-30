package com.apimarketplace.agent.completion;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.cloud.RuntimeLlmProviderResolver;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.LLMProviderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ProviderLlmJsonInvoker} - the direct-access
 * production wiring that routes a {@code (provider, model, system, user)}
 * prompt through {@link LLMProviderFactory} and returns the raw content
 * string.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>Happy path - provider dispatch + field mapping (including
 *       tenant-scoped and tenant-less overloads).</li>
 *   <li>Null / blank / null-response content → explicit failure
 *       (callers need a non-empty JSON body to parse).</li>
 *   <li>Fence stripping - plain {@code ```}, {@code ```json}, no fence,
 *       fence without trailing newline.</li>
 *   <li>Provider-layer exceptions propagate.</li>
 * </ul>
 */
@DisplayName("ProviderLlmJsonInvoker")
@ExtendWith(MockitoExtension.class)
class ProviderLlmJsonInvokerTest {

    @Mock LLMProviderFactory factory;
    @Mock LLMProvider provider;
    @Mock RuntimeLlmProviderResolver resolver;

    private ProviderLlmJsonInvoker invoker;

    @BeforeEach
    void setUp() {
        invoker = new ProviderLlmJsonInvoker(factory);
    }

    @Test
    @DisplayName("Routes request to the named provider with the caller's (model, system, user) and tenantId")
    void routesRequestToProvider() {
        when(factory.getProvider("google")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(CompletionResponse.text("{\"summary\":\"ok\"}"));

        String json = invoker.invoke("google", "gemini-3-flash", "SYS", "USER", "tenant-42");

        assertThat(json).isEqualTo("{\"summary\":\"ok\"}");
        ArgumentCaptor<CompletionRequest> captor = ArgumentCaptor.forClass(CompletionRequest.class);
        org.mockito.Mockito.verify(provider).complete(captor.capture());
        CompletionRequest req = captor.getValue();
        assertThat(req.model()).isEqualTo("gemini-3-flash");
        assertThat(req.systemPrompt()).isEqualTo("SYS");
        assertThat(req.userPrompt()).isEqualTo("USER");
        assertThat(req.tenantId()).isEqualTo("tenant-42");
        assertThat(req.isStreaming()).isFalse();
        // Compaction cold-summary must advertise NO tools - a future .tools(...) here would
        // serialize tool schemas into the COMPACTION_SUMMARY prompt and silently over-bill it.
        assertThat(req.tools())
            .as("json-completion (compaction) must send no tools - never bill a tool schema")
            .isNullOrEmpty();
    }

    @Test
    @DisplayName("Tenant-less overload leaves tenantId null")
    void tenantlessOverloadPassesNull() {
        when(factory.getProvider("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(CompletionResponse.text("{}"));

        invoker.invoke("anthropic", "claude-sonnet-4-6", "s", "u");

        ArgumentCaptor<CompletionRequest> captor = ArgumentCaptor.forClass(CompletionRequest.class);
        org.mockito.Mockito.verify(provider).complete(captor.capture());
        assertThat(captor.getValue().tenantId()).isNull();
    }

    @Test
    @DisplayName("Tenant-aware constructor routes through RuntimeLlmProviderResolver for Cloud relay selection")
    void tenantAwareConstructorUsesRuntimeResolver() {
        ProviderLlmJsonInvoker tenantAwareInvoker = new ProviderLlmJsonInvoker(factory, resolver);
        when(resolver.resolve(eq("deepseek"), any(AgentLoopContext.class))).thenReturn(provider);
        when(provider.complete(any())).thenReturn(CompletionResponse.text("{\"ok\":true}"));

        String json = tenantAwareInvoker.invoke("deepseek", "deepseek-chat", "s", "u", "tenant-42");

        assertThat(json).isEqualTo("{\"ok\":true}");
        ArgumentCaptor<AgentLoopContext> contextCaptor = ArgumentCaptor.forClass(AgentLoopContext.class);
        verify(resolver).resolve(eq("deepseek"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().tenantId()).isEqualTo("tenant-42");
        assertThat(contextCaptor.getValue().provider()).isEqualTo("deepseek");
        verify(factory, never()).getProvider(any());
    }

    @Test
    @DisplayName("Null response object throws IllegalStateException")
    void nullResponseThrows() {
        when(factory.getProvider("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(null);

        assertThatThrownBy(() -> invoker.invoke("anthropic", "c", "s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty content");
    }

    @Test
    @DisplayName("Null content field throws IllegalStateException")
    void nullContentThrows() {
        when(factory.getProvider("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(CompletionResponse.builder().content(null).build());

        assertThatThrownBy(() -> invoker.invoke("anthropic", "c", "s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty content");
    }

    @Test
    @DisplayName("Blank content throws IllegalStateException")
    void blankContentThrows() {
        when(factory.getProvider("openai")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(CompletionResponse.text("   \n\t  "));

        assertThatThrownBy(() -> invoker.invoke("openai", "gpt-4o", "s", "u"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("empty content");
    }

    @Test
    @DisplayName("Strips ```json fence wrapping the entire payload")
    void stripsJsonFence() {
        when(factory.getProvider("google")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(CompletionResponse.text(
                "```json\n{\"summary\":\"hello\"}\n```"));

        assertThat(invoker.invoke("google", "m", "s", "u"))
                .isEqualTo("{\"summary\":\"hello\"}");
    }

    @Test
    @DisplayName("Strips bare ``` fence without language tag")
    void stripsBareFence() {
        when(factory.getProvider("google")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(CompletionResponse.text(
                "```\n{\"a\":1}\n```"));

        assertThat(invoker.invoke("google", "m", "s", "u")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("Leaves un-fenced JSON untouched")
    void leavesUnfencedContentAlone() {
        when(factory.getProvider("google")).thenReturn(provider);
        when(provider.complete(any())).thenReturn(CompletionResponse.text("{\"a\":1}"));

        assertThat(invoker.invoke("google", "m", "s", "u")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("Unknown provider propagates LLMProviderException from factory")
    void unknownProviderPropagates() {
        when(factory.getProvider("mystery"))
                .thenThrow(new LLMProviderException("mystery", "Provider not found: mystery"));

        assertThatThrownBy(() -> invoker.invoke("mystery", "m", "s", "u"))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("Provider not found");
    }

    @Test
    @DisplayName("Provider exception during complete() propagates to caller")
    void providerExceptionPropagates() {
        when(factory.getProvider("anthropic")).thenReturn(provider);
        when(provider.complete(any())).thenThrow(new RuntimeException("upstream 503"));

        assertThatThrownBy(() -> invoker.invoke("anthropic", "c", "s", "u"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("upstream 503");
    }

    @Test
    @DisplayName("stripFence: handles fence with no trailing newline gracefully")
    void stripFenceEdgeCases() {
        assertThat(ProviderLlmJsonInvoker.stripFence("```")).isEqualTo("```");
        assertThat(ProviderLlmJsonInvoker.stripFence("{\"x\":1}")).isEqualTo("{\"x\":1}");
        assertThat(ProviderLlmJsonInvoker.stripFence("  \n```json\n{}\n```\n  ")).isEqualTo("{}");
    }
}
