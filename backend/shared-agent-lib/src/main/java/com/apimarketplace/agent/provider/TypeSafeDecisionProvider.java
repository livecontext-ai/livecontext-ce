package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.streaming.StreamingCallback;
import com.apimarketplace.agent.streaming.StreamingEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Catalog entry for TypeSafe's decision models (Jev), which return a TYPED DECISION
 * instead of text.
 *
 * <p><b>Why a provider that cannot complete.</b> The model catalog is built from
 * {@code LLMProviderFactory.getAllModelsInfoAdmin()}, so a model only exists for the
 * pickers, the admin Models panel and the pricing surfaces if a provider bean declares
 * it. Jev is not chat-shaped: it is called on TypeSafe's own {@code /v1/systemone}
 * endpoint with a map of typed questions and answers with probabilities, so it has no
 * {@code /chat/completions} to implement. This class therefore declares the models and
 * nothing else, exactly as {@link BridgeProviderStub} does for the CLI bridges, and the
 * execution path lives beside the caller that knows what to ask ({@code ClassifyService}).
 *
 * <p><b>The three refusals below are the backend half of the cloisonnement.</b> A
 * decision model is kept out of every conversational picker by its {@code mode}
 * ({@code ModelCategory.DECISION_MODE}), but a picker filter only protects the user who
 * clicks. A plan authored by the workflow-building agent, a copied node, or an imported
 * workflow can still name this model on an Agent node, and that path lands here. Failing
 * with a sentence that names the node to use is the difference between a user who moves
 * the model and a user who files a bug about an incomprehensible crash.
 */
@Component("typeSafeDecisionProvider")
public class TypeSafeDecisionProvider implements LLMProvider {

    /** Provider name, matching {@code ai.agent.providers.typesafe.*} and the pricing rows. */
    public static final String PROVIDER_NAME = "typesafe";

    private static final String NOT_A_CHAT_MODEL =
            // No UI deixis: this string is read by an LLM agent through a tool result as
            // often as by a person on a screen, and "here" means nothing to the first.
            "TypeSafe decision models return a typed decision, not text, so they cannot run an "
            + "Agent node, a Guardrail node or a chat. Run this model on a Classify node, or "
            + "set a chat provider and model on this node instead.";

    private final String apiKey;
    private final List<String> models;
    private final int displayOrder;
    private final boolean enabled;

    public TypeSafeDecisionProvider(
            @Value("${ai.agent.providers.typesafe.enabled:true}") boolean enabled,
            @Value("${ai.agent.providers.typesafe.api-key:}") String apiKey,
            @Value("${ai.agent.providers.typesafe.models:}") String models,
            @Value("${ai.agent.providers.typesafe.display-order:20}") int displayOrder) {
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.models = (models != null && !models.isBlank())
                ? Arrays.stream(models.split(",")).map(String::trim).filter(m -> !m.isEmpty()).toList()
                : Collections.emptyList();
        this.displayOrder = displayOrder;
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * Declares these models as decision-shaped, which is what keeps them out of every
     * conversational surface.
     *
     * <p>Without this the YAML-declared row reaches the catalog with no mode at all and is
     * read as chat-eligible, whatever the database row says: the override is dropped from
     * the overlay but the YAML model itself survives, so it is offered in the chat picker
     * and can be picked as the platform default. Matches the {@code mode} column V504
     * writes for the same models.
     */
    @Override
    public String getModelMode() {
        return MODE_DECISION;
    }

    @Override
    public String getDefaultModel() {
        return models.isEmpty() ? null : models.get(0);
    }

    @Override
    public List<String> getSupportedModels() {
        return models;
    }

    /**
     * Keyed, unlike a bridge stub. TypeSafe is an HTTP API billed to the platform's own
     * account, so with no key there is nothing to offer: the picker filter drops an
     * unconfigured provider rather than listing a model whose every call would 401.
     */
    @Override
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** No token stream exists: one call returns one decision. */
    @Override
    public boolean supportsStreaming() {
        return false;
    }

    /** No tool calling, and no text in which to ask for one. */
    @Override
    public boolean supportsToolCalling() {
        return false;
    }

    @Override
    public boolean supportsImageAttachments() {
        return false;
    }

    @Override
    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        throw new UnsupportedOperationException(NOT_A_CHAT_MODEL);
    }

    @Override
    public void completeStreaming(CompletionRequest request, StreamingCallback callback) {
        throw new UnsupportedOperationException(NOT_A_CHAT_MODEL);
    }

    @Override
    public Flux<StreamingEvent> streamReactive(CompletionRequest request) {
        return Flux.error(new UnsupportedOperationException(NOT_A_CHAT_MODEL));
    }
}
