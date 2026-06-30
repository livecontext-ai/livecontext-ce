package com.apimarketplace.agent.cloud;

/**
 * Runtime source for CE LLM calls.
 */
public enum CloudLlmSource {
    CLOUD,
    BYOK;

    public static CloudLlmSource from(String value) {
        if (value == null || value.isBlank()) {
            return BYOK;
        }
        try {
            return CloudLlmSource.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return BYOK;
        }
    }
}
