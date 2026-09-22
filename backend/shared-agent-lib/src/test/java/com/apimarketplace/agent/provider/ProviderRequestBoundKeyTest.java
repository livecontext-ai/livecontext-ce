package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.KeyRoute;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every concrete provider puts the key somewhere different (a bearer, {@code x-api-key},
 * Google's {@code ?key=} query string). This pins that each of those places carries the
 * REQUEST tenant's own key, and that a {@code PLATFORM} pin puts the platform key there
 * instead, with no servlet request bound on the calling thread.
 */
@DisplayName("Concrete providers - the request-bound key reaches the wire")
class ProviderRequestBoundKeyTest {

    /** user "tenant-9" holds "sk-user-9"; the platform holds "sk-platform". */
    private static final LlmCredentialResolver RESOLVER = new LlmCredentialResolver() {
        private final Map<String, String> userKeys = Map.of("tenant-9", "sk-user-9");

        @Override
        public Optional<String> resolveApiKey(String providerName) {
            return Optional.of("sk-thread-user");
        }

        @Override
        public Optional<String> resolveApiKey(String userId, String providerName) {
            return Optional.of(userId != null && userKeys.containsKey(userId) ? userKeys.get(userId) : "sk-platform");
        }

        @Override
        public Optional<String> resolveUserApiKey(String userId, String providerName) {
            return Optional.ofNullable(userKeys.get(userId));
        }
    };

    private static CompletionRequest ownKeyRequest() {
        return CompletionRequest.builder().tenantId("tenant-9").keyRoute(KeyRoute.OWN_KEY).model("m").build();
    }

    private static CompletionRequest platformRequest() {
        return CompletionRequest.builder().tenantId("tenant-9").keyRoute(KeyRoute.PLATFORM).model("m").build();
    }

    private static <P extends AbstractLLMProvider> P wired(P provider) {
        provider.setCredentialResolver(RESOLVER);
        return provider;
    }

    @Test
    @DisplayName("Claude: x-api-key is the tenant's own key on OWN_KEY, the platform key on PLATFORM")
    void claudeXApiKey() {
        ClaudeProvider provider = wired(new ClaudeProvider());

        assertThat(provider.buildHeaders(ownKeyRequest()).getFirst("x-api-key")).isEqualTo("sk-user-9");
        assertThat(provider.buildHeaders(platformRequest()).getFirst("x-api-key")).isEqualTo("sk-platform");
    }

    @Test
    @DisplayName("OpenAI, Mistral, DeepSeek: the bearer token is the tenant's own key on OWN_KEY, the platform key on PLATFORM")
    void bearerProviders() {
        for (AbstractLLMProvider provider : List.of(
                wired(new OpenAIProvider()), wired(new MistralProvider()), wired(new DeepSeekProvider()))) {
            HttpHeaders own = provider.buildHeaders(ownKeyRequest());
            HttpHeaders platform = provider.buildHeaders(platformRequest());
            assertThat(own.getFirst("Authorization")).as(provider.getProviderName()).isEqualTo("Bearer sk-user-9");
            assertThat(platform.getFirst("Authorization")).as(provider.getProviderName()).isEqualTo("Bearer sk-platform");
        }
    }

    @Test
    @DisplayName("OpenAI-compatible vendors (constructed per provider) behave like the bearer providers")
    void openAiCompatibleBearer() {
        OpenAICompatibleProvider provider = wired(new OpenAICompatibleProvider(
                "zai", "https://open.bigmodel.cn/api/paas/v4/chat/completions", "", List.of("glm-5"), 10));

        assertThat(provider.buildHeaders(ownKeyRequest()).getFirst("Authorization")).isEqualTo("Bearer sk-user-9");
        assertThat(provider.buildHeaders(platformRequest()).getFirst("Authorization")).isEqualTo("Bearer sk-platform");
    }

    @Test
    @DisplayName("Gemini: the key rides in BOTH request URLs (sync and streaming), bound to the request tenant")
    void geminiKeyInUrl() {
        GeminiProvider provider = wired(new GeminiProvider());

        assertThat(provider.getApiUrlForModel("gemini-x", ownKeyRequest())).endsWith(":generateContent?key=sk-user-9");
        assertThat(provider.getStreamingUrlForModel("gemini-x", ownKeyRequest())).endsWith("alt=sse&key=sk-user-9");
        assertThat(provider.getApiUrlForModel("gemini-x", platformRequest())).endsWith("?key=sk-platform");
        assertThat(provider.getStreamingUrlForModel("gemini-x", platformRequest())).endsWith("&key=sk-platform");
        // Google authenticates in the URL: no key may leak into the headers.
        assertThat(provider.buildHeaders(ownKeyRequest()).containsKey("x-goog-api-key")).isFalse();
    }

    @Test
    @DisplayName("an unpinned request with a tenant resolves user-first for that tenant on every provider")
    void unpinnedRequestResolvesForTheTenant() {
        CompletionRequest unpinned = CompletionRequest.builder().tenantId("tenant-9").model("m").build();

        assertThat(wired(new OpenAIProvider()).buildHeaders(unpinned).getFirst("Authorization")).isEqualTo("Bearer sk-user-9");
        assertThat(wired(new ClaudeProvider()).buildHeaders(unpinned).getFirst("x-api-key")).isEqualTo("sk-user-9");
        assertThat(wired(new GeminiProvider()).getApiUrlForModel("g", unpinned)).endsWith("?key=sk-user-9");
    }
}
