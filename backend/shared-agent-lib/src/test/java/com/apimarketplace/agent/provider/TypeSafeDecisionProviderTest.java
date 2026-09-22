package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The catalog entry for a model that returns a decision rather than text.
 *
 * <p>Two contracts live here. The provider must DECLARE its models, because a model no
 * provider declares does not exist for any picker or pricing surface. And it must REFUSE
 * to complete, because a picker filter only protects the user who clicks: an authored
 * plan, a copied node or an imported workflow can still name this model on an Agent node,
 * and that path arrives here.
 */
@DisplayName("TypeSafeDecisionProvider")
class TypeSafeDecisionProviderTest {

    private TypeSafeDecisionProvider provider(boolean enabled, String key, String models) {
        return new TypeSafeDecisionProvider(enabled, key, models, 20);
    }

    @Nested
    @DisplayName("Catalog declaration")
    class CatalogDeclaration {

        @Test
        @DisplayName("declares the models it is configured with, trimmed")
        void declaresItsModels() {
            TypeSafeDecisionProvider p = provider(true, "k", " jev-latest , jev-1.13 ");

            assertThat(p.getSupportedModels()).containsExactly("jev-latest", "jev-1.13");
            assertThat(p.getDefaultModel()).isEqualTo("jev-latest");
            assertThat(p.getProviderName()).isEqualTo("typesafe");
        }

        @Test
        @DisplayName("an empty model list leaves no default rather than an empty-string model")
        void emptyModelListHasNoDefault() {
            assertThat(provider(true, "k", "").getSupportedModels()).isEmpty();
            assertThat(provider(true, "k", "").getDefaultModel()).isNull();
            assertThat(provider(true, "k", null).getDefaultModel()).isNull();
        }

        @Test
        @DisplayName("needs a key, unlike a CLI bridge: without one every call would 401")
        void requiresAKeyToBeConfigured() {
            assertThat(provider(true, "ts-key", "jev-latest").isConfigured()).isTrue();
            assertThat(provider(true, "", "jev-latest").isConfigured()).isFalse();
            assertThat(provider(true, "   ", "jev-latest").isConfigured()).isFalse();
            assertThat(provider(true, null, "jev-latest").isConfigured()).isFalse();
        }

        @Test
        @DisplayName("disabling it reports unconfigured even when a key is present")
        void disabledIsUnconfigured() {
            assertThat(provider(false, "ts-key", "jev-latest").isConfigured()).isFalse();
        }

        @Test
        @DisplayName("advertises none of the conversational capabilities, because it has none")
        void advertisesNoConversationalCapability() {
            TypeSafeDecisionProvider p = provider(true, "k", "jev-latest");

            // Each of these is read somewhere to decide what a model may be asked to do.
            // Reporting true would invite exactly the calls that cannot be answered.
            assertThat(p.supportsStreaming()).isFalse();
            assertThat(p.supportsToolCalling()).isFalse();
            assertThat(p.supportsImageAttachments()).isFalse();
        }
    }

    @Nested
    @DisplayName("Refusals name the node to use instead")
    class Refusals {

        private final CompletionRequest request = CompletionRequest.builder()
                .model("jev-latest")
                .userPrompt("classify this")
                .build();

        @Test
        @DisplayName("complete() refuses, and the message says where the model does work")
        void completeRefuses() {
            assertThatThrownBy(() -> provider(true, "k", "jev-latest").complete(request))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("typed decision")
                    // A refusal that only says no leaves the reader stuck. This one names
                    // the node that does run this model.
                    .hasMessageContaining("Classify node");
        }

        @Test
        @DisplayName("completeStreaming() refuses the same way")
        void completeStreamingRefuses() {
            assertThatThrownBy(() -> provider(true, "k", "jev-latest")
                    .completeStreaming(request, null))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Classify node");
        }

        @Test
        @DisplayName("streamReactive() fails the flux rather than throwing at subscribe time")
        void streamReactiveFailsTheFlux() {
            // A Flux that throws on assembly escapes the subscriber's error handling; this
            // one carries the error to whoever subscribes.
            assertThatThrownBy(() -> provider(true, "k", "jev-latest")
                    .streamReactive(request).blockFirst())
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("Classify node");
        }
    }
}
