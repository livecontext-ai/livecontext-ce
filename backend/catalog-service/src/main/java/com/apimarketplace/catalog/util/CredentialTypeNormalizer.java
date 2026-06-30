package com.apimarketplace.catalog.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Normalizes the catalog's raw {@code apis.auth_type} value into the small, stable
 * vocabulary the rest of the engine already speaks - the same set
 * {@code HttpExecutionService} uses when it resolves a credential variant
 * ({@code api_key}, {@code oauth2}, {@code bearer_token}, {@code basic_auth},
 * {@code custom}) plus {@code none} for tools that need no credential.
 *
 * <p>Why a normalizer at all: the seed/import history left the column inconsistent
 * (e.g. both {@code apiKey} and {@code api_key} exist for the same mechanism). Agent-facing
 * surfaces ({@code catalog(action='response_schema')} and the execute pre-flight
 * {@code approval_needed}) must hand the LLM ONE token per auth mechanism so it can reason
 * about it deterministically and tell the user what kind of connection
 * {@code request_credential} will trigger - never the raw, drifting column value.
 *
 * <p>Unknown / future values are lower-cased and passed through (covers {@code custom}),
 * so a new mechanism degrades to "the agent sees its literal name" rather than being hidden.
 */
public final class CredentialTypeNormalizer {

    private CredentialTypeNormalizer() {}

    /**
     * @param rawAuthType the catalog {@code apis.auth_type} value (may be null/blank)
     * @return one of {@code none|api_key|oauth2|bearer_token|basic_auth|custom} (or a
     *         lower-cased pass-through for an unrecognized value)
     */
    public static String normalize(String rawAuthType) {
        if (rawAuthType == null) {
            return "none";
        }
        String v = rawAuthType.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("none")) {
            return "none";
        }
        return switch (v) {
            case "apikey", "api_key", "api-key" -> "api_key";
            case "oauth2", "oauth", "oauth_2", "oauth2.0" -> "oauth2";
            case "bearer_token", "bearer", "bearertoken", "bearer-token" -> "bearer_token";
            case "basic_auth", "basic", "basicauth", "basic-auth" -> "basic_auth";
            default -> v; // custom, or any unrecognized value, lower-cased
        };
    }

    /**
     * Builds the agent-facing credential-requirement block from a
     * {@code GET /api/catalog/tools/{id}/info} body: {@code {type, requiredScopes?}}.
     * {@code type} is the normalized auth mechanism; {@code requiredScopes} is emitted only
     * for OAuth tools that declare a non-empty scope list. Always returns a block (never null,
     * {@code type:"none"} for keyless tools) so the agent can positively conclude a credential
     * is - or is not - needed. Single source of the shape shared by the {@code response_schema}
     * and the execute pre-flight {@code approval_needed} paths.
     */
    public static Map<String, Object> buildRequirement(Map<String, Object> info) {
        Object rawAuth = info == null ? null : info.get("authType");
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("type", normalize(rawAuth == null ? null : rawAuth.toString()));
        Object scopes = info == null ? null : info.get("requiredScopes");
        if (scopes instanceof List<?> scopeList && !scopeList.isEmpty()) {
            credential.put("requiredScopes", scopeList);
        }
        return credential;
    }
}
