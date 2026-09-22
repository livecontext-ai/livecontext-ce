package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.KeyRoute;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gemini does not inherit the streaming transport, it has its own copy: Google takes the key in
 * the URL, so the provider builds the connection itself and flattens the HTTP status into a
 * string in its own code. The classification therefore had to be written twice, and two copies
 * of one rule drift. This pins the second copy against the first.
 *
 * @see AbstractLLMProviderOwnKeyRejectedTest the same scenario on the shared transport
 */
@DisplayName("GeminiProvider - an own-key rejection says so, like every other provider")
class GeminiProviderOwnKeyRejectedTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * A provider whose keys resolve on either route, so the call actually leaves for the vendor.
     * Without this the pre-flight gate refuses first and the transport is never reached, which is
     * a different failure and not the one under test.
     */
    private GeminiProvider provider(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        GeminiProvider provider = new GeminiProvider();
        ReflectionTestUtils.setField(provider, "apiBaseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta/models");
        ReflectionTestUtils.setField(provider, "apiKey", "platform-key");
        provider.setCredentialResolver(new LlmCredentialResolver() {
            @Override public Optional<String> resolveApiKey(String providerName) {
                return Optional.of("platform-key");
            }
            @Override public Optional<String> resolveApiKey(String userId, String providerName) {
                return Optional.of("platform-key");
            }
            @Override public Optional<String> resolveUserApiKey(String userId, String providerName) {
                // The user's own saved key: present, and about to be refused, which is the scenario.
                return Optional.of("the-users-own-key");
            }
        });
        return provider;
    }

    private static CompletionRequest request(KeyRoute route) {
        return CompletionRequest.builder()
                .model("gemini-2.5-flash")
                .userPrompt("hello")
                .tenantId("42")
                .keyRoute(route)
                .build();
    }

    private static String errorFrom(GeminiProvider provider, CompletionRequest request) {
        AtomicReference<String> captured = new AtomicReference<>();
        provider.completeStreaming(request, new StreamingCallback() {
            @Override public void onChunk(String content) { }
            @Override public void onToolCall(com.apimarketplace.agent.domain.ToolCall toolCall) { }
            @Override public void onComplete(com.apimarketplace.agent.domain.CompletionResponse response) { }
            @Override public void onError(String error) { captured.set(error); }
        });
        return captured.get();
    }

    @Test
    @DisplayName("STREAMING: a 403 on the caller's own key names the key and the way out")
    void streamingOwnKeyRejectionIsExplained() throws IOException {
        // 403 on purpose: Google answers it for a disabled or restricted key, which is exactly
        // the status the run-time path used to ignore while Settings already refused on it.
        GeminiProvider provider = provider(403, "{\"error\":{\"message\":\"API key not valid\"}}");

        String error = errorFrom(provider, request(KeyRoute.OWN_KEY));

        assertThat(error).isNotNull();
        assertThat(error).contains("Your own google API key was rejected");
        assertThat(error).contains("revoked, expired or run out of quota");
        assertThat(error).contains("switch the provider back to the platform key");
    }

    @Test
    @DisplayName("STREAMING: the same rejection on the PLATFORM key is not blamed on the user")
    void streamingPlatformRejectionIsNotBlamedOnTheUser() throws IOException {
        GeminiProvider provider = provider(401, "{\"error\":{\"message\":\"API key not valid\"}}");

        String error = errorFrom(provider, request(KeyRoute.PLATFORM));

        assertThat(error).isNotNull();
        assertThat(error).doesNotContain("Your own");
        // Unchanged: the status and what the vendor said, which is what an operator needs.
        assertThat(error).startsWith("HTTP 401:");
    }

    @Test
    @DisplayName("STREAMING: a failure that is not about the key is left alone, own key or not")
    void streamingNonAuthFailureIsUntouched() throws IOException {
        GeminiProvider provider = provider(429, "{\"error\":{\"message\":\"Resource exhausted\"}}");

        String error = errorFrom(provider, request(KeyRoute.OWN_KEY));

        assertThat(error).startsWith("HTTP 429:");
        assertThat(error).doesNotContain("Your own");
    }
}
