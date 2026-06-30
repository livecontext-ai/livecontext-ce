package com.apimarketplace.agent.webhook;

import java.util.Map;

/**
 * Configuration for agent webhook authentication and method validation.
 * Similar to WebhookConfig but specific to agent webhooks.
 */
public record AgentWebhookConfig(
    String httpMethod,
    String authType,
    String basicUsername,
    String basicPassword,
    String authHeaderName,
    String authHeaderValue,
    String jwtSecretKey,
    String jwtAlgorithm
) {
    /**
     * Create config with default values (POST, no auth).
     */
    public static AgentWebhookConfig defaults() {
        return new AgentWebhookConfig("POST", "none", null, null, null, null, null, null);
    }

    /**
     * Create config from entity auth config map.
     */
    @SuppressWarnings("unchecked")
    public static AgentWebhookConfig fromAuthConfig(String httpMethod, String authType, Map<String, Object> authConfig) {
        if (authConfig == null) {
            return new AgentWebhookConfig(
                httpMethod != null ? httpMethod : "POST",
                authType != null ? authType : "none",
                null, null, null, null, null, null
            );
        }

        return new AgentWebhookConfig(
            httpMethod != null ? httpMethod : "POST",
            authType != null ? authType : "none",
            getStringValue(authConfig, "username"),
            getStringValue(authConfig, "password"),
            getStringValue(authConfig, "headerName"),
            getStringValue(authConfig, "headerValue"),
            getStringValue(authConfig, "jwtSecret"),
            getStringValue(authConfig, "jwtAlgorithm")
        );
    }

    private static String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Check if HTTP method matches expected.
     */
    public boolean matchesMethod(String method) {
        if (httpMethod == null || httpMethod.isBlank()) {
            return "POST".equalsIgnoreCase(method);
        }
        return httpMethod.equalsIgnoreCase(method);
    }

    /**
     * Check if authentication is required.
     */
    public boolean requiresAuth() {
        return authType != null && !authType.isBlank() && !"none".equalsIgnoreCase(authType);
    }
}
