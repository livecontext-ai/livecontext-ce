package com.apimarketplace.sse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring {@link WebClient}-based implementation of {@link SseStreamConsumer}.
 *
 * <p>Consumes the upstream response as a {@code Flux<String>} of newline-delimited lines,
 * filters SSE {@code data:} lines, parses each opportunistically as JSON, applies the
 * configured limits, and blocks until completion. Returns an
 * {@link SseAggregatedResponse} containing every chunk in arrival order.
 *
 * <p>Errors during the upstream call (HTTP 4xx/5xx, IO failure, deadline exceeded) are
 * captured into {@link SseAggregatedResponse#error()} along with whatever partial chunks
 * had been collected - the caller can decide whether to surface the error or treat the
 * partial data as a degraded success.
 */
@Component
@Slf4j
public class WebClientSseConsumer implements SseStreamConsumer {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WebClientSseConsumer(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        // Each consumer gets its own WebClient with no base URL - the caller passes a
        // fully-qualified URL on every call. This keeps the consumer reusable across any
        // number of upstream APIs.
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public SseAggregatedResponse consume(
            String url,
            HttpMethod method,
            HttpHeaders headers,
            Object requestBody,
            SseConsumerConfig config
    ) {
        if (url == null || url.isBlank()) {
            return SseAggregatedResponse.withError(List.of(), "url is required");
        }
        if (config == null) {
            config = SseConsumerConfig.defaults();
        }

        final SseConsumerConfig effective = config;
        final List<SseChunk> chunks = new ArrayList<>();
        final AtomicBoolean terminated = new AtomicBoolean(false);
        final AtomicBoolean truncated = new AtomicBoolean(false);
        final AtomicLong bytesReceived = new AtomicLong(0L);
        final List<String> errorRef = new ArrayList<>(1);

        WebClient.RequestBodySpec requestSpec = webClient
                .method(method == null ? HttpMethod.GET : method)
                .uri(url)
                .headers(h -> {
                    if (headers != null) {
                        h.addAll(headers);
                    }
                    // Make sure Accept advertises text/event-stream so upstreams that
                    // content-negotiate (Anthropic, etc.) actually return SSE.
                    if (h.getAccept().isEmpty()) {
                        h.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
                    }
                });

        WebClient.RequestHeadersSpec<?> finalSpec;
        if (requestBody != null && method != HttpMethod.GET) {
            finalSpec = requestSpec.bodyValue(requestBody);
        } else {
            finalSpec = requestSpec;
        }

        try {
            // bodyToFlux(ServerSentEvent.class) decodes the SSE protocol natively:
            // - splits the body on event boundaries (blank line)
            // - assembles multi-line `data:` payloads
            // - exposes `event:` and `id:` separately
            // This is far more robust than reading the body as raw String chunks because
            // WebClient buffers the body in arbitrary chunk sizes that don't align with
            // SSE event boundaries.
            Flux<ServerSentEvent<String>> events = finalSpec
                    .retrieve()
                    .onStatus(
                            status -> status.isError(),
                            response -> response.bodyToMono(String.class)
                                    .defaultIfEmpty("")
                                    .map(body -> new SseUpstreamException(
                                            "HTTP " + response.statusCode().value() + ": " + body))
                    )
                    .bodyToFlux(SSE_TYPE);

            events
                    .takeWhile(event -> !terminated.get() && !truncated.get())
                    .doOnNext(event -> handleEvent(event, chunks, terminated, truncated,
                            bytesReceived, effective))
                    .blockLast(effective.maxWait());
        } catch (SseUpstreamException upstream) {
            log.warn("[SseConsumer] upstream error for {}: {}", url, upstream.getMessage());
            errorRef.add(upstream.getMessage());
        } catch (IllegalStateException timeout) {
            // blockLast throws IllegalStateException("Timeout on blocking read for ...")
            // when the maxWait deadline is reached without a terminal signal.
            log.warn("[SseConsumer] deadline reached for {} after {} ({} chunks collected)",
                    url, effective.maxWait(), chunks.size());
            // Treat deadline as a soft truncation, not an error.
            truncated.set(true);
        } catch (Exception e) {
            // Some upstream errors are wrapped in WebClientResponseException by the codec.
            // Surface them as errorRef so the caller sees them.
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            // Reactor wraps our SseUpstreamException inside a RuntimeException - unwrap it.
            Throwable cause = e.getCause();
            if (cause instanceof SseUpstreamException upstreamCause) {
                log.warn("[SseConsumer] upstream error for {}: {}", url, upstreamCause.getMessage());
                errorRef.add(upstreamCause.getMessage());
            } else {
                log.warn("[SseConsumer] unexpected error for {}: {}", url, message, e);
                errorRef.add(e.getClass().getSimpleName() + ": " + message);
            }
        }

        if (!errorRef.isEmpty()) {
            return SseAggregatedResponse.withError(chunks, errorRef.get(0));
        }
        return SseAggregatedResponse.of(chunks, terminated.get(), truncated.get());
    }

    /**
     * Process a single decoded SSE event. The Spring SSE codec has already done the
     * heavy lifting (splitting on blank lines, assembling multi-line {@code data:} fields,
     * exposing {@code event:} / {@code id:} separately) so we only need to:
     * <ul>
     *   <li>skip events with no data payload (keep-alive comments, lone {@code event:} lines)</li>
     *   <li>match the configured terminators</li>
     *   <li>enforce the byte/chunk budgets</li>
     *   <li>opportunistically parse the data payload as JSON</li>
     * </ul>
     */
    private void handleEvent(ServerSentEvent<String> event,
                             List<SseChunk> chunks,
                             AtomicBoolean terminated,
                             AtomicBoolean truncated,
                             AtomicLong bytesReceived,
                             SseConsumerConfig config) {
        if (event == null) return;
        String payload = event.data();
        if (payload == null) {
            return; // event:/id: only line, or comment-only event
        }
        payload = payload.trim();
        if (payload.isEmpty()) {
            return;
        }

        // Terminator detection - must happen BEFORE adding the chunk so the terminator
        // itself is not surfaced to the caller.
        if (config.terminators() != null && config.terminators().contains(payload)) {
            terminated.set(true);
            return;
        }

        // Byte budget enforcement.
        long newTotal = bytesReceived.addAndGet(payload.length());
        if (newTotal > config.maxBytes()) {
            log.debug("[SseConsumer] byte budget reached ({} > {})", newTotal, config.maxBytes());
            truncated.set(true);
            return;
        }

        // Try to parse as JSON; fall back to raw text on parse failure.
        JsonNode parsed = null;
        try {
            parsed = objectMapper.readTree(payload);
        } catch (Exception ignore) {
            // Not JSON - keep the raw payload, parsedJson stays null.
        }

        chunks.add(new SseChunk(payload, parsed, event.event()));

        // Chunk budget enforcement.
        if (chunks.size() >= config.maxChunks()) {
            log.debug("[SseConsumer] chunk budget reached ({})", chunks.size());
            truncated.set(true);
        }
    }

    /** Internal marker for upstream HTTP errors so we can route them to errorRef. */
    private static final class SseUpstreamException extends RuntimeException {
        SseUpstreamException(String message) {
            super(message);
        }
    }
}
