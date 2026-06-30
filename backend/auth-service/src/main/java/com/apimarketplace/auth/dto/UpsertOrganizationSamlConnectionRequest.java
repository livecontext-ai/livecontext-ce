package com.apimarketplace.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertOrganizationSamlConnectionRequest(
        @NotBlank
        @Size(max = 80)
        String displayName,

        @NotBlank
        @Size(max = 512)
        String idpEntityId,

        @NotBlank
        @Size(max = 2048)
        String ssoUrl,

        @Size(max = 20000)
        String x509Certificate,

        Boolean hideOnLoginPage
) {
}
