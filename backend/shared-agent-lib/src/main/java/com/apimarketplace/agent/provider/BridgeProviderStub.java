package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Lightweight LLMProvider stub for bridge-based providers (Claude Code, Codex, Gemini CLI, Mistral Vibe).
 * These providers appear in the models endpoint so the frontend can select them,
 * but never execute LLM calls directly - execution is routed through the bridge server.
 *
 * complete() and completeStreaming() throw UnsupportedOperationException since
 * bridge providers are handled by conversation-service → bridge → CLI.
 */
public abstract class BridgeProviderStub implements LLMProvider {

    private final String name;
    private final boolean enabled;
    private final List<String> models;
    private final int displayOrder;

    protected BridgeProviderStub(String name, boolean enabled, String modelsStr,
                                 int displayOrder) {
        this.name = name;
        this.enabled = enabled;
        this.models = (modelsStr != null && !modelsStr.isBlank())
                ? Arrays.asList(modelsStr.split(","))
                : Collections.emptyList();
        this.displayOrder = displayOrder;
    }

    @Override
    public String getProviderName() {
        return name;
    }

    @Override
    public String getDefaultModel() {
        return models.isEmpty() ? null : models.get(0);
    }

    @Override
    public List<String> getSupportedModels() {
        return models;
    }

    @Override
    public boolean isConfigured() {
        return enabled;
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
    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        throw new UnsupportedOperationException(
                "Bridge provider '" + name + "' does not support direct LLM calls. " +
                "Use the bridge server for execution.");
    }

    @Override
    public void completeStreaming(CompletionRequest request, StreamingCallback callback) {
        throw new UnsupportedOperationException(
                "Bridge provider '" + name + "' does not support direct LLM calls. " +
                "Use the bridge server for execution.");
    }

    @Override
    public Flux<StreamingEvent> streamReactive(CompletionRequest request) {
        return Flux.error(new UnsupportedOperationException(
                "Bridge provider '" + name + "' does not support direct LLM calls. " +
                "Use the bridge server for execution."));
    }
}
