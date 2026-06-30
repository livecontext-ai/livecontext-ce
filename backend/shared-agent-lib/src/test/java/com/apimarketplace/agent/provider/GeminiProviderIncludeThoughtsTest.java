package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1a.3 - {@code includeThoughts} must default to {@code false} so thinking
 * output (billed as {@code thoughtsTokenCount}) is not emitted unless the caller
 * explicitly opts in. Previously the provider hard-coded {@code true}, which was
 * the single largest Gemini output-cost driver on hello-turns.
 */
@DisplayName("GeminiProvider - includeThoughts default")
class GeminiProviderIncludeThoughtsTest {

    private final GeminiProvider provider = new GeminiProvider();

    @Test
    @DisplayName("defaults to includeThoughts=false for gemini-3 when caller does not opt in")
    @SuppressWarnings("unchecked")
    void defaultsToFalseForGemini3() {
        CompletionRequest request = CompletionRequest.builder()
            .model("gemini-3-flash")
            .userPrompt("hello")
            .build();

        Map<String, Object> body = provider.buildRequestBody(request);

        Map<String, Object> genConfig = (Map<String, Object>) body.get("generationConfig");
        assertThat(genConfig).isNotNull();
        Map<String, Object> thinkingConfig = (Map<String, Object>) genConfig.get("thinkingConfig");
        assertThat(thinkingConfig).isNotNull();
        assertThat(thinkingConfig).containsEntry("includeThoughts", false);
    }

    @Test
    @DisplayName("defaults to includeThoughts=false for gemini-2.5 when caller does not opt in")
    @SuppressWarnings("unchecked")
    void defaultsToFalseForGemini25() {
        CompletionRequest request = CompletionRequest.builder()
            .model("gemini-2.5-pro")
            .userPrompt("hello")
            .build();

        Map<String, Object> body = provider.buildRequestBody(request);

        Map<String, Object> genConfig = (Map<String, Object>) body.get("generationConfig");
        Map<String, Object> thinkingConfig = (Map<String, Object>) genConfig.get("thinkingConfig");
        assertThat(thinkingConfig).containsEntry("includeThoughts", false);
    }

    @Test
    @DisplayName("honours caller opt-in by emitting includeThoughts=true")
    @SuppressWarnings("unchecked")
    void honoursCallerOptIn() {
        CompletionRequest request = CompletionRequest.builder()
            .model("gemini-3-flash")
            .userPrompt("hello")
            .includeThoughts(true)
            .build();

        Map<String, Object> body = provider.buildRequestBody(request);

        Map<String, Object> genConfig = (Map<String, Object>) body.get("generationConfig");
        Map<String, Object> thinkingConfig = (Map<String, Object>) genConfig.get("thinkingConfig");
        assertThat(thinkingConfig).containsEntry("includeThoughts", true);
    }

    @Test
    @DisplayName("treats includeThoughts=null as opt-out (no false-positive on Boolean.TRUE.equals)")
    @SuppressWarnings("unchecked")
    void nullIsOptOut() {
        CompletionRequest request = CompletionRequest.builder()
            .model("gemini-3-flash")
            .userPrompt("hello")
            .includeThoughts(null)
            .build();

        Map<String, Object> body = provider.buildRequestBody(request);

        Map<String, Object> genConfig = (Map<String, Object>) body.get("generationConfig");
        Map<String, Object> thinkingConfig = (Map<String, Object>) genConfig.get("thinkingConfig");
        assertThat(thinkingConfig).containsEntry("includeThoughts", false);
    }

    @Test
    @DisplayName("skips thinkingConfig entirely for non-thinking Gemini models")
    @SuppressWarnings("unchecked")
    void skipsThinkingForNonThinkingModels() {
        CompletionRequest request = CompletionRequest.builder()
            .model("gemini-1.5-pro")
            .userPrompt("hello")
            .includeThoughts(true) // even an opt-in should not inject config for non-thinking models
            .build();

        Map<String, Object> body = provider.buildRequestBody(request);

        Map<String, Object> genConfig = (Map<String, Object>) body.get("generationConfig");
        if (genConfig != null) {
            assertThat(genConfig).doesNotContainKey("thinkingConfig");
        }
    }

    @Test
    @DisplayName("wantsThoughts() on CompletionRequest returns false when field is null")
    void wantsThoughtsNullSafe() {
        CompletionRequest req = CompletionRequest.builder().model("gemini-3-flash").build();
        assertThat(req.wantsThoughts()).isFalse();
    }

    @Test
    @DisplayName("wantsThoughts() returns true only when explicitly set to true")
    void wantsThoughtsExplicit() {
        assertThat(CompletionRequest.builder().includeThoughts(true).build().wantsThoughts()).isTrue();
        assertThat(CompletionRequest.builder().includeThoughts(false).build().wantsThoughts()).isFalse();
    }
}
