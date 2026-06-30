package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers {@link PlatformCredentialsController#enableVariant} and
 * {@link PlatformCredentialsController#disableVariant} - the Phase 2d
 * per-variant toggle endpoints.
 *
 * <p>The admin UI calls these when an operator flips the OAuth2 / PAT / API-key
 * row individually in the integration card. Key contract points:
 * <ul>
 *   <li>Body echoes {@code integrationName}, {@code variant}, {@code enabled}
 *       - the UI uses that echo to replace the specific variant row without
 *       re-fetching the full list.</li>
 *   <li>404 when the service reports no row affected (UNIQUE(name, variant)
 *       miss) - the UI treats this as a stale-row warning, not a silent 200.</li>
 *   <li>403 when the caller is not admin - {@code AdminRoleGuard} short-circuits
 *       before the service is touched.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialsController - per-variant toggle (Phase 2d)")
class PlatformCredentialsControllerVariantToggleTest {

    @Mock
    private PlatformCredentialService service;

    @Mock
    private PlatformCredentialPricingService pricingService;

    @Mock
    private CredentialService credentialService;

    @InjectMocks
    private PlatformCredentialsController controller;

    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";

    // ---------------------------------------------------------------------
    // enableVariant
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("enableVariant: admin + known variant → 200 and body echoes enabled=true")
    void enableVariant_adminKnownVariant_returnsOk() {
        when(service.setVariantEnabled("gmail", "oauth2", true)).thenReturn(true);

        ResponseEntity<?> response = controller.enableVariant(ADMIN, "gmail", "oauth2");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("integrationName")).isEqualTo("gmail");
        assertThat(body.get("variant")).isEqualTo("oauth2");
        assertThat(body.get("enabled")).isEqualTo(true);
    }

    @Test
    @DisplayName("enableVariant: admin + unknown (name, variant) pair → 404 with error body")
    void enableVariant_unknownVariant_returns404() {
        when(service.setVariantEnabled("gmail", "bogus_variant", true)).thenReturn(false);

        ResponseEntity<?> response = controller.enableVariant(ADMIN, "gmail", "bogus_variant");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("error")).isEqualTo("Platform credential variant not found");
        assertThat(body.get("integrationName")).isEqualTo("gmail");
        assertThat(body.get("variant")).isEqualTo("bogus_variant");
    }

    @Test
    @DisplayName("enableVariant: non-admin caller → 403 and service is never invoked")
    void enableVariant_nonAdmin_returns403() {
        ResponseEntity<?> response = controller.enableVariant(USER, "gmail", "oauth2");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).setVariantEnabled(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ---------------------------------------------------------------------
    // disableVariant
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("disableVariant: admin + known variant → 200 and body echoes enabled=false")
    void disableVariant_adminKnownVariant_returnsOk() {
        when(service.setVariantEnabled("slack", "bearer_token", false)).thenReturn(true);

        ResponseEntity<?> response = controller.disableVariant(ADMIN, "slack", "bearer_token");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
        assertThat(body.get("variant")).isEqualTo("bearer_token");
        assertThat(body.get("enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("disableVariant: admin + unknown pair → 404")
    void disableVariant_unknownVariant_returns404() {
        when(service.setVariantEnabled("slack", "phantom", false)).thenReturn(false);

        ResponseEntity<?> response = controller.disableVariant(ADMIN, "slack", "phantom");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("disableVariant: non-admin caller → 403, service never invoked")
    void disableVariant_nonAdmin_returns403() {
        ResponseEntity<?> response = controller.disableVariant(USER, "slack", "bearer_token");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(service, never()).setVariantEnabled(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyBoolean());
    }

    // ---------------------------------------------------------------------
    // Path-variable faithfulness: ensure controller forwards the *exact*
    // strings from the URL so the service can normalize. Regression guard
    // against any accidental lower-casing or trimming in the controller layer.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("passes path variables verbatim to service (no pre-normalization in controller)")
    void forwardsPathVariablesVerbatim() {
        when(service.setVariantEnabled("Gmail Credential", "oauth2", true)).thenReturn(true);

        controller.enableVariant(ADMIN, "Gmail Credential", "oauth2");

        verify(service).setVariantEnabled("Gmail Credential", "oauth2", true);
    }
}
