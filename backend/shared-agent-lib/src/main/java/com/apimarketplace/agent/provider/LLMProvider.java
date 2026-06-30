package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Interface for LLM providers.
 * Follows the Open/Closed Principle - new providers can be added without modifying existing code.
 *
 * Each implementation handles communication with a specific LLM API (OpenAI, Anthropic, etc.)
 */
public interface LLMProvider {

    /**
     * Get the provider name (e.g., "openai", "anthropic", "google", "mistral", "deepseek")
     */
    String getProviderName();

    /**
     * Get the default model for this provider
     */
    String getDefaultModel();

    /**
     * Get all supported models for this provider
     */
    List<String> getSupportedModels();

    /**
     * Check if this provider is properly configured (API key present, etc.)
     */
    boolean isConfigured();

    /**
     * Check if this provider supports streaming responses
     */
    boolean supportsStreaming();

    /**
     * Check if this provider supports tool/function calling
     */
    boolean supportsToolCalling();

    /**
     * Send a completion request to the LLM.
     *
     * @param request The completion request
     * @return The completion response
     * @throws LLMProviderException if the request fails
     */
    CompletionResponse complete(CompletionRequest request);

    /**
     * Send a streaming completion request to the LLM.
     * Chunks are delivered via the callback.
     *
     * @param request The completion request
     * @param callback The callback to receive streaming chunks
     * @throws LLMProviderException if the request fails
     */
    void completeStreaming(CompletionRequest request, StreamingCallback callback);

    /**
     * Send a reactive streaming completion request to the LLM.
     * Returns a Flux of StreamingEvents for non-blocking streaming.
     * This is the preferred method for reactive applications.
     *
     * @param request The completion request
     * @return Flux of streaming events (content chunks, tool calls, completion, errors)
     */
    Flux<StreamingEvent> streamReactive(CompletionRequest request);

    /**
     * Check if this provider supports a specific model
     */
    default boolean supportsModel(String model) {
        return getSupportedModels().contains(model);
    }

    /**
     * Get the display order for this provider (lower = first).
     * Used for sorting providers and models in the UI.
     * Default is 100 if not configured.
     */
    default int getDisplayOrder() {
        return 100;
    }
}
