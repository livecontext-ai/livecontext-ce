package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.apimarketplace.agent.resolver.LlmCredentialResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
            // Mutable: the streaming paths put "stream": true into the body they are handed.
            return new HashMap<>(Map.of("model", request.model() != null ? request.model() : "test-model-1"));
        }

        @Override
        protected CompletionResponse parseResponse(Map<String, Object> response) {
            return CompletionResponse.text("response");
        }

        @Override
        protected HttpHeaders buildHeaders(CompletionRequest request) {
            HttpHeaders headers = new HttpHeaders();
            // Mirrors every real provider: the key is resolved from the request, not the field.
            headers.set("Authorization", "Bearer " + resolveApiKey(request));
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

    /**
     * A credential resolver that records HOW it was asked. The two overloads mean two
     * different things: the 1-arg form reads the user off the calling thread's servlet
     * request (the pre-pin behaviour), the 2-arg form resolves for an explicit user, and
     * a null user there means "platform key only".
     */
    static class RecordingResolver implements LlmCredentialResolver {
        final List<String> twoArgUserIds = new ArrayList<>();
        final List<String> userOnlyUserIds = new ArrayList<>();
        int oneArgCalls = 0;
        final Map<String, String> userKeys = new HashMap<>();
        String platformKey;

        @Override
        public Optional<String> resolveApiKey(String providerName) {
            oneArgCalls++;
            return Optional.ofNullable(platformKey);
        }

        @Override
        public Optional<String> resolveApiKey(String userId, String providerName) {
            twoArgUserIds.add(userId);
            if (userId != null && userKeys.containsKey(userId)) {
                return Optional.of(userKeys.get(userId));
            }
            return Optional.ofNullable(platformKey);
        }

        /** The user's own key only: what an OWN_KEY-pinned call must resolve through. */
        @Override
        public Optional<String> resolveUserApiKey(String userId, String providerName) {
            userOnlyUserIds.add(userId);
            return Optional.ofNullable(userKeys.get(userId));
        }
    }

    /** A fake connection that records the request properties the streaming setup sets. */
    static class RecordingConnection extends java.net.HttpURLConnection {
        final Map<String, String> properties = new HashMap<>();

        RecordingConnection() throws java.net.MalformedURLException {
            super(new java.net.URL("http://localhost/stream"));
        }

        @Override
        public void setRequestProperty(String key, String value) {
            properties.put(key, value);
        }

        @Override public void connect() { }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
    }

    @Nested
    @DisplayName("resolveApiKey(CompletionRequest) - whose key serves a call")
    class KeyRouteResolutionTests {

        private RecordingResolver resolver;

        @BeforeEach
        void wireResolver() {
            resolver = new RecordingResolver();
            resolver.platformKey = "sk-platform";
            resolver.userKeys.put("tenant-9", "sk-user-9");
            provider.setCredentialResolver(resolver);
        }

        @Test
        @DisplayName("regression: off a queue worker (no servlet request bound) the request's tenant still resolves the user's own key")
        void queueWorkerPathResolvesTheRequestTenantsKey() {
            // No RequestContextHolder is bound in this test, exactly like a dequeued
            // agent execution. Before the fix the provider asked the 1-arg overload,
            // found no thread user, and silently served the platform key.
            CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-9")
                .model("test-model-1")
                .build();

            assertThat(provider.resolveApiKey(request)).isEqualTo("sk-user-9");
            assertThat(resolver.twoArgUserIds).containsExactly("tenant-9");
            assertThat(resolver.oneArgCalls).isZero();
        }

        @Test
        @DisplayName("a PLATFORM-pinned request skips the user credential even when the tenant holds a key")
        void platformPinSkipsTheUserCredential() {
            CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-9")
                .keyRoute(KeyRoute.PLATFORM)
                .model("test-model-1")
                .build();

            assertThat(provider.resolveApiKey(request)).isEqualTo("sk-platform");
            assertThat(resolver.twoArgUserIds).containsExactly((String) null);
            assertThat(resolver.oneArgCalls).isZero();
        }

        @Test
        @DisplayName("a request naming no tenant keeps the pre-pin behaviour (in-flight servlet user via the 1-arg overload)")
        void requestWithoutTenantKeepsLegacyThreadResolution() {
            CompletionRequest request = CompletionRequest.builder()
                .model("test-model-1")
                .build();

            assertThat(provider.resolveApiKey(request)).isEqualTo("sk-platform");
            assertThat(resolver.oneArgCalls).isEqualTo(1);
            assertThat(resolver.twoArgUserIds).isEmpty();
        }

        @Test
        @DisplayName("falls back to the env-injected key when the resolver has nothing for the tenant nor the platform")
        void fallsBackToEnvKeyWhenResolverMisses() {
            resolver.platformKey = null;
            resolver.userKeys.clear();
            CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-9")
                .model("test-model-1")
                .build();

            assertThat(provider.resolveApiKey(request)).isEqualTo("test-api-key");
        }

        @Test
        @DisplayName("isConfigured(request) is true when ONLY the tenant's own key exists, while the request-less check stays false")
        void configuredForTheTenantWhenOnlyTheirKeyExists() {
            TestLLMProvider noEnvKey = new TestLLMProvider(null);
            RecordingResolver userOnly = new RecordingResolver();
            userOnly.userKeys.put("tenant-9", "sk-user-9");
            noEnvKey.setCredentialResolver(userOnly);
            CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-9")
                .model("test-model-1")
                .build();

            assertThat(noEnvKey.isConfigured()).isFalse();
            assertThat(noEnvKey.isConfigured(request)).isTrue();
        }

        @Test
        @DisplayName("regression: complete() sends the request tenant's own key in the Authorization header, with no servlet request bound")
        void completeSendsTheTenantsOwnKeyWithoutAServletRequest() {
            // The pre-fix provider built its headers from the calling thread's user, which a
            // dequeued execution does not have: this call went out with the platform key.
            RestTemplate restTemplate = mock(RestTemplate.class);
            TestLLMProvider dequeued = new TestLLMProvider(null, restTemplate, new ObjectMapper());
            dequeued.setCredentialResolver(resolver);
            @SuppressWarnings("rawtypes")
            ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), sent.capture(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("choices", List.of())));

            CompletionResponse response = dequeued.complete(CompletionRequest.builder()
                .tenantId("tenant-9")
                .model("test-model-1")
                .build());

            assertThat(response).isNotNull();
            assertThat(sent.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer sk-user-9");
            assertThat(resolver.oneArgCalls).as("the calling thread was never consulted").isZero();
        }

        @Test
        @DisplayName("complete() proceeds for a tenant whose own key is the ONLY key (no env key, no platform key)")
        void completeProceedsOnTheTenantsKeyAlone() {
            RestTemplate restTemplate = mock(RestTemplate.class);
            TestLLMProvider noPlatformKey = new TestLLMProvider(null, restTemplate, new ObjectMapper());
            RecordingResolver userOnly = new RecordingResolver();
            userOnly.userKeys.put("tenant-9", "sk-user-9");
            noPlatformKey.setCredentialResolver(userOnly);
            @SuppressWarnings("rawtypes")
            ArgumentCaptor<HttpEntity> sent = ArgumentCaptor.forClass(HttpEntity.class);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), sent.capture(), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("choices", List.of())));

            noPlatformKey.complete(CompletionRequest.builder().tenantId("tenant-9").model("test-model-1").build());

            // The gate that used to say "Provider is not configured" for this tenant now lets
            // the call through on their key.
            assertThat(sent.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer sk-user-9");
        }

        @Test
        @DisplayName("an OWN_KEY pin with no usable user key fails closed: never the platform key, never the env key")
        void ownKeyPinNeverFallsBackToPlatform() {
            resolver.userKeys.clear();   // platformKey "sk-platform" and env key "test-api-key" both still exist
            CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-9")
                .keyRoute(KeyRoute.OWN_KEY)
                .model("test-model-1")
                .build();

            assertThatThrownBy(() -> provider.resolveApiKey(request))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("own test key")
                .hasMessageContaining("Save a key");
            assertThat(resolver.userOnlyUserIds).containsExactly("tenant-9");
            assertThat(resolver.twoArgUserIds).as("the user-first slot (which may hold the platform key) is never consulted").isEmpty();
            assertThatThrownBy(() -> provider.complete(request)).isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("own test key");
        }

        @Test
        @DisplayName("an OWN_KEY pin resolves through the user-only lookup, not the user-first one")
        void ownKeyPinUsesTheUserOnlyLookup() {
            CompletionRequest request = CompletionRequest.builder()
                .tenantId("tenant-9")
                .keyRoute(KeyRoute.OWN_KEY)
                .model("test-model-1")
                .build();

            assertThat(provider.resolveApiKey(request)).isEqualTo("sk-user-9");
            assertThat(resolver.userOnlyUserIds).containsExactly("tenant-9");
            assertThat(resolver.twoArgUserIds).isEmpty();
        }

        @Test
        @DisplayName("isConfiguredFor / configurationProblem answer per (tenant, route) without reading the calling thread")
        void configuredForAnswersPerTenantAndRoute() {
            assertThat(provider.isConfiguredFor("tenant-9", KeyRoute.OWN_KEY)).isTrue();
            assertThat(provider.isConfiguredFor("tenant-9", KeyRoute.PLATFORM)).isTrue();
            assertThat(provider.configurationProblem("tenant-9", KeyRoute.OWN_KEY)).isNull();

            resolver.userKeys.clear();
            assertThat(provider.isConfiguredFor("tenant-9", KeyRoute.OWN_KEY)).isFalse();
            assertThat(provider.configurationProblem("tenant-9", KeyRoute.OWN_KEY)).contains("own test key");
            assertThat(provider.isConfiguredFor("tenant-9", KeyRoute.PLATFORM)).isTrue();
            assertThat(provider.configurationProblem(null, KeyRoute.OWN_KEY)).contains("names no tenant");
            assertThat(resolver.oneArgCalls).isZero();
        }

        @Test
        @DisplayName("catalog discovery keeps the request-less resolution (the in-flight user, via the 1-arg overload)")
        void discoveryStaysRequestLess() {
            provider.discoveryHeaders();

            assertThat(resolver.oneArgCalls).isEqualTo(1);
            assertThat(resolver.twoArgUserIds).isEmpty();
            assertThat(resolver.userOnlyUserIds).isEmpty();
        }

        @Test
        @DisplayName("streamReactive builds its headers from the request tenant before any subscription")
        void streamReactiveHeadersAreRequestBound() {
            provider.streamReactive(CompletionRequest.builder()
                .tenantId("tenant-9")
                .model("test-model-1")
                .build());

            assertThat(resolver.twoArgUserIds).isNotEmpty().allMatch("tenant-9"::equals);
            assertThat(resolver.oneArgCalls).isZero();
        }

        @Test
        @DisplayName("the streaming connection carries the request tenant's own key")
        void streamingConnectionCarriesTheTenantsKey() throws Exception {
            RecordingConnection connection = new RecordingConnection();

            provider.setupStreamingConnection(connection, CompletionRequest.builder()
                .tenantId("tenant-9")
                .model("test-model-1")
                .build());

            assertThat(connection.properties).containsEntry("Authorization", "Bearer sk-user-9");
            assertThat(resolver.oneArgCalls).isZero();
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
        @DisplayName("should tell the model an array of objects holds objects, not strings")
        void arrayItemsFollowWhatTheParameterDeclares() {
            // This schema is what a strict provider validates the call against, so a parameter
            // that carries objects while its schema says strings can have its entries refused or
            // stringified before the tool is ever reached - and the description telling the agent
            // to send objects cannot override it. Undeclared stays "string", which is what every
            // array parameter emitted before item types existed.
            ToolParameter objects = ToolParameter.builder()
                    .name("rows").type("array").description("Rows").required(false)
                    .itemType("object").build();
            ToolParameter strings = ToolParameter.builder()
                    .name("tags").type("array").description("Tags").required(false).build();

            Map<String, Object> schema = provider.buildParametersSchema(ToolDefinition.builder()
                    .name("t").description("T").parameters(List.of(objects, strings)).build());

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> rows = (Map<String, Object>) props.get("rows");
            @SuppressWarnings("unchecked")
            Map<String, Object> tags = (Map<String, Object>) props.get("tags");
            assertThat(rows.get("items")).isEqualTo(Map.of("type", "object"));
            assertThat(tags.get("items")).isEqualTo(Map.of("type", "string"));
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
