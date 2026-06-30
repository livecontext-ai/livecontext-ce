package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for AbstractLLMProvider - abstract base class for LLM providers.
 * Uses a concrete test implementation to test shared behavior.
 */
@DisplayName("AbstractLLMProvider")
class AbstractLLMProviderTest {

    private TestLLMProvider provider;

    /**
     * Minimal concrete implementation of AbstractLLMProvider for testing.
     */
    static class TestLLMProvider extends AbstractLLMProvider {
        private String apiKey;
        private String apiUrl = "https://api.test.com/v1/chat/completions";
        private String providerName = "test";
        private List<String> supportedModels = List.of("test-model-1", "test-model-2");

        TestLLMProvider(String apiKey) {
            super();
            this.apiKey = apiKey;
        }

        TestLLMProvider(String apiKey, RestTemplate restTemplate, ObjectMapper objectMapper) {
            super(restTemplate, objectMapper);
            this.apiKey = apiKey;
        }

        @Override
        protected String getApiKey() { return apiKey; }

        @Override
        protected String getApiUrl() { return apiUrl; }

        @Override
        protected Map<String, Object> buildRequestBody(CompletionRequest request) {
            return Map.of("model", request.model() != null ? request.model() : "test-model-1");
        }

        @Override
        protected CompletionResponse parseResponse(Map<String, Object> response) {
            return CompletionResponse.text("response");
        }

        @Override
        protected HttpHeaders buildHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");
            return headers;
        }

        @Override
        protected String processStreamingLine(String line) {
            if (line.startsWith("data: ") && !line.equals("data: [DONE]")) {
                return line.substring(6);
            }
            return null;
        }

        @Override
        public String getProviderName() { return providerName; }

        @Override
        public String getDefaultModel() { return "test-model-1"; }

        @Override
        public List<String> getSupportedModels() { return supportedModels; }
    }

    @BeforeEach
    void setUp() {
        provider = new TestLLMProvider("test-api-key");
    }

    @Nested
    @DisplayName("isConfigured()")
    class IsConfiguredTests {

        @Test
        @DisplayName("should return true when API key is set")
        void shouldReturnTrueWhenKeySet() {
            assertThat(provider.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("should return false when API key is null")
        void shouldReturnFalseWhenKeyNull() {
            TestLLMProvider unconfigured = new TestLLMProvider(null);
            assertThat(unconfigured.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("should return false when API key is empty")
        void shouldReturnFalseWhenKeyEmpty() {
            TestLLMProvider unconfigured = new TestLLMProvider("");
            assertThat(unconfigured.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("should return false when API key is blank")
        void shouldReturnFalseWhenKeyBlank() {
            TestLLMProvider unconfigured = new TestLLMProvider("   ");
            assertThat(unconfigured.isConfigured()).isFalse();
        }
    }

    @Nested
    @DisplayName("supportsStreaming() and supportsToolCalling()")
    class CapabilityTests {

        @Test
        @DisplayName("should support streaming by default")
        void shouldSupportStreaming() {
            assertThat(provider.supportsStreaming()).isTrue();
        }

        @Test
        @DisplayName("should support tool calling by default")
        void shouldSupportToolCalling() {
            assertThat(provider.supportsToolCalling()).isTrue();
        }
    }

    @Nested
    @DisplayName("supportsModel()")
    class SupportsModelTests {

        @Test
        @DisplayName("should return true for supported model")
        void shouldSupportKnownModel() {
            assertThat(provider.supportsModel("test-model-1")).isTrue();
            assertThat(provider.supportsModel("test-model-2")).isTrue();
        }

        @Test
        @DisplayName("should return false for unsupported model")
        void shouldNotSupportUnknownModel() {
            assertThat(provider.supportsModel("unknown-model")).isFalse();
        }
    }

    @Nested
    @DisplayName("getDisplayOrder()")
    class DisplayOrderTests {

        @Test
        @DisplayName("should return 100 by default")
        void shouldReturnDefaultOrder() {
            assertThat(provider.getDisplayOrder()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("complete()")
    class CompleteTests {

        @Test
        @DisplayName("should throw when not configured")
        void shouldThrowWhenNotConfigured() {
            TestLLMProvider unconfigured = new TestLLMProvider(null);
            CompletionRequest request = CompletionRequest.simple("Hello");

            assertThatThrownBy(() -> unconfigured.complete(request))
                    .isInstanceOf(LLMProviderException.class)
                    .hasMessageContaining("not configured");
        }
    }

    @Nested
    @DisplayName("isEndOfStream()")
    class IsEndOfStreamTests {

        @Test
        @DisplayName("should detect data: [DONE]")
        void shouldDetectDone() {
            assertThat(provider.isEndOfStream("data: [DONE]")).isTrue();
        }

        @Test
        @DisplayName("should detect lines containing [DONE]")
        void shouldDetectContainingDone() {
            assertThat(provider.isEndOfStream("something [DONE] here")).isTrue();
        }

        @Test
        @DisplayName("should not detect regular data lines")
        void shouldNotDetectRegularLines() {
            assertThat(provider.isEndOfStream("data: {\"content\": \"hello\"}")).isFalse();
        }
    }

    @Nested
    @DisplayName("extractErrorMessage()")
    class ExtractErrorMessageTests {

        @Test
        @DisplayName("should extract message from error JSON")
        void shouldExtractFromErrorJson() {
            String body = "{\"error\": {\"message\": \"Rate limit exceeded\"}}";
            assertThat(provider.extractErrorMessage(body)).isEqualTo("Rate limit exceeded");
        }

        @Test
        @DisplayName("should extract text from simple error field")
        void shouldExtractSimpleError() {
            String body = "{\"error\": \"Something went wrong\"}";
            assertThat(provider.extractErrorMessage(body)).isEqualTo("Something went wrong");
        }

        @Test
        @DisplayName("should return body when not valid JSON")
        void shouldReturnBodyForNonJson() {
            String body = "Not JSON content";
            assertThat(provider.extractErrorMessage(body)).isEqualTo("Not JSON content");
        }
    }

    @Nested
    @DisplayName("convertMessage()")
    class ConvertMessageTests {

        @Test
        @DisplayName("should convert system message")
        void shouldConvertSystemMessage() {
            Message msg = Message.system("You are a helper");
            Map<String, Object> result = provider.convertMessage(msg);

            assertThat(result.get("role")).isEqualTo("system");
            assertThat(result.get("content")).isEqualTo("You are a helper");
        }

        @Test
        @DisplayName("should convert user message")
        void shouldConvertUserMessage() {
            Message msg = Message.user("Hello");
            Map<String, Object> result = provider.convertMessage(msg);

            assertThat(result.get("role")).isEqualTo("user");
            assertThat(result.get("content")).isEqualTo("Hello");
        }

        @Test
        @DisplayName("should convert assistant message")
        void shouldConvertAssistantMessage() {
            Message msg = Message.assistant("Hi there");
            Map<String, Object> result = provider.convertMessage(msg);

            assertThat(result.get("role")).isEqualTo("assistant");
            assertThat(result.get("content")).isEqualTo("Hi there");
        }

        @Test
        @DisplayName("should convert assistant message with tool calls")
        void shouldConvertAssistantWithToolCalls() {
            ToolCall tc = ToolCall.builder()
                    .id("tc-1")
                    .toolName("search")
                    .arguments(Map.of("query", "test"))
                    .build();
            Message msg = Message.assistantWithToolCalls("", List.of(tc));
            Map<String, Object> result = provider.convertMessage(msg);

            assertThat(result.get("role")).isEqualTo("assistant");
            assertThat(result).containsKey("tool_calls");
        }

        @Test
        @DisplayName("should convert tool result message")
        void shouldConvertToolMessage() {
            Message msg = Message.toolResult("tc-1", "search", "search result");
            Map<String, Object> result = provider.convertMessage(msg);

            assertThat(result.get("role")).isEqualTo("tool");
            assertThat(result.get("content")).isEqualTo("search result");
            assertThat(result.get("tool_call_id")).isEqualTo("tc-1");
        }

        @Test
        @DisplayName("should return null for null message")
        void shouldReturnNullForNull() {
            assertThat(provider.convertMessage(null)).isNull();
        }
    }

    @Nested
    @DisplayName("buildMessages()")
    class BuildMessagesTests {

        @Test
        @DisplayName("should include system prompt as first message")
        void shouldIncludeSystemPrompt() {
            CompletionRequest request = CompletionRequest.builder()
                    .systemPrompt("You are a helper")
                    .userPrompt("Hello")
                    .build();

            List<Map<String, Object>> messages = provider.buildMessages(request);

            assertThat(messages).hasSizeGreaterThanOrEqualTo(2);
            assertThat(messages.get(0).get("role")).isEqualTo("system");
            assertThat(messages.get(0).get("content")).isEqualTo("You are a helper");
        }

        @Test
        @DisplayName("should add user message at the end")
        void shouldAddUserMessage() {
            CompletionRequest request = CompletionRequest.simple("What is 2+2?");

            List<Map<String, Object>> messages = provider.buildMessages(request);

            Map<String, Object> lastMessage = messages.get(messages.size() - 1);
            assertThat(lastMessage.get("role")).isEqualTo("user");
            assertThat(lastMessage.get("content")).isEqualTo("What is 2+2?");
        }

        @Test
        @DisplayName("should add default user message when none present")
        void shouldAddDefaultUserMessage() {
            CompletionRequest request = CompletionRequest.builder().build();

            List<Map<String, Object>> messages = provider.buildMessages(request);

            assertThat(messages).isNotEmpty();
            assertThat(messages.stream().anyMatch(m -> "user".equals(m.get("role")))).isTrue();
        }
    }

    @Nested
    @DisplayName("buildOpenAITools()")
    class BuildOpenAIToolsTests {

        @Test
        @DisplayName("should return null for null tools")
        void shouldReturnNullForNull() {
            assertThat(provider.buildOpenAITools(null)).isNull();
        }

        @Test
        @DisplayName("should return null for empty tools")
        void shouldReturnNullForEmpty() {
            assertThat(provider.buildOpenAITools(List.of())).isNull();
        }

        @Test
        @DisplayName("should build OpenAI format tools")
        void shouldBuildOpenAITools() {
            ToolParameter param = ToolParameter.builder()
                    .name("query").type("string").description("Search query").required(true).build();
            ToolDefinition tool = ToolDefinition.builder()
                    .name("search")
                    .description("Search for items")
                    .parameters(List.of(param))
                    .build();

            List<Map<String, Object>> result = provider.buildOpenAITools(List.of(tool));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).get("type")).isEqualTo("function");

            @SuppressWarnings("unchecked")
            Map<String, Object> function = (Map<String, Object>) result.get(0).get("function");
            assertThat(function.get("name")).isEqualTo("search");
            assertThat(function.get("description")).isEqualTo("Search for items");
            assertThat(function).containsKey("parameters");
        }
    }

    @Nested
    @DisplayName("buildParametersSchema()")
    class BuildParametersSchemaTests {

        @Test
        @DisplayName("should build schema with properties and required")
        void shouldBuildSchema() {
            ToolParameter required = ToolParameter.builder()
                    .name("query").type("string").description("Query").required(true).build();
            ToolParameter optional = ToolParameter.builder()
                    .name("limit").type("integer").description("Limit").required(false).build();

            ToolDefinition tool = ToolDefinition.builder()
                    .name("search")
                    .description("Search")
                    .parameters(List.of(required, optional))
                    .build();

            Map<String, Object> schema = provider.buildParametersSchema(tool);

            assertThat(schema.get("type")).isEqualTo("object");

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertThat(props).containsKeys("query", "limit");

            @SuppressWarnings("unchecked")
            List<String> requiredList = (List<String>) schema.get("required");
            assertThat(requiredList).containsExactly("query");
        }

        @Test
        @DisplayName("should build empty properties for no parameters")
        void shouldBuildEmptyForNoParams() {
            ToolDefinition tool = ToolDefinition.builder()
                    .name("test").description("Test").build();

            Map<String, Object> schema = provider.buildParametersSchema(tool);

            assertThat(schema.get("type")).isEqualTo("object");
            assertThat(schema.get("properties")).isEqualTo(Map.of());
        }

        @Test
        @DisplayName("should add items schema for array parameters")
        void shouldAddItemsForArray() {
            ToolParameter arrayParam = ToolParameter.builder()
                    .name("tags").type("array").description("Tags").build();

            ToolDefinition tool = ToolDefinition.builder()
                    .name("test").description("Test")
                    .parameters(List.of(arrayParam))
                    .build();

            Map<String, Object> schema = provider.buildParametersSchema(tool);

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> tagsProp = (Map<String, Object>) props.get("tags");
            assertThat(tagsProp).containsKey("items");
        }
    }

    @Nested
    @DisplayName("parseOpenAIToolCalls()")
    class ParseOpenAIToolCallsTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(provider.parseOpenAIToolCalls(null)).isNull();
        }

        @Test
        @DisplayName("should return null for empty input")
        void shouldReturnNullForEmpty() {
            assertThat(provider.parseOpenAIToolCalls(List.of())).isNull();
        }

        @Test
        @DisplayName("should parse OpenAI tool call format")
        void shouldParseToolCall() {
            Map<String, Object> toolCall = Map.of(
                    "id", "call_123",
                    "type", "function",
                    "function", Map.of(
                            "name", "search",
                            "arguments", "{\"query\": \"test\"}"
                    )
            );

            List<ToolCall> result = provider.parseOpenAIToolCalls(List.of(toolCall));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).id()).isEqualTo("call_123");
            assertThat(result.get(0).toolName()).isEqualTo("search");
            assertThat(result.get(0).arguments()).containsEntry("query", "test");
        }

        @Test
        @DisplayName("should handle invalid JSON arguments gracefully")
        void shouldHandleInvalidJsonArgs() {
            Map<String, Object> toolCall = Map.of(
                    "id", "call_123",
                    "function", Map.of(
                            "name", "search",
                            "arguments", "not-json"
                    )
            );

            List<ToolCall> result = provider.parseOpenAIToolCalls(List.of(toolCall));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).arguments()).isEmpty(); // Fallback to empty
        }
    }

    @Nested
    @DisplayName("parseUsageInfo()")
    class ParseUsageInfoTests {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            assertThat(provider.parseUsageInfo(null)).isNull();
        }

        @Test
        @DisplayName("should parse usage info from map")
        void shouldParseUsageInfo() {
            Map<String, Object> usage = Map.of(
                    "prompt_tokens", 100,
                    "completion_tokens", 50,
                    "total_tokens", 150
            );

            UsageInfo result = provider.parseUsageInfo(usage);

            assertThat(result.promptTokens()).isEqualTo(100);
            assertThat(result.completionTokens()).isEqualTo(50);
            assertThat(result.totalTokens()).isEqualTo(150);
        }
    }

    @Nested
    @DisplayName("estimateTokens()")
    class EstimateTokensTests {

        @Test
        @DisplayName("should estimate tokens from prompt lengths")
        void shouldEstimateFromPrompts() {
            CompletionRequest request = CompletionRequest.builder()
                    .systemPrompt("You are a helper") // 16 chars
                    .userPrompt("Hello world") // 11 chars
                    .maxTokens(100)
                    .build();

            int estimate = provider.estimateTokens(request);

            // (16 + 11) / 4 + 100 = 6 + 100 = ~106
            assertThat(estimate).isGreaterThan(0);
        }

        @Test
        @DisplayName("should use default 500 max tokens when not specified")
        void shouldUseDefaultMaxTokens() {
            CompletionRequest request = CompletionRequest.builder()
                    .userPrompt("Hello")
                    .build();

            int estimate = provider.estimateTokens(request);

            // 5/4 + 500 = 501
            assertThat(estimate).isGreaterThanOrEqualTo(500);
        }

        @Test
        @DisplayName("should include tool definition estimates")
        void shouldIncludeToolEstimates() {
            ToolDefinition tool = ToolDefinition.builder()
                    .name("search").description("Search API").build();

            CompletionRequest requestWithTools = CompletionRequest.builder()
                    .userPrompt("Hello")
                    .tools(List.of(tool))
                    .maxTokens(100)
                    .build();

            CompletionRequest requestWithout = CompletionRequest.builder()
                    .userPrompt("Hello")
                    .maxTokens(100)
                    .build();

            int withTools = provider.estimateTokens(requestWithTools);
            int withoutTools = provider.estimateTokens(requestWithout);

            assertThat(withTools).isGreaterThan(withoutTools);
        }
    }

    @Nested
    @DisplayName("Helper methods")
    class HelperMethodTests {

        @Test
        @DisplayName("getIntValue should extract integer from Number")
        void shouldExtractInt() {
            assertThat(provider.getIntValue(Map.of("key", 42), "key")).isEqualTo(42);
        }

        @Test
        @DisplayName("getIntValue should return null for missing key")
        void shouldReturnNullForMissingKey() {
            assertThat(provider.getIntValue(Map.of(), "key")).isNull();
        }

        @Test
        @DisplayName("getDoubleValue should extract double from Number")
        void shouldExtractDouble() {
            assertThat(provider.getDoubleValue(Map.of("key", 3.14), "key")).isEqualTo(3.14);
        }

        @Test
        @DisplayName("getDoubleValue should return null for missing key")
        void shouldReturnNullDoubleForMissingKey() {
            assertThat(provider.getDoubleValue(Map.of(), "key")).isNull();
        }
    }

    @Nested
    @DisplayName("StreamingToolCallAccumulator")
    class StreamingToolCallAccumulatorTests {

        @Test
        @DisplayName("isComplete should return false without id or name")
        void shouldNotBeCompleteWithoutIdOrName() {
            AbstractLLMProvider.StreamingToolCallAccumulator acc = new AbstractLLMProvider.StreamingToolCallAccumulator();
            assertThat(acc.isComplete()).isFalse();

            acc.id = "123";
            assertThat(acc.isComplete()).isFalse();

            acc.id = null;
            acc.name = "search";
            assertThat(acc.isComplete()).isFalse();
        }

        @Test
        @DisplayName("isComplete should return true with both id and name")
        void shouldBeCompleteWithIdAndName() {
            AbstractLLMProvider.StreamingToolCallAccumulator acc = new AbstractLLMProvider.StreamingToolCallAccumulator();
            acc.id = "123";
            acc.name = "search";
            assertThat(acc.isComplete()).isTrue();
        }

        @Test
        @DisplayName("build should return ToolCall with parsed arguments")
        void shouldBuildToolCall() {
            ObjectMapper mapper = new ObjectMapper();
            AbstractLLMProvider.StreamingToolCallAccumulator acc = new AbstractLLMProvider.StreamingToolCallAccumulator();
            acc.id = "call_123";
            acc.name = "search";
            acc.arguments.append("{\"query\": \"test\"}");

            ToolCall result = acc.build(mapper);

            assertThat(result).isNotNull();
            assertThat(result.id()).isEqualTo("call_123");
            assertThat(result.toolName()).isEqualTo("search");
            assertThat(result.arguments()).containsEntry("query", "test");
        }

        @Test
        @DisplayName("build should return null when not complete")
        void shouldReturnNullWhenNotComplete() {
            ObjectMapper mapper = new ObjectMapper();
            AbstractLLMProvider.StreamingToolCallAccumulator acc = new AbstractLLMProvider.StreamingToolCallAccumulator();

            assertThat(acc.build(mapper)).isNull();
        }

        @Test
        @DisplayName("build should use empty args for invalid JSON")
        void shouldUseEmptyArgsForInvalidJson() {
            ObjectMapper mapper = new ObjectMapper();
            AbstractLLMProvider.StreamingToolCallAccumulator acc = new AbstractLLMProvider.StreamingToolCallAccumulator();
            acc.id = "call_123";
            acc.name = "search";
            acc.arguments.append("not-valid-json");

            ToolCall result = acc.build(mapper);

            assertThat(result).isNotNull();
            assertThat(result.arguments()).isEmpty();
        }

        @Test
        @DisplayName("build should use empty object for empty arguments")
        void shouldUseEmptyObjectForEmptyArgs() {
            ObjectMapper mapper = new ObjectMapper();
            AbstractLLMProvider.StreamingToolCallAccumulator acc = new AbstractLLMProvider.StreamingToolCallAccumulator();
            acc.id = "call_123";
            acc.name = "search";

            ToolCall result = acc.build(mapper);

            assertThat(result).isNotNull();
            assertThat(result.arguments()).isEmpty();
        }
    }
}
