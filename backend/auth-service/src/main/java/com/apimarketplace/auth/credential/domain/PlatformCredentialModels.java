package com.apimarketplace.auth.credential.domain;

import com.apimarketplace.auth.credential.util.ClientIdMasker;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Domain models for platform-level credentials.
 * These credentials (OAuth2 Client ID/Secret, API Keys) are owned by the platform
 * and used to enable OAuth2 flows for all users.
 */
public final class PlatformCredentialModels {

    private PlatformCredentialModels() {
    }

    /**
     * Authentication types supported for platform credentials.
     */
    public enum AuthType {
        OAUTH2("oauth2"),
        API_KEY("api_key"),
        BASIC("basic"),
        BEARER("bearer"),
        CUSTOM("custom"),
        NONE("none");

        private final String value;

        // Aliases for catalog auth_type values that differ from enum values
        private static final Map<String, AuthType> ALIASES = Map.of(
                "apikey", API_KEY,
                "basic_auth", BASIC,
                "bearer_token", BEARER,
                "aws_signature_v4", CUSTOM
        );

        AuthType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static AuthType fromValue(String value) {
            if (value == null) {
                return OAUTH2;
            }
            String lower = value.toLowerCase();
            // Check aliases first
            AuthType aliased = ALIASES.get(lower);
            if (aliased != null) {
                return aliased;
            }
            for (AuthType type : values()) {
                if (type.value.equalsIgnoreCase(lower)) {
                    return type;
                }
            }
            return CUSTOM;
        }
    }

    /**
     * Main domain entity for platform credentials.
     *
     * <p>{@code variant} is the V103 per-auth-method discriminator - one of
     * {@code oauth2}, {@code api_key}, {@code basic_auth}, {@code bearer_token},
     * {@code custom}, or {@code primary} (legacy fallback for rows imported before
     * V103 ran). The DB has a UNIQUE (integration_name, variant) so a single
     * {@code integrationName} may have several independent rows, each with its
     * own secrets and {@code isEnabled}. The overloaded constructor without the
     * {@code variant} slot defaults to {@link #DEFAULT_VARIANT} so existing call
     * sites that pre-date Phase 2d compile unchanged.
     */
    public record PlatformCredential(
            Long id,
            String integrationName,
            String displayName,
            AuthType authType,
            String clientId,
            String clientSecret,
            String apiKey,
            String username,
            String password,
            String authUrl,
            String tokenUrl,
            String defaultScopes,
            String iconSlug,
            String category,
            String description,
            boolean showUnverifiedAppWarning,
            boolean isEnabled,
            Map<String, String> customFields,
            BigDecimal defaultMarkupCredits,
            Integer maxCallsPerRun,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            String tenantId,
            String variant,
            String organizationId
    ) {

        /** Fallback for rows created before V103 or by callers that don't specify a variant. */
        public static final String DEFAULT_VARIANT = "primary";

        /**
         * Legacy 23-arg constructor - defaults {@code variant} to {@value #DEFAULT_VARIANT}
         * and {@code organizationId} to {@code null} (personal scope).
         * Kept so pre-Phase 2d callers (tests, older service code) compile without
         * modification. New code should pass the variant explicitly.
         */
        public PlatformCredential(
                Long id,
                String integrationName,
                String displayName,
                AuthType authType,
                String clientId,
                String clientSecret,
                String apiKey,
                String username,
                String password,
                String authUrl,
                String tokenUrl,
                String defaultScopes,
                String iconSlug,
                String category,
                String description,
                boolean isEnabled,
                Map<String, String> customFields,
                BigDecimal defaultMarkupCredits,
                Integer maxCallsPerRun,
                Instant createdAt,
                Instant updatedAt,
                String createdBy,
                String tenantId
        ) {
            this(id, integrationName, displayName, authType,
                    clientId, clientSecret, apiKey, username, password,
                    authUrl, tokenUrl, defaultScopes,
                    iconSlug, category, description, true, isEnabled,
                    customFields,
                    defaultMarkupCredits, maxCallsPerRun,
                    createdAt, updatedAt, createdBy, tenantId,
                    DEFAULT_VARIANT, null);
        }

        /**
         * 24-arg constructor (no {@code showUnverifiedAppWarning}) - defaults
         * {@code organizationId} to {@code null} (personal scope).
         */
        public PlatformCredential(
                Long id,
                String integrationName,
                String displayName,
                AuthType authType,
                String clientId,
                String clientSecret,
                String apiKey,
                String username,
                String password,
                String authUrl,
                String tokenUrl,
                String defaultScopes,
                String iconSlug,
                String category,
                String description,
                boolean isEnabled,
                Map<String, String> customFields,
                BigDecimal defaultMarkupCredits,
                Integer maxCallsPerRun,
                Instant createdAt,
                Instant updatedAt,
                String createdBy,
                String tenantId,
                String variant
        ) {
            this(id, integrationName, displayName, authType,
                    clientId, clientSecret, apiKey, username, password,
                    authUrl, tokenUrl, defaultScopes,
                    iconSlug, category, description, true, isEnabled,
                    customFields,
                    defaultMarkupCredits, maxCallsPerRun,
                    createdAt, updatedAt, createdBy, tenantId,
                    variant, null);
        }

        /**
         * Pre-org canonical shape (with {@code showUnverifiedAppWarning} and
         * {@code variant}) - defaults {@code organizationId} to {@code null}
         * (personal scope). Kept so callers that built the 25-arg record before
         * the org column compile unchanged.
         */
        public PlatformCredential(
                Long id,
                String integrationName,
                String displayName,
                AuthType authType,
                String clientId,
                String clientSecret,
                String apiKey,
                String username,
                String password,
                String authUrl,
                String tokenUrl,
                String defaultScopes,
                String iconSlug,
                String category,
                String description,
                boolean showUnverifiedAppWarning,
                boolean isEnabled,
                Map<String, String> customFields,
                BigDecimal defaultMarkupCredits,
                Integer maxCallsPerRun,
                Instant createdAt,
                Instant updatedAt,
                String createdBy,
                String tenantId,
                String variant
        ) {
            this(id, integrationName, displayName, authType,
                    clientId, clientSecret, apiKey, username, password,
                    authUrl, tokenUrl, defaultScopes,
                    iconSlug, category, description, showUnverifiedAppWarning, isEnabled,
                    customFields,
                    defaultMarkupCredits, maxCallsPerRun,
                    createdAt, updatedAt, createdBy, tenantId,
                    variant, null);
        }

        public PlatformCredential withId(Long newId) {
            return new PlatformCredential(
                    newId, integrationName, displayName, authType,
                    clientId, clientSecret, apiKey, username, password,
                    authUrl, tokenUrl, defaultScopes,
                    iconSlug, category, description, showUnverifiedAppWarning, isEnabled,
                    customFields,
                    defaultMarkupCredits, maxCallsPerRun,
                    createdAt, updatedAt, createdBy, tenantId, variant, organizationId
            );
        }

        public PlatformCredential withTenantId(String newTenantId) {
            return new PlatformCredential(
                    id, integrationName, displayName, authType,
                    clientId, clientSecret, apiKey, username, password,
                    authUrl, tokenUrl, defaultScopes,
                    iconSlug, category, description, showUnverifiedAppWarning, isEnabled,
                    customFields,
                    defaultMarkupCredits, maxCallsPerRun,
                    createdAt, updatedAt, createdBy, newTenantId, variant, organizationId
            );
        }

        public PlatformCredential withOrganizationId(String newOrganizationId) {
            return new PlatformCredential(
                    id, integrationName, displayName, authType,
                    clientId, clientSecret, apiKey, username, password,
                    authUrl, tokenUrl, defaultScopes,
                    iconSlug, category, description, showUnverifiedAppWarning, isEnabled,
                    customFields,
                    defaultMarkupCredits, maxCallsPerRun,
                    createdAt, updatedAt, createdBy, tenantId, variant, newOrganizationId
            );
        }

        /** Whether this is a platform-wide credential (not tenant-scoped). */
        public boolean isPlatformWide() {
            return tenantId == null;
        }

        public boolean hasOAuth2Credentials() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        public boolean hasBasicAuth() {
            return username != null && !username.isBlank()
                    && password != null && !password.isBlank();
        }
    }

    /**
     * Endpoint status tracking for a platform credential.
     */
    public record PlatformCredentialEndpoint(
            Long id,
            Long platformCredentialId,
            String toolId,
            String toolName,
            boolean isEnabled,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    /**
     * Request to create a new platform credential.
     *
     * <p>{@code variant} targets the V103 {@code (integration_name, variant)}
     * UNIQUE key. When non-null, the admin dialog is configuring a specific
     * auth method (e.g. Airtable's {@code bearer_token} vs {@code oauth2}) and
     * the service upserts that exact row, leaving sibling variants intact.
     * When null, legacy behaviour kicks in: the service resolves the "primary"
     * row via {@code findByIntegrationName} and defaults the stored variant to
     * {@link PlatformCredential#DEFAULT_VARIANT}.
     */
    public record CreatePlatformCredentialRequest(
            String integrationName,
            String displayName,
            String authType,
            String clientId,
            String clientSecret,
            String apiKey,
            String username,
            String password,
            String authUrl,
            String tokenUrl,
            String defaultScopes,
            String iconSlug,
            String category,
            String description,
            Map<String, String> customFields,
            BigDecimal defaultMarkupCredits,
            Integer maxCallsPerRun,
            Boolean showUnverifiedAppWarning,
            String variant
    ) {

        /**
         * Legacy 17-arg constructor - defaults {@code variant} to {@code null}
         * so pre-Phase 2d callers (tests, InternalCredentialController) compile
         * unchanged. New admin-dialog calls pass the variant explicitly.
         */
        public CreatePlatformCredentialRequest(
                String integrationName,
                String displayName,
                String authType,
                String clientId,
                String clientSecret,
                String apiKey,
                String username,
                String password,
                String authUrl,
                String tokenUrl,
                String defaultScopes,
                String iconSlug,
                String category,
                String description,
                Map<String, String> customFields,
                BigDecimal defaultMarkupCredits,
                Integer maxCallsPerRun
        ) {
            this(integrationName, displayName, authType,
                    clientId, clientSecret, apiKey, username, password,
                    authUrl, tokenUrl, defaultScopes,
                    iconSlug, category, description,
                    customFields, defaultMarkupCredits, maxCallsPerRun,
                    null,
                    null);
        }

        public CreatePlatformCredentialRequest(
                String integrationName,
                String displayName,
                String authType,
                String clientId,
                String clientSecret,
                String apiKey,
                String username,
                String password,
                String authUrl,
                String tokenUrl,
                String defaultScopes,
                String iconSlug,
                String category,
                String description,
                Map<String, String> customFields,
                BigDecimal defaultMarkupCredits,
                Integer maxCallsPerRun,
                String variant
        ) {
            this(integrationName, displayName, authType,
                    clientId, clientSecret, apiKey, username, password,
                    authUrl, tokenUrl, defaultScopes,
                    iconSlug, category, description,
                    customFields, defaultMarkupCredits, maxCallsPerRun,
                    null,
                    variant);
        }

        public AuthType authTypeEnum() {
            return AuthType.fromValue(authType);
        }
    }

    /**
     * Request to update an existing platform credential.
     */
    public record UpdatePlatformCredentialRequest(
            String displayName,
            String clientId,
            String clientSecret,
            String apiKey,
            String username,
            String password,
            String authUrl,
            String tokenUrl,
            String defaultScopes,
            String iconSlug,
            String category,
            String description,
            Boolean isEnabled,
            Boolean showUnverifiedAppWarning,
            BigDecimal defaultMarkupCredits,
            Integer maxCallsPerRun
    ) {
    }

    /**
     * Response DTO for platform credentials.
     * Sensitive data (secrets, passwords) are never included.
     */
    public record PlatformCredentialResponse(
            Long id,
            String integrationName,
            String displayName,
            String authType,
            String clientIdMasked,
            boolean hasClientSecret,
            boolean hasApiKey,
            boolean hasBasicAuth,
            boolean hasCustomFields,
            String authUrl,
            String tokenUrl,
            String defaultScopes,
            String iconSlug,
            String category,
            String description,
            boolean showUnverifiedAppWarning,
            boolean isEnabled,
            BigDecimal defaultMarkupCredits,
            Integer maxCallsPerRun,
            List<EndpointStatusResponse> endpoints,
            Instant createdAt,
            Instant updatedAt,
            String tenantId,
            String variant,
            String organizationId
    ) {

        /**
         * Legacy 22-arg constructor - defaults {@code variant} to
         * {@link PlatformCredential#DEFAULT_VARIANT} and {@code organizationId}
         * to {@code null}. Kept so pre-Phase 2d callers (especially tests that
         * built the response manually) compile without modification.
         */
        public PlatformCredentialResponse(
                Long id,
                String integrationName,
                String displayName,
                String authType,
                String clientIdMasked,
                boolean hasClientSecret,
                boolean hasApiKey,
                boolean hasBasicAuth,
                boolean hasCustomFields,
                String authUrl,
                String tokenUrl,
                String defaultScopes,
                String iconSlug,
                String category,
                String description,
                boolean isEnabled,
                BigDecimal defaultMarkupCredits,
                Integer maxCallsPerRun,
                List<EndpointStatusResponse> endpoints,
                Instant createdAt,
                Instant updatedAt,
                String tenantId
        ) {
            this(id, integrationName, displayName, authType, clientIdMasked,
                    hasClientSecret, hasApiKey, hasBasicAuth, hasCustomFields,
                    authUrl, tokenUrl, defaultScopes, iconSlug, category, description,
                    true, isEnabled, defaultMarkupCredits, maxCallsPerRun,
                    endpoints, createdAt, updatedAt, tenantId,
                    PlatformCredential.DEFAULT_VARIANT, null);
        }

        /**
         * Pre-org canonical shape (with {@code showUnverifiedAppWarning} and
         * {@code variant}) - defaults {@code organizationId} to {@code null}.
         * Kept so callers that built the response with a variant but before the
         * org column compile unchanged.
         */
        public PlatformCredentialResponse(
                Long id,
                String integrationName,
                String displayName,
                String authType,
                String clientIdMasked,
                boolean hasClientSecret,
                boolean hasApiKey,
                boolean hasBasicAuth,
                boolean hasCustomFields,
                String authUrl,
                String tokenUrl,
                String defaultScopes,
                String iconSlug,
                String category,
                String description,
                boolean showUnverifiedAppWarning,
                boolean isEnabled,
                BigDecimal defaultMarkupCredits,
                Integer maxCallsPerRun,
                List<EndpointStatusResponse> endpoints,
                Instant createdAt,
                Instant updatedAt,
                String tenantId,
                String variant
        ) {
            this(id, integrationName, displayName, authType, clientIdMasked,
                    hasClientSecret, hasApiKey, hasBasicAuth, hasCustomFields,
                    authUrl, tokenUrl, defaultScopes, iconSlug, category, description,
                    showUnverifiedAppWarning, isEnabled, defaultMarkupCredits, maxCallsPerRun,
                    endpoints, createdAt, updatedAt, tenantId,
                    variant, null);
        }

        /**
         * Create a response from a domain entity.
         */
        public static PlatformCredentialResponse from(PlatformCredential credential) {
            return from(credential, List.of());
        }

        /**
         * Create a response from a domain entity with endpoints.
         */
        public static PlatformCredentialResponse from(
                PlatformCredential credential,
                List<EndpointStatusResponse> endpoints
        ) {
            return new PlatformCredentialResponse(
                    credential.id(),
                    credential.integrationName(),
                    credential.displayName(),
                    credential.authType().getValue(),
                    maskClientId(credential.clientId()),
                    credential.hasOAuth2Credentials(),
                    credential.hasApiKey(),
                    credential.hasBasicAuth(),
                    credential.customFields() != null && !credential.customFields().isEmpty(),
                    credential.authUrl(),
                    credential.tokenUrl(),
                    credential.defaultScopes(),
                    credential.iconSlug(),
                    credential.category(),
                    credential.description(),
                    credential.showUnverifiedAppWarning(),
                    credential.isEnabled(),
                    credential.defaultMarkupCredits() != null ? credential.defaultMarkupCredits() : BigDecimal.ZERO,
                    credential.maxCallsPerRun() != null ? credential.maxCallsPerRun() : 0,
                    endpoints,
                    credential.createdAt(),
                    credential.updatedAt(),
                    credential.tenantId(),
                    credential.variant() != null ? credential.variant() : PlatformCredential.DEFAULT_VARIANT,
                    credential.organizationId()
            );
        }

        private static String maskClientId(String clientId) {
            return ClientIdMasker.mask(clientId);
        }
    }

    /**
     * Response DTO for endpoint status.
     */
    public record EndpointStatusResponse(
            String toolId,
            String toolName,
            String method,
            String endpoint,
            boolean isEnabled
    ) {
    }

    /**
     * Request to toggle endpoint status.
     */
    public record ToggleEndpointRequest(
            boolean enabled
    ) {
    }

    /**
     * Public OAuth2 availability result consumed before rendering the standard
     * platform OAuth sign-in flow.
     */
    public record PlatformCredentialsAvailability(
            boolean available,
            boolean showUnverifiedAppWarning
    ) {
    }

    /**
     * Simple OAuth2 credentials holder for internal use.
     */
    public record OAuth2Credentials(
            String clientId,
            String clientSecret
    ) {

        public boolean isValid() {
            return clientId != null && !clientId.isBlank()
                    && clientSecret != null && !clientSecret.isBlank();
        }
    }

    /**
     * Category information for grouping integrations.
     */
    public record CategoryInfo(
            String slug,
            String name,
            String icon,
            int integrationCount
    ) {
    }

    /**
     * Integration info combining catalog data with credential status.
     */
    public record IntegrationInfo(
            String id,
            String name,
            String iconSlug,
            String authType,
            String category,
            boolean hasCredential,
            boolean isEnabled,
            List<EndpointInfo> endpoints
    ) {
    }

    /**
     * Endpoint info from catalog.
     */
    public record EndpointInfo(
            String toolId,
            String toolName,
            String method,
            String endpoint,
            String description
    ) {
    }
}
