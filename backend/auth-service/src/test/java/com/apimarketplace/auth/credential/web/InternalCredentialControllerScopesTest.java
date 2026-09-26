package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/internal/credentials/scopes} - the OAuth-scope preflight catalog-service
 * calls before executing a tool ({@code HttpExecutionService.preflightScopeCheck}).
 *
 * <p>Resolution lives in {@link InternalCredentialService#getCredentialScopes}, which tries
 * the integration-FILTERED by-name lookup first and only then falls back to the INTEGRATION,
 * exactly like the token path. That fallback is why the guard runs at all: a credential's
 * name is free text the user typed, and in production the two Gmail credentials are called
 * "Jaden" and "Gmail Credential" while the requirement is keyed on `gmail`. Name-only
 * resolution answered 404 every time and the catalog side failed OPEN, so the preflight
 * fired 0 times while the provider refused 91 calls for the very scope gap it screens for.
 *
 * <p>A regression here raises no error anywhere, it just quietly stops enforcing scopes.
 * Hence the two properties pinned below: the endpoint must go through that shared
 * resolution, and a credential belonging to ANOTHER provider must never answer.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCredentialController GET /scopes")
class InternalCredentialControllerScopesTest {

    @Mock
    private InternalCredentialService credentialService;

    @Mock
    private CredentialService userCredentialService;

    @Mock
    private PlatformCredentialService platformCredentialService;

    @Mock
    private PlatformCredentialPricingService pricingService;

    @Mock
    private PricingVersionService pricingVersionService;

    @Mock
    private CredentialEncryptionService encryptionService;

    @InjectMocks
    private InternalCredentialController controller;

    private static InternalCredentialService.CredentialScopes scopes(
            String type, List<String> granted, String integration, String name) {
        return new InternalCredentialService.CredentialScopes(type, granted, integration, name);
    }

    @Test
    @DisplayName("resolves through the shared credential resolution, never a raw by-name lookup")
    void usesTheSharedResolution() {
        when(credentialService.getCredentialScopes("user-1", "gmail", null))
                .thenReturn(Optional.of(scopes(
                        "OAuth2", List.of("https://mail.google.com/"), "gmail", "Gmail Credential")));

        ResponseEntity<Map<String, Object>> response =
                controller.getCredentialScopes("user-1", "gmail", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("integration", "gmail");
        assertThat(response.getBody()).containsEntry("scopes", List.of("https://mail.google.com/"));
        // The credential answering here is named "Gmail Credential", not "gmail": that is the
        // production shape a name-only lookup could never find.
        assertThat(response.getBody()).containsEntry("name", "Gmail Credential");
        // Reaching the raw by-name lookup would answer for a credential of any provider.
        verify(userCredentialService, never())
                .findByNameIdentifyingIntegration(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("forwards the workspace so an org-shared credential resolves like its token does")
    void forwardsTheOrganization() {
        when(credentialService.getCredentialScopes("user-1", "gmail", "org-9"))
                .thenReturn(Optional.of(scopes("OAuth2", List.of("email"), "gmail", "Shared Gmail")));

        ResponseEntity<Map<String, Object>> response =
                controller.getCredentialScopes("user-1", "gmail", "org-9");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(credentialService).getCredentialScopes("user-1", "gmail", "org-9");
    }

    @Test
    @DisplayName("404s when no credential of that integration exists")
    void mislabelledCredentialDoesNotAnswer() {
        // A Slack key a user happened to name "elevenlabs" is rejected by the filtered
        // lookup AND carries integration 'slack', so the integration fallback misses it too.
        // The preflight must find nothing rather than compare Slack's granted scopes against
        // ElevenLabs' requirement and reach a verdict about the wrong account.
        when(credentialService.getCredentialScopes("user-1", "elevenlabs", null))
                .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response =
                controller.getCredentialScopes("user-1", "elevenlabs", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("reports null scopes for a non-OAuth2 credential rather than an empty list")
    void nonOauth2CredentialReportsNullScopes() {
        // An empty list would read as "this credential was granted zero scopes" and fail the
        // preflight; null is what tells the caller the scope concept does not apply.
        when(credentialService.getCredentialScopes("user-1", "smtp", null))
                .thenReturn(Optional.of(scopes("API_Key", null, "smtp", "my mailer")));

        ResponseEntity<Map<String, Object>> response =
                controller.getCredentialScopes("user-1", "smtp", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("type", "API_Key");
        assertThat(response.getBody()).containsEntry("scopes", null);
    }

    @Test
    @DisplayName("carries no secret: the body names the credential without exposing its data")
    void bodyCarriesNoSecret() {
        when(credentialService.getCredentialScopes("user-1", "gmail", null))
                .thenReturn(Optional.of(scopes("OAuth2", List.of("email"), "gmail", "Gmail Credential")));

        ResponseEntity<Map<String, Object>> response =
                controller.getCredentialScopes("user-1", "gmail", null);

        assertThat(response.getBody()).containsOnlyKeys("type", "scopes", "integration", "name");
        assertThat(response.getBody().toString()).doesNotContain("super-secret");
        verify(encryptionService, never()).decrypt(ArgumentMatchers.anyString());
    }
}
