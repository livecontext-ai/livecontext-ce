package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.UsageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the 2026-06-11 cache-aware billing fix: DeepSeek reports
 * cache hits as the top-level {@code prompt_cache_hit_tokens} counter (a subset of
 * {@code prompt_tokens}, sold at ~0.1x of the miss price). Both the non-streaming
 * parse (inherited {@code parseUsageInfo}) and the streaming parse must surface it
 * in the generic {@code cachedTokens} field that flows to billing - pre-fix the
 * field was dropped and cache hits were billed at full input rate.
 */
@DisplayName("DeepSeekProvider - usage parsing (cache hits)")
class DeepSeekProviderUsageTest {

    private final DeepSeekProvider provider = new DeepSeekProvider();

    @Test
    @DisplayName("non-streaming parse surfaces prompt_cache_hit_tokens as cachedTokens")
    void parseUsageInfoSurfacesCacheHits() {
        UsageInfo usage = provider.parseUsageInfo(Map.of(
                "prompt_tokens", 10000,
                "completion_tokens", 100,
                "total_tokens", 10100,
                "prompt_cache_hit_tokens", 9000,
                "prompt_cache_miss_tokens", 1000));

        assertThat(usage).isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(10000);
        assertThat(usage.completionTokens()).isEqualTo(100);
        assertThat(usage.cachedTokens()).isEqualTo(9000);
    }

    @Test
    @DisplayName("non-streaming parse prefers prompt_tokens_details.cached_tokens (OpenAI-compatible shape)")
    void parseUsageInfoPrefersOpenAiDetailsShape() {
        UsageInfo usage = provider.parseUsageInfo(Map.of(
                "prompt_tokens", 5000,
                "completion_tokens", 50,
                "prompt_tokens_details", Map.of("cached_tokens", 4000)));

        assertThat(usage).isNotNull();
        assertThat(usage.cachedTokens()).isEqualTo(4000);
    }

    @Test
    @DisplayName("non-streaming parse leaves cachedTokens null when no cache counter is present")
    void parseUsageInfoNullWithoutCacheCounters() {
        UsageInfo usage = provider.parseUsageInfo(Map.of(
                "prompt_tokens", 100,
                "completion_tokens", 10));

        assertThat(usage).isNotNull();
        assertThat(usage.cachedTokens()).isNull();
    }

    @Test
    @DisplayName("streaming usage chunk surfaces prompt_cache_hit_tokens as cachedTokens")
    void streamingUsageSurfacesCacheHits() {
        String sseLine = "data: {\"usage\":{" +
                "\"prompt_tokens\":10000," +
                "\"completion_tokens\":100," +
                "\"total_tokens\":10100," +
                "\"prompt_cache_hit_tokens\":9000}}";

        UsageInfo usage = provider.extractStreamingUsage(sseLine);

        assertThat(usage).isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(10000);
        assertThat(usage.cachedTokens()).isEqualTo(9000);
    }

    @Test
    @DisplayName("streaming usage chunk leaves cachedTokens null when the counter is absent")
    void streamingUsageNullWithoutCacheCounter() {
        String sseLine = "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10,\"total_tokens\":110}}";

        UsageInfo usage = provider.extractStreamingUsage(sseLine);

        assertThat(usage).isNotNull();
        assertThat(usage.cachedTokens()).isNull();
    }
}
