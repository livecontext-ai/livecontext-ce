package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest;
import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2SimpleInitiateRequest;
import com.apimarketplace.auth.credential.domain.OAuth2Models.PickerTokenRequest;
import com.apimarketplace.auth.credential.domain.OAuth2Models.PickerTokenResponse;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredentialsAvailability;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.OAuth2Service;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2Controller")
class OAuth2ControllerTest {

    @Mock
    private OAuth2Service oAuth2Service;
    @Mock
    private TenantResolver tenantResolver;
    @Mock
    private InternalCredentialService internalCredentialService;

    private OAuth2Controller controller;

    @BeforeEach
    void setUp() {
        controller = new OAuth2Controller(oAuth2Service, tenantResolver, internalCredentialService);
    }

    @Test
    @DisplayName("has-platform-credentials returns availability and the unverified-app warning flag")
    void hasPlatformCredentials_returnsAvailabilityWithWarningFlag() {
        when(oAuth2Service.getPlatformCredentialsAvailability("gmail"))
                .thenReturn(new PlatformCredentialsAvailability(true, false));

        ResponseEntity<Map<String, Boolean>> response = controller.hasPlatformCredentials("gmail");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody())
                .containsEntry("available", true)
                .containsEntry("showUnverifiedAppWarning", false);
    }

    @Test
    @DisplayName("picker-token returns the caller's fresh access token for an allowed Google integration")
    void pickerToken_returnsAccessToken_forAllowedIntegration() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org1");
        when(internalCredentialService.refreshAccessToken("u1", "googlesheets", "org1"))
                .thenReturn(Optional.of("ya29.fresh"));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Sheets", "googlesheets"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(PickerTokenResponse.class);
        assertThat(((PickerTokenResponse) response.getBody()).accessToken()).isEqualTo("ya29.fresh");
    }

    @Test
    @DisplayName("picker-token refuses a non-picker integration with 403 and never touches the credential service")
    void pickerToken_403_forUnsupportedIntegration() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Stripe", "stripe-cred"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        verify(internalCredentialService, never()).refreshAccessToken(any(), any(), any());
    }

    @Test
    @DisplayName("picker-token returns 404 when the user has no refreshable Google credential")
    void pickerToken_404_whenNoCredential() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.refreshAccessToken("u1", "googledocs", null))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Docs", "googledocs"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(internalCredentialService).refreshAccessToken("u1", "googledocs", null);
    }

    @Test
    @DisplayName("picker-token refuses a null integration with 403 (null-coalesce guard)")
    void pickerToken_403_forNullIntegration() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest(null, "whatever"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(internalCredentialService, never()).refreshAccessToken(any(), any(), any());
    }

    @Test
    @DisplayName("picker-token normalizes the integration (trim + case-insensitive) before the allowlist check")
    void pickerToken_normalizesIntegration() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.refreshAccessToken("u1", "googledrive", null))
                .thenReturn(Optional.of("ya29.drive"));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("  GOOGLE DRIVE  ", "googledrive"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((PickerTokenResponse) response.getBody()).accessToken()).isEqualTo("ya29.drive");
    }

    // ─────────────── initiate: ?locale= drives the consent-screen UI locale ───────────────

    private OAuth2InitiateRequest initiateRequest() {
        return new OAuth2InitiateRequest("tmpl-1", null, null, null, "Production", null, null);
    }

    @Test
    @DisplayName("initiate: ?locale=fr-FR is normalized to 'fr' and forwarded to the service")
    void initiate_forwardsNormalizedLocale() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org1");
        OAuth2InitiateRequest request = initiateRequest();

        controller.initiate(req, "fr-FR", request);

        verify(oAuth2Service).initiate(eq(request), eq("u1"), eq("org1"), eq("fr"));
    }

    @Test
    @DisplayName("initiate: ?locale absent → null forwarded (provider keeps its own default)")
    void initiate_nullLocaleForwarded() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        OAuth2InitiateRequest request = initiateRequest();

        controller.initiate(req, null, request);

        verify(oAuth2Service).initiate(eq(request), eq("u1"), isNull(), isNull());
    }

    @Test
    @DisplayName("initiate: ?locale=EN is lowercased to 'en'")
    void initiate_lowercasesLocale() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        OAuth2InitiateRequest request = initiateRequest();

        controller.initiate(req, "EN", request);

        verify(oAuth2Service).initiate(eq(request), eq("u1"), isNull(), eq("en"));
    }

    @Test
    @DisplayName("initiate: junk ?locale ('*') is rejected → null (never reaches the authorize URL)")
    void initiate_rejectsJunkLocale() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        OAuth2InitiateRequest request = initiateRequest();

        controller.initiate(req, "*", request);

        verify(oAuth2Service).initiate(eq(request), eq("u1"), isNull(), isNull());
    }

    @Test
    @DisplayName("initiate-simple: ?locale=de is forwarded to the simple service path")
    void initiateSimple_forwardsLocale() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org1");
        OAuth2SimpleInitiateRequest request =
                new OAuth2SimpleInitiateRequest("tmpl-1", null, "Production", null);

        controller.initiateSimple(req, "de", request);

        verify(oAuth2Service).initiateSimple(eq(request), eq("u1"), eq("org1"), eq("de"));
    }
}
