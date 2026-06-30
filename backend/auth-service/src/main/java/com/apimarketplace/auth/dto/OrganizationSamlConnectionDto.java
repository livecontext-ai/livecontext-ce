package com.apimarketplace.auth.dto;

import com.apimarketplace.auth.domain.OrganizationSamlConnection;

import java.time.Instant;
import java.util.UUID;

public record OrganizationSamlConnectionDto(
        boolean configured,
        UUID organizationId,
        String idpAlias,
        String displayName,
        String idpEntityId,
        String ssoUrl,
        String status,
        boolean hideOnLoginPage,
        String ssoStartPath,
        String serviceProviderEntityId,
        String assertionConsumerServiceUrl,
        String serviceProviderMetadataUrl,
        String certificateFingerprintSha256,
        Instant lastSyncedAt,
        String lastError
) {
    public static OrganizationSamlConnectionDto notConfigured(
            UUID organizationId,
            String idpAlias,
            String ssoStartPath,
            String serviceProviderEntityId,
            String assertionConsumerServiceUrl,
            String serviceProviderMetadataUrl
    ) {
        return new OrganizationSamlConnectionDto(
                false,
                organizationId,
                idpAlias,
                "",
                "",
                "",
                "NOT_CONFIGURED",
                true,
                ssoStartPath,
                serviceProviderEntityId,
                assertionConsumerServiceUrl,
                serviceProviderMetadataUrl,
                null,
                null,
                null
        );
    }

    public static OrganizationSamlConnectionDto fromEntity(
            OrganizationSamlConnection connection,
            String ssoStartPath,
            String serviceProviderEntityId,
            String assertionConsumerServiceUrl,
            String serviceProviderMetadataUrl,
            String certificateFingerprintSha256
    ) {
        return new OrganizationSamlConnectionDto(
                true,
                connection.getOrganization().getId(),
                connection.getIdpAlias(),
                connection.getDisplayName(),
                connection.getIdpEntityId(),
                connection.getSsoUrl(),
                connection.getStatus().name(),
                connection.isHideOnLoginPage(),
                ssoStartPath,
                serviceProviderEntityId,
                assertionConsumerServiceUrl,
                serviceProviderMetadataUrl,
                certificateFingerprintSha256,
                connection.getLastSyncedAt(),
                connection.getLastError()
        );
    }
}
