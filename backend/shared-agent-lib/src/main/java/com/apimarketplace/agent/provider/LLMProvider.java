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
     * Ask the vendor itself which models it serves today, so the catalog can
     * carry a model no third-party mirror has published yet.
     *
     * <p>The three possible answers are deliberately distinct, and a caller
     * must not collapse them:
     * <ul>
     *   <li>{@link Optional#empty()} - COULD NOT ASK. No key, no reachable
     *       endpoint, an auth rejection, or a provider that exposes no model
     *       listing at all. Never an exception: a discovery pass must not be
     *       able to break a catalog refresh.</li>
     *   <li>A present but EMPTY list - asked, and the vendor serves nothing.</li>
     *   <li>A present non-empty list - the authoritative set of ids callable
     *       on the endpoint this provider is configured against.</li>
     * </ul>
     *
     * <p>The default is {@link Optional#empty()} so a provider opts IN by
     * being able to answer, never by forgetting to opt out. What the result
     * does NOT carry is pricing: {@code /models} endpoints publish none, so a
     * caller must treat it as an existence list only and source the rate
     * elsewhere.
     */
    default java.util.Optional<List<String>> listRemoteModelIds() {
        return java.util.Optional.empty();
    }

    /**
     * Get the display order for this provider (lower = first).
     * Used for sorting providers and models in the UI.
     * Default is 100 if not configured.
     */
    default int getDisplayOrder() {
        return 100;
    }

    /**
     * True when this provider SERIALISES user-message image attachments
     * ({@code MessageAttachment} of type IMAGE) into its native vision block. The
     * agent loop consults this before appending the synthetic "image shown below"
     * USER message for tool-result {@code __media__} images: a provider that drops
     * attachments at serialisation time must not have the model told an image is
     * visible (misleading-prompt defect). Default {@code false} - only providers
     * whose request serialiser actually emits the image bytes override to true.
     */
    default boolean supportsImageAttachments() {
        return false;
    }

    /**
     * Whether an API key is accepted by this provider, asked BEFORE the key is saved so a
     * wrong, exhausted or region-locked key fails in Settings and not three nodes deep in a
     * run. {@code valid} is false only when the vendor REJECTED the key (401/403): a vendor
     * that could not be asked is not a bad key, and never blocks saving.
     *
     * @param valid    false only on a rejection by the vendor
     * @param verified true when the vendor actually answered the question
     * @param error    what the vendor said, or why it could not be asked; null when accepted
     */
    record KeyCheck(boolean valid, boolean verified, String error) {
        public static KeyCheck accepted() {
            return new KeyCheck(true, true, null);
        }

        public static KeyCheck rejected(String error) {
            return new KeyCheck(false, true, error);
        }

        public static KeyCheck unverified(String reason) {
            return new KeyCheck(true, false, reason);
        }
    }

    /** Default: this provider cannot ask its vendor; the key is taken as given. */
    default KeyCheck validateApiKey(String apiKey) {
        return KeyCheck.unverified("not supported by " + getProviderName());
    }

    /**
     * The {@code mode} of a model that returns a TYPED DECISION rather than text: a choice
     * among declared options, a score, a boolean, each with calibrated probabilities.
     *
     * <p>Declared here, in the lowest layer both sides can see, so the catalog row written
     * by the migration, the value {@link #getModelMode()} reports and the eligibility rule
     * in {@code ModelCategory} are ONE spelling. Two literals for one mode is the shape of
     * bug this codebase has already paid for once, in the billing multiplier: a change that
     * moved one of them left the other quietly deciding the old way.
     */
    String MODE_DECISION = "decision";

    /**
     * The {@code mode} carried by this provider's models: what KIND of thing they return.
     * {@code null} (the default) means chat-shaped, which is what every provider written
     * before decision models was.
     *
     * <p><b>Why the interface has to answer this.</b> The catalog is assembled from two
     * sources: rows in {@code agent.model_config_overrides}, which carry a {@code mode}
     * column, and the YAML-declared models this factory emits, which did not carry one at
     * all. {@code ModelCatalogService.filterProvidersByCategoryMode} reads {@code mode} off
     * BOTH and drops a model whose mode the requested category does not accept, so a YAML
     * row with no mode was read as chat-eligible and survived every conversational filter
     * regardless of what its database row said. That was harmless while every YAML provider
     * really was chat-shaped. It stops being harmless the moment one is not: dropping the
     * override from the overlay map does NOT remove the YAML model, so the model stayed in
     * the chat picker, the flat model list and the default-model pick.
     *
     * <p>Answering here is what makes the filter mean what its name says, for this provider
     * and for every future one. A provider that returns {@code null} behaves exactly as it
     * did before.
     */
    default String getModelMode() {
        return null;
    }
}
