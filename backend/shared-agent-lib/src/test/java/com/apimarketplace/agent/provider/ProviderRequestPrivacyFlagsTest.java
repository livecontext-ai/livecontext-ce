package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.Message;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-004: vendor-documented no-retention flags are sent in code, only to the vendors that
 * document them, so a strict OpenAI-shaped server never receives a field it would reject.
 */
@DisplayName("ProviderRequestPrivacyFlags")
class ProviderRequestPrivacyFlagsTest {

    private static CompletionRequest request() {
        return CompletionRequest.builder().model("m").userPrompt("hi").conversationHistory(List.of(Message.user("hi"))).build();
    }

    @Test
    @DisplayName("OpenAI at its vendor endpoint gets store=false")
    void openAiVendorGetsStoreFalse() {
        Map<String, Object> body = new HashMap<>();
        ProviderRequestPrivacyFlags.apply("openai", "https://api.openai.com/v1/chat/completions", body);
        assertThat(body).containsEntry("store", false);

        Map<String, Object> defaultUrl = new HashMap<>();
        ProviderRequestPrivacyFlags.apply("openai", null, defaultUrl);
        assertThat(defaultUrl).containsEntry("store", false);
    }

    @Test
    @DisplayName("OpenAI pointed at another host (Azure, proxy) gets nothing, so it cannot 400 on an unknown field")
    void openAiElsewhereGetsNothing() {
        Map<String, Object> body = new HashMap<>();
        ProviderRequestPrivacyFlags.apply("openai", "https://my-resource.openai.azure.com/openai/deployments/x", body);
        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("OpenRouter gets provider.data_collection=deny; every other OpenAI-shaped vendor gets nothing")
    void perVendorFlags() {
        Map<String, Object> openrouter = new HashMap<>();
        ProviderRequestPrivacyFlags.apply("openrouter", "https://openrouter.ai/api/v1/chat/completions", openrouter);
        assertThat(openrouter).containsEntry("provider", Map.of("data_collection", "deny"));

        for (String vendor : new String[] {"zai", "qwen", "moonshot", "minimax", "deepseek", "xai", "mistral"}) {
            Map<String, Object> body = new HashMap<>();
            ProviderRequestPrivacyFlags.apply(vendor, "https://example.test/v1/chat/completions", body);
            assertThat(body).as(vendor).isEmpty();
        }
    }

    @Test
    @DisplayName("the OpenAI provider's real request body carries store=false")
    void openAiProviderBodyCarriesFlag() {
        OpenAIProvider provider = new OpenAIProvider();
        ReflectionTestUtils.setField(provider, "apiUrl", "https://api.openai.com/v1/chat/completions");
        Map<String, Object> body = provider.buildRequestBody(request());
        assertThat(body).containsEntry("store", false);
    }

    @Test
    @DisplayName("an OpenAI-compatible vendor's real request body carries no extra field")
    void compatibleProviderBodyUnchanged() {
        OpenAICompatibleProvider zai = new OpenAICompatibleProvider("zai",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions", "", List.of(), 1);
        Map<String, Object> body = zai.buildRequestBody(request());
        assertThat(body).doesNotContainKeys("store", "provider");
    }
}
