package com.apimarketplace.auth.credential.web.dto;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredential;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredentialResponse;
import com.apimarketplace.auth.credential.util.ClientIdMasker;

import java.time.Instant;

/**
 * Tenant-facing summary of a custom OAuth connection (a tenant-owned
 * {@code auth.platform_credentials} row).
 *
 * <p>Explicit allowlist of fields - never expose secrets, custom_fields, or
 * markup/billing config to a regular user. The {@code MyOAuthAppDtoLeakTest}
 * regression guard asserts the field set and that no raw secret value appears
 * in the JSON serialization.
 */
public record MyOAuthAppDto(
        Long id,
        String integrationName,
        String displayName,
        String iconSlug,
        String authType,
        String clientIdMasked,
        boolean hasClientSecret,
        boolean hasApiKey,
        boolean isEnabled,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        // Org that owns this connection. null = personal scope (visible in every
        // workspace). When set, the connection is scoped to that workspace; the
        // frontend compares it to the active org to render a scope badge.
        String organizationId
) {

    public static MyOAuthAppDto from(PlatformCredential c) {
        // For non-OAuth2 rows (API_KEY, BASIC, …) the source row carries no
        // clientId. Returning null here keeps the contract self-explanatory
        // - UIs render "-" rather than a misleading "****" placeholder that
        // looks like there's a hidden value.
        String clientIdMasked = (c.clientId() == null || c.clientId().isBlank())
                ? null
                : ClientIdMasker.mask(c.clientId());
        return new MyOAuthAppDto(
                c.id(),
                c.integrationName(),
                c.displayName(),
                c.iconSlug(),
                c.authType().getValue(),
                clientIdMasked,
                c.clientSecret() != null && !c.clientSecret().isBlank(),
                c.apiKey() != null && !c.apiKey().isBlank(),
                c.isEnabled(),
                c.createdAt(),
                c.updatedAt(),
                c.createdBy(),
                c.organizationId()
        );
    }

    public static MyOAuthAppDto from(PlatformCredentialResponse response) {
        String clientIdMasked = "oauth2".equalsIgnoreCase(response.authType())
                ? response.clientIdMasked()
                : null;
        return new MyOAuthAppDto(
                response.id(),
                response.integrationName(),
                response.displayName(),
                response.iconSlug(),
                response.authType(),
                clientIdMasked,
                response.hasClientSecret(),
                response.hasApiKey(),
                response.isEnabled(),
                response.createdAt(),
                response.updatedAt(),
                null,
                response.organizationId()
        );
    }
}
