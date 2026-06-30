package com.apimarketplace.auth.credential.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Domain models for user credentials (API keys, OAuth2 tokens, etc.).
 */
public final class CredentialModels {
    private CredentialModels() {}

    /**
     * Main credential record.
     *
     * <p>{@code organizationId} (PR19) carries the org workspace this credential
     * belongs to. NULL = personal scope (visible only to the owning tenant_id
     * outside any org workspace); non-NULL = team scope (visible to all
     * members of the org via strict-isolation repository finders). Cross-scope
     * mixing is forbidden - the {@code findByXxx} methods on
     * {@link com.apimarketplace.auth.credential.repository.CredentialRepository}
     * return rows from exactly one of the two scopes, never both.</p>
     */
    public record Credential(
            Long id,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("organization_id") String organizationId,
            String name,
            String integration,
            CredentialType type,
            CredentialEnvironment environment,
            CredentialStatus status,
            String description,
            @JsonProperty("credential_data") Map<String, Object> credentialData,
            List<String> scopes,
            List<String> tags,
            String owner,
            @JsonProperty("icon_url") String iconUrl,
            @JsonProperty("is_default") boolean isDefault,
            @JsonProperty("last_used") Instant lastUsed,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt
    ) {
        /**
         * Legacy constructor - defaults {@code organizationId} to
         * {@code null} (personal scope). Kept so pre-PR19 call sites continue
         * to compile; new code should use the canonical constructor or
         * {@link #withOrganizationId(String)} to opt into org scope.
         */
        public Credential(
                Long id,
                String tenantId,
                String name,
                String integration,
                CredentialType type,
                CredentialEnvironment environment,
                CredentialStatus status,
                String description,
                Map<String, Object> credentialData,
                List<String> scopes,
                List<String> tags,
                String owner,
                String iconUrl,
                boolean isDefault,
                Instant lastUsed,
                Instant createdAt,
                Instant updatedAt) {
            this(id, tenantId, null, name, integration, type, environment, status, description,
                    credentialData, scopes, tags, owner, iconUrl, isDefault,
                    lastUsed, createdAt, updatedAt);
        }

        public Credential withId(Long newId) {
            return new Credential(newId, tenantId, organizationId, name, integration, type, environment, status, description, credentialData, scopes, tags, owner, iconUrl, isDefault, lastUsed, createdAt, updatedAt);
        }

        public Credential withIsDefault(boolean newIsDefault) {
            return new Credential(id, tenantId, organizationId, name, integration, type, environment, status, description, credentialData, scopes, tags, owner, iconUrl, newIsDefault, lastUsed, createdAt, updatedAt);
        }

        /**
         * PR19 - tag (or clear) the org scope. Pass {@code null} to revert to
         * personal scope. Used at INSERT-time by the controller after reading
         * the {@code X-Organization-ID} header.
         */
        public Credential withOrganizationId(String newOrganizationId) {
            return new Credential(id, tenantId, newOrganizationId, name, integration, type, environment, status, description, credentialData, scopes, tags, owner, iconUrl, isDefault, lastUsed, createdAt, updatedAt);
        }

        /**
         * Public-API allowlist of diagnostic keys retained by {@link #withoutSecrets()}.
         *
         * <p>These are non-secret fields written by the OAuth2 refresh pipeline
         * ({@code OAuth2Service.releaseTerminal}/{@code releaseTransient}) and the BYOK
         * cascade ({@code CredentialService.revokeForByokDelete}) that the frontend needs
         * in order to explain to the user <em>why</em> a credential is in
         * {@code needs_reauth}. Any key not on this allowlist is dropped.
         *
         * <p>The set is a STRICT ALLOWLIST - adding a new diagnostic field here is the
         * only way it reaches the API. Anything related to tokens, OAuth client secrets,
         * or API keys must NOT appear in this list (it would leak through the
         * scrub-on-public-response path).
         */
        public static final Set<String> PUBLIC_DIAGNOSTIC_KEYS = Set.of(
                // Cascade-revoke trail (CredentialService.revokeForByokDelete)
                "byok_revoke_reason",
                "byok_revoked_at",
                // OAuth2 refresh terminal trail (OAuth2Service.releaseTerminal / releaseTransient)
                "refresh_error_reason",
                "refresh_error_reason_detail",
                "refresh_error_provider_code",
                "refresh_error_http_status",
                "refresh_error_at",
                "refresh_attempts_before_terminal",
                // Lifecycle hint shown next to status badges
                "expires_at"
        );

        /**
         * Returns a copy safe for public API responses: secrets are removed but
         * a small allowlist of diagnostic keys ({@link #PUBLIC_DIAGNOSTIC_KEYS}) is
         * preserved so the frontend can explain {@code needs_reauth}/error states
         * to the user (e.g. "your custom OAuth app was deleted").
         *
         * <p>The allowlist is intentionally narrow - anything not enumerated is
         * dropped. Tokens, client secrets, API keys, etc. never appear in the
         * allowlist by construction.
         */
        public Credential withoutSecrets() {
            Map<String, Object> publicData;
            if (credentialData == null || credentialData.isEmpty()) {
                publicData = Collections.emptyMap();
            } else {
                publicData = new HashMap<>();
                for (Map.Entry<String, Object> e : credentialData.entrySet()) {
                    if (PUBLIC_DIAGNOSTIC_KEYS.contains(e.getKey()) && e.getValue() != null) {
                        publicData.put(e.getKey(), e.getValue());
                    }
                }
                if (publicData.isEmpty()) {
                    publicData = Collections.emptyMap();
                }
            }
            return new Credential(id, tenantId, organizationId, name, integration, type, environment, status, description, publicData, scopes, tags, owner, iconUrl, isDefault, lastUsed, createdAt, updatedAt);
        }
    }

    /**
     * Credential types.
     */
    public enum CredentialType {
        OAuth2("OAuth2"),
        API_Key("API Key"),
        Basic_Auth("Basic Auth"),
        Webhook("Webhook");

        private final String displayName;

        CredentialType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public static CredentialType fromString(String value) {
            if (value == null) return OAuth2;
            String normalized = value.replace(" ", "_");
            try {
                return valueOf(normalized);
            } catch (IllegalArgumentException e) {
                // Try direct match
                for (CredentialType type : values()) {
                    if (type.displayName.equals(value)) {
                        return type;
                    }
                }
                return OAuth2;
            }
        }
    }

    /**
     * Credential environments.
     */
    public enum CredentialEnvironment {
        Production,
        Sandbox
    }

    /**
     * Credential statuses.
     *
     * <p>Lifecycle:
     * <ul>
     *   <li>{@link #active} - normal, eligible for proactive refresh.</li>
     *   <li>{@link #expiring} - access_token near expiry; proactive refresh queue.</li>
     *   <li>{@link #error} - TERMINAL_CONFIG: admin must fix template/client secret/scope.
     *       Covers RFC 6749 {@code invalid_client}, {@code invalid_scope}, and
     *       client-side protocol bugs. Tokens are scrubbed by the refresh pipeline.</li>
     *   <li>{@link #needs_reauth} - TERMINAL_USER: refresh_token revoked or expired by provider
     *       (RFC 6749 {@code invalid_grant} / {@code unauthorized_client}). User must re-OAuth.
     *       Tokens are scrubbed; do NOT retry - retries will produce the same error and risk
     *       quota / account flagging from the provider.</li>
     * </ul>
     */
    public enum CredentialStatus {
        active,
        expiring,
        error,
        needs_reauth
    }

    /**
     * Paginated response for credentials listing.
     */
    public record PaginatedCredentialsResponse(
            List<Credential> credentials,
            int page,
            int pageSize,
            int totalItems,
            int totalPages,
            boolean hasNext,
            boolean hasPrevious
    ) {}
}
