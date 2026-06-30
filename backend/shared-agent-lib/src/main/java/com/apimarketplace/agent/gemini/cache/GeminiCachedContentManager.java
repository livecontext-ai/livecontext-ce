package com.apimarketplace.agent.gemini.cache;

import com.apimarketplace.agent.domain.ToolDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stage 2 - manager for Gemini {@code cachedContent} manual cache
 * entries. Wires the primitives ({@link GeminiCacheGate} +
 * {@link GeminiCachedContentKey}) to the Google REST API and holds the
 * {@code (keyHash → cachedName)} map so subsequent requests with the same
 * static prefix can attach the cache by name instead of re-uploading.
 *
 * <p><b>Lifecycle.</b> On {@link #getOrCreate}:
 * <ol>
 *   <li>If {@link GeminiCacheProperties#isEnabled() cache.enabled=false}
 *       → return {@link Optional#empty()}. No HTTP, no counters.</li>
 *   <li>Tick the per-(key, hour-bucket) counter. This produces
 *       {@code recentReqPerHour} for the eligibility gate.</li>
 *   <li>Ask {@link GeminiCacheGate#decide} whether to proceed. On skip,
 *       log the reason + threshold (so Grafana can chart skip rates)
 *       and return empty.</li>
 *   <li>Compute {@link GeminiCachedContentKey#compute canonical key}
 *       over {@code (staticSystemBlock, tools)}.</li>
 *   <li>Check the in-memory {@code entries} map. Hit (not expired) →
 *       return cached name.</li>
 *   <li>Miss → POST to {@code /v1beta/cachedContents?key={apiKey}} with
 *       {@code (model, systemInstruction, tools, ttl)}. Store the
 *       returned {@code name} in {@code entries} with a local
 *       expiry derived from {@code ttlSeconds}.</li>
 * </ol>
 *
 * <p><b>Failure semantics.</b> Every failure mode - HTTP 4xx/5xx, network
 * error, malformed response body - is <em>swallowed</em> (logged, never
 * rethrown). The main {@code generateContent} request must run with or
 * without cache; caching is a cost optimization, not a correctness
 * requirement. A failed cache create MUST NOT break the chat turn.
 *
 * <p><b>Thread-safety.</b> {@code entries} is a {@link ConcurrentHashMap};
 * the traffic counter uses {@link AtomicInteger}. Two concurrent misses
 * on the same key can race and upload twice - we accept this because
 * Gemini deduplicates identical content server-side and the cost of
 * one extra upload is negligible compared to the lock contention a
 * per-key CAS would introduce.
 *
 * <p><b>Scope.</b> In-memory only. If the pod restarts the name map is
 * lost, which means the next request pays one upload to re-create. A
 * Redis-backed variant is tracked in Stage 2 follow-ups (task #51).
 *
 * <p><b>Background sweeper.</b> {@link #sweep()} runs every
 * {@link #SWEEP_INTERVAL_MS} to evict locally-expired {@code entries}
 * and stale {@code trafficByKey} hour-buckets. Without the sweeper,
 * keys that stop being hit would leak both maps forever on long-running
 * pods - the existing miss-on-expiry cleanup at
 * {@link #getOrCreate} only fires when the same key is re-queried.
 * The sweeper is a pure in-memory pass; it does not DELETE cache
 * entries on Google's side (they TTL-out server-side automatically).
 */
@Slf4j
@Component
public class GeminiCachedContentManager {

    private final GeminiCacheProperties properties;
    private final RestTemplate restTemplate;
    /**
     * Injectable clock so tests can drive expiry without {@code Thread.sleep}.
     * Production uses {@link Clock#systemUTC()}; tests substitute a
     * {@link Clock#fixed} or {@link Clock#offset}-based clock.
     */
    private final Clock clock;

    /** {@code keyHash → (cachedName, localExpiry)}. Cleared by miss-on-expiry. */
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    /**
     * {@code keyHash → (hourEpoch, count)}. Bounded at one entry per
     * distinct cache key: on each {@link #tickTraffic} we either bump
     * the counter for the current hour or reset it when the hour
     * rolls over. This avoids the per-hour leak where stale
     * {@code (key, hourEpoch)} entries would otherwise accumulate
     * indefinitely on a long-running pod.
     */
    private final Map<String, HourBucket> trafficByKey = new ConcurrentHashMap<>();

    @Autowired
    public GeminiCachedContentManager(GeminiCacheProperties properties, RestTemplate restTemplate) {
        this(properties, restTemplate, Clock.systemUTC());
    }

    /** Test-only ctor; allows a mutable {@link Clock} for expiry assertions. */
    GeminiCachedContentManager(GeminiCacheProperties properties,
                               RestTemplate restTemplate,
                               Clock clock) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.clock = clock;
    }

    /**
     * Resolve or create a cached-content entry for a static prefix.
     *
     * @param apiKey             Gemini API key used to authorise both
     *                           the cache create call and the subsequent
     *                           {@code generateContent} call.
     * @param model              model id (e.g. {@code "gemini-1.5-pro"}).
     *                           Must match the model the
     *                           {@code generateContent} call will target
     *                           - cached content is pinned per-model by
     *                           Google.
     * @param systemBlock        static system block text (block 0 only).
     *                           Per-tenant material must be excluded
     *                           upstream; see {@link GeminiCachedContentKey}
     *                           for the security invariant.
     * @param tools              tool list to attach to the cache.
     * @param prefixTokenCount   caller's estimate of the static prefix
     *                           token count (for the API floor gate).
     * @return cached-content resource name ({@code "cachedContents/…"})
     *         to attach to the next {@code generateContent} request, or
     *         empty if the gate rejected the prefix, the cache is
     *         disabled, or the POST failed.
     */
    public Optional<String> getOrCreate(String apiKey,
                                        String model,
                                        String systemBlock,
                                        List<ToolDefinition> tools,
                                        int prefixTokenCount) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("gemini.cache skip reason=missing_api_key");
            return Optional.empty();
        }

        String key;
        try {
            key = GeminiCachedContentKey.compute(systemBlock == null ? "" : systemBlock, tools);
        } catch (RuntimeException e) {
            log.warn("gemini.cache skip reason=key_compute_failed error={}", e.getMessage());
            return Optional.empty();
        }

        int recentReqPerHour = tickTraffic(key);

        GeminiCacheGate.Decision decision = GeminiCacheGate.decide(
                model, prefixTokenCount, recentReqPerHour, properties.getMinReqPerHour());
        if (!decision.eligible()) {
            log.debug("gemini.cache skip reason={} model={} threshold={} prefix_tokens={} req_per_hour={}",
                    decision.reason(), decision.modelName(), decision.threshold(),
                    prefixTokenCount, recentReqPerHour);
            return Optional.empty();
        }

        CacheEntry existing = entries.get(key);
        Instant now = clock.instant();
        if (existing != null && existing.expiresAt.isAfter(now)) {
            log.debug("gemini.cache hit key={} name={}", shortKey(key), existing.cachedName);
            return Optional.of(existing.cachedName);
        }
        if (existing != null) {
            entries.remove(key, existing);
        }

        Optional<String> created = createRemote(apiKey, model, systemBlock, tools);
        created.ifPresent(name -> {
            // Subtract a safety margin so we don't hand out a name whose
            // server-side TTL may already have elapsed due to clock skew
            // between this pod and Google. A failed generateContent due to
            // "cachedContent not found" would break the entire chat turn
            // - the try/catch around attach is too late at that point.
            long localTtlSeconds = Math.max(1L,
                    properties.getTtlSeconds() - TTL_SAFETY_MARGIN_SECONDS);
            Instant expiresAt = now.plus(Duration.ofSeconds(localTtlSeconds));
            entries.put(key, new CacheEntry(name, expiresAt));
            log.info("gemini.cache create key={} name={} ttl_s={} local_ttl_s={}",
                    shortKey(key), name, properties.getTtlSeconds(), localTtlSeconds);
        });
        return created;
    }

    /**
     * Safety margin (seconds) subtracted from {@code ttlSeconds} when we
     * compute local expiry. Guards against clock skew between this pod
     * and Google: if Google already expired our cache name we must
     * discover that on our next {@code getOrCreate} call and re-upload,
     * not on a live {@code generateContent} request.
     */
    private static final long TTL_SAFETY_MARGIN_SECONDS = 60L;

    /**
     * Sweeper tick interval (ms). 5 minutes is short enough that a bursty
     * workload which evicts thousands of keys won't pile more than a
     * handful of megabytes of stale map entries, and long enough that
     * the sweeper cost is negligible. Not configurable: the default
     * suits every current workload and an extra property would add
     * surface area without a concrete use case.
     */
    static final long SWEEP_INTERVAL_MS = 5 * 60 * 1_000L;

    /**
     * Drop {@code trafficByKey} buckets older than this many hours. The
     * gate only reads the current hour's counter, so anything older is
     * dead weight. 2h is a conservative margin against clock skew and
     * sweeper scheduling jitter.
     */
    static final long TRAFFIC_BUCKET_STALE_HOURS = 2L;

    private int tickTraffic(String key) {
        long hourEpoch = clock.instant().getEpochSecond() / 3600L;
        HourBucket updated = trafficByKey.compute(key, (k, prev) -> {
            if (prev == null || prev.hourEpoch != hourEpoch) {
                return new HourBucket(hourEpoch, 1);
            }
            return new HourBucket(hourEpoch, prev.count + 1);
        });
        return updated.count;
    }

    private Optional<String> createRemote(String apiKey,
                                          String model,
                                          String systemBlock,
                                          List<ToolDefinition> tools) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("model", "models/" + stripLeadingModels(model));
            if (systemBlock != null && !systemBlock.isEmpty()) {
                body.put("systemInstruction", Map.of(
                        "parts", List.of(Map.of("text", systemBlock))
                ));
            }
            if (tools != null && !tools.isEmpty()) {
                body.put("tools", List.of(Map.of(
                        "functionDeclarations", buildFunctionDeclarations(tools)
                )));
            }
            body.put("ttl", properties.getTtlSeconds() + "s");

            // API key travels as a header, not in the query string. Any
            // exception thrown by RestTemplate includes the URL in its
            // message; keeping the key out of the URL prevents the
            // catch-all log.warn below from leaking the credential to
            // the log stream.
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-goog-api-key", apiKey);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            String url = properties.getApiBaseUrl();

            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object name = response.getBody().get("name");
                if (name instanceof String s && !s.isBlank()) {
                    return Optional.of(s);
                }
                log.warn("gemini.cache create malformed_response body_keys={}", response.getBody().keySet());
                return Optional.empty();
            }
            log.warn("gemini.cache create non_2xx status={}", response.getStatusCode());
            return Optional.empty();
        } catch (Exception e) {
            log.warn("gemini.cache create error={}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Evict locally-expired {@code entries} and hour-buckets older than
     * {@link #TRAFFIC_BUCKET_STALE_HOURS}. Runs on Spring's default
     * scheduler thread; idempotent, safe to call concurrently with
     * {@link #getOrCreate} (both maps are {@link ConcurrentHashMap}).
     *
     * <p>Package-visible so tests can drive eviction without sleeping
     * {@link #SWEEP_INTERVAL_MS}. The scheduled trigger lives on a
     * separate {@code GeminiCacheSweeper} component so this class avoids
     * {@code @Scheduled}-induced CGLIB proxying - a proxied target with
     * a parameterised-only constructor trips Spring's bean instantiation.
     */
    void sweep() {
        // Intentionally NOT gated on properties.isEnabled(): if the
        // operator toggles the feature off while entries are still in
        // memory, we still need to evict them - the getOrCreate path
        // also early-returns on !enabled so there's no other cleanup
        // opportunity. The sweep is I/O-free and allocation-light;
        // the guard would re-create the leak the sweeper exists to fix.
        Instant now = clock.instant();
        long staleBefore = (now.getEpochSecond() / 3600L) - TRAFFIC_BUCKET_STALE_HOURS;

        int evictedEntries = 0;
        // Iterate over a snapshot of the entry set so concurrent
        // getOrCreate inserts don't trip the sweep. ConcurrentHashMap's
        // iterator is weakly consistent which is what we want here.
        for (Map.Entry<String, CacheEntry> e : entries.entrySet()) {
            if (!e.getValue().expiresAt.isAfter(now)) {
                if (entries.remove(e.getKey(), e.getValue())) {
                    evictedEntries++;
                }
            }
        }

        int evictedBuckets = 0;
        for (Map.Entry<String, HourBucket> e : trafficByKey.entrySet()) {
            if (e.getValue().hourEpoch < staleBefore) {
                if (trafficByKey.remove(e.getKey(), e.getValue())) {
                    evictedBuckets++;
                }
            }
        }

        if (evictedEntries > 0 || evictedBuckets > 0) {
            log.info("gemini.cache sweep evicted_entries={} stale_buckets={} live_entries={} live_buckets={}",
                    evictedEntries, evictedBuckets, entries.size(), trafficByKey.size());
        } else {
            log.debug("gemini.cache sweep no-op live_entries={} live_buckets={}",
                    entries.size(), trafficByKey.size());
        }
    }

    private static String stripLeadingModels(String model) {
        if (model == null) return "";
        return model.startsWith("models/") ? model.substring("models/".length()) : model;
    }

    private List<Map<String, Object>> buildFunctionDeclarations(List<ToolDefinition> tools) {
        List<Map<String, Object>> out = new java.util.ArrayList<>(tools.size());
        for (ToolDefinition t : tools) {
            Map<String, Object> decl = new HashMap<>();
            decl.put("name", t.name());
            if (t.description() != null) {
                decl.put("description", t.description());
            }
            Map<String, Object> schema = new HashMap<>();
            schema.put("type", "object");
            Map<String, Object> props = new HashMap<>();
            List<String> required = new java.util.ArrayList<>();
            if (t.parameters() != null) {
                for (var p : t.parameters()) {
                    Map<String, Object> pSchema = new HashMap<>();
                    pSchema.put("type", p.type() == null ? "string" : p.type());
                    if (p.description() != null) pSchema.put("description", p.description());
                    if (p.enumValues() != null && !p.enumValues().isEmpty()) {
                        pSchema.put("enum", p.enumValues());
                    }
                    props.put(p.name(), pSchema);
                    if (Boolean.TRUE.equals(p.required())) required.add(p.name());
                }
            }
            schema.put("properties", props);
            if (!required.isEmpty()) schema.put("required", required);
            decl.put("parameters", schema);
            out.add(decl);
        }
        return out;
    }

    private static String shortKey(String key) {
        return key == null || key.length() <= 12 ? key : key.substring(0, 12);
    }

    private record CacheEntry(String cachedName, Instant expiresAt) {}

    private record HourBucket(long hourEpoch, int count) {}
}
