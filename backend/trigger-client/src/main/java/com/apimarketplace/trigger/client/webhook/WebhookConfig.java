package com.apimarketplace.trigger.client.webhook;

import com.apimarketplace.trigger.client.dto.StandaloneWebhookDto;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Configuration for webhook trigger including HTTP method, authentication, and token.
 * This record holds the configuration extracted from trigger.params in the workflow plan.
 *
 * Supported HTTP methods: GET, POST, PUT, PATCH, DELETE
 * Supported auth types: none, basic, header, jwt
 */
public record WebhookConfig(
        String webhookToken,      // The webhook token for URL
        String httpMethod,        // GET, POST, PUT, PATCH, DELETE (default: POST)
        String authType,          // none, basic, header, jwt (default: none)
        String basicUsername,     // For basic auth
        String basicPassword,     // For basic auth
        String authHeaderName,    // For header auth (e.g., "X-API-Key")
        String authHeaderValue,   // For header auth
        String jwtSecretKey,      // For JWT validation (HMAC secret)
        String jwtAlgorithm       // HS256, HS384, HS512 (default: HS256)
) {
    /**
     * Key used to store webhook config in trigger.params map.
     */
    public static final String PARAMS_KEY = "webhook";

    /**
     * Token prefix for easy identification.
     */
    public static final String TOKEN_PREFIX = "wh_";

    /**
     * Generate a new webhook token.
     */
    public static String generateToken() {
        return TOKEN_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Creates a default webhook configuration (POST, no auth).
     */
    public static WebhookConfig defaults() {
        return new WebhookConfig(null, "POST", "none", null, null, null, null, null, "HS256");
    }

    /**
     * Creates a WebhookConfig from trigger params map.
     * Reads all fields from the nested "webhook" sub-map within params.
     *
     * @param params The trigger.params map from the workflow plan
     * @return WebhookConfig with values from params or defaults
     */
    @SuppressWarnings("unchecked")
    public static WebhookConfig fromTriggerParams(Map<String, Object> params) {
        if (params == null) {
            return defaults();
        }

        Object webhookObj = params.get(PARAMS_KEY);
        if (webhookObj instanceof Map<?, ?> webhookMap) {
            return new WebhookConfig(
                    getStr(webhookMap, "webhookToken"),
                    getStr(webhookMap, "httpMethod", "POST"),
                    getStr(webhookMap, "authType", "none"),
                    getStr(webhookMap, "basicUsername"),
                    getStr(webhookMap, "basicPassword"),
                    getStr(webhookMap, "authHeaderName"),
                    getStr(webhookMap, "authHeaderValue"),
                    getStr(webhookMap, "jwtSecretKey"),
                    getStr(webhookMap, "jwtAlgorithm")
            );
        }
        return defaults();
    }

    /**
     * Checks if this webhook requires authentication.
     */
    public boolean requiresAuth() {
        return authType != null && !"none".equalsIgnoreCase(authType);
    }

    /**
     * Checks if the given HTTP method matches this configuration.
     * Defaults to POST when httpMethod is null (strict security behavior).
     */
    public boolean matchesMethod(String method) {
        if (httpMethod == null || method == null) {
            return "POST".equalsIgnoreCase(method);
        }
        return httpMethod.equalsIgnoreCase(method);
    }

    /**
     * Check if this config has a valid token.
     */
    public boolean hasValidToken() {
        return webhookToken != null && !webhookToken.isBlank();
    }

    /**
     * Creates a WebhookConfig from a standalone webhook DTO with a pre-decrypted authConfig.
     *
     * @param dto The standalone webhook DTO (from trigger-service)
     * @param decryptedAuthConfig The decrypted auth config map
     * @return WebhookConfig with decrypted values
     */
    public static WebhookConfig fromStandaloneWebhook(StandaloneWebhookDto dto,
                                                       Map<String, String> decryptedAuthConfig) {
        return new WebhookConfig(
                dto.getToken(),
                dto.getHttpMethod() != null ? dto.getHttpMethod() : "POST",
                dto.getAuthType() != null ? dto.getAuthType() : "none",
                decryptedAuthConfig != null ? decryptedAuthConfig.get("basicUsername") : null,
                decryptedAuthConfig != null ? decryptedAuthConfig.get("basicPassword") : null,
                decryptedAuthConfig != null ? decryptedAuthConfig.get("authHeaderName") : null,
                decryptedAuthConfig != null ? decryptedAuthConfig.get("authHeaderValue") : null,
                decryptedAuthConfig != null ? decryptedAuthConfig.get("jwtSecretKey") : null,
                decryptedAuthConfig != null ? decryptedAuthConfig.getOrDefault("jwtAlgorithm", "HS256") : "HS256"
        );
    }

    /**
     * Convert to map for storage in trigger.params.
     * Includes all non-null fields for complete serialization.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (webhookToken != null) map.put("webhookToken", webhookToken);
        if (httpMethod != null) map.put("httpMethod", httpMethod);
        if (authType != null) map.put("authType", authType);
        if (basicUsername != null) map.put("basicUsername", basicUsername);
        if (basicPassword != null) map.put("basicPassword", basicPassword);
        if (authHeaderName != null) map.put("authHeaderName", authHeaderName);
        if (authHeaderValue != null) map.put("authHeaderValue", authHeaderValue);
        if (jwtSecretKey != null) map.put("jwtSecretKey", jwtSecretKey);
        if (jwtAlgorithm != null) map.put("jwtAlgorithm", jwtAlgorithm);
        return map;
    }

    private static String getStr(Map<?, ?> map, String key) {
        return getStr(map, key, null);
    }

    private static String getStr(Map<?, ?> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
