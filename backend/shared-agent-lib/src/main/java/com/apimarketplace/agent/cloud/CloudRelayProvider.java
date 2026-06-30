package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Provider wrapper used in CE cloud mode. It preserves local provider metadata
 * while sending only completion calls to Cloud.
 */
public class CloudRelayProvider implements LLMProvider {

    private final LLMProvider delegate;
    private final CloudLlmRuntimeCredentials credentials;
    private final CloudLlmRelayClient relayClient;

    /**
     * Stable per-execution id stamped onto every relay request so the cloud bills the whole
     * execution as ONE aggregated {@code CE_LLM_RELAY} line (accrue→settle). {@code null} ⇒ legacy
     * per-call billing (the cloud bills each forwarded call separately, one line each).
     */
    private final String executionId;

    public CloudRelayProvider(LLMProvider delegate,
                              CloudLlmRuntimeCredentials credentials,
                              CloudLlmRelayClient relayClient) {
        this(delegate, credentials, relayClient, null);
    }

    public CloudRelayProvider(LLMProvider delegate,
                              CloudLlmRuntimeCredentials credentials,
                              CloudLlmRelayClient relayClient,
                              String executionId) {
        this.delegate = delegate;
        this.credentials = credentials;
        this.relayClient = relayClient;
        this.executionId = executionId;
    }

    @Override
    public String getProviderName() {
        return delegate.getProviderName();
    }

    @Override
    public String getDefaultModel() {
        return delegate.getDefaultModel();
    }

    @Override
    public List<String> getSupportedModels() {
        return delegate.getSupportedModels();
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public boolean supportsStreaming() {
        return delegate.supportsStreaming();
    }

    @Override
    public boolean supportsToolCalling() {
        return delegate.supportsToolCalling();
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        return relayClient.complete(credentials,
                new CloudLlmRelayRequest(getProviderName(), request, executionId));
    }

    @Override
    public void completeStreaming(CompletionRequest request, StreamingCallback callback) {
        relayClient.stream(credentials,
                new CloudLlmRelayRequest(getProviderName(), request, executionId), callback);
    }

    @Override
    public Flux<StreamingEvent> streamReactive(CompletionRequest request) {
        return Flux.create(sink -> {
            AtomicBoolean cancelled = new AtomicBoolean(false);
            sink.onCancel(() -> cancelled.set(true));
            sink.onDispose(() -> cancelled.set(true));
            completeStreaming(request, new StreamingCallback() {
                @Override
                public void onChunk(String content) {
                    sink.next(StreamingEvent.content(content));
                }

                @Override
                public void onToolCall(com.apimarketplace.agent.domain.ToolCall toolCall) {
                    sink.next(StreamingEvent.toolCall(toolCall));
                }

                @Override
                public void onComplete(CompletionResponse response) {
                    sink.next(StreamingEvent.completed(response));
                    sink.complete();
                }

                @Override
                public void onError(String error) {
                    sink.next(StreamingEvent.error(error));
                    sink.complete();
                }

                @Override
                public boolean shouldStop() {
                    return cancelled.get();
                }
            });
        });
    }

    @Override
    public boolean supportsModel(String model) {
        return delegate.supportsModel(model);
    }

    @Override
    public int getDisplayOrder() {
        return delegate.getDisplayOrder();
    }
}
