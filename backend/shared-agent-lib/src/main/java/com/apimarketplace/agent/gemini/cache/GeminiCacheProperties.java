package com.apimarketplace.agent.gemini.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Stage 2 - configuration for Gemini {@code cachedContent} manual caching.
 *
 * <p>Default {@link #enabled} is {@code false}: opt-in per deployment so
 * the feature can be rolled out behind a flag. When disabled,
 * {@link GeminiCachedContentManager#getOrCreate} short-circuits to
 * {@link java.util.Optional#empty()} and the provider builds a request
 * body without a {@code cachedContent} field - the pre-Stage-2 behavior.
 *
 * <p>Property prefix: {@code ai.agent.providers.google.cache.*}
 *
 * <ul>
 *   <li>{@code enabled} - master switch. Default {@code false}.</li>
 *   <li>{@code min-req-per-hour} - traffic floor passed to
 *       {@link GeminiCacheGate}. Default {@code 0} means "accept any
 *       traffic level"; raise to {@code 3}+ to avoid paying storage
 *       fees on low-volume tenants.</li>
 *   <li>{@code ttl-seconds} - how long each cached prefix lives on
 *       Google's side. Default 1h (3600 s). Google bills storage by
 *       the minute, so longer TTL = more storage cost but fewer
 *       re-uploads. 1h is the published recommended default.</li>
 *   <li>{@code api-base-url} - cachedContents endpoint. Overridable
 *       for tests (WireMock) or for private preview endpoints.</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "ai.agent.providers.google.cache")
@Getter
@Setter
public class GeminiCacheProperties {

    /** Master switch. {@code false} → manager returns empty on every call. */
    private boolean enabled = false;

    /**
     * Per-tenant traffic floor passed to {@link GeminiCacheGate#decide}.
     * {@code 0} disables the traffic gate; the token floor (1024/4096)
     * is always enforced because Gemini rejects sub-floor prefixes API-side.
     */
    private int minReqPerHour = 0;

    /**
     * TTL (seconds) for newly-created cache entries. Both sent to the
     * Gemini API as {@code ttl} and used locally to age out the in-memory
     * (key → cachedName) map so we don't keep re-sending a name Google
     * has already expired.
     */
    private long ttlSeconds = 3600L;

    /**
     * Base URL for the cachedContents REST API. Split from the models
     * URL because tests and staging environments may point to a mock.
     */
    private String apiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/cachedContents";
}
