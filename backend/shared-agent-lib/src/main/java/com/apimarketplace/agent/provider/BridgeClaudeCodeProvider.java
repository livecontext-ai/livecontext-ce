package com.apimarketplace.agent.provider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bridge provider stub for Claude Code CLI.
 * Appears in the models endpoint so the frontend can select it.
 * Execution is handled by conversation-service → bridge → claude CLI.
 */
@Component("bridgeClaudeCodeProvider")
public class BridgeClaudeCodeProvider extends BridgeProviderStub {

    public BridgeClaudeCodeProvider(
            @Value("${ai.agent.providers.claude-code.enabled:false}") boolean enabled,
            @Value("${ai.agent.providers.claude-code.models:}") String models,
            @Value("${ai.agent.providers.claude-code.display-order:99}") int displayOrder) {
        super("claude-code", enabled, models, displayOrder);
    }
}
