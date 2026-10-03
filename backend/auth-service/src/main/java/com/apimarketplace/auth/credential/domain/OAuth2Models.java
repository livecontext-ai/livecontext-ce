package com.apimarketplace.auth.credential.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Models for OAuth2 authentication flow.
 */
public final class OAuth2Models {
    private OAuth2Models() {}

    /**
     * Request to initiate OAuth2 flow.
     * client_id and client_secret are now OPTIONAL - if not provided,
     * the backend will use platform credentials from configuration.
     */
    public record OAuth2InitiateRequest(
            @JsonProperty("credential_template_id") String credentialTemplateId,
            @JsonProperty("credential_name") String credentialName,
            @JsonProperty("client_id") String clientId,
            @JsonProperty("client_secret") String clientSecret,
            String environment,
            String integration,
            @JsonProperty("return_url") String returnUrl,
            // Per-instance URL host placeholders the user supplies at connect time
            // (Shopify shop, Zendesk subdomain, NetSuite account_id, ...). Keyed by the
            // placeholder name as it appears in the OAuth/base URL templates ({shop} -> "shop").
            // The importer derives which fields belong here from the URL templates, so this is
            // data-driven per provider. Empty/absent for the vast majority of providers.
            @JsonProperty("template_vars") Map<String, String> templateVars,
            // Optional subset of the integration's scopes to request (LC-072, minimum scope).
            // Absent/empty = the full template list, exactly as before. Every entry must be a
            // scope the integration already offers: the subset can only NARROW the request.
            @JsonProperty("scopes") List<String> scopes
    ) {
        /** Back-compat constructor without templateVars (defaults to none). */
        public OAuth2InitiateRequest(String credentialTemplateId, String credentialName, String clientId,
                String clientSecret, String environment, String integration, String returnUrl) {
            this(credentialTemplateId, credentialName, clientId, clientSecret, environment, integration,
                    returnUrl, null, null);
        }

        /** Back-compat constructor without a scope subset (requests the full template list). */
        public OAuth2InitiateRequest(String credentialTemplateId, String credentialName, String clientId,
                String clientSecret, String environment, String integration, String returnUrl,
                Map<String, String> templateVars) {
            this(credentialTemplateId, credentialName, clientId, clientSecret, environment, integration,
                    returnUrl, templateVars, null);
        }

        /** Never-null view of the requested scope subset (empty = no narrowing). */
        public List<String> scopesOrEmpty() {
            return scopes != null ? scopes : List.of();
        }

        /**
         * Check if user provided their own credentials
         */
        public boolean hasUserCredentials() {
            return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
        }

        /** Never-null view of the supplied template vars. */
        public Map<String, String> templateVarsOrEmpty() {
            return templateVars != null ? templateVars : Map.of();
        }
    }

    /**
     * Simplified request to initiate OAuth2 flow using platform credentials.
     * Used when the platform provides the client_id/client_secret.
     */
    public record OAuth2SimpleInitiateRequest(
            @JsonProperty("credential_template_id") String credentialTemplateId,
            @JsonProperty("credential_name") String credentialName,
            String environment,
            String integration,
            // Per-instance URL host placeholders (see OAuth2InitiateRequest.templateVars).
            @JsonProperty("template_vars") Map<String, String> templateVars,
            // Optional scope subset (see OAuth2InitiateRequest.scopes, LC-072).
            @JsonProperty("scopes") List<String> scopes
    ) {
        /** Back-compat constructor without templateVars (defaults to none). */
        public OAuth2SimpleInitiateRequest(String credentialTemplateId, String credentialName,
                String environment, String integration) {
            this(credentialTemplateId, credentialName, environment, integration, null, null);
        }

        /** Back-compat constructor without a scope subset. */
        public OAuth2SimpleInitiateRequest(String credentialTemplateId, String credentialName,
                String environment, String integration, Map<String, String> templateVars) {
            this(credentialTemplateId, credentialName, environment, integration, templateVars, null);
        }

        /**
         * Convert to full request with platform credentials
         */
        public OAuth2InitiateRequest toFullRequest(String clientId, String clientSecret) {
            return new OAuth2InitiateRequest(
                    credentialTemplateId,
                    credentialName,
                    clientId,
                    clientSecret,
                    environment,
                    integration,
                    null,  // No returnUrl for simple requests
                    templateVars,
                    scopes
            );
        }
    }

    /**
     * Response with authorization URL
     */
    public record OAuth2InitiateResponse(
            @JsonProperty("authorization_url") String authorizationUrl,
            String state
    ) {}

    /**
     * Request a short-lived OAuth access token for the browser-side Google Drive Picker. The token
     * is the CALLER's own connected Google credential's access token (owner-gated by X-User-ID),
     * returned only for Picker-enabled Google Workspace integrations. The refresh token is never
     * returned. Used so the Picker can grant the app per-file drive.file access to existing files.
     */
    public record PickerTokenRequest(
            String integration,
            @JsonProperty("credential_name") String credentialName
    ) {}

    /**
     * Response carrying the short-lived access token for the Google Picker, plus the Picker App ID.
     *
     * <p>{@code appId} is the Cloud project number of the OAuth client the token was minted from.
     * The browser must pass it to {@code PickerBuilder.setAppId}, which Google REQUIRES for the
     * {@code drive.file} scope: without it the picked file is never granted to the app and every
     * later API call on it fails with a 404. It is derived server-side (see
     * {@code GooglePickerAppId}) so cloud, CE and BYOK each get their own correct value with no
     * client-side configuration. Null when the client ID is not a Google one.
     */
    public record PickerTokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("app_id") String appId
    ) {}

    /**
     * State stored during OAuth2 flow (in Redis, TTL-bound).
     *
     * <p>{@code codeVerifier} holds the PKCE verifier (RFC 7636) that must be kept server-side
     * between the authorize call and the callback. It is {@code null} for providers that do not
     * require PKCE. Jackson parses missing fields as {@code null}, so older state blobs written
     * before this field was introduced continue to deserialize cleanly.
     */
    public record OAuth2State(
            String userId,
            String credentialTemplateId,
            String credentialName,
            String clientId,
            String clientSecret,
            String authUrl,
            String accessTokenUrl,
            String scope,
            String environment,
            String integration,
            String iconUrl,
            String returnUrl,
            Instant createdAt,
            String codeVerifier,
            // PR19 - workspace the user was in when they initiated the OAuth
            // flow. Persisted into the Redis state blob so the callback can
            // tag the resulting credential with the right organization_id even
            // if the user switches workspace mid-flow. {@code null} = personal
            // scope. Older state blobs (pre-PR19) deserialize with null.
            String organizationId,
            // Per-instance URL host placeholders (Shopify {shop}, Zendesk {subdomain}, ...) the
            // user supplied at connect time, captured so the callback can (a) resolve the token
            // URL and (b) persist them into credential_data for runtime base-URL substitution.
            // Older state blobs (pre-this-field) deserialize with null.
            Map<String, String> templateVars,
            // SHA-256 of the one-time value handed to the INITIATING browser as a cookie
            // (OAuth2BrowserBinding). The callback completes the flow only when the browser
            // presents the matching value (LC-005). Older blobs deserialize with null and are
            // refused, which only affects flows in flight across the deploy (10 min TTL).
            String browserBindingHash,
            // Id of the platform/BYOK client row the flow was started with. When set, the
            // client secret is NOT kept in the blob: the callback re-reads it from that row
            // (LC-068). Null when the user typed the client credentials inline, in which case
            // clientSecret carries them ENCRYPTED.
            Long platformCredentialId
    ) {
        /** Never-null view of the captured template vars. */
        public Map<String, String> templateVarsOrEmpty() {
            return templateVars != null ? templateVars : Map.of();
        }

        /** Pre-binding constructor (PKCE + org + templateVars): no browser binding, no row id. */
        public OAuth2State(
                String userId,
                String credentialTemplateId,
                String credentialName,
                String clientId,
                String clientSecret,
                String authUrl,
                String accessTokenUrl,
                String scope,
                String environment,
                String integration,
                String iconUrl,
                String returnUrl,
                Instant createdAt,
                String codeVerifier,
                String organizationId,
                Map<String, String> templateVars
        ) {
            this(userId, credentialTemplateId, credentialName, clientId, clientSecret, authUrl,
                    accessTokenUrl, scope, environment, integration, iconUrl, returnUrl,
                    createdAt, codeVerifier, organizationId, templateVars, null, null);
        }

        /** Convenience constructor for callers that don't use PKCE. */
        public OAuth2State(
                String userId,
                String credentialTemplateId,
                String credentialName,
                String clientId,
                String clientSecret,
                String authUrl,
                String accessTokenUrl,
                String scope,
                String environment,
                String integration,
                String iconUrl,
                String returnUrl,
                Instant createdAt
        ) {
            this(userId, credentialTemplateId, credentialName, clientId, clientSecret, authUrl,
                    accessTokenUrl, scope, environment, integration, iconUrl, returnUrl,
                    createdAt, null, null, null, null, null);
        }

        /** Pre-PR19 PKCE constructor - defaults organizationId to null. */
        public OAuth2State(
                String userId,
                String credentialTemplateId,
                String credentialName,
                String clientId,
                String clientSecret,
                String authUrl,
                String accessTokenUrl,
                String scope,
                String environment,
                String integration,
                String iconUrl,
                String returnUrl,
                Instant createdAt,
                String codeVerifier
        ) {
            this(userId, credentialTemplateId, credentialName, clientId, clientSecret, authUrl,
                    accessTokenUrl, scope, environment, integration, iconUrl, returnUrl,
                    createdAt, codeVerifier, null, null, null, null);
        }

        /** Pre-templateVars constructor (PKCE + org) - defaults templateVars to null. */
        public OAuth2State(
                String userId,
                String credentialTemplateId,
                String credentialName,
                String clientId,
                String clientSecret,
                String authUrl,
                String accessTokenUrl,
                String scope,
                String environment,
                String integration,
                String iconUrl,
                String returnUrl,
                Instant createdAt,
                String codeVerifier,
                String organizationId
        ) {
            this(userId, credentialTemplateId, credentialName, clientId, clientSecret, authUrl,
                    accessTokenUrl, scope, environment, integration, iconUrl, returnUrl,
                    createdAt, codeVerifier, organizationId, null, null, null);
        }
    }

    /**
     * Token response from OAuth2 provider
     */
    public record OAuth2TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") Long expiresIn,
            String scope
    ) {}

    /**
     * Credential template from catalog service
     */
    public record CredentialTemplate(
            String id,
            @JsonProperty("credential_name") String credentialName,
            @JsonProperty("display_name") String displayName,
            String description,
            @JsonProperty("credential_type") String credentialType,
            @JsonProperty("auth_type") String authType,
            @JsonProperty("test_endpoint") String testEndpoint,
            @JsonProperty("documentation_url") String documentationUrl,
            @JsonProperty("icon_url") String iconUrl,
            Object properties,
            @JsonProperty("extends_") Object extendsFrom,
            Object metadata
    ) {}

    /**
     * Property field definition from credential template
     */
    public record CredentialProperty(
            String name,
            String displayName,
            String type,
            @JsonProperty("default") String defaultValue,
            boolean required,
            String description,
            Object typeOptions,
            Object displayOptions,
            List<PropertyOption> options
    ) {}

    /**
     * Option for select-type properties
     */
    public record PropertyOption(
            String name,
            String value,
            String description
    ) {}
}
