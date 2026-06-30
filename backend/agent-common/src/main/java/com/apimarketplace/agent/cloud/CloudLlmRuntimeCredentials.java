package com.apimarketplace.agent.cloud;

/**
 * Credentials used by a CE instance to relay only the LLM completion call to Cloud.
 */
public record CloudLlmRuntimeCredentials(
        String accessToken,
        String installId,
        String cloudApiUrl
) {
    public CloudLlmRuntimeCredentials {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken is required");
        }
        if (installId == null || installId.isBlank()) {
            throw new IllegalArgumentException("installId is required");
        }
        if (cloudApiUrl == null || cloudApiUrl.isBlank()) {
            throw new IllegalArgumentException("cloudApiUrl is required");
        }
    }
}
