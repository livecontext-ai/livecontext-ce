package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.gemini.cache.GeminiCachedContentManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Stage 2 wiring - pin {@link GeminiProvider#buildRequestBody} behavior
 * against the injected {@link GeminiCachedContentManager}:
 *
 * <ul>
 *   <li><b>Manager absent</b> - provider emits the pre-Stage-2 body
 *       (systemInstruction + tools inline, no cachedContent key). Safe
 *       default when {@code ai.agent.providers.google.cache.enabled=false}
 *       and in non-Spring test contexts.</li>
 *   <li><b>Manager empty</b> - gate rejected / create failed → again the
 *       pre-Stage-2 body. No cachedContent attached.</li>
 *   <li><b>Manager returns name</b> - body carries {@code cachedContent}
 *       and <em>omits</em> {@code systemInstruction} + {@code tools}
 *       (Google rejects duplicated state).</li>
 * </ul>
 */
@DisplayName("GeminiProvider - cachedContent wiring")
@ExtendWith(MockitoExtension.class)
class GeminiProviderCachedContentWiringTest {

    @Mock private GeminiCachedContentManager cacheManager;

    private static CompletionRequest request() {
        return CompletionRequest.builder()
                .model("gemini-1.5-flash")
                .systemPrompt("You are a helpful assistant.")
                .userPrompt("hi")
                .tools(List.of(ToolDefinition.builder()
                        .name("agent")
                        .description("agent tool")
                        .parameters(List.of(ToolParameter.builder().name("action").type("string").build()))
                        .build()))
                .build();
    }

    @Test
    @DisplayName("no manager injected → legacy body (systemInstruction + tools inline)")
    void noManagerLegacyBody() {
        GeminiProvider provider = new GeminiProvider();

        Map<String, Object> body = provider.buildRequestBody(request());

        assertThat(body).containsKey("systemInstruction");
        assertThat(body).containsKey("tools");
        assertThat(body).doesNotContainKey("cachedContent");
    }

    @Test
    @DisplayName("manager returns empty → legacy body, no cachedContent")
    void managerEmptyLegacyBody() {
        GeminiProvider provider = new GeminiProvider();
        provider.setCachedContentManager(cacheManager);
        when(cacheManager.getOrCreate(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(Optional.empty());

        Map<String, Object> body = provider.buildRequestBody(request());

        assertThat(body).containsKey("systemInstruction");
        assertThat(body).containsKey("tools");
        assertThat(body).doesNotContainKey("cachedContent");
    }

    @Test
    @DisplayName("cachedContent swap preserves generationConfig.thinkingConfig for gemini-2.5/3 models")
    void thinkingConfigSurvivesSwap() {
        GeminiProvider provider = new GeminiProvider();
        provider.setCachedContentManager(cacheManager);
        when(cacheManager.getOrCreate(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(Optional.of("cachedContents/thinking-ok"));

        CompletionRequest req = CompletionRequest.builder()
                .model("gemini-2.5-flash")   // triggers thinkingConfig branch
                .systemPrompt("sys")
                .userPrompt("hi")
                .build();
        Map<String, Object> body = provider.buildRequestBody(req);

        @SuppressWarnings("unchecked")
        Map<String, Object> genCfg = (Map<String, Object>) body.get("generationConfig");
        assertThat(genCfg).isNotNull();
        assertThat(genCfg).containsKey("thinkingConfig");
        // cachedContent still present, systemInstruction/tools gone.
        assertThat(body).containsKey("cachedContent");
        assertThat(body).doesNotContainKey("systemInstruction");
    }

    @Test
    @DisplayName("manager returns name → body carries cachedContent, omits systemInstruction + tools")
    void managerHitSwapsBody() {
        GeminiProvider provider = new GeminiProvider();
        provider.setCachedContentManager(cacheManager);
        when(cacheManager.getOrCreate(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(Optional.of("cachedContents/abc-123"));

        Map<String, Object> body = provider.buildRequestBody(request());

        assertThat(body.get("cachedContent")).isEqualTo("cachedContents/abc-123");
        assertThat(body).doesNotContainKey("systemInstruction");
        assertThat(body).doesNotContainKey("tools");
        // generationConfig and contents are still required - those stay.
        assertThat(body).containsKey("generationConfig");
        assertThat(body).containsKey("contents");
    }

    @Test
    @DisplayName("manager throws → swallowed, legacy body returned")
    void managerThrowsSwallowed() {
        GeminiProvider provider = new GeminiProvider();
        provider.setCachedContentManager(cacheManager);
        when(cacheManager.getOrCreate(anyString(), anyString(), anyString(), any(), anyInt()))
                .thenThrow(new RuntimeException("unexpected"));

        Map<String, Object> body = provider.buildRequestBody(request());

        assertThat(body).containsKey("systemInstruction");
        assertThat(body).containsKey("tools");
        assertThat(body).doesNotContainKey("cachedContent");
    }
}
