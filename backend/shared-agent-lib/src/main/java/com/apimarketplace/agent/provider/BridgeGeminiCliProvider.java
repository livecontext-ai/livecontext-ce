package com.apimarketplace.agent.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bridge provider stub for Google Gemini CLI.
 * Appears in the models endpoint so the frontend can select it.
 * Execution is handled by conversation-service → bridge → gemini CLI.
 */
@Component("bridgeGeminiCliProvider")
public class BridgeGeminiCliProvider extends BridgeProviderStub {

    public BridgeGeminiCliProvider(
            @Value("${ai.agent.providers.gemini-cli.enabled:false}") boolean enabled,
            @Value("${ai.agent.providers.gemini-cli.models:}") String models,
            @Value("${ai.agent.providers.gemini-cli.display-order:99}") int displayOrder) {
        super("gemini-cli", enabled, models, displayOrder);
    }
}
