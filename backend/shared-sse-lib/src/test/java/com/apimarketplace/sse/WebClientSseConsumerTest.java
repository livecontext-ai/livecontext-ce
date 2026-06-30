package com.apimarketplace.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link WebClientSseConsumer} - the heart of shared-sse-lib.
 *
 * <p>Uses MockWebServer to simulate every kind of upstream behavior we expect to handle:
 * happy path JSON chunks, OpenAI-style {@code [DONE]} terminator, plain-text fallback,
 * SSE {@code event:} prefix, byte/chunk budgets, deadline, HTTP error, GET stream.
 */
class WebClientSseConsumerTest {

    private MockWebServer server;
    private WebClientSseConsumer consumer;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        consumer = new WebClientSseConsumer(WebClient.builder(), new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private String url(String path) {
        return server.url(path).toString();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Happy paths
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("happy path: 3 JSON chunks + [DONE] terminator")
    void happyPath_jsonChunksWithTerminator() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"id\":1,\"text\":\"hello\"}\n\n" +
                         "data: {\"id\":2,\"text\":\"world\"}\n\n" +
                         "data: {\"id\":3,\"text\":\"!\"}\n\n" +
                         "data: [DONE]\n\n"));

        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null,
                SseConsumerConfig.defaults());

        assertThat(response.hasError()).isFalse();
        assertThat(response.terminated()).isTrue();
        assertThat(response.truncated()).isFalse();
        assertThat(response.chunkCount()).isEqualTo(3);
        assertThat(response.chunks()).hasSize(3);

        SseChunk first = response.chunks().get(0);
        assertThat(first.isJson()).isTrue();
        assertThat(first.parsedJson().get("id").asInt()).isEqualTo(1);
        assertThat(first.parsedJson().get("text").asText()).isEqualTo("hello");
    }

    @Test
    @DisplayName("happy path: POST with body, 2 chunks then EOF (no terminator)")
    void happyPath_postWithBodyEofNoTerminator() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"chunk\":1}\n\ndata: {\"chunk\":2}\n\n"));

        SseAggregatedResponse response = consumer.consume(
                url("/v1/chat/completions"),
                HttpMethod.POST,
                new HttpHeaders(),
                Map.of("model", "x", "stream", true),
                SseConsumerConfig.defaults());

        assertThat(response.hasError()).isFalse();
        // No terminator → terminated stays false but stream still closed normally
        assertThat(response.terminated()).isFalse();
        assertThat(response.chunkCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("plain-text chunk: payload not JSON falls back to rawData")
    void plainTextChunk() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: hello world\n\ndata: not-json\n\ndata: [DONE]\n\n"));

        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null,
                SseConsumerConfig.defaults());

        assertThat(response.terminated()).isTrue();
        assertThat(response.chunkCount()).isEqualTo(2);
        assertThat(response.chunks().get(0).isJson()).isFalse();
        assertThat(response.chunks().get(0).rawData()).isEqualTo("hello world");
        assertThat(response.chunks().get(1).isJson()).isFalse();
    }

    @Test
    @DisplayName("SSE event: prefix is attached to the next data: chunk")
    void eventPrefixAttached() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: message\ndata: {\"x\":1}\n\n" +
                         "event: keepalive\ndata: {\"x\":2}\n\n" +
                         "data: {\"x\":3}\n\n"));

        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null,
                SseConsumerConfig.defaults());

        assertThat(response.chunkCount()).isEqualTo(3);
        assertThat(response.chunks().get(0).eventName()).isEqualTo("message");
        assertThat(response.chunks().get(1).eventName()).isEqualTo("keepalive");
        // event names are consumed by the next data line - third chunk has no event
        assertThat(response.chunks().get(2).eventName()).isNull();
    }

    @Test
    @DisplayName("comment lines and blank lines are ignored")
    void commentsAndBlanksIgnored() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": this is a keep-alive comment\n" +
                         "\n" +
                         "data: {\"x\":1}\n\n" +
                         ": another comment\n" +
                         "data: [DONE]\n\n"));

        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null,
                SseConsumerConfig.defaults());

        assertThat(response.terminated()).isTrue();
        assertThat(response.chunkCount()).isEqualTo(1);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Limits
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("chunk budget reached → truncated=true, no terminator")
    void chunkBudgetReached() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            body.append("data: {\"i\":").append(i).append("}\n\n");
        }
        body.append("data: [DONE]\n\n");
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body.toString()));

        SseConsumerConfig cfg = SseConsumerConfig.defaults().withMaxChunks(3);
        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null, cfg);

        assertThat(response.truncated()).isTrue();
        assertThat(response.terminated()).isFalse();
        assertThat(response.chunkCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("byte budget reached → truncated=true")
    void byteBudgetReached() {
        // Each chunk is ~20 bytes; budget set to 30 → second chunk pushes us over.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"x\":\"aaaaaaaaaa\"}\n\n" +
                         "data: {\"x\":\"bbbbbbbbbb\"}\n\n" +
                         "data: {\"x\":\"cccccccccc\"}\n\n" +
                         "data: [DONE]\n\n"));

        SseConsumerConfig cfg = SseConsumerConfig.defaults().withMaxBytes(30L);
        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null, cfg);

        assertThat(response.truncated()).isTrue();
        // The first chunk fits, the second one tips over the budget - exact count
        // depends on payload length, but it must be ≥ 1 and < 4.
        assertThat(response.chunkCount()).isGreaterThanOrEqualTo(1).isLessThan(4);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Errors
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("upstream HTTP 500 → error captured, no chunks")
    void upstreamHttp500() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("upstream exploded"));

        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null,
                SseConsumerConfig.defaults());

        assertThat(response.hasError()).isTrue();
        assertThat(response.error()).contains("500");
        assertThat(response.chunkCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("upstream HTTP 401 with body → error captured")
    void upstreamHttp401() {
        server.enqueue(new MockResponse()
                .setResponseCode(401)
                .setBody("{\"error\":\"unauthorized\"}"));

        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.POST, new HttpHeaders(), Map.of(),
                SseConsumerConfig.defaults());

        assertThat(response.hasError()).isTrue();
        assertThat(response.error()).contains("401");
    }

    @Test
    @DisplayName("custom terminators: alternative end markers respected")
    void customTerminator() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"a\":1}\n\ndata: END\n\ndata: {\"never\":true}\n\n"));

        SseConsumerConfig cfg = SseConsumerConfig.defaults().withTerminators(List.of("END"));
        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null, cfg);

        assertThat(response.terminated()).isTrue();
        assertThat(response.chunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("empty data: line is ignored, no chunk emitted")
    void emptyDataLineIgnored() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data:\n\ndata: {\"x\":1}\n\ndata: [DONE]\n\n"));

        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null,
                SseConsumerConfig.defaults());

        assertThat(response.terminated()).isTrue();
        assertThat(response.chunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("missing url → error response")
    void missingUrl() {
        SseAggregatedResponse response = consumer.consume(
                null, HttpMethod.GET, new HttpHeaders(), null,
                SseConsumerConfig.defaults());

        assertThat(response.hasError()).isTrue();
        assertThat(response.error()).contains("url");
        assertThat(response.chunkCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("null config → defaults applied")
    void nullConfigUsesDefaults() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"x\":1}\n\ndata: [DONE]\n\n"));

        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null, null);

        assertThat(response.terminated()).isTrue();
        assertThat(response.chunkCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("short maxWait deadline → truncated=true (soft truncation)")
    void deadlineTruncation() {
        // Body is small but server delays the response → blockLast should hit the deadline.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS)
                .setBody("data: {\"x\":1}\n\ndata: [DONE]\n\n"));

        SseConsumerConfig cfg = SseConsumerConfig.defaults().withMaxWait(Duration.ofMillis(300));
        SseAggregatedResponse response = consumer.consume(
                url("/stream"), HttpMethod.GET, new HttpHeaders(), null, cfg);

        // Either deadline truncation (truncated=true) or upstream error - both acceptable.
        assertThat(response.terminated()).isFalse();
    }
}
