package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudLlmRelayClient")
class CloudLlmRelayClientTest {

    private static final CloudLlmRuntimeCredentials CREDENTIALS = new CloudLlmRuntimeCredentials(
            "access-token", "install-1", "http://placeholder.test/api");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private CloudLlmRelayClient client;
    private String baseApiUrl;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseApiUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/api";
        client = new CloudLlmRelayClient(objectMapper);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    @DisplayName("complete posts to the Cloud /api/ce-llm endpoint with OAuth and install headers")
    void completePostsToCloudRelayWithHeaders() {
        server.createContext("/api/ce-llm/complete", exchange -> handle(exchange, () -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access-token");
            assertThat(exchange.getRequestHeaders().getFirst("X-LiveContext-Install-Id")).isEqualTo("install-1");
            CloudLlmRelayRequest request = objectMapper.readValue(exchange.getRequestBody(), CloudLlmRelayRequest.class);
            assertThat(request.provider()).isEqualTo("deepseek");
            assertThat(request.completionRequest().tenantId()).isEqualTo("tenant-1");
            writeJson(exchange, 200, CompletionResponse.text("ok"));
        }));

        CompletionResponse response = client.complete(credentials(), relayRequest());

        assertThat(response.content()).isEqualTo("ok");
    }

    @Test
    @DisplayName("complete reports non-2xx Cloud relay responses with the response body")
    void completeReportsNon2xxResponses() {
        server.createContext("/api/ce-llm/complete",
                exchange -> writeText(exchange, 402, "{\"error\":\"INSUFFICIENT_CREDITS\"}"));

        assertThatThrownBy(() -> client.complete(credentials(), relayRequest()))
                .hasMessageContaining("Cloud LLM relay returned 402")
                .hasMessageContaining("INSUFFICIENT_CREDITS");
    }

    @Test
    @DisplayName("stream dispatches Cloud NDJSON events to the local streaming callback")
    void streamDispatchesNdjsonEvents() {
        ToolCall toolCall = ToolCall.builder()
                .id("call-1")
                .toolName("local_lookup")
                .arguments(Map.of("query", "hello"))
                .index(0)
                .build();
        CompletionResponse completion = CompletionResponse.builder()
                .content("done")
                .finishReason("stop")
                .build();
        server.createContext("/api/ce-llm/stream", exchange -> handle(exchange, () -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access-token");
            assertThat(exchange.getRequestHeaders().getFirst("X-LiveContext-Install-Id")).isEqualTo("install-1");
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            writeEvent(exchange, CloudLlmStreamEvent.content("he"));
            writeEvent(exchange, CloudLlmStreamEvent.thinking("think"));
            writeEvent(exchange, CloudLlmStreamEvent.toolCall(toolCall));
            writeEvent(exchange, CloudLlmStreamEvent.completed(completion));
            exchange.close();
        }));
        RecordingCallback callback = new RecordingCallback();

        client.stream(credentials(), relayRequest(), callback);

        assertThat(callback.chunks).containsExactly("he");
        assertThat(callback.thinking).containsExactly("think");
        assertThat(callback.toolCalls).containsExactly(toolCall);
        assertThat(callback.completed).isEqualTo(completion);
        assertThat(callback.errors).isEmpty();
    }

    @Test
    @DisplayName("stream reports non-2xx Cloud relay responses as callback errors")
    void streamReportsNon2xxResponses() {
        server.createContext("/api/ce-llm/stream", exchange -> writeText(exchange, 402, "INSUFFICIENT_CREDITS"));
        RecordingCallback callback = new RecordingCallback();

        client.stream(credentials(), relayRequest(), callback);

        assertThat(callback.errors).singleElement()
                .satisfies(error -> assertThat(error)
                        .contains("Cloud LLM relay returned 402")
                        .contains("INSUFFICIENT_CREDITS"));
        assertThat(callback.completed).isNull();
    }

    @Test
    @DisplayName("stream read timeout is bounded")
    void streamReadTimeoutIsBounded() {
        server.createContext("/api/ce-llm/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(1_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        CloudLlmRelayClient timeoutClient = new CloudLlmRelayClient(
                new org.springframework.web.client.RestTemplate(),
                objectMapper,
                (int) Duration.ofMillis(200).toMillis());
        RecordingCallback callback = new RecordingCallback();

        long startedAt = System.nanoTime();
        timeoutClient.stream(credentials(), relayRequest(), callback);
        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertThat(elapsedMs).isLessThan(900L);
        assertThat(callback.errors).singleElement()
                .satisfies(error -> assertThat(error)
                        .contains("Cloud LLM relay stream failed")
                        .contains("Read timed out"));
    }

    @Test
    @DisplayName("stream stops reading Cloud events when the local callback requests cancellation")
    void streamStopsWhenCallbackRequestsCancellation() {
        server.createContext("/api/ce-llm/stream", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            exchange.sendResponseHeaders(200, 0);
            writeEvent(exchange, CloudLlmStreamEvent.content("first"));
            writeEvent(exchange, CloudLlmStreamEvent.content("second"));
            writeEvent(exchange, CloudLlmStreamEvent.completed(CompletionResponse.text("done")));
            exchange.close();
        });
        RecordingCallback callback = new RecordingCallback() {
            @Override
            public boolean shouldStop() {
                return !chunks.isEmpty();
            }
        };

        client.stream(credentials(), relayRequest(), callback);

        assertThat(callback.chunks).containsExactly("first");
        assertThat(callback.completed).isNull();
    }

    @Test
    @DisplayName("settle posts the executionId to the Cloud /api/ce-llm/settle endpoint with headers")
    void settlePostsToCloudSettleEndpoint() {
        List<String> received = new ArrayList<>();
        server.createContext("/api/ce-llm/settle", exchange -> handle(exchange, () -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer access-token");
            assertThat(exchange.getRequestHeaders().getFirst("X-LiveContext-Install-Id")).isEqualTo("install-1");
            CeRelaySettleRequest req = objectMapper.readValue(exchange.getRequestBody(), CeRelaySettleRequest.class);
            received.add(req.executionId());
            writeText(exchange, 200, "{\"settled\":true}");
        }));

        client.settle(credentials(), new CeRelaySettleRequest("exec-1"));

        assertThat(received).containsExactly("exec-1");
    }

    @Test
    @DisplayName("settle reports non-2xx Cloud relay responses with the response body")
    void settleReportsNon2xxResponses() {
        server.createContext("/api/ce-llm/settle", exchange -> writeText(exchange, 403, "CE_LINK_NOT_ACTIVE"));

        assertThatThrownBy(() -> client.settle(credentials(), new CeRelaySettleRequest("exec-1")))
                .hasMessageContaining("/ce-llm/settle returned 403")
                .hasMessageContaining("CE_LINK_NOT_ACTIVE");
    }

    @Test
    @DisplayName("release posts the executionId to the Cloud /api/ce-llm/release endpoint")
    void releasePostsToCloudReleaseEndpoint() {
        List<String> received = new ArrayList<>();
        server.createContext("/api/ce-llm/release", exchange -> handle(exchange, () -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            CeRelayReleaseRequest req = objectMapper.readValue(exchange.getRequestBody(), CeRelayReleaseRequest.class);
            received.add(req.executionId());
            writeText(exchange, 200, "{\"released\":true}");
        }));

        client.release(credentials(), new CeRelayReleaseRequest("exec-1", "no billable calls"));

        assertThat(received).containsExactly("exec-1");
    }

    private CloudLlmRuntimeCredentials credentials() {
        return new CloudLlmRuntimeCredentials(CREDENTIALS.accessToken(), CREDENTIALS.installId(), baseApiUrl);
    }

    private static CloudLlmRelayRequest relayRequest() {
        return new CloudLlmRelayRequest("deepseek", CompletionRequest.builder()
                .tenantId("tenant-1")
                .model("deepseek-chat")
                .userPrompt("hello")
                .build());
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void writeEvent(HttpExchange exchange, CloudLlmStreamEvent event) throws IOException {
        exchange.getResponseBody().write(objectMapper.writeValueAsBytes(event));
        exchange.getResponseBody().write('\n');
        exchange.getResponseBody().flush();
    }

    private static void handle(HttpExchange exchange, ThrowingHandler handler) throws IOException {
        try {
            handler.handle();
        } catch (Throwable throwable) {
            StringWriter stackTrace = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stackTrace));
            writeText(exchange, 500, stackTrace.toString());
        }
    }

    private static void writeText(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle() throws Exception;
    }

    private static class RecordingCallback implements StreamingCallback {
        protected final List<String> chunks = new ArrayList<>();
        protected final List<String> thinking = new ArrayList<>();
        protected final List<ToolCall> toolCalls = new ArrayList<>();
        protected final List<String> errors = new ArrayList<>();
        protected CompletionResponse completed;

        @Override
        public void onChunk(String content) {
            chunks.add(content);
        }

        @Override
        public void onThinking(String value) {
            thinking.add(value);
        }

        @Override
        public void onToolCall(ToolCall toolCall) {
            toolCalls.add(toolCall);
        }

        @Override
        public void onComplete(CompletionResponse response) {
            completed = response;
        }

        @Override
        public void onError(String error) {
            errors.add(error);
        }
    }
}
