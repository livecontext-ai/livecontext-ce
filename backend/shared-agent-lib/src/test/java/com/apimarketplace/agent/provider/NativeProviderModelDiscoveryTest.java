package com.apimarketplace.agent.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Model discovery for the vendors that have their OWN provider class.
 *
 * <p>These were invisible to the catalog refresh until discovery stopped being
 * gated on {@code instanceof OpenAICompatibleProvider}. DeepSeek, Mistral and
 * OpenAI speak the OpenAI dialect and only ever needed the inherited behaviour;
 * Anthropic and Google authenticate and answer differently, so they join
 * through the three hooks rather than being excluded by default.
 *
 * <p>The concrete case behind this: DeepSeek retired V4 Flash for V4.1 Flash
 * under the id {@code deepseek-flash}, no aggregator had published that id, and
 * no refresh could surface it because the vendor was never asked.
 */
@DisplayName("Native-SDK providers - vendor /models discovery")
class NativeProviderModelDiscoveryTest {

    /**
     * Captures WARN lines from one logger for the duration of a block. Needed
     * because on the truncation path the log line IS the behaviour under test:
     * asserting only that the ids pass through would stay green with the whole
     * warning branch deleted.
     */
    private static final class LogCapture implements AutoCloseable {
        private final ch.qos.logback.classic.Logger logger;
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();

        static LogCapture on(Class<?> type) {
            return new LogCapture(type);
        }

        private LogCapture(Class<?> type) {
            logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(type);
            appender.start();
            logger.addAppender(appender);
        }

        List<String> warnings() {
            return appender.list.stream()
                    .filter(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** Transport stub, so a contract assertion never depends on the network. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RestTemplate stubRestTemplate(Map<String, Object> body) {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(anyString(), eq(HttpMethod.GET), any(), eq(Map.class)))
                .thenReturn((ResponseEntity) ResponseEntity.ok(body));
        return rest;
    }

    // ── The OpenAI dialect: endpoint derived, nothing else needed ───────────

    @Test
    @DisplayName("DeepSeek derives its listing endpoint from the configured completions URL")
    void deepSeekDerivesItsModelsEndpoint() {
        DeepSeekProvider provider = new DeepSeekProvider();
        ReflectionTestUtils.setField(provider, "apiUrl", "https://api.deepseek.com/v1/chat/completions");

        String endpoint = provider.modelsEndpoint();
        assertThat(endpoint).isEqualTo("https://api.deepseek.com/v1/models");
    }

    @Test
    @DisplayName("Mistral and OpenAI derive theirs the same way - one mechanism, not three")
    void otherOpenAiDialectProvidersDeriveTheirs() {
        MistralProvider mistral = new MistralProvider();
        ReflectionTestUtils.setField(mistral, "apiUrl", "https://api.mistral.ai/v1/chat/completions");
        String mistralEndpoint = mistral.modelsEndpoint();
        assertThat(mistralEndpoint).isEqualTo("https://api.mistral.ai/v1/models");

        OpenAIProvider openai = new OpenAIProvider();
        ReflectionTestUtils.setField(openai, "apiUrl", "https://api.openai.com/v1/chat/completions");
        String openaiEndpoint = openai.modelsEndpoint();
        assertThat(openaiEndpoint).isEqualTo("https://api.openai.com/v1/models");
    }

    @Test
    @DisplayName("An unconfigured URL yields no endpoint, so the provider degrades to 'could not ask'")
    void noUrlMeansNoDiscovery() {
        DeepSeekProvider provider = new DeepSeekProvider();
        ReflectionTestUtils.setField(provider, "apiUrl", null);
        assertThat(provider.modelsEndpoint()).isNull();

        ReflectionTestUtils.setField(provider, "apiUrl", "https://api.deepseek.com/v1/responses");
        assertThat(provider.modelsEndpoint()).isNull();
    }

    @Test
    @DisplayName("An unconfigured provider is never probed, whatever its URL says")
    void unconfiguredProviderIsNeverProbed() {
        // Asserting only that the result is empty would not tell a skipped call
        // apart from a failed one, and would quietly start making real network
        // calls if the isConfigured() guard were removed. Recording whether the
        // URL was even computed is what makes this discriminating.
        AtomicBoolean endpointComputed = new AtomicBoolean(false);
        DeepSeekProvider provider = new DeepSeekProvider() {
            @Override
            protected String modelsEndpoint() {
                endpointComputed.set(true);
                return super.modelsEndpoint();
            }
        };
        ReflectionTestUtils.setField(provider, "apiUrl", "https://api.deepseek.com/v1/chat/completions");
        ReflectionTestUtils.setField(provider, "apiKey", "");

        assertThat(provider.listRemoteModelIds()).isEmpty();
        assertThat(endpointComputed).isFalse();
    }

    @Test
    @DisplayName("A vendor endpoint that throws yields 'could not ask', never a failed catalog sync")
    void aThrowingEndpointFailsSoft() {
        // The load-bearing safety property of the whole feature: discovery runs
        // inside the catalog sync transaction, so an exception escaping here
        // would take down a refresh because one vendor had a bad day.
        DeepSeekProvider provider = new DeepSeekProvider() {
            @Override
            protected String modelsEndpoint() {
                throw new IllegalStateException("vendor is having a bad day");
            }
        };
        ReflectionTestUtils.setField(provider, "apiKey", "sk-test");

        assertThat(provider.listRemoteModelIds()).isEmpty();
    }

    @Test
    @DisplayName("'Asked, and the vendor serves nothing' is a present empty list, not 'could not ask'")
    void anEmptyVendorCatalogueIsNotTheSameAsNoAnswer() {
        // The interface contract goes out of its way to separate these two, and
        // the discovery pass reports them differently: one is a skipped
        // provider, the other is a vendor with nothing new.
        DeepSeekProvider provider = new DeepSeekProvider() {
            @Override
            protected String modelsEndpoint() { return "https://vendor.test/v1/models"; }
            @Override
            protected List<String> extractModelIds(Map<String, Object> body) { return List.of(); }
            @Override
            protected org.springframework.http.HttpHeaders discoveryHeaders() {
                return new org.springframework.http.HttpHeaders();
            }
        };
        ReflectionTestUtils.setField(provider, "apiKey", "sk-test");
        // Stub the transport so the assertion is about the contract, not the network.
        ReflectionTestUtils.setField(provider, "discoveryRestTemplate", stubRestTemplate(Map.of()));

        assertThat(provider.listRemoteModelIds()).isPresent();
        assertThat(provider.listRemoteModelIds()).get().asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.list(String.class)).isEmpty();
    }

    @Test
    @DisplayName("Anthropic and Google degrade to 'could not ask' rather than guessing a path")
    void theOverriddenEndpointsHaveANullBranchToo() {
        ClaudeProvider claude = new ClaudeProvider();
        ReflectionTestUtils.setField(claude, "apiUrl", null);
        assertThat(claude.modelsEndpoint()).isNull();
        // A URL that is not a messages path: no derivation rule applies.
        ReflectionTestUtils.setField(claude, "apiUrl", "https://api.anthropic.com/v1/complete");
        assertThat(claude.modelsEndpoint()).isNull();

        GeminiProvider gemini = new GeminiProvider();
        ReflectionTestUtils.setField(gemini, "apiBaseUrl", null);
        assertThat(gemini.modelsEndpoint()).isNull();
        ReflectionTestUtils.setField(gemini, "apiBaseUrl", "  ");
        assertThat(gemini.modelsEndpoint()).isNull();
    }

    @Test
    @DisplayName("Both paginated listings ask for the full page, so neither is silently truncated")
    void paginatedListingsRequestTheWholeCollection() {
        // Anthropic defaults to 20 per page and Google to 50. Left implicit,
        // a vendor's newest model can sit past the cut and look exactly like a
        // model that was retired.
        ClaudeProvider claude = new ClaudeProvider();
        ReflectionTestUtils.setField(claude, "apiUrl", "https://api.anthropic.com/v1/messages");
        String claudeEndpoint = claude.modelsEndpoint();
        assertThat(claudeEndpoint).isEqualTo("https://api.anthropic.com/v1/models?limit=1000");

        GeminiProvider gemini = new GeminiProvider();
        ReflectionTestUtils.setField(gemini, "apiBaseUrl",
                "https://generativelanguage.googleapis.com/v1beta/models");
        String geminiEndpoint = gemini.modelsEndpoint();
        assertThat(geminiEndpoint)
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000");

        // A configured URL that already carries a query keeps it.
        ReflectionTestUtils.setField(gemini, "apiBaseUrl", "https://proxy.test/v1beta/models?alt=json");
        String proxied = gemini.modelsEndpoint();
        assertThat(proxied).isEqualTo("https://proxy.test/v1beta/models?alt=json&pageSize=1000");
    }

    @Test
    @DisplayName("A listing that overflows the requested page still yields its ids, and WARNS")
    void anOverflowingListingIsReportedRatherThanAssumedAway() {
        // Asking for the maximum page makes truncation improbable, not
        // impossible, and a short listing is indistinguishable from a retired
        // model. The ids that did arrive are still correct and discovery only
        // ever adds, so the right response is to say it, not to drop them.
        //
        // The warning IS the behaviour here: assert it, or deleting the branch
        // leaves this green while the truncation goes back to being silent.
        ClaudeProvider claude = new ClaudeProvider();
        List<String> claudeIds;
        try (LogCapture logs = LogCapture.on(ClaudeProvider.class)) {
            claudeIds = claude.extractModelIds(Map.of(
                    "has_more", true,
                    "data", List.of(Map.of("id", "claude-opus-9"))));
            assertThat(logs.warnings()).anyMatch(m -> m.contains("more pages"));
        }
        assertThat(claudeIds).containsExactly("claude-opus-9");

        GeminiProvider gemini = new GeminiProvider();
        List<String> geminiIds;
        try (LogCapture logs = LogCapture.on(GeminiProvider.class)) {
            geminiIds = gemini.extractModelIds(Map.of(
                    "nextPageToken", "tok",
                    "models", List.of(Map.of("name", "models/gemini-4-pro",
                            "supportedGenerationMethods", List.of("generateContent")))));
            assertThat(logs.warnings()).anyMatch(m -> m.contains("more pages"));
        }
        assertThat(geminiIds).containsExactly("gemini-4-pro");
    }

    @Test
    @DisplayName("A single-page listing stays silent - a warning on every sync would train operators to ignore it")
    void aCompleteListingReportsNoOverflow() {
        ClaudeProvider claude = new ClaudeProvider();
        try (LogCapture logs = LogCapture.on(ClaudeProvider.class)) {
            claude.extractModelIds(Map.of(
                    "has_more", false, "data", List.of(Map.of("id", "claude-opus-9"))));
            assertThat(logs.warnings()).isEmpty();
        }

        GeminiProvider gemini = new GeminiProvider();
        try (LogCapture logs = LogCapture.on(GeminiProvider.class)) {
            // An empty token is "no more pages", same as an absent one. Google
            // returns one on the last page, so treating it as overflow would
            // warn on every healthy run.
            gemini.extractModelIds(Map.of(
                    "nextPageToken", "",
                    "models", List.of(Map.of("name", "models/gemini-4-pro",
                            "supportedGenerationMethods", List.of("generateContent")))));
            assertThat(logs.warnings()).isEmpty();
        }
    }

    @Test
    @DisplayName("Google's discovery key goes through the same sanitiser as its completion calls")
    void googleSanitisesItsKeyForDiscoveryToo() {
        // Keys arrive from env files and k8s secrets with trailing newlines.
        // Raw, HttpHeaders rejects the value, the catch-all turns that into
        // "could not ask", and Google looks permanently unreachable with
        // nothing pointing at the key.
        GeminiProvider provider = new GeminiProvider();
        ReflectionTestUtils.setField(provider, "apiKey", "AIza-test\n");

        org.springframework.http.HttpHeaders headers = provider.discoveryHeaders();

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst("x-goog-api-key")).isEqualTo("AIza-test");
    }

    // ── Anthropic: same envelope, different path and auth ───────────────────

    @Test
    @DisplayName("Anthropic lists at /v1/models, a sibling of /v1/messages")
    void anthropicDerivesFromItsMessagesPath() {
        ClaudeProvider provider = new ClaudeProvider();
        ReflectionTestUtils.setField(provider, "apiUrl", "https://api.anthropic.com/v1/messages");

        String endpoint = provider.modelsEndpoint();
        // The query string is the pagination test's business, not this one's.
        assertThat(endpoint).startsWith("https://api.anthropic.com/v1/models");
    }

    @Test
    @DisplayName("Anthropic authenticates with x-api-key, never a bearer token")
    void anthropicUsesItsOwnAuthHeader() {
        ClaudeProvider provider = new ClaudeProvider();
        ReflectionTestUtils.setField(provider, "apiKey", "sk-ant-test");

        org.springframework.http.HttpHeaders headers = provider.discoveryHeaders();

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst("x-api-key")).isEqualTo("sk-ant-test");
        assertThat(headers.getFirst("anthropic-version")).isNotBlank();
        // A bearer header would be silently ignored by Anthropic and the call
        // would 401 - the failure mode this pins.
        assertThat(headers.getFirst("Authorization")).isNull();
    }

    // ── Google: different envelope, and the only one that states capability ─

    @Test
    @DisplayName("Google's configured URL IS the model collection")
    void googleListsAtItsConfiguredUrl() {
        GeminiProvider provider = new GeminiProvider();
        ReflectionTestUtils.setField(provider, "apiBaseUrl",
                "https://generativelanguage.googleapis.com/v1beta/models");

        String endpoint = provider.modelsEndpoint();
        assertThat(endpoint).startsWith("https://generativelanguage.googleapis.com/v1beta/models");
    }

    @Test
    @DisplayName("Google's key travels in a header, so a failure never logs it in a URL")
    void googleKeysStayOutOfTheUrl() {
        GeminiProvider provider = new GeminiProvider();
        ReflectionTestUtils.setField(provider, "apiKey", "AIza-test");
        ReflectionTestUtils.setField(provider, "apiBaseUrl",
                "https://generativelanguage.googleapis.com/v1beta/models");

        org.springframework.http.HttpHeaders headers = provider.discoveryHeaders();

        assertThat(headers).isNotNull();
        assertThat(headers.getFirst("x-goog-api-key")).isEqualTo("AIza-test");

        String endpoint = provider.modelsEndpoint();
        assertThat(endpoint).isNotBlank().doesNotContain("AIza-test");
    }

    @Test
    @DisplayName("Google's envelope: resource name to callable id, generateContent only")
    void googleExtractsChatModelsOnly() {
        GeminiProvider provider = new GeminiProvider();

        Map<String, Object> body = Map.of("models", List.of(
                Map.of("name", "models/gemini-3-pro",
                        "supportedGenerationMethods", List.of("generateContent", "countTokens")),
                // An embedding model: same collection, not a chat model. Without
                // the capability filter this would enter the catalog as one.
                Map.of("name", "models/text-embedding-005",
                        "supportedGenerationMethods", List.of("embedContent")),
                Map.of("name", "models/gemini-3-flash",
                        "supportedGenerationMethods", List.of("generateContent"))
        ));

        List<String> ids = provider.extractModelIds(body);

        assertThat(ids).containsExactly("gemini-3-pro", "gemini-3-flash");
    }

    @Test
    @DisplayName("A malformed Google body yields an empty list, never an exception")
    void googleToleratesAMalformedBody() {
        GeminiProvider provider = new GeminiProvider();

        assertThat(provider.extractModelIds(null)).isEmpty();
        assertThat(provider.extractModelIds(Map.of("models", "nope"))).isEmpty();
        // Present but with no declared capability - excluded rather than assumed.
        assertThat(provider.extractModelIds(Map.of("models", List.of(Map.of("name", "models/x")))))
                .isEmpty();
    }

    // ── The opt-in default ─────────────────────────────────────────────────

    @Test
    @DisplayName("A provider that answers nothing opts OUT by default, never in by omission")
    void defaultIsCouldNotAsk() {
        LLMProvider bare = new LLMProvider() {
            @Override public String getProviderName() { return "test"; }
            @Override public String getDefaultModel() { return null; }
            @Override public List<String> getSupportedModels() { return List.of(); }
            @Override public boolean isConfigured() { return true; }
            @Override public boolean supportsToolCalling() { return false; }
            @Override public boolean supportsStreaming() { return false; }
            @Override public com.apimarketplace.agent.domain.CompletionResponse complete(
                    com.apimarketplace.agent.domain.CompletionRequest r) { return null; }
            @Override public void completeStreaming(
                    com.apimarketplace.agent.domain.CompletionRequest r,
                    com.apimarketplace.agent.streaming.StreamingCallback c) { }
            @Override public reactor.core.publisher.Flux<com.apimarketplace.agent.streaming.StreamingEvent>
                    streamReactive(com.apimarketplace.agent.domain.CompletionRequest r) {
                return reactor.core.publisher.Flux.empty();
            }
        };

        assertThat(bare.listRemoteModelIds()).isEmpty();
    }
}
