package com.apimarketplace.auth.credential.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

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
            @JsonProperty("return_url") String returnUrl
    ) {
        /**
         * Check if user provided their own credentials
         */
        public boolean hasUserCredentials() {
            return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
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
            String integration
    ) {
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
                    null  // No returnUrl for simple requests
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

    /** Response carrying the short-lived access token for the Google Picker. */
    public record PickerTokenResponse(
            @JsonProperty("access_token") String accessToken
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
            String organizationId
    ) {
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
                    createdAt, null, null);
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
                    createdAt, codeVerifier, null);
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
