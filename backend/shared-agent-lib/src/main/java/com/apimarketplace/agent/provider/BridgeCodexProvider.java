package com.apimarketplace.agent.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bridge provider stub for OpenAI Codex CLI.
 * Appears in the models endpoint so the frontend can select it.
 * Execution is handled by conversation-service → bridge → codex CLI.
 */
@Component("bridgeCodexProvider")
public class BridgeCodexProvider extends BridgeProviderStub {

    public BridgeCodexProvider(
            @Value("${ai.agent.providers.codex.enabled:false}") boolean enabled,
            @Value("${ai.agent.providers.codex.models:}") String models,
            @Value("${ai.agent.providers.codex.display-order:99}") int displayOrder) {
        super("codex", enabled, models, displayOrder);
    }
}
