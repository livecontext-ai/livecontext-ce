package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.UsageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for OpenAICompatibleProvider streaming and parsing methods.
 * Verifies parity with OpenAIProvider for all OpenAI-format operations.
 */
@DisplayName("OpenAICompatibleProvider")
class OpenAICompatibleProviderTest {

    private OpenAICompatibleProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OpenAICompatibleProvider(
                "test-provider", "https://api.example.com/v1/chat/completions",
                "test-key", List.of("model-a", "model-b"), 10);
    }

    @Nested
    @DisplayName("processStreamingLine()")
    class ProcessStreamingLineTests {

        @Test
        @DisplayName("extracts content from SSE data line")
        void extractsContentFromSse() {
            String line = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}";
            assertThat(provider.processStreamingLine(line)).isEqualTo("Hello");
        }

        @Test
        @DisplayName("extracts content from raw JSON line")
        void extractsContentFromRawJson() {
            String line = "{\"choices\":[{\"delta\":{\"content\":\"World\"}}]}";
            assertThat(provider.processStreamingLine(line)).isEqualTo("World");
        }

        @Test
        @DisplayName("returns null for null JSON content (prevents 'null' string)")
        void returnsNullForJsonNull() {
            String line = "data: {\"choices\":[{\"delta\":{\"content\":null}}]}";
            assertThat(provider.processStreamingLine(line)).isNull();
        }

        @Test
        @DisplayName("returns null for [DONE] marker")
        void returnsNullForDone() {
            assertThat(provider.processStreamingLine("data: [DONE]")).isNull();
        }

        @Test
        @DisplayName("returns null for non-data lines")
        void returnsNullForNonData() {
            assertThat(provider.processStreamingLine(": keep-alive")).isNull();
            assertThat(provider.processStreamingLine("")).isNull();
        }

        @Test
        @DisplayName("returns null for delta without content (tool-call-only chunk)")
        void returnsNullForToolCallOnlyDelta() {
            String line = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0}]}}]}";
            assertThat(provider.processStreamingLine(line)).isNull();
        }
    }

    @Nested
    @DisplayName("accumulateStreamingToolCalls()")
    class AccumulateToolCallsTests {

        @Test
        @DisplayName("accumulates multi-chunk tool calls by index")
        void accumulatesMultiChunk() {
            Map<Integer, AbstractLLMProvider.StreamingToolCallAccumulator> accumulators = new HashMap<>();

            // First chunk: id + function name
            String chunk1 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\",\"function\":{\"name\":\"web_search\",\"arguments\":\"\"}}]}}]}";
            provider.accumulateStreamingToolCalls(chunk1, accumulators);

            // Second chunk: argument fragment
            String chunk2 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"query\\\"\"}}]}}]}";
            provider.accumulateStreamingToolCalls(chunk2, accumulators);

            // Third chunk: more arguments
            String chunk3 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\": \\\"test\\\"}\"}}]}}]}";
            provider.accumulateStreamingToolCalls(chunk3, accumulators);

            assertThat(accumulators).hasSize(1);
            AbstractLLMProvider.StreamingToolCallAccumulator acc = accumulators.get(0);
            assertThat(acc.id).isEqualTo("call_123");
            assertThat(acc.name).isEqualTo("web_search");
            assertThat(acc.arguments.toString()).isEqualTo("{\"query\": \"test\"}");
        }

        @Test
        @DisplayName("accumulates parallel tool calls with different indices")
        void accumulatesParallelToolCalls() {
            Map<Integer, AbstractLLMProvider.StreamingToolCallAccumulator> accumulators = new HashMap<>();

            String chunk = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[" +
                    "{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"tool_a\",\"arguments\":\"{}\"}}," +
                    "{\"index\":1,\"id\":\"call_2\",\"function\":{\"name\":\"tool_b\",\"arguments\":\"{}\"}}" +
                    "]}}]}";
            provider.accumulateStreamingToolCalls(chunk, accumulators);

            assertThat(accumulators).hasSize(2);
            assertThat(accumulators.get(0).name).isEqualTo("tool_a");
            assertThat(accumulators.get(1).name).isEqualTo("tool_b");
        }

        @Test
        @DisplayName("handles raw JSON format (reactive streaming)")
        void handlesRawJson() {
            Map<Integer, AbstractLLMProvider.StreamingToolCallAccumulator> accumulators = new HashMap<>();

            String raw = "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_x\",\"function\":{\"name\":\"fn\",\"arguments\":\"{}\"}}]}}]}";
            provider.accumulateStreamingToolCalls(raw, accumulators);

            assertThat(accumulators).hasSize(1);
            assertThat(accumulators.get(0).name).isEqualTo("fn");
        }

        @Test
        @DisplayName("ignores [DONE] and non-data lines")
        void ignoresNonDataLines() {
            Map<Integer, AbstractLLMProvider.StreamingToolCallAccumulator> accumulators = new HashMap<>();
            provider.accumulateStreamingToolCalls("data: [DONE]", accumulators);
            provider.accumulateStreamingToolCalls(": keep-alive", accumulators);
            assertThat(accumulators).isEmpty();
        }
    }

    @Nested
    @DisplayName("extractStreamingUsage()")
    class ExtractStreamingUsageTests {

        @Test
        @DisplayName("extracts basic usage from SSE line")
        void extractsBasicUsage() {
            String line = "data: {\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":50,\"total_tokens\":150}}";
            UsageInfo usage = provider.extractStreamingUsage(line);

            assertThat(usage).isNotNull();
            assertThat(usage.promptTokens()).isEqualTo(100);
            assertThat(usage.completionTokens()).isEqualTo(50);
            assertThat(usage.totalTokens()).isEqualTo(150);
        }

        @Test
        @DisplayName("extracts cached_tokens and reasoning_tokens from details")
        void extractsDetailedUsage() {
            String line = "data: {\"usage\":{\"prompt_tokens\":200,\"completion_tokens\":80,\"total_tokens\":280," +
                    "\"prompt_tokens_details\":{\"cached_tokens\":50}," +
                    "\"completion_tokens_details\":{\"reasoning_tokens\":30}}}";
            UsageInfo usage = provider.extractStreamingUsage(line);

            assertThat(usage).isNotNull();
            assertThat(usage.cachedTokens()).isEqualTo(50);
            assertThat(usage.reasoningTokens()).isEqualTo(30);
        }

        @Test
        @DisplayName("handles missing details gracefully")
        void handlesMissingDetails() {
            String line = "data: {\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
            UsageInfo usage = provider.extractStreamingUsage(line);

            assertThat(usage).isNotNull();
            assertThat(usage.promptTokens()).isEqualTo(10);
            // cachedTokens/reasoningTokens should be null when details are absent
        }

        @Test
        @DisplayName("extracts usage from raw JSON")
        void extractsFromRawJson() {
            String line = "{\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":8,\"total_tokens\":50}}";
            UsageInfo usage = provider.extractStreamingUsage(line);

            assertThat(usage).isNotNull();
            assertThat(usage.promptTokens()).isEqualTo(42);
        }

        @Test
        @DisplayName("returns null when no usage present")
        void returnsNullNoUsage() {
            String line = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}";
            assertThat(provider.extractStreamingUsage(line)).isNull();
        }

        @Test
        @DisplayName("returns null for [DONE]")
        void returnsNullForDone() {
            assertThat(provider.extractStreamingUsage("data: [DONE]")).isNull();
        }
    }

    @Nested
    @DisplayName("parseUsageInfo()")
    class ParseUsageInfoTests {

        @Test
        @DisplayName("parses basic usage fields")
        void parsesBasicUsage() {
            Map<String, Object> usage = Map.of(
                    "prompt_tokens", 100,
                    "completion_tokens", 50,
                    "total_tokens", 150);

            UsageInfo result = provider.parseUsageInfo(usage);
            assertThat(result).isNotNull();
            assertThat(result.promptTokens()).isEqualTo(100);
            assertThat(result.completionTokens()).isEqualTo(50);
            assertThat(result.totalTokens()).isEqualTo(150);
        }

        @Test
        @DisplayName("parses cached_tokens from prompt_tokens_details")
        void parsesCachedTokens() {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", 200);
            usage.put("completion_tokens", 80);
            usage.put("total_tokens", 280);
            usage.put("prompt_tokens_details", Map.of("cached_tokens", 60));

            UsageInfo result = provider.parseUsageInfo(usage);
            assertThat(result.cachedTokens()).isEqualTo(60);
        }

        @Test
        @DisplayName("parses reasoning_tokens from completion_tokens_details")
        void parsesReasoningTokens() {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("prompt_tokens", 100);
            usage.put("completion_tokens", 90);
            usage.put("total_tokens", 190);
            usage.put("completion_tokens_details", Map.of("reasoning_tokens", 40));

            UsageInfo result = provider.parseUsageInfo(usage);
            assertThat(result.reasoningTokens()).isEqualTo(40);
        }

        @Test
        @DisplayName("returns null for null usage")
        void returnsNullForNull() {
            assertThat(provider.parseUsageInfo(null)).isNull();
        }

        @Test
        @DisplayName("handles missing details without error")
        void handlesMissingDetails() {
            Map<String, Object> usage = Map.of(
                    "prompt_tokens", 10,
                    "completion_tokens", 5,
                    "total_tokens", 15);

            UsageInfo result = provider.parseUsageInfo(usage);
            assertThat(result).isNotNull();
            assertThat(result.promptTokens()).isEqualTo(10);
        }
    }
}
