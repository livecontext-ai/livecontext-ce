package com.apimarketplace.agent.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bridge provider stub for Mistral Vibe CLI.
 * Appears in the models endpoint so the frontend can select it.
 * Execution is handled by conversation-service → bridge → vibe CLI.
 */
@Component("bridgeMistralVibeProvider")
public class BridgeMistralVibeProvider extends BridgeProviderStub {

    public BridgeMistralVibeProvider(
            @Value("${ai.agent.providers.mistral-vibe.enabled:false}") boolean enabled,
            @Value("${ai.agent.providers.mistral-vibe.models:}") String models,
            @Value("${ai.agent.providers.mistral-vibe.display-order:99}") int displayOrder) {
        super("mistral-vibe", enabled, models, displayOrder);
    }
}
