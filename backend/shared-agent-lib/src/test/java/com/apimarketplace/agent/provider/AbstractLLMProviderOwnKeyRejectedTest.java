package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.KeyRoute;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A key that WORKED when it was saved and is refused later.
 *
 * <p>Settings already explains a bad key at save time. Nothing explained one that stops working
 * afterwards: the provider's raw text came back ("Incorrect API key provided: sk-...") with
 * nothing saying whose key it was or where to fix it, and on the streaming transport the HTTP
 * status is flattened into that string before any caller can branch on it. So the classification
 * happens where the status and the route still coexist, which is the provider itself.
 *
 * <p>The route check is the point: on the PLATFORM route the same 401 is an operator problem, and
 * telling a user their key was rejected when the call ran on ours would send them to a settings
 * page with nothing to fix.
 */
@DisplayName("AbstractLLMProvider - an own-key rejection says so")
class AbstractLLMProviderOwnKeyRejectedTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * A provider that CAN resolve a key on either route, so the call actually leaves for the
     * vendor. Without this the pre-flight gate refuses first and nothing reaches the transport,
     * which is a different failure and not the one under test.
     */
    private static DeepSeekProvider provider() {
        DeepSeekProvider provider = new DeepSeekProvider();
        ReflectionTestUtils.setField(provider, "apiUrl", "https://api.deepseek.com/v1/chat/completions");
        ReflectionTestUtils.setField(provider, "apiKey", "platform-key");
        provider.setCredentialResolver(new LlmCredentialResolver() {
            @Override public java.util.Optional<String> resolveApiKey(String providerName) {
                return java.util.Optional.of("platform-key");
            }
            @Override public java.util.Optional<String> resolveApiKey(String userId, String providerName) {
                return java.util.Optional.of("platform-key");
            }
            @Override public java.util.Optional<String> resolveUserApiKey(String userId, String providerName) {
                // The user's own saved key: present, and about to be refused by the vendor,
                // which is the whole scenario.
                return java.util.Optional.of("the-users-own-key");
            }
        });
        return provider;
    }

    private static CompletionRequest request(KeyRoute route) {
        return CompletionRequest.builder()
                .model("deepseek-chat")
                .userPrompt("hello")
                .tenantId("42")
                .keyRoute(route)
                .build();
    }

    /** A provider endpoint that answers one status with one body, on a free port. */
    private String serverAnswering(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    private static AtomicReference<String> errorFrom(DeepSeekProvider provider, CompletionRequest request) {
        AtomicReference<String> captured = new AtomicReference<>();
        provider.completeStreaming(request, new StreamingCallback() {
            @Override public void onChunk(String content) { }
            @Override public void onToolCall(com.apimarketplace.agent.domain.ToolCall toolCall) { }
            @Override public void onComplete(com.apimarketplace.agent.domain.CompletionResponse response) { }
            @Override public void onError(String error) { captured.set(error); }
        });
        return captured;
    }

    @Test
    @DisplayName("STREAMING, the path production uses: a 401 on the caller's own key names the key and the way out")
    void streamingOwnKeyRejectionIsExplained() throws IOException {
        DeepSeekProvider provider = provider();
        ReflectionTestUtils.setField(provider, "apiUrl",
                serverAnswering(401, "{\"error\":{\"message\":\"Incorrect API key provided: sk-abc\"}}"));

        String error = errorFrom(provider, request(KeyRoute.OWN_KEY)).get();

        assertThat(error).isNotNull();
        // Names whose key it is, what probably happened, and BOTH moves available.
        assertThat(error).contains("Your own deepseek API key was rejected");
        assertThat(error).contains("revoked, expired or run out of quota");
        assertThat(error).contains("switch the provider back to the platform key");
        // "API key" is load-bearing: the chat surface classifies key problems on that phrase
        // and it is what opens the modal that links to the settings.
        assertThat(error).contains("API key");
        // The vendor's raw text is replaced, not appended: it named a key fragment and nothing
        // actionable.
        assertThat(error).doesNotContain("sk-abc");
    }

    @Test
    @DisplayName("STREAMING: the same 401 on the PLATFORM key is not blamed on the user")
    void streamingPlatformRejectionIsNotBlamedOnTheUser() throws IOException {
        DeepSeekProvider provider = provider();
        ReflectionTestUtils.setField(provider, "apiUrl",
                serverAnswering(401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}"));

        String error = errorFrom(provider, request(KeyRoute.PLATFORM)).get();

        assertThat(error).isNotNull();
        assertThat(error).doesNotContain("Your own");
        // Unchanged from before: the status and the vendor's text, which is what an operator
        // needs and what every existing consumer already reads.
        assertThat(error).startsWith("HTTP 401:");
    }

    @Test
    @DisplayName("STREAMING: a rejection that is not about the key is left alone, own key or not")
    void streamingNonAuthFailureIsUntouched() throws IOException {
        DeepSeekProvider provider = provider();
        ReflectionTestUtils.setField(provider, "apiUrl",
                serverAnswering(429, "{\"error\":{\"message\":\"Rate limit reached\"}}"));

        String error = errorFrom(provider, request(KeyRoute.OWN_KEY)).get();

        assertThat(error).startsWith("HTTP 429:");
        assertThat(error).doesNotContain("Your own");
    }

    @Test
    @DisplayName("SYNC: an own-key rejection carries the same sentence, unauthorized, and is not retried")
    void syncOwnKeyRejectionIsExplainedAndNotRetried() {
        DeepSeekProvider provider = provider();
        HttpClientErrorException refused = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(),
                "{\"error\":{\"message\":\"Incorrect API key\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> provider.handleHttpError(refused, request(KeyRoute.OWN_KEY)))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("Your own deepseek API key was rejected")
                .satisfies(e -> {
                    LLMProviderException llm = (LLMProviderException) e;
                    assertThat(llm.getErrorCode()).isEqualTo("unauthorized");
                    // Retrying never fixes a key the provider refuses, and the retry policy
                    // keys off exactly this flag.
                    assertThat(llm.isRetryable()).isFalse();
                });
    }

    @Test
    @DisplayName("SYNC: 403 on the OWN key reads as a rejected key, like the save-time check has always said")
    void syncForbiddenOnTheOwnKeyIsAnAuthRejection() {
        DeepSeekProvider provider = provider();
        HttpClientErrorException forbidden = HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                "{\"error\":{\"message\":\"Key disabled\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> provider.handleHttpError(forbidden, request(KeyRoute.OWN_KEY)))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("Your own deepseek API key was rejected")
                .satisfies(e -> assertThat(((LLMProviderException) e).getErrorCode()).isEqualTo("unauthorized"));
    }

    @Test
    @DisplayName("SYNC: 403 on the PLATFORM key still says what the vendor said, not 'Invalid API key'")
    void syncForbiddenOnThePlatformKeyKeepsTheVendorText() {
        DeepSeekProvider provider = provider();
        // The shape a platform 403 actually takes: nothing to do with the key. Calling this an
        // invalid API key sends the reader to a settings page where there is nothing to fix,
        // because the chat surface classifies that exact phrase as a key problem.
        HttpClientErrorException forbidden = HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                "{\"error\":{\"message\":\"unsupported_country_region_territory\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> provider.handleHttpError(forbidden, request(KeyRoute.PLATFORM)))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("unsupported_country_region_territory")
                .hasMessageNotContaining("Invalid API key")
                .hasMessageNotContaining("Your own");
    }

    @Test
    @DisplayName("SYNC: a 401 on the platform key is untouched by any of this")
    void syncUnauthorizedOnThePlatformKeyIsUnchanged() {
        DeepSeekProvider provider = provider();
        HttpClientErrorException refused = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(),
                "{\"error\":{\"message\":\"Incorrect API key provided\"}}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> provider.handleHttpError(refused, request(KeyRoute.PLATFORM)))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("Invalid API key")
                .satisfies(e -> assertThat(((LLMProviderException) e).getErrorCode()).isEqualTo("unauthorized"));
    }

    @Test
    @DisplayName("SYNC: a request-less caller keeps the old behaviour")
    void syncWithoutARequestIsUnchanged() {
        DeepSeekProvider provider = provider();
        HttpClientErrorException refused = HttpClientErrorException.create(
                HttpStatus.UNAUTHORIZED, "Unauthorized", new HttpHeaders(),
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);

        assertThatThrownBy(() -> provider.handleHttpError(refused))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("Invalid API key");
    }
}
