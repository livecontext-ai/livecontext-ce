package com.apimarketplace.common.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Server-side PostHog emitter for backend product analytics (what agents/workflows
 * actually do). Shared across services via {@link PostHogAutoConfiguration}.
 *
 * <p><b>Performance contract - this runs near the agent/workflow hot path, so it
 * MUST stay invisible to user-facing latency:</b>
 * <ul>
 *   <li>{@link #capture} only enqueues onto a small bounded pool and returns
 *       immediately - it never does I/O on the caller's thread.</li>
 *   <li>The pool is bounded (queue cap) with a DROP-on-overflow policy: under a
 *       PostHog outage or burst, events are silently dropped, never queued
 *       unboundedly and never back-pressuring execution.</li>
 *   <li>It NEVER throws: every failure (rejected task, HTTP error, serialization)
 *       is swallowed and logged at debug.</li>
 *   <li>Short HTTP timeouts so a slow endpoint can't tie up the worker threads.</li>
 * </ul>
 *
 * <p><b>No-op unless active</b>: inert when {@code posthog.enabled=false} or the
 * api-key is blank - same "safe to ship without config" guarantee as the frontend
 * facade. <b>No PII</b>: callers pass UUID/enum/count properties only; never emit
 * emails, names, prompts, or tool payloads.
 */
public class PostHogAnalyticsClient {

    private static final Logger log = LoggerFactory.getLogger(PostHogAnalyticsClient.class);

    private final boolean active;
    private final String apiKey;
    private final String captureUrl;
    private final RestTemplate restTemplate;
    private final ExecutorService executor;

    public PostHogAnalyticsClient(boolean enabled, String apiKey, String host) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.active = enabled && !this.apiKey.isEmpty();

        String h = (host == null || host.isBlank()) ? "https://eu.i.posthog.com" : host.trim();
        while (h.endsWith("/")) {
            h = h.substring(0, h.length() - 1);
        }
        this.captureUrl = h + "/capture/";

        if (active) {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
            factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
            this.restTemplate = new RestTemplate(factory);

            this.executor = new ThreadPoolExecutor(
                    1, 2, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(500),
                    daemonThreadFactory(),
                    new ThreadPoolExecutor.DiscardPolicy());
            log.info("[posthog] backend analytics enabled (host={})", h);
        } else {
            this.restTemplate = null;
            this.executor = null;
        }
    }

    /** True when a key is configured and analytics is enabled. */
    public boolean isActive() {
        return active;
    }

    /**
     * Fire-and-forget capture. Returns immediately; the HTTP send happens on a
     * bounded background pool and is dropped (never retried, never blocks) on
     * overflow or failure.
     */
    public void capture(String distinctId, String event, Map<String, Object> properties) {
        if (!active) return;
        if (distinctId == null || distinctId.isBlank() || event == null || event.isBlank()) return;
        try {
            final Map<String, Object> payload = buildPayload(distinctId, event, properties);
            executor.execute(() -> send(payload));
        } catch (RejectedExecutionException dropped) {
            // Queue full - intentionally drop, never block the caller.
        } catch (Exception e) {
            log.debug("[posthog] capture enqueue failed (dropped): {}", e.toString());
        }
    }

    /** Builds the PostHog `/capture/` request body. Pure / never throws on null props. */
    Map<String, Object> buildPayload(String distinctId, String event, Map<String, Object> properties) {
        Map<String, Object> props = new HashMap<>();
        if (properties != null) {
            properties.forEach((k, v) -> {
                if (k != null && v != null) props.put(k, v);
            });
        }
        props.putIfAbsent("$lib", "livecontext-backend");

        Map<String, Object> body = new HashMap<>();
        body.put("api_key", apiKey);
        body.put("event", event);
        body.put("distinct_id", distinctId);
        body.put("properties", props);
        return body;
    }

    private void send(Map<String, Object> payload) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            restTemplate.postForEntity(captureUrl, new HttpEntity<>(payload, headers), Void.class);
        } catch (Exception e) {
            // Analytics is best-effort: a failed send must never surface anywhere.
            log.debug("[posthog] capture send failed (dropped): {}", e.toString());
        }
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory() {
        final AtomicInteger n = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, "posthog-emit-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
