package com.apimarketplace.catalog.service.execution;

import com.apimarketplace.sse.SseAggregatedResponse;
import com.apimarketplace.sse.SseChunk;
import com.apimarketplace.sse.SseConsumerConfig;
import com.apimarketplace.sse.SseStreamConsumer;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates a Server-Sent Events stream into a single, projection-friendly response map.
 *
 * <p>Used by {@code HttpExecutionService.executeHttpCallTyped} when an MCP tool declares
 * {@code execution.mode = "streaming"}. The handler is intentionally generic - it does not
 * know whether the upstream is OpenAI, ntfy, sanity, speechify, etc. Each chunk is parsed
 * opportunistically as JSON; the caller's {@code outputSchema} declares how the
 * {@code chunks} array is shaped (typically as {@code chunks: array} with children matching
 * one chunk's fields).
 *
 * <p>The returned map is what eventually lands in {@code data} on the tool execution
 * envelope:
 *
 * <pre>{@code
 * {
 *   "chunks":     [ {...}, {...}, … ],   // each chunk: parsed JSON or {"text": "<raw>"} fallback
 *   "chunk_count": 42,
 *   "terminated":  true,                  // upstream sent an explicit terminator (e.g. data: [DONE])
 *   "truncated":   false                  // chunk/byte budget reached before EOF
 * }
 * }</pre>
 *
 * <p>If the upstream call fails mid-stream, the handler still returns whatever chunks were
 * collected, plus an {@code error} field. {@code OutputProjector} will then either project
 * the partial data or pass it through, depending on the schema declared.
 *
 * <p><b>Reference.</b> The chunk-handling pattern mirrors
 * {@code shared-agent-lib/.../AbstractLLMProvider.streamReactive()} but stays domain-neutral.
 * Future work could refactor that LLM provider to delegate its SSE plumbing to
 * {@link com.apimarketplace.sse.WebClientSseConsumer} (see {@code shared-sse-lib}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StreamingResponseHandler {

    private final SseStreamConsumer sseStreamConsumer;

    /**
     * Open the upstream stream and aggregate every chunk into a single response map.
     *
     * @param url         fully qualified upstream URL (already templated with path/query params
     *                    and credentials by HttpExecutionService)
     * @param method      HTTP method (POST for OpenAI-style, GET for ntfy-style)
     * @param headers     request headers including any Authorization injection
     * @param requestBody request body for POST streams (typically a Map / JsonNode), or
     *                    {@code null} for GET streams
     * @return aggregated response map ready to be wrapped in the standard tool envelope and
     *         projected against the tool's {@code outputSchema}
     */
    public Map<String, Object> handle(String url,
                                       HttpMethod method,
                                       HttpHeaders headers,
                                       Object requestBody) {
        log.info("[StreamingResponseHandler] {} {} (streaming)", method, url);

        SseAggregatedResponse aggregated = sseStreamConsumer.consume(
                url,
                method,
                headers,
                requestBody,
                SseConsumerConfig.defaults()
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunks", toChunkMaps(aggregated.chunks()));
        result.put("chunk_count", aggregated.chunkCount());
        result.put("terminated", aggregated.terminated());
        result.put("truncated", aggregated.truncated());
        if (aggregated.hasError()) {
            result.put("error", aggregated.error());
            log.warn("[StreamingResponseHandler] upstream error after {} chunks: {}",
                    aggregated.chunkCount(), aggregated.error());
        }
        return result;
    }

    /**
     * Convert each {@link SseChunk} to a JSON-friendly map. Parsed JSON chunks are passed
     * through as-is (so OutputProjector can recurse into their fields). Plain text chunks
     * are wrapped as {@code {"text": "<raw>"}} so the projection schema can declare a
     * uniform shape.
     */
    private List<Object> toChunkMaps(List<SseChunk> chunks) {
        List<Object> out = new ArrayList<>(chunks.size());
        for (SseChunk chunk : chunks) {
            JsonNode parsed = chunk.parsedJson();
            if (parsed != null && parsed.isObject()) {
                Map<String, Object> map = new LinkedHashMap<>();
                parsed.fields().forEachRemaining(e -> map.put(e.getKey(), unwrap(e.getValue())));
                if (chunk.eventName() != null) {
                    map.putIfAbsent("event", chunk.eventName());
                }
                out.add(map);
            } else if (parsed != null) {
                // JSON scalar/array - keep the parsed value, wrapped under "value" so the
                // schema sees a uniform object key.
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("value", unwrap(parsed));
                if (chunk.eventName() != null) {
                    map.put("event", chunk.eventName());
                }
                out.add(map);
            } else {
                // Plain text fallback.
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("text", chunk.rawData());
                if (chunk.eventName() != null) {
                    map.put("event", chunk.eventName());
                }
                out.add(map);
            }
        }
        return out;
    }

    /**
     * Convert a JsonNode to a plain Java value (Map / List / String / Number / Boolean).
     * Required because OutputProjector receives the result via valueToTree, and Jackson
     * round-trips cleanly only when the input is already in plain Java collection form.
     */
    private Object unwrap(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), unwrap(e.getValue())));
            return map;
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            node.forEach(child -> list.add(unwrap(child)));
            return list;
        }
        if (node.isInt() || node.isLong()) return node.longValue();
        if (node.isDouble() || node.isFloat()) return node.doubleValue();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }
}
