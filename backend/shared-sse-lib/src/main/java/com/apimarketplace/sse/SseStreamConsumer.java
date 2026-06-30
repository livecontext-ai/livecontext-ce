package com.apimarketplace.sse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

/**
 * Generic Server-Sent Events consumer.
 *
 * <p>Issues an HTTP request to an upstream URL and aggregates every {@code data:} chunk
 * received until the stream ends (terminator, EOF, deadline, byte budget, or chunk budget).
 *
 * <p><b>Design.</b> This is pure plumbing. It knows nothing about LLMs, tools, or workflow
 * nodes. The caller is responsible for:
 *
 * <ul>
 *   <li>Building the URL, headers, and request body that triggers the stream
 *       (e.g. setting {@code stream: true} for OpenAI-compatible APIs).</li>
 *   <li>Interpreting the chunks once aggregation is complete (typically by projecting
 *       them against an {@code outputSchema}).</li>
 * </ul>
 *
 * <p><b>Reference.</b> The pattern mirrors
 * {@code shared-agent-lib/.../AbstractLLMProvider.streamReactive()} but stays
 * domain-agnostic to avoid a layering inversion: catalog-service must not depend on
 * shared-agent-lib. A future hardening pass may refactor AbstractLLMProvider to delegate
 * its SSE plumbing to this interface.
 */
public interface SseStreamConsumer {

    /**
     * Open the upstream connection and consume the SSE stream until completion.
     *
     * @param url           the upstream URL to call
     * @param method        the HTTP method (typically POST for LLM-style APIs, GET for ntfy)
     * @param headers       request headers (Authorization, Accept, Content-Type…)
     * @param requestBody   the request body to send, or {@code null} for GET-style streams.
     *                      Should already be a JSON-serializable object (Map, record, JsonNode);
     *                      the consumer will let WebClient handle the encoding.
     * @param config        consumption tunables (timeouts, byte/chunk budgets, terminators)
     * @return aggregated response - never {@code null}, may carry an error in {@code error}
     *         and partial chunks if the stream failed mid-flight
     */
    SseAggregatedResponse consume(
            String url,
            HttpMethod method,
            HttpHeaders headers,
            Object requestBody,
            SseConsumerConfig config
    );
}
