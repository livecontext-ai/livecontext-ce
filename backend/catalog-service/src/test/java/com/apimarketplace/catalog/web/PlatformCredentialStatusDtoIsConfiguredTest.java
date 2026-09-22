package com.apimarketplace.catalog.web;

import com.apimarketplace.credential.client.dto.PlatformCredentialStatusDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two {@link PlatformCredentialStatusDto} predicates that decide whether an
 * admin-disabled row is hidden from end users: {@code isConfigured()} gates phantom
 * placeholder rows, and {@code holdsOAuthClient()} keeps an OAuth row listed so the
 * user can still connect BYOK. Both are read by the twin {@code fetchDisabledVariantKeys}
 * helpers in {@link CredentialTemplateController} and {@code WorkflowInspectorService}.
 * Lives in catalog-service (not credential-client) because the client module has no
 * test dependencies - and catalog-service is the only consumer of the predicates today.
 */
@DisplayName("PlatformCredentialStatusDto - the predicates that gate variant hiding")
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

    @Nested
    @DisplayName("holdsOAuthClient - the row the user can substitute with their own app")
    class HoldsOAuthClient {

        @Test
        @DisplayName("true for the catalog's oauth2 variant - the literal every seed uses")
        void oauth2Variant() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setVariant("oauth2");
            assertThat(dto.holdsOAuthClient()).isTrue();
        }

        @Test
        @DisplayName("true for a row saved with no variant, stored under the 'primary' default, when it carries a client secret - the variant literal alone would miss it")
        void primaryVariantWithClientSecret() {
            // auth.platform_credentials rows written by a variant-less platform-wide save
            // land on DEFAULT_VARIANT="primary"; the platform infers authType=oauth2 from
            // the client_id/secret pair, so the secret shape is the reliable signal.
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setVariant("primary");
            dto.setHasClientSecret(true);
            assertThat(dto.holdsOAuthClient()).isTrue();
        }

        @Test
        @DisplayName("false for an api_key row - whether hiding a disabled one is still right is a separate and wider question, left as it is rather than settled by this predicate")
        void apiKeyRow() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setVariant("api_key");
            dto.setHasApiKey(true);
            assertThat(dto.holdsOAuthClient()).isFalse();
        }

        @Test
        @DisplayName("false for a custom row carrying a client_id/client_secret pair - tidio, personio and box declare those field names, and the admin dialog files them in the OAuth columns, but they are not OAuth clients")
        void customVariantWithClientSecretIsNotAnOAuthClient() {
            // The wizard's BYOK branch is gated on authType === "oauth2", so these land on
            // the custom-fields form. Keying on hasClientSecret alone would silently widen
            // the carve-out to them and contradict the scope this change claims.
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setVariant("custom");
            dto.setHasClientSecret(true);
            assertThat(dto.holdsOAuthClient()).isFalse();
        }

        @Test
        @DisplayName("false for a bare 'primary' row with no client secret - absence of every hasX flag must not be read as an OAuth client")
        void primaryVariantWithoutSecrets() {
            PlatformCredentialStatusDto dto = new PlatformCredentialStatusDto();
            dto.setVariant("primary");
            assertThat(dto.holdsOAuthClient()).isFalse();
        }
    }
}
