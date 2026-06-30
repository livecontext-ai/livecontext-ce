package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.ratelimit.ProviderRateLimiter;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * No-cost in-process LLM provider for tests and load tests - returns a canned
 * completion with configurable latency + token usage, so anything that needs a
 * real provider API key can run WITHOUT one (no key, no spend, no network).
 *
 * <p>Registered as provider name {@code "mock"} (point a request at
 * {@code provider="mock", model="mock-model"}). It is auto-discovered by
 * {@link LLMProviderFactory} like any other {@link LLMProvider} bean.
 *
 * <p><b>Rate limiting is real.</b> {@code complete}/{@code completeStreaming}/
 * {@code streamReactive} call the same {@link ProviderRateLimiter}
 * ({@code checkRateLimit} before, {@code recordRequest} after) as the HTTP
 * providers, so the per-model TPM/RPM machinery - including the distributed
 * Redis window when {@code scaling.backend=redis} - can be exercised across
 * multiple instances without touching a real API.
 *
 * <p><b>Off by default.</b> Gated by {@code ai.agent.mock-provider.enabled=true}
 * ({@code matchIfMissing=false}). It logs a loud WARN on construction so it can
 * never be silently active in production. Mirrors the orchestrator's
 * {@code orchestrator.mock.enabled} pattern.
 */
@Slf4j
@Component("sharedAgentMockLLMProvider")
@ConditionalOnProperty(name = "ai.agent.mock-provider.enabled", havingValue = "true", matchIfMissing = false)
public class MockLLMProvider implements LLMProvider {

    public static final String PROVIDER_NAME = "mock";

    private final ProviderRateLimiter rateLimiter; // null only in direct-construction unit tests
    private final String reply;
    private final long latencyMs;
    private final int completionTokens;
    private final List<String> models;

    public MockLLMProvider(
            ProviderRateLimiter rateLimiter,
            @Value("${ai.agent.mock-provider.reply:ok}") String reply,
            @Value("${ai.agent.mock-provider.latency-ms:0}") long latencyMs,
            @Value("${ai.agent.mock-provider.completion-tokens:50}") int completionTokens,
            @Value("${ai.agent.mock-provider.models:mock-model}") String modelsCsv) {
        this.rateLimiter = rateLimiter;
        this.reply = reply;
        this.latencyMs = Math.max(0, latencyMs);
        this.completionTokens = Math.max(0, completionTokens);
        this.models = (modelsCsv == null || modelsCsv.isBlank())
                ? List.of("mock-model")
                : Arrays.stream(modelsCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        log.warn("⚠️ MockLLMProvider ACTIVE (ai.agent.mock-provider.enabled=true) - provider 'mock' returns canned "
                + "responses, NO real LLM calls. This MUST NOT be enabled in production. reply='{}' latencyMs={} completionTokens={}",
                this.reply, this.latencyMs, this.completionTokens);
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public String getDefaultModel() {
        return models.isEmpty() ? "mock-model" : models.get(0);
    }

    @Override
    public List<String> getSupportedModels() {
        return models;
    }

    @Override
    public boolean isConfigured() {
        return true; // no API key needed
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public boolean supportsToolCalling() {
        return false; // no tool calls -> the agent loop completes in one turn (predictable load)
    }

    @Override
    public int getDisplayOrder() {
        // Deliberately last: if the mock is enabled on a box that ALSO has a real
        // configured provider, the mock must never win default-provider selection
        // (which sorts by displayOrder). Tests/load-tests select it explicitly by name.
        return 9999;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        int promptTokens = estimatePromptTokens(request);
        rateLimitBefore(request, promptTokens + completionTokens);
        sleepLatency();
        CompletionResponse response = buildResponse(request, promptTokens);
        rateLimitAfter(request, promptTokens + completionTokens);
        return response;
    }

    @Override
    public void completeStreaming(CompletionRequest request, StreamingCallback callback) {
        try {
            int promptTokens = estimatePromptTokens(request);
            rateLimitBefore(request, promptTokens + completionTokens);
            sleepLatency();
            callback.onChunk(reply);
            CompletionResponse response = buildResponse(request, promptTokens);
            rateLimitAfter(request, promptTokens + completionTokens);
            callback.onComplete(response);
        } catch (RuntimeException e) {
            callback.onError("mock provider error", e);
            throw e;
        }
    }

    @Override
    public Flux<StreamingEvent> streamReactive(CompletionRequest request) {
        return Flux.defer(() -> {
            int promptTokens = estimatePromptTokens(request);
            rateLimitBefore(request, promptTokens + completionTokens);
            sleepLatency();
            CompletionResponse response = buildResponse(request, promptTokens);
            rateLimitAfter(request, promptTokens + completionTokens);
            return Flux.just(StreamingEvent.content(reply), StreamingEvent.completed(response));
        });
    }

    private CompletionResponse buildResponse(CompletionRequest request, int promptTokens) {
        String model = request.model() != null ? request.model() : getDefaultModel();
        return CompletionResponse.builder()
                .content(reply)
                .finishReason("stop")
                .model(model)
                .usage(UsageInfo.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(promptTokens + completionTokens)
                        .build())
                .build();
    }

    /** ~4 chars/token heuristic over system + user + history, matching the HTTP providers' estimate. */
    private int estimatePromptTokens(CompletionRequest request) {
        int chars = 0;
        if (request.effectiveSystemPrompt() != null) chars += request.effectiveSystemPrompt().length();
        if (request.userPrompt() != null) chars += request.userPrompt().length();
        if (request.conversationHistory() != null) {
            chars += request.conversationHistory().stream()
                    .mapToInt(m -> m != null && m.toString() != null ? m.toString().length() : 0).sum();
        }
        return Math.max(1, chars / 4);
    }

    private void rateLimitBefore(CompletionRequest request, int estimatedTokens) {
        if (rateLimiter != null) {
            rateLimiter.checkRateLimit(PROVIDER_NAME, request.model(), request.tenantId(), estimatedTokens);
        }
    }

    private void rateLimitAfter(CompletionRequest request, int actualTokens) {
        if (rateLimiter != null) {
            rateLimiter.recordRequest(PROVIDER_NAME, request.model(), request.tenantId(), actualTokens);
        }
    }

    private void sleepLatency() {
        if (latencyMs > 0) {
            try {
                Thread.sleep(latencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
