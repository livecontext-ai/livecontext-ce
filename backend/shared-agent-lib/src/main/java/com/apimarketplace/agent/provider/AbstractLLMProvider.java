package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.netty.http.client.HttpClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Abstract base class for LLM providers.
 * Provides common functionality for HTTP communication, response parsing, and streaming.
 *
 * Follows Template Method Pattern - subclasses implement specific methods.
 */
@Slf4j
public abstract class AbstractLLMProvider implements LLMProvider {

    protected final RestTemplate restTemplate;
    protected final ObjectMapper objectMapper;

    /**
     * True when this provider created its OWN {@link RestTemplate} (the no-arg constructor) and is
     * therefore responsible for bounding it. False when a caller supplied a pre-configured
     * RestTemplate (a Spring bean owns its request factory/timeouts - we must not clobber them).
     */
    private final boolean ownsRestTemplate;

    // Optional rate limiter (null if not configured via Spring)
    protected com.apimarketplace.agent.ratelimit.ProviderRateLimiter rateLimiter;

    // Optional credential resolver for DB-stored API keys (null if not configured via Spring)
    protected com.apimarketplace.agent.resolver.LlmCredentialResolver credentialResolver;

    // Optional token estimator - Jtokkit (cl100k_base) when available, falls back
    // to the inline chars/4 heuristic when no bean is wired. Stage 1a.4.
    protected com.apimarketplace.agent.tokenizer.TokenEstimator tokenEstimator;

    @org.springframework.beans.factory.annotation.Value("${ai.agent.llm.connect-timeout-ms:${workflow.execution.step-timeout-ms:30000}}")
    private long llmConnectTimeoutMs = 30_000;

    @org.springframework.beans.factory.annotation.Value("${ai.agent.llm.read-timeout-ms:${workflow.execution.workflow-timeout-ms:3600000}}")
    private long llmReadTimeoutMs = 3_600_000;

    // Lazy-initialized WebClient for reactive streaming
    private volatile WebClient webClient;
    private final Object webClientLock = new Object();

    protected AbstractLLMProvider() {
        this.restTemplate = new RestTemplate();
        this.objectMapper = new ObjectMapper();
        // A plain `new RestTemplate()` has an INFINITE read timeout (SimpleClientHttpRequestFactory
        // default readTimeout = -1): a provider that accepts the request then goes silent would hang
        // the calling agent-loop thread forever on the non-streaming complete() path (the streaming
        // path already has a finite SO_TIMEOUT via setupStreamingConnection). Bound it now with the
        // field-initializer defaults so even plain `new`-constructed providers (unit tests) are
        // bounded; @PostConstruct re-applies once @Value overrides are injected.
        this.ownsRestTemplate = true;
        applyOwnedRestTemplateTimeouts();
    }

    protected AbstractLLMProvider(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        // Caller-supplied RestTemplate already carries its own request factory/timeouts - do not touch.
        this.ownsRestTemplate = false;
    }

    /**
     * Bound the non-streaming {@code complete()} path with finite connect + read timeouts.
     *
     * <p>Only applies when this provider owns its {@link RestTemplate} (no-arg constructor). The
     * read timeout reuses {@code ai.agent.llm.read-timeout-ms} (the same property the streaming
     * SO_TIMEOUT uses); on a non-streaming call it bounds total response time, sized generously
     * (default 1h) so a legitimately long completion is never killed while an infinite hang can no
     * longer happen. Invoked from the no-arg constructor (field-initializer defaults, so non-Spring
     * unit tests are bounded too) and again by Spring as {@code @PostConstruct} once the
     * {@code @Value} overrides are injected. Idempotent.</p>
     */
    @jakarta.annotation.PostConstruct
    void applyOwnedRestTemplateTimeouts() {
        if (!ownsRestTemplate) {
            return;
        }
        org.springframework.http.client.SimpleClientHttpRequestFactory factory =
            new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(llmConnectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(llmReadTimeoutMs));
        restTemplate.setRequestFactory(factory);
    }

    /**
     * Set rate limiter (called by Spring if available).
     * Optional dependency - provider works without rate limiting.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRateLimiter(com.apimarketplace.agent.ratelimit.ProviderRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
        if (rateLimiter != null) {
            log.info("Rate limiter enabled for {}", getProviderName());
        }
    }

    /**
     * Set credential resolver (called by Spring if available).
     * Optional dependency - providers fall back to @Value-injected keys when not available.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCredentialResolver(com.apimarketplace.agent.resolver.LlmCredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
        if (credentialResolver != null) {
            log.info("DB credential resolver enabled for {}", getProviderName());
        }
    }

    /**
     * Set token estimator (Stage 1a.4, called by Spring if available).
     * Optional dependency - falls back to the inline chars/4 heuristic when no
     * bean is wired (non-Spring contexts, tests, services that opt out via
     * {@code ai.token-estimator.mode=none}).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setTokenEstimator(com.apimarketplace.agent.tokenizer.TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
        if (tokenEstimator != null) {
            log.info("Token estimator '{}' enabled for {}", tokenEstimator.name(), getProviderName());
        }
    }

    /**
     * Resolve the API key: checks DB-stored credentials first, falls back to @Value-injected key.
     */
    protected String resolveApiKey() {
        if (credentialResolver != null) {
            var dbKey = credentialResolver.resolveApiKey(getProviderName());
            if (dbKey.isPresent()) {
                return dbKey.get();
            }
        }
        return getApiKey();
    }

    /**
     * Get or create WebClient for reactive streaming.
     * Lazy-initialized to avoid overhead if not used.
     */
    protected WebClient getWebClient() {
        if (webClient == null) {
            synchronized (webClientLock) {
                if (webClient == null) {
                    HttpClient httpClient = HttpClient.create()
                            .responseTimeout(Duration.ofMillis(llmReadTimeoutMs));

                    webClient = WebClient.builder()
                            .clientConnector(new ReactorClientHttpConnector(httpClient))
                            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                            .build();
                }
            }
        }
        return webClient;
    }

    /**
     * Get the API key for this provider.
     */
    protected abstract String getApiKey();

    /**
     * Get the API URL for this provider.
     */
    protected abstract String getApiUrl();

    /**
     * Build the request body for this provider.
     */
    protected abstract Map<String, Object> buildRequestBody(CompletionRequest request);

    /**
     * Parse the response from this provider.
     */
    protected abstract CompletionResponse parseResponse(Map<String, Object> response);

    /**
     * Build HTTP headers for this provider.
     */
    protected abstract HttpHeaders buildHeaders();

    /**
     * Process a streaming line and extract content.
     * Returns the content chunk or null if this line should be skipped.
     */
    protected abstract String processStreamingLine(String line);

    /**
     * Parse tool calls from streaming data.
     * Override in providers that support tool calling in streaming mode.
     */
    protected List<ToolCall> parseStreamingToolCalls(String data) {
        return null;
    }

    /**
     * Customize the request body for streaming requests.
     * Called after "stream": true is set. Override to add provider-specific streaming options.
     * For example, OpenAI-compatible APIs use stream_options to request usage in streaming responses.
     */
    protected void addStreamingRequestOptions(Map<String, Object> body) {
        // Default: no additional options. Override in providers.
    }

    /**
     * Extract token usage information from a streaming line.
     * Override in providers to capture usage data sent during streaming.
     *
     * Different providers send usage at different points:
     * - Claude: message_start (input_tokens) + message_delta (output_tokens)
     * - OpenAI: final chunk with usage field (when stream_options.include_usage=true)
     * - Gemini: usageMetadata in the last streaming chunk
     *
     * Results are accumulated across all lines using UsageInfo.add().
     *
     * @param line the raw streaming line (may include "data: " prefix)
     * @return UsageInfo if this line contains usage data, null otherwise
     */
    protected UsageInfo extractStreamingUsage(String line) {
        return null;
    }

    @Override
    public boolean isConfigured() {
        String key = resolveApiKey();
        if (key == null) return false;
        String trimmed = key.trim();
        if (trimmed.isEmpty()) return false;
        // Reject obvious placeholder values that env templates ship by default
        // (CHANGEME, your-key-here, etc.). Without this guard the admin UI
        // reports providers as "configured from environment" when env.conf
        // still has the bootstrap stub - confusing operators into thinking
        // a real key is loaded.
        String upper = trimmed.toUpperCase();
        if (upper.startsWith("CHANGEME") || upper.startsWith("YOUR_") || upper.startsWith("YOUR-")
                || upper.equals("PLACEHOLDER") || upper.startsWith("PLACEHOLDER_")
                || upper.equals("TODO") || upper.equals("XXXX")) {
            return false;
        }
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean supportsToolCalling() {
        return true;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        if (!isConfigured()) {
            throw new LLMProviderException(getProviderName(),
                "Provider is not configured. API key is missing.");
        }

        // Check rate limit before making request
        int estimatedTokens = estimateTokens(request);
        if (rateLimiter != null) {
            rateLimiter.checkRateLimit(getProviderName(), request.model(), request.tenantId(), estimatedTokens);
        }

        try {
            Map<String, Object> requestBody = buildRequestBody(request);
            HttpHeaders headers = buildHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.debug("Sending request to {} with model {} (estimated tokens: {})",
                getProviderName(),
                request.model() != null ? request.model() : getDefaultModel(),
                estimatedTokens);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(
                getApiUrl(), HttpMethod.POST, entity, Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();
                CompletionResponse result = parseResponse(body);

                // Record actual usage
                if (rateLimiter != null && result.usage() != null) {
                    int actualTokens = result.usage().totalTokens() != null
                        ? result.usage().totalTokens()
                        : estimatedTokens;
                    rateLimiter.recordRequest(getProviderName(), request.model(), request.tenantId(), actualTokens);
                }

                return result;
            } else {
                throw new LLMProviderException(getProviderName(),
                    "Unexpected response status: " + response.getStatusCode());
            }

        } catch (HttpClientErrorException e) {
            return handleHttpError(e);
        } catch (LLMProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling {} API: {}", getProviderName(), e.getMessage(), e);
            throw new LLMProviderException(getProviderName(),
                "Error calling API: " + e.getMessage(), e);
        }
    }

    @Override
    public void completeStreaming(CompletionRequest request, StreamingCallback callback) {
        if (!isConfigured()) {
            // Visibility: streaming silent-return paths used to die without any agent-service log.
            // Sync complete() throws LLMProviderException → propagated by AgentLoopService.execute
            // catch block. Streaming had no equivalent surface - the only visible signal was the
            // SSE channel onError, which the orchestrator's NodeExecutionResult never carried back
            // to the user. Now we log so future failures are debuggable from agent-service.log alone.
            log.warn("[{}] completeStreaming aborted: provider not configured (tenant={}, model={}, cache miss + invalid env fallback)",
                getProviderName(), request.tenantId(), request.model());
            callback.onError("Provider is not configured. API key is missing.");
            return;
        }

        if (!supportsStreaming()) {
            throw LLMProviderException.streamingNotSupported(getProviderName());
        }

        // Check rate limit before making request
        int estimatedTokens = estimateTokens(request);
        if (rateLimiter != null) {
            try {
                // F1.3 - pass shouldStop so a STOP arriving while we're queued
                // behind the rate limiter doesn't wait for the next slot.
                rateLimiter.checkRateLimit(getProviderName(), request.model(), request.tenantId(), estimatedTokens,
                    (java.util.function.BooleanSupplier) callback::shouldStop);
            } catch (LLMProviderException e) {
                log.warn("[{}] completeStreaming aborted by rate limiter (tenant={}, est_tokens={}): {}",
                    getProviderName(), request.tenantId(), estimatedTokens, e.getMessage());
                callback.onError(e.getMessage());
                return;
            }
        }

        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            // Build streaming request
            Map<String, Object> requestBody = buildRequestBody(request);
            requestBody.put("stream", true);
            addStreamingRequestOptions(requestBody);

            // Create connection
            URI uri = URI.create(getApiUrl());
            connection = (HttpURLConnection) uri.toURL().openConnection();
            setupStreamingConnection(connection);
            // Inactivity watchdog: tighten the socket read timeout to a sub-window poll cadence so
            // shouldStop() is consulted even while the provider streams nothing (a fully-silent
            // stream is then broken at the inactivity window, not only at the larger read timeout).
            applyInactivityPollTimeout(connection, callback);

            // Send request
            String requestJson = objectMapper.writeValueAsString(requestBody);
            log.info("Sending streaming request to {} - tools count: {}, body length: {} chars",
                getProviderName(),
                requestBody.containsKey("tools") ? ((java.util.List<?>)requestBody.get("tools")).size() : 0,
                requestJson.length());
            connection.getOutputStream().write(requestJson.getBytes(StandardCharsets.UTF_8));

            // Check response code
            int responseCode = connection.getResponseCode();
            if (responseCode >= 400) {
                String errorMessage = readErrorStream(connection);
                log.error("HTTP {} from {} streaming - body length was {} chars, error: {}",
                    responseCode, getProviderName(), requestJson.length(), errorMessage);
                callback.onError("HTTP " + responseCode + ": " + errorMessage);
                return;
            }

            // Process streaming response
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            CompletionResponse streamingResult = processStreamingResponse(reader, callback);

            // Record usage after streaming completes (prefer real usage over estimate)
            UsageInfo realUsage = streamingResult != null ? streamingResult.usage() : null;
            recordStreamingUsage(request.model(), request.tenantId(), estimatedTokens, realUsage);

        } catch (Exception e) {
            log.error("Error during {} streaming: {}", getProviderName(), e.getMessage(), e);
            callback.onError("Streaming error: " + e.getMessage());
        } finally {
            closeResources(reader, connection);
        }
    }

    @Override
    public Flux<StreamingEvent> streamReactive(CompletionRequest request) {
        if (!isConfigured()) {
            return Flux.error(new LLMProviderException(getProviderName(),
                    "Provider is not configured. API key is missing."));
        }

        // Check rate limit before making request.
        // F1.3 - streamReactive has no StreamingCallback to poll, but the
        // caller can dispose the returned Flux at any time. The rate-limit
        // wait is unaffected by Flux dispose (we're in a synchronous prelude).
        // We still pass () -> false explicitly so the call site is documented;
        // Phase 4 will plumb a real cancel signal here via a Reactor sink hook
        // or a per-call cancel token sourced from the orchestrator runId.
        int estimatedTokens = estimateTokens(request);
        if (rateLimiter != null) {
            try {
                rateLimiter.checkRateLimit(getProviderName(), request.model(), request.tenantId(), estimatedTokens,
                    (java.util.function.BooleanSupplier) () -> false);
            } catch (LLMProviderException e) {
                return Flux.error(e);
            }
        }

        Map<String, Object> requestBody = buildRequestBody(request);
        requestBody.put("stream", true);
        addStreamingRequestOptions(requestBody);

        HttpHeaders headers = buildHeaders();
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(requestBody);
            log.debug("Request body for {}: {}", getProviderName(),
                    requestJson.length() > 500 ? requestJson.substring(0, 500) + "..." : requestJson);
        } catch (Exception e) {
            requestJson = "serialization error";
        }

        final int estimatedTokensForStream = estimatedTokens;
        final String modelIdForStream = request.model();
        final String tenantIdForStream = request.tenantId();

        return Flux.create(sink -> {
            StringBuilder fullContent = new StringBuilder();
            StringBuilder rawResponse = new StringBuilder();
            Map<Integer, StreamingToolCallAccumulator> toolCallAccumulators = new HashMap<>();
            AtomicReference<reactor.core.Disposable> disposableRef = new AtomicReference<>();
            AtomicReference<Boolean> hasEmittedContent = new AtomicReference<>(false);
            AtomicReference<UsageInfo> streamUsageRef = new AtomicReference<>(null);

            log.info("Starting reactive stream to {} with model {} (estimated tokens: {})",
                    getProviderName(), request.model() != null ? request.model() : getDefaultModel(),
                    estimatedTokensForStream);

            reactor.core.Disposable subscription = getWebClient().post()
                    .uri(getApiUrl())
                    .headers(h -> headers.forEach(h::addAll))
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            response.bodyToMono(String.class)
                                    .doOnNext(body -> log.error("HTTP error from {}: {} - {}",
                                            getProviderName(), response.statusCode(), body))
                                    .map(body -> new LLMProviderException(getProviderName(),
                                            "HTTP " + response.statusCode() + ": " + extractErrorMessage(body))))
                    .bodyToFlux(String.class)
                    .doOnNext(line -> {
                        // Log raw response for debugging
                        log.debug("📥 [STREAM RAW] {}: {}", getProviderName(),
                                line.length() > 200 ? line.substring(0, 200) + "..." : line);

                        if (rawResponse.length() < 2000) {
                            rawResponse.append(line).append("\n");
                        }

                        // Extract usage from every line.
                        // NOTE: summing here is safe for providers that emit usage deltas
                        // (Anthropic) or once on the final chunk (OpenAI). Providers that
                        // emit CUMULATIVE running totals on every chunk (Gemini) must
                        // override their streaming loop to replace-with-latest - summing
                        // would triple-count tokens. See GeminiProvider.processGeminiStreamingResponse.
                        UsageInfo lineUsage = extractStreamingUsage(line);
                        if (lineUsage != null) {
                            streamUsageRef.updateAndGet(current ->
                                current == null ? lineUsage : current.add(lineUsage));
                        }

                        // Check for end of stream
                        if (isEndOfStream(line)) {
                            log.info("📥 [STREAM] End of stream detected for {}", getProviderName());
                            hasEmittedContent.set(true);
                            // Finalize accumulated tool calls
                            for (StreamingToolCallAccumulator acc : toolCallAccumulators.values()) {
                                if (acc.isComplete()) {
                                    ToolCall tc = acc.build(objectMapper);
                                    if (tc != null) {
                                        sink.next(StreamingEvent.toolCall(tc));
                                    }
                                }
                            }

                            // Emit completed event with accumulated usage
                            UsageInfo finalUsage = streamUsageRef.get();
                            if (finalUsage != null) {
                                log.info("📊 [STREAM USAGE] {} reactive captured: promptTokens={}, completionTokens={}, total={}",
                                    getProviderName(), finalUsage.promptTokens(), finalUsage.completionTokens(), finalUsage.getTotal());
                            }
                            CompletionResponse response = CompletionResponse.builder()
                                    .content(fullContent.toString())
                                    .finishReason("stop")
                                    .usage(finalUsage)
                                    .build();
                            sink.next(StreamingEvent.completed(response));
                            return;
                        }

                        // Check for error in response (some APIs return error in SSE format)
                        if (line.contains("\"error\"")) {
                            log.error("💥 [STREAM] Error in SSE response from {}: {}", getProviderName(), line);
                            String errorMsg = extractErrorMessage(line.startsWith("data: ") ? line.substring(6) : line);
                            sink.next(StreamingEvent.error(errorMsg, null));
                            hasEmittedContent.set(true);
                            return;
                        }

                        // Process content chunk
                        String chunk = processStreamingLine(line);
                        if (chunk != null && !chunk.isEmpty()) {
                            log.debug("📥 [STREAM] Content chunk from {}: '{}'", getProviderName(),
                                    chunk.length() > 50 ? chunk.substring(0, 50) + "..." : chunk);
                            fullContent.append(chunk);
                            sink.next(StreamingEvent.content(chunk));
                            hasEmittedContent.set(true);
                        } else {
                            log.trace("📥 [STREAM] No content extracted from line: {}",
                                    line.length() > 100 ? line.substring(0, 100) + "..." : line);
                        }

                        // Accumulate tool call chunks
                        accumulateStreamingToolCalls(line, toolCallAccumulators);
                    })
                    .doOnComplete(() -> {
                        log.info("✅ [STREAM] Completed for {}, content length: {}, hasContent: {}",
                                getProviderName(), fullContent.length(), hasEmittedContent.get());

                        // If no content was emitted, there might be an error we missed
                        if (fullContent.length() == 0) {
                            log.warn("⚠️ [STREAM] Completed with no content! Raw response:\n{}", rawResponse);
                            if (!hasEmittedContent.get()) {
                                sink.next(StreamingEvent.error("Stream completed with no content - possible API error", null));
                            }
                        }

                        // Record usage after streaming completes (prefer real usage over estimate)
                        recordStreamingUsage(modelIdForStream, tenantIdForStream, estimatedTokensForStream, streamUsageRef.get());

                        sink.complete();
                    })
                    .doOnError(error -> {
                        log.error("Reactive stream error for {}: {}", getProviderName(), error.getMessage(), error);
                        sink.next(StreamingEvent.error(error.getMessage(), error));
                        sink.complete();
                    })
                    .subscribe();

            disposableRef.set(subscription);

            // Handle cancellation
            sink.onDispose(() -> {
                log.debug("Reactive stream disposed for {}", getProviderName());
                reactor.core.Disposable d = disposableRef.get();
                if (d != null && !d.isDisposed()) {
                    d.dispose();
                }
            });
        });
    }

    /**
     * Setup HTTP connection for streaming.
     */
    protected void setupStreamingConnection(HttpURLConnection connection) throws Exception {
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setConnectTimeout((int) llmConnectTimeoutMs);
        connection.setReadTimeout((int) llmReadTimeoutMs);

        HttpHeaders headers = buildHeaders();
        headers.forEach((key, values) -> {
            if (values != null && !values.isEmpty()) {
                connection.setRequestProperty(key, values.get(0));
            }
        });
        connection.setRequestProperty("Accept", "text/event-stream");
    }

    /**
     * When an inactivity watchdog is active for this run (the callback exposes a positive window),
     * set the streaming socket read timeout to the inactivity window so a fully-silent stream throws
     * a {@link java.net.SocketTimeoutException} once that window of no bytes elapses (the read loop
     * then re-checks {@link StreamingCallback#shouldStop()}). No-op otherwise: the read timeout set
     * by {@link #setupStreamingConnection} stands.
     */
    private void applyInactivityPollTimeout(HttpURLConnection connection, StreamingCallback callback) {
        long windowMs = callback.getInactivityTimeoutMs();
        if (windowMs > 0) {
            connection.setReadTimeout((int) streamingReadTimeoutMs(llmReadTimeoutMs, windowMs));
        }
    }

    /**
     * Streaming socket read (inter-byte) timeout when an inactivity watchdog is active: the
     * inactivity window itself, capped by the configured read timeout, floored at 1s.
     *
     * <p>Set to the WINDOW (not a short poll cadence) on purpose: a fully-silent stream then throws
     * exactly ONE {@link java.net.SocketTimeoutException} once the window of no bytes elapses (the
     * watchdog classifies it as INACTIVITY_TIMEOUT), instead of throwing every few seconds. That
     * matters because {@link java.io.BufferedReader#readLine()} discards its in-progress line buffer
     * when the underlying read throws, so a timeout landing mid-line silently truncates that SSE
     * event. One timeout per FULL window of silence makes that risk negligible (it would need a
     * single line to stall mid-transfer for the entire window), while the bytes-but-no-content case
     * is still caught promptly by the per-line {@code shouldStop()} check.</p>
     */
    static long streamingReadTimeoutMs(long readTimeoutMs, long inactivityWindowMs) {
        long capped = Math.min(readTimeoutMs > 0 ? readTimeoutMs : Long.MAX_VALUE, inactivityWindowMs);
        return Math.max(1000L, capped);
    }

    /**
     * Process streaming response line by line.
     * Returns the final CompletionResponse (with usage if the provider extracts it).
     */
    protected CompletionResponse processStreamingResponse(BufferedReader reader, StreamingCallback callback) throws Exception {
        String line;
        StringBuilder fullContent = new StringBuilder();
        List<ToolCall> pendingToolCalls = new ArrayList<>();
        UsageInfo streamUsage = null;

        // Accumulate streaming tool calls (OpenAI sends arguments in chunks)
        Map<Integer, StreamingToolCallAccumulator> toolCallAccumulators = new HashMap<>();

        // Delta 3 - local completion-token cap. When the callback declares a budget, the loop
        // aborts once the accumulated content exceeds ~budget tokens (approximated at 4 chars
        // per token). Purely local - no HTTP call in the hot path. Default −1 → inert.
        long completionTokenBudget = callback.getCompletionTokenBudget();
        boolean budgetCapped = false;

        // Inactivity poll: when a watchdog is active the socket read timeout is tightened to a
        // sub-window cadence (applyInactivityPollTimeout). A read timeout then means "no provider
        // output for the poll window" - we re-check shouldStop() (the inactivity watchdog trips once
        // total silence exceeds the configured window, and a user/system cancel also surfaces here)
        // and otherwise keep waiting, instead of erroring out. With no watchdog the timeout
        // propagates exactly as before.
        long inactivityWindowMs = callback.getInactivityTimeoutMs();
        while (true) {
            try {
                line = reader.readLine();
            } catch (java.net.SocketTimeoutException ste) {
                if (inactivityWindowMs > 0) {
                    if (callback.shouldStop()) {
                        log.warn("[STREAMING] No provider output for the inactivity window - breaking stream (content so far: {} chars)", fullContent.length());
                        break;
                    }
                    continue;
                }
                throw ste;
            }
            if (line == null) {
                break;
            }
            // Check for stop signal during streaming (allows mid-stream interruption)
            if (callback.shouldStop()) {
                log.info("[STREAMING] Stop signal detected mid-stream, breaking (content so far: {} chars)", fullContent.length());
                break;
            }

            // Hard cap on completion tokens - last line of defense against runaway generation
            // when the pre-flight estimate under-shot the real cost. Once tripped, we stop
            // reading from the provider stream; backpressure causes the server to drop the
            // connection for us. The partial response is still persisted by onComplete.
            if (completionTokenBudget >= 0
                    && (fullContent.length() / 4L) >= completionTokenBudget) {
                log.warn("[STREAMING] Completion token budget reached (budget={}, streamed≈{} tokens, {} chars) - aborting stream",
                    completionTokenBudget, fullContent.length() / 4L, fullContent.length());
                budgetCapped = true;
                break;
            }

            // Extract usage from every line (providers send usage at different points)
            UsageInfo lineUsage = extractStreamingUsage(line);
            if (lineUsage != null) {
                streamUsage = streamUsage == null ? lineUsage : streamUsage.add(lineUsage);
            }

            // Check for end of stream
            if (isEndOfStream(line)) {
                // Finalize accumulated tool calls
                for (StreamingToolCallAccumulator acc : toolCallAccumulators.values()) {
                    if (acc.isComplete()) {
                        ToolCall tc = acc.build(objectMapper);
                        if (tc != null) {
                            pendingToolCalls.add(tc);
                            callback.onToolCall(tc);
                        }
                    }
                }
                log.info("End of stream detected, total content length: {}, tool calls: {}",
                    fullContent.length(), pendingToolCalls.size());
                break;
            }

            // Process line
            String chunk = processStreamingLine(line);
            if (chunk != null && !chunk.isEmpty()) {
                fullContent.append(chunk);
                log.info("📨 [PROVIDER] Calling callback.onChunk with {} chars", chunk.length());
                callback.onChunk(chunk);
            }

            // Accumulate tool call chunks
            accumulateStreamingToolCalls(line, toolCallAccumulators);
        }

        if (streamUsage != null) {
            log.info("📊 [STREAM USAGE] {} captured: promptTokens={}, completionTokens={}, total={}",
                getProviderName(), streamUsage.promptTokens(), streamUsage.completionTokens(), streamUsage.getTotal());
        }

        // Build final response. When the budget cap fired we report it as the finish reason
        // so the agent loop can surface BUDGET_EXHAUSTED cleanly instead of "stop".
        CompletionResponse.CompletionResponseBuilder responseBuilder = CompletionResponse.builder()
            .content(fullContent.toString())
            .finishReason(budgetCapped ? "budget_exhausted" : "stop")
            .usage(streamUsage);

        if (!pendingToolCalls.isEmpty()) {
            responseBuilder.toolCalls(pendingToolCalls);
            responseBuilder.finishReason(budgetCapped ? "budget_exhausted" : "tool_calls");
        }

        CompletionResponse response = responseBuilder.build();
        callback.onComplete(response);
        return response;
    }
    
    /**
     * Accumulate streaming tool call chunks.
     * OpenAI sends tool calls in multiple chunks - first with id/name, then arguments piece by piece.
     */
    protected void accumulateStreamingToolCalls(String line, Map<Integer, StreamingToolCallAccumulator> accumulators) {
        // Default implementation - override in providers
    }
    
    /**
     * Helper class to accumulate streaming tool call chunks.
     */
    protected static class StreamingToolCallAccumulator {
        String id;
        String name;
        StringBuilder arguments = new StringBuilder();
        
        public boolean isComplete() {
            return id != null && name != null;
        }
        
        public ToolCall build(ObjectMapper mapper) {
            if (!isComplete()) return null;
            try {
                String argsStr = arguments.toString().trim();
                if (argsStr.isEmpty()) argsStr = "{}";
                Map<String, Object> args = mapper.readValue(argsStr, Map.class);
                return ToolCall.builder()
                    .id(id)
                    .toolName(name)
                    .arguments(args)
                    .build();
            } catch (Exception e) {
                return ToolCall.builder()
                    .id(id)
                    .toolName(name)
                    .arguments(Map.of())
                    .build();
            }
        }
    }

    /**
     * Check if this line indicates end of stream.
     */
    protected boolean isEndOfStream(String line) {
        return line.equals("data: [DONE]") || line.contains("[DONE]");
    }

    /**
     * Handle HTTP errors from the API.
     *
     * <p>Classification (extended 2026-04-29):
     * <ul>
     *   <li>401 → unauthorized (not retryable)</li>
     *   <li>429 → rateLimited (retryable, with parsed retry-after hint)</li>
     *   <li>408/425/5xx → transientFailure (retryable, with parsed retry-after hint)</li>
     *   <li>everything else → generic non-retryable</li>
     * </ul>
     * The retry-after hint is parsed from {@code Retry-After} header OR Google body
     * {@code error.details[].retryDelay} OR Google message regex {@code "retry in Xs"}.
     * Returns empty on parse failure (caller falls back to backoff). Never throws.
     */
    protected CompletionResponse handleHttpError(HttpClientErrorException e) {
        HttpStatusCode status = e.getStatusCode();
        String body = e.getResponseBodyAsString();
        Optional<Duration> retryAfter = parseRetryAfter(e.getResponseHeaders(), body);

        log.error("{} API error: {} - {}", getProviderName(), status, body);

        if (status == HttpStatus.UNAUTHORIZED) {
            throw LLMProviderException.unauthorized(getProviderName());
        }
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            throw LLMProviderException.rateLimited(getProviderName(), retryAfter.orElse(null));
        }
        int code = status.value();
        if (code == 408 || code == 425 || code == 503 || code == 504 || (code >= 500 && code < 600)) {
            throw LLMProviderException.transientFailure(getProviderName(),
                "HTTP " + code + " (" + status + ")", retryAfter.orElse(null));
        }

        String errorMessage = extractErrorMessage(body);
        throw new LLMProviderException(getProviderName(), errorMessage);
    }

    private static final java.util.regex.Pattern RETRY_IN_REGEX =
        java.util.regex.Pattern.compile("retry in (\\d+(?:\\.\\d+)?)s", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Parse retry hint, in priority order:
     * 1) Retry-After header (seconds OR HTTP date)
     * 2) Google body {@code error.details[].retryDelay} (textual {@code "16s"} or numeric)
     * 3) Google body {@code error.message} regex {@code /retry in (\d+(?:\.\d+)?)s/}
     * Returns empty on any parse failure. Never throws - used inside exception path.
     */
    Optional<Duration> parseRetryAfter(HttpHeaders headers, String body) {
        if (headers != null) {
            String h = headers.getFirst("Retry-After");
            if (h != null && !h.isBlank()) {
                try {
                    long seconds = Long.parseLong(h.trim());
                    return Optional.of(Duration.ofSeconds(seconds));
                } catch (NumberFormatException ignored) {
                    // HTTP-date format - fall through to body parsing
                }
            }
        }
        if (body != null && !body.isEmpty()) {
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode err = root.path("error");
                // Google structured: error.details[].retryDelay (e.g. {"retryDelay":"16s"})
                JsonNode details = err.path("details");
                if (details.isArray()) {
                    for (JsonNode d : details) {
                        JsonNode rd = d.path("retryDelay");
                        if (!rd.isMissingNode()) {
                            String s = rd.isTextual() ? rd.asText() : rd.path("seconds").asText("");
                            if (s != null && !s.isBlank()) {
                                if (s.endsWith("s")) s = s.substring(0, s.length() - 1);
                                try {
                                    double secs = Double.parseDouble(s);
                                    return Optional.of(Duration.ofMillis((long) (secs * 1000)));
                                } catch (NumberFormatException ignored) {
                                    // try next
                                }
                            }
                        }
                    }
                }
                // Google body message regex: "Please retry in 16.4s"
                String msg = err.path("message").asText("");
                if (!msg.isEmpty()) {
                    var m = RETRY_IN_REGEX.matcher(msg);
                    if (m.find()) {
                        double secs = Double.parseDouble(m.group(1));
                        return Optional.of(Duration.ofMillis((long) (secs * 1000)));
                    }
                }
            } catch (Exception ignored) {
                // body not JSON or malformed - fall through
            }
        }
        return Optional.empty();
    }

    /**
     * Extract error message from response body.
     */
    protected String extractErrorMessage(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            if (node.has("error")) {
                JsonNode error = node.get("error");
                if (error.has("message")) {
                    return error.get("message").asText();
                }
                return error.asText();
            }
            return body;
        } catch (Exception e) {
            return body;
        }
    }

    /**
     * Read error stream from connection.
     */
    protected String readErrorStream(HttpURLConnection connection) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return extractErrorMessage(sb.toString());
        } catch (Exception e) {
            return "Unknown error";
        }
    }

    /**
     * Close resources safely.
     */
    protected void closeResources(BufferedReader reader, HttpURLConnection connection) {
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Exception e) {
            log.warn("Error closing reader: {}", e.getMessage());
        }

        try {
            if (connection != null) {
                connection.disconnect();
            }
        } catch (Exception e) {
            log.warn("Error closing connection: {}", e.getMessage());
        }
    }

    /**
     * Build messages list from request.
     */
    protected List<Map<String, Object>> buildMessages(CompletionRequest request) {
        // Validate message sequence before sending to API
        if (request.conversationHistory() != null && !request.conversationHistory().isEmpty()) {
            var validation = com.apimarketplace.agent.loop.ToolCallBatchAppender.validateSequence(request.conversationHistory());
            if (!validation.valid()) {
                log.error("[API REQUEST] Invalid message sequence detected:\n{}", String.join("\n", validation.errors()));
                // Log but don't throw - let the API return the error for now
                // In production, you might want to throw here to fail fast
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();

        // Add system message if present. Generic provider has no per-block prompt cache
        // control, so we fold systemBlocks (when set) into a single concatenated string
        // via effectiveSystemPrompt(); callers that still pass legacy systemPrompt() fall
        // through the same helper.
        String systemInstruction = request.effectiveSystemPrompt();
        if (systemInstruction != null && !systemInstruction.isEmpty()) {
            messages.add(Map.of(
                "role", "system",
                "content", systemInstruction
            ));
        }

        // Add conversation history
        if (request.conversationHistory() != null) {
            for (Message msg : request.conversationHistory()) {
                Map<String, Object> converted = convertMessage(msg);
                if (converted != null && converted.get("content") != null) {
                    messages.add(converted);
                }
            }
        }

        // Add current user message
        if (request.userPrompt() != null && !request.userPrompt().isEmpty()) {
            messages.add(Map.of(
                "role", "user",
                "content", request.userPrompt()
            ));
        }

        // Ensure at least one user message exists
        if (messages.isEmpty() || messages.stream().noneMatch(m -> "user".equals(m.get("role")))) {
            messages.add(Map.of(
                "role", "user",
                "content", "Hello"
            ));
        }

        return messages;
    }

    /**
     * Convert a Message to the API format.
     */
    protected Map<String, Object> convertMessage(Message message) {
        if (message == null) {
            return null;
        }

        Map<String, Object> msg = new HashMap<>();
        String content = message.content() != null ? message.content() : "";

        switch (message.role()) {
            case SYSTEM -> {
                msg.put("role", "system");
                msg.put("content", content);
            }
            case USER -> {
                msg.put("role", "user");
                msg.put("content", content);
            }
            case ASSISTANT -> {
                msg.put("role", "assistant");
                msg.put("content", content);
                // Include tool_calls if present (required by OpenAI for proper message flow)
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    msg.put("tool_calls", convertToolCalls(message.toolCalls()));
                }
            }
            case TOOL -> {
                msg.put("role", "tool");
                msg.put("content", content);
                if (message.toolCallId() != null) {
                    msg.put("tool_call_id", message.toolCallId());
                }
            }
        }

        return msg;
    }

    /**
     * Convert ToolCall objects to OpenAI format for message history.
     */
    protected List<Map<String, Object>> convertToolCalls(List<ToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolCall tc : toolCalls) {
            Map<String, Object> toolCallMap = new HashMap<>();
            toolCallMap.put("id", tc.id());
            toolCallMap.put("type", "function");

            Map<String, Object> functionMap = new HashMap<>();
            functionMap.put("name", tc.toolName());
            try {
                functionMap.put("arguments", objectMapper.writeValueAsString(tc.arguments()));
            } catch (Exception e) {
                functionMap.put("arguments", "{}");
            }
            toolCallMap.put("function", functionMap);

            result.add(toolCallMap);
        }
        return result;
    }

    /**
     * Build tools list for OpenAI-compatible APIs (OpenAI, DeepSeek, Mistral, ZAI, Groq, …).
     *
     * <p>Stage 1a.1: tools are sorted by name and each tool's JSON body is built with
     * {@link LinkedHashMap} so serialization is byte-identical across pod restarts.
     * The same {@code CoreToolsProvider} ConcurrentHashMap feeds every provider, so
     * this invariant must hold for every tool-serializing path or prompt caches
     * (Anthropic, Gemini cachedContent) silently invalidate on rollout.
     */
    protected List<Map<String, Object>> buildOpenAITools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) {
            return null;
        }

        List<ToolDefinition> sorted = tools.stream()
            .sorted(Comparator.comparing(ToolDefinition::name,
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (ToolDefinition tool : sorted) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", buildParametersSchema(tool));

            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("type", "function");
            wrapper.put("function", function);
            result.add(wrapper);
        }

        // Log first tool for debugging
        if (!result.isEmpty()) {
            try {
                String firstTool = objectMapper.writeValueAsString(result.get(0));
                log.info("First tool sample: {}", firstTool.length() > 500 ? firstTool.substring(0, 500) : firstTool);
            } catch (Exception e) {
                log.debug("Could not serialize first tool");
            }
        }

        return result;
    }

    /**
     * Build JSON Schema for tool parameters.
     */
    protected Map<String, Object> buildParametersSchema(ToolDefinition tool) {
        // Stage 1a.1: every nested map is a LinkedHashMap so that Jackson serializes the
        // keys in insertion order. HashMap iteration order is stable within a single JVM
        // but differs across pods (different rehash), which would silently break prompt
        // caches on rollout even after the top-level tools list is sorted.
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        if (tool.parameters() != null && !tool.parameters().isEmpty()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();

            for (ToolParameter param : tool.parameters()) {
                Map<String, Object> paramSchema = new LinkedHashMap<>();
                // OpenAI strict schema validation rejects "type": null with
                // "None is not valid under any of the given schemas". Default to "string"
                // when a tool author forgets to set the type (mirrors ToolSchemaGenerator.mapType).
                String type = param.type() != null ? param.type() : "string";
                paramSchema.put("type", type);
                if (param.description() != null) {
                    paramSchema.put("description", param.description());
                }
                if (param.enumValues() != null && !param.enumValues().isEmpty()) {
                    paramSchema.put("enum", param.enumValues());
                }
                // OpenAI requires array types to have items schema
                if ("array".equals(type)) {
                    paramSchema.put("items", Map.of("type", "string"));
                }
                properties.put(param.name(), paramSchema);

                if (param.required()) {
                    required.add(param.name());
                }
            }

            schema.put("properties", properties);
            if (!required.isEmpty()) {
                schema.put("required", required);
            }
        } else {
            schema.put("properties", Map.of());
        }

        return schema;
    }

    /**
     * Parse tool calls from OpenAI-compatible response.
     */
    @SuppressWarnings("unchecked")
    protected List<ToolCall> parseOpenAIToolCalls(List<Map<String, Object>> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return null;
        }

        List<ToolCall> result = new ArrayList<>();
        for (int i = 0; i < toolCalls.size(); i++) {
            Map<String, Object> tc = toolCalls.get(i);
            String id = (String) tc.get("id");
            Map<String, Object> function = (Map<String, Object>) tc.get("function");

            if (function != null) {
                String name = (String) function.get("name");
                String argsJson = (String) function.get("arguments");

                Map<String, Object> args = Map.of();
                if (argsJson != null && !argsJson.isEmpty()) {
                    try {
                        args = objectMapper.readValue(argsJson, Map.class);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to parse tool call arguments: {}", argsJson);
                    }
                }

                result.add(ToolCall.builder()
                    .id(id)
                    .toolName(name)
                    .arguments(args)
                    .index(i)
                    .build());
            }
        }

        return result.isEmpty() ? null : result;
    }

    /**
     * Parse usage info from response (OpenAI-compatible shape).
     *
     * <p>Cached prompt tokens are read from {@code prompt_tokens_details.cached_tokens}
     * (OpenAI) with a fallback to the top-level {@code prompt_cache_hit_tokens}
     * (DeepSeek). Both are a SUBSET of {@code prompt_tokens} - billing applies the
     * provider's cached discount to that subset.
     */
    @SuppressWarnings("unchecked")
    protected UsageInfo parseUsageInfo(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }

        Integer cachedTokens = null;
        Object details = usage.get("prompt_tokens_details");
        if (details instanceof Map<?, ?> detailsMap) {
            cachedTokens = getIntValue((Map<String, Object>) detailsMap, "cached_tokens");
        }
        if (cachedTokens == null) {
            cachedTokens = getIntValue(usage, "prompt_cache_hit_tokens");
        }

        return UsageInfo.builder()
            .promptTokens(getIntValue(usage, "prompt_tokens"))
            .completionTokens(getIntValue(usage, "completion_tokens"))
            .totalTokens(getIntValue(usage, "total_tokens"))
            .cachedTokens(cachedTokens)
            .build();
    }

    protected Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    protected Double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    /**
     * Estimate the number of tokens for a request.
     * Used for rate limiting before making the API call.
     *
     * <p>Stage 1a.4: delegates to the injected {@link com.apimarketplace.agent.tokenizer.TokenEstimator}
     * when available (default: {@code JtokkitTokenEstimator}, cl100k_base).
     * Falls back to the inline chars/4 heuristic when no bean is wired - used in
     * non-Spring contexts (unit tests, {@code @Service}-less provider instantiations).
     * The fallback intentionally mirrors the pre-1a.4 logic so existing tests that
     * compare against chars/4 numbers continue to pass.
     *
     * @param request The completion request
     * @return Estimated token count
     */
    protected int estimateTokens(CompletionRequest request) {
        if (tokenEstimator != null) {
            return tokenEstimator.estimate(request);
        }
        return estimateTokensHeuristic(request);
    }

    /**
     * Legacy chars/4 estimate. Package-private for test access; not intended for
     * production paths - those should go through the injected {@link TokenEstimator}.
     */
    int estimateTokensHeuristic(CompletionRequest request) {
        int totalChars = 0;

        String sysForCount = request.effectiveSystemPrompt();
        if (sysForCount != null) {
            totalChars += sysForCount.length();
        }
        if (request.userPrompt() != null) {
            totalChars += request.userPrompt().length();
        }
        if (request.conversationHistory() != null) {
            for (Message msg : request.conversationHistory()) {
                if (msg.content() != null) {
                    totalChars += msg.content().length();
                }
            }
        }
        if (request.tools() != null) {
            totalChars += request.tools().size() * 200;
        }

        int maxTokens = request.maxTokens() != null ? request.maxTokens() : 500;
        int promptTokens = totalChars / 4;
        return promptTokens + maxTokens;
    }

    /**
     * Record request usage after streaming completes.
     * Prefers real usage from the provider over the estimate.
     *
     * @param tenantId the tenant identifier
     * @param estimatedTokens fallback token estimate
     * @param realUsage actual usage extracted from streaming (may be null)
     */
    protected void recordStreamingUsage(String modelId, String tenantId, int estimatedTokens, UsageInfo realUsage) {
        if (rateLimiter != null) {
            int tokens = estimatedTokens;
            if (realUsage != null && realUsage.getTotal() > 0) {
                tokens = realUsage.getTotal();
            }
            rateLimiter.recordRequest(getProviderName(), modelId, tenantId, tokens);
        }
    }
}
