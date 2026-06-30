package com.apimarketplace.catalog.web;

import com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@link PlatformCredentialStatusDto#isConfigured()} contract used by
 * {@link CredentialTemplateController#fetchDisabledVariantKeys()} to gate phantom
 * placeholder rows. Lives in catalog-service (not credential-client) because the
 * client module has no test dependencies - and catalog-service is the only
 * consumer of the predicate today.
 */
@DisplayName("PlatformCredentialStatusDto.isConfigured()")
class PlatformCredentialStatusDtoIsConfiguredTest {

    @Nested
    @DisplayName("returns false")
    class ReturnsFalse {

        @Test
        @DisplayName("when every hasX flag is null - the phantom placeholder shape inserted by setEnabledForVariant before any admin save")
        void allNullFlagsAreUnconfigured() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            assertThat(dto.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("when every hasX flag is explicitly false - server side knows no secret has been saved")
        void allFalseFlagsAreUnconfigured() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setHasClientSecret(false);
            dto.setHasApiKey(false);
            dto.setHasBasicAuth(false);
            dto.setHasCustomFields(false);
            assertThat(dto.isConfigured()).isFalse();
        }
    }

    @Nested
    @DisplayName("returns true")
    class ReturnsTrue {

        @Test
        @DisplayName("when hasClientSecret is true - admin saved an OAuth2 app")
        void oauth2Configured() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setHasClientSecret(true);
            assertThat(dto.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("when hasApiKey is true - admin saved an API key credential")
        void apiKeyConfigured() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setHasApiKey(true);
            assertThat(dto.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("when hasBasicAuth is true - admin saved username+password")
        void basicAuthConfigured() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setHasBasicAuth(true);
            assertThat(dto.isConfigured()).isTrue();
        }

        @Test
        @DisplayName("when hasCustomFields is true - bearer-token / custom-auth credential saved")
        void customFieldsConfigured() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setHasCustomFields(true);
            assertThat(dto.isConfigured()).isTrue();
        }
    }
}
