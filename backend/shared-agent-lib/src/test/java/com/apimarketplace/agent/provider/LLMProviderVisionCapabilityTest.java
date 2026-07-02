package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins WHICH providers declare {@link LLMProvider#supportsImageAttachments()}. The agent
 * loop consults this flag before appending the synthetic "image shown below" USER message
 * for tool-result {@code __media__} images. A provider whose request serialiser drops
 * attachments must stay {@code false}: flipping it to true without wiring a native vision
 * block re-introduces the misleading-label defect (the model is told an image is visible
 * while the bytes never reach the API).
 *
 * <p>Mockito {@code thenCallRealMethod} is used so the concrete overrides are exercised
 * without constructing the full provider (their constructors need live HTTP wiring).</p>
 */
@DisplayName("LLMProvider.supportsImageAttachments capability matrix")
class LLMProviderVisionCapabilityTest {

    private static boolean realCapability(Class<? extends LLMProvider> providerClass) {
        LLMProvider provider = mock(providerClass);
        when(provider.supportsImageAttachments()).thenCallRealMethod();
        return provider.supportsImageAttachments();
    }

    @Test
    @DisplayName("interface default is false: an unwired provider never claims vision")
    void interfaceDefaultIsFalse() {
        LLMProvider bare = mock(LLMProvider.class);
        when(bare.supportsImageAttachments()).thenCallRealMethod();

        assertThat(bare.supportsImageAttachments()).isFalse();
    }

    @Test
    @DisplayName("Claude, Gemini and OpenAI serialise USER image attachments natively -> true")
    void visionWiredProvidersDeclareSupport() {
        assertThat(realCapability(ClaudeProvider.class)).isTrue();
        assertThat(realCapability(GeminiProvider.class)).isTrue();
        assertThat(realCapability(OpenAIProvider.class)).isTrue();
    }

    @Test
    @DisplayName("DeepSeek, Mistral and OpenAI-compatible drop attachments at serialisation -> false")
    void attachmentDroppingProvidersStayFalse() {
        assertThat(realCapability(DeepSeekProvider.class)).isFalse();
        assertThat(realCapability(MistralProvider.class)).isFalse();
        assertThat(realCapability(OpenAICompatibleProvider.class)).isFalse();
    }
}
