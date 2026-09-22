package com.apimarketplace.agent.factory;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.TypeSafeDecisionProvider;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The platform default provider must be one that can answer in text.
 *
 * <p>Every caller of a default is a caller with no model of its own: a chat turn that
 * named none, an agent loop resolving its own. A decision provider refuses
 * {@code complete()} outright, so defaulting to one produces a turn that cannot succeed.
 *
 * <p>Display order very nearly hides this - a decision provider sits last, and any chat
 * provider outranks it - which is exactly why it is worth a test: "nearly" is one
 * configuration away, and the configuration in question (a decision key, no chat key, no
 * bridge) is a perfectly ordinary CE install.
 */
@DisplayName("LLMProviderFactory - the default provider can hold a conversation")
class LLMProviderFactoryDefaultModelTest {

    private static final TypeSafeDecisionProvider JEV =
            new TypeSafeDecisionProvider(true, "ts-key", "jev-latest", 20);

    @Test
    @DisplayName("a decision provider is never the default, even when it is the ONLY one configured")
    void decisionProviderIsNeverTheDefault() {
        // The whole point: with no chat provider to outrank it, display order protects
        // nothing and the rule has to.
        LLMProviderFactory factory = new LLMProviderFactory(List.of(JEV));

        assertThat(factory.getDefaultProviderName()).isNull();
    }

    @Test
    @DisplayName("the published catalogue names no decision model as its default either")
    void catalogueDefaultIsNotADecisionModel() {
        // Two readers, one rule: getDefaultProviderName and the defaultProvider/defaultModel
        // the catalogue payload carries. A caller reading the second would otherwise pick
        // exactly what the first refuses.
        LLMProviderFactory factory = new LLMProviderFactory(List.of(JEV));

        Map<String, Object> info = factory.getAllModelsInfo();

        assertThat(info.get("defaultProvider")).isNull();
        assertThat(info.get("defaultModel")).isNull();
    }

    @Test
    @DisplayName("a chat provider is still the default, whatever its display order")
    void chatProviderRemainsTheDefault() {
        LLMProviderFactory factory = new LLMProviderFactory(List.of(JEV, new FakeChatProvider(1)));

        assertThat(factory.getDefaultProviderName()).isEqualTo("fake-chat");
        assertThat(factory.getAllModelsInfo().get("defaultModel")).isEqualTo("fake-chat-1");
    }

    @Test
    @DisplayName("a decision provider does not become the default by outranking a chat one")
    void displayOrderDoesNotOverrideTheRule() {
        // The decision provider is given the BEST display order here. Ordering must not be
        // what keeps it out, or the protection evaporates the day someone reorders the YAML.
        TypeSafeDecisionProvider firstPlace = new TypeSafeDecisionProvider(true, "k", "jev-latest", 0);
        LLMProviderFactory factory = new LLMProviderFactory(List.of(firstPlace, new FakeChatProvider(9)));

        assertThat(factory.getDefaultProviderName()).isEqualTo("fake-chat");
    }

    @Test
    @DisplayName("an unconfigured chat provider still cannot be the default")
    void unconfiguredProvidersAreStillSkipped() {
        // The pre-existing rule is untouched: this filter narrows, it does not widen.
        LLMProviderFactory factory = new LLMProviderFactory(List.of(new FakeChatProvider(1, false)));

        assertThat(factory.getDefaultProviderName()).isNull();
    }

    /** A minimal chat provider: the control case. */
    private static class FakeChatProvider implements LLMProvider {
        private final int order;
        private final boolean configured;

        FakeChatProvider(int order) { this(order, true); }
        FakeChatProvider(int order, boolean configured) {
            this.order = order;
            this.configured = configured;
        }

        @Override public String getProviderName() { return "fake-chat"; }
        @Override public String getDefaultModel() { return "fake-chat-1"; }
        @Override public List<String> getSupportedModels() { return List.of("fake-chat-1"); }
        @Override public boolean isConfigured() { return configured; }
        @Override public boolean supportsStreaming() { return true; }
        @Override public boolean supportsToolCalling() { return true; }
        @Override public int getDisplayOrder() { return order; }
        @Override public CompletionResponse complete(CompletionRequest request) { return null; }
        @Override public void completeStreaming(CompletionRequest request, StreamingCallback callback) { }
        @Override public Flux<StreamingEvent> streamReactive(CompletionRequest request) { return Flux.empty(); }
    }
}
