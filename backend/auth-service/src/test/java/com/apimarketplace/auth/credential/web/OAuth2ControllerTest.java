package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateRequest;
import com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2SimpleInitiateRequest;
import com.apimarketplace.auth.credential.domain.OAuth2Models.PickerTokenRequest;
import com.apimarketplace.auth.credential.domain.OAuth2Models.PickerTokenResponse;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredentialsAvailability;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.OAuth2Service;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshErrorBucket;
import com.apimarketplace.auth.credential.service.oauth2.refresh.RefreshTerminalException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    /** Picker-safe by default (LC-028/LC-072 drive.file scope): most tests exercise the happy path. */
    private static com.apimarketplace.auth.credential.domain.CredentialModels.Credential googleCredential(String integration) {
        return googleCredential(integration, java.util.List.of("https://www.googleapis.com/auth/drive.file"));
    }

    private static com.apimarketplace.auth.credential.domain.CredentialModels.Credential googleCredential(
            String integration, java.util.List<String> scopes) {
        return new com.apimarketplace.auth.credential.domain.CredentialModels.Credential(
                10L, "u1", null, integration + " Credential", integration,
                com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType.OAuth2,
                com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment.Production,
                com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus.active,
                null, Map.of(), scopes, null, null, null, false, null, null, null);
    }

    @Test
    @DisplayName("callback: TikTok-Business auth_code param is used when the RFC code param is absent")
    void callback_fallsBackToAuthCode() throws Exception {
        jakarta.servlet.http.HttpServletResponse response =
                mock(jakarta.servlet.http.HttpServletResponse.class);
        when(oAuth2Service.handleCallback("AC-123", "st-1", null)).thenReturn("https://app/ok");

        controller.callback(mock(HttpServletRequest.class), response, null, "AC-123", "st-1", null, null, null);

        verify(oAuth2Service).handleCallback("AC-123", "st-1", null);
        verify(response).sendRedirect("https://app/ok");
    }

    @Test
    @DisplayName("callback: the RFC code param wins when both code and auth_code are present")
    void callback_prefersCodeOverAuthCode() throws Exception {
        jakarta.servlet.http.HttpServletResponse response =
                mock(jakarta.servlet.http.HttpServletResponse.class);
        when(oAuth2Service.handleCallback("RFC-CODE", "st-2", null)).thenReturn("https://app/ok");

        controller.callback(mock(HttpServletRequest.class), response, "RFC-CODE", "AC-should-be-ignored", "st-2", null, null, null);

        verify(oAuth2Service).handleCallback("RFC-CODE", "st-2", null);
    }

    @Test
    @DisplayName("callback: neither code nor auth_code → missing_code redirect, service never called")
    void callback_missingBothCodes() throws Exception {
        jakarta.servlet.http.HttpServletResponse response =
                mock(jakarta.servlet.http.HttpServletResponse.class);

        controller.callback(mock(HttpServletRequest.class), response, null, null, "st-3", null, null, null);

        verify(oAuth2Service, never()).handleCallback(any(), any(), any());
        verify(response).sendRedirect(contains("error=missing_code"));
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
        when(internalCredentialService.findActiveCredential("u1", "googlesheets", "org1")).thenReturn(Optional.of(googleCredential("googlesheets")));
        when(internalCredentialService.getOrRefreshOAuth2AccessTokenById("u1", 10L, "org1"))
                .thenReturn(Optional.of("ya29.fresh"));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Sheets", "googlesheets"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(PickerTokenResponse.class);
        assertThat(((PickerTokenResponse) response.getBody()).accessToken()).isEqualTo("ya29.fresh");
    }

    @Test
    @DisplayName("LC-058: a successful picker-token mint is recorded as a credential secret read")
    void pickerToken_recordsSecretReadOnSuccess() {
        com.apimarketplace.auth.credential.service.CredentialAuditRecorder auditRecorder =
                mock(com.apimarketplace.auth.credential.service.CredentialAuditRecorder.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "auditRecorder", auditRecorder);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org1");
        when(internalCredentialService.findActiveCredential("u1", "googlesheets", "org1"))
                .thenReturn(Optional.of(googleCredential("googlesheets")));
        when(internalCredentialService.getOrRefreshOAuth2AccessTokenById("u1", 10L, "org1"))
                .thenReturn(Optional.of("ya29.fresh"));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Sheets", "googlesheets"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(auditRecorder).recordSecretRead("u1", 10L, "googlesheets", "picker_token");
    }

    @Test
    @DisplayName("LC-058: a refused picker-token (scope too broad) does NOT record a secret read")
    void pickerToken_scopeRefusal_recordsNoSecretRead() {
        com.apimarketplace.auth.credential.service.CredentialAuditRecorder auditRecorder =
                mock(com.apimarketplace.auth.credential.service.CredentialAuditRecorder.class);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "auditRecorder", auditRecorder);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.findActiveCredential("u1", "googlesheets", null))
                .thenReturn(Optional.of(googleCredential("googlesheets", null)));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Sheets", "googlesheets"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(auditRecorder);
    }

    @Test
    @DisplayName("picker-token returns the App ID of the credential's own OAuth client, which drive.file needs for the pick to grant anything")
    void pickerToken_returnsAppId_derivedFromTheCredentialsClient() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org1");
        when(internalCredentialService.findActiveCredential("u1", "googledocs", "org1")).thenReturn(Optional.of(googleCredential("googledocs")));
        when(internalCredentialService.getOrRefreshOAuth2AccessTokenById("u1", 10L, "org1"))
                .thenReturn(Optional.of("ya29.fresh"));
        when(internalCredentialService.getCredentialDataMapById("u1", 10L, "org1"))
                .thenReturn(Map.of("oauth_client_id", "785967600625-abc.apps.googleusercontent.com"));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Docs", "googledocs"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // Resolved from the SAME credential the token was minted from, so BYOK/CE clients each
        // yield their own project number instead of a baked-in platform value.
        assertThat(((PickerTokenResponse) response.getBody()).appId()).isEqualTo("785967600625");
    }

    @Test
    @DisplayName("picker-token falls back to client_id when the credential has no oauth_client_id copy")
    void pickerToken_appId_fallsBackToClientId() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.findActiveCredential("u1", "googleslides", null)).thenReturn(Optional.of(googleCredential("googleslides")));
        when(internalCredentialService.getOrRefreshOAuth2AccessTokenById("u1", 10L, null))
                .thenReturn(Optional.of("ya29.fresh"));
        when(internalCredentialService.getCredentialDataMapById("u1", 10L, null))
                .thenReturn(Map.of("client_id", "111222333444-xyz.apps.googleusercontent.com"));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Slides", "googleslides"));

        assertThat(((PickerTokenResponse) response.getBody()).appId()).isEqualTo("111222333444");
    }

    @Test
    @DisplayName("picker-token still returns the token when the App ID cannot be resolved, rather than failing the mint")
    void pickerToken_appIdNull_stillReturnsToken() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.findActiveCredential("u1", "googlesheets", null)).thenReturn(Optional.of(googleCredential("googlesheets")));
        when(internalCredentialService.getOrRefreshOAuth2AccessTokenById("u1", 10L, null))
                .thenReturn(Optional.of("ya29.fresh"));
        when(internalCredentialService.getCredentialDataMapById("u1", 10L, null))
                .thenThrow(new IllegalStateException("credential vault unavailable"));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Sheets", "googlesheets"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((PickerTokenResponse) response.getBody()).accessToken()).isEqualTo("ya29.fresh");
        assertThat(((PickerTokenResponse) response.getBody()).appId()).isNull();
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
        verify(internalCredentialService, never()).getOrRefreshOAuth2AccessTokenById(any(), any(), any());
    }

    @Test
    @DisplayName("picker-token returns 404 when the user has no refreshable Google credential")
    void pickerToken_404_whenNoCredential() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.findActiveCredential("u1", "googledocs", null)).thenReturn(Optional.of(googleCredential("googledocs")));
        when(internalCredentialService.getOrRefreshOAuth2AccessTokenById("u1", 10L, null))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Docs", "googledocs"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(internalCredentialService).getOrRefreshOAuth2AccessTokenById("u1", 10L, null);
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
        verify(internalCredentialService, never()).getOrRefreshOAuth2AccessTokenById(any(), any(), any());
    }

    @Test
    @DisplayName("picker-token normalizes the integration (trim + case-insensitive) before the allowlist check")
    void pickerToken_normalizesIntegration() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.findActiveCredential("u1", "googledrive", null)).thenReturn(Optional.of(googleCredential("googledrive")));
        when(internalCredentialService.getOrRefreshOAuth2AccessTokenById("u1", 10L, null))
                .thenReturn(Optional.of("ya29.drive"));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("  GOOGLE DRIVE  ", "googledrive"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((PickerTokenResponse) response.getBody()).accessToken()).isEqualTo("ya29.drive");
    }

    @Test
    @DisplayName("refresh scrubs secrets from the response: no oauth_client_secret / refresh_token / access_token leaks, diagnostic keys kept")
    void refreshToken_stripsSecretsFromResponseBody() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");

        // The refreshed credential carries plaintext-decrypted secrets (this is what
        // OAuth2Service.refreshToken returns after the RowMapper decrypts on load).
        Map<String, Object> secretData = new java.util.HashMap<>();
        secretData.put("oauth_client_secret", "supersecret-app-secret");
        secretData.put("refresh_token", "1//non-rotating-refresh");
        secretData.put("access_token", "ya29.enc");
        secretData.put("expires_at", "2026-01-01T00:00:00Z"); // diagnostic (allowlisted)
        com.apimarketplace.auth.credential.domain.CredentialModels.Credential withSecrets =
                new com.apimarketplace.auth.credential.domain.CredentialModels.Credential(
                        7L, "u1", null, "My Gmail", "gmail",
                        com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType.OAuth2,
                        com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment.Production,
                        com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus.active,
                        null, secretData, null, null, null, null, false, null, null, null);
        when(oAuth2Service.refreshToken(7L, "u1")).thenReturn(withSecrets);

        ResponseEntity<?> response = controller.refreshToken(req, 7L);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var body = (com.apimarketplace.auth.credential.domain.CredentialModels.Credential) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.credentialData())
                .doesNotContainKey("oauth_client_secret")
                .doesNotContainKey("refresh_token")
                .doesNotContainKey("access_token")
                // A narrow diagnostic allowlist is preserved so the UI can explain state.
                .containsEntry("expires_at", "2026-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("refresh rejects an invalid/expired/revoked token by surfacing the terminal exception, never masking it as a 200 success")
    void refreshToken_surfacesTerminalException_forInvalidOrExpiredToken() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        // invalid_grant (RFC 6749) is the canonical signal for a revoked/expired refresh token.
        RefreshTerminalException terminal = new RefreshTerminalException(
                RefreshErrorBucket.TERMINAL_USER, "invalid_grant", 400,
                "refresh token invalid, expired, or revoked");
        when(oAuth2Service.refreshToken(7L, "u1")).thenThrow(terminal);

        // The endpoint must reject the dead token by propagating the terminal failure to the HTTP
        // layer (so re-OAuth is triggered), not swallow it into a successful credential response.
        assertThatThrownBy(() -> controller.refreshToken(req, 7L))
                .isSameAs(terminal);

        verify(oAuth2Service).refreshToken(7L, "u1");
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

        controller.initiate(req, mock(jakarta.servlet.http.HttpServletResponse.class), "fr-FR", request);

        verify(oAuth2Service).initiate(eq(request), eq("u1"), eq("org1"), eq("fr"), any());
    }

    @Test
    @DisplayName("initiate: ?locale absent → null forwarded (provider keeps its own default)")
    void initiate_nullLocaleForwarded() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        OAuth2InitiateRequest request = initiateRequest();

        controller.initiate(req, mock(jakarta.servlet.http.HttpServletResponse.class), null, request);

        verify(oAuth2Service).initiate(eq(request), eq("u1"), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("initiate: ?locale=EN is lowercased to 'en'")
    void initiate_lowercasesLocale() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        OAuth2InitiateRequest request = initiateRequest();

        controller.initiate(req, mock(jakarta.servlet.http.HttpServletResponse.class), "EN", request);

        verify(oAuth2Service).initiate(eq(request), eq("u1"), isNull(), eq("en"), any());
    }

    @Test
    @DisplayName("initiate: junk ?locale ('*') is rejected → null (never reaches the authorize URL)")
    void initiate_rejectsJunkLocale() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        OAuth2InitiateRequest request = initiateRequest();

        controller.initiate(req, mock(jakarta.servlet.http.HttpServletResponse.class), "*", request);

        verify(oAuth2Service).initiate(eq(request), eq("u1"), isNull(), isNull(), any());
    }

    @Test
    @DisplayName("initiate-simple: ?locale=de is forwarded to the simple service path")
    void initiateSimple_forwardsLocale() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org1");
        OAuth2SimpleInitiateRequest request =
                new OAuth2SimpleInitiateRequest("tmpl-1", null, "Production", null);

        controller.initiateSimple(req, mock(jakarta.servlet.http.HttpServletResponse.class), "de", request);

        verify(oAuth2Service).initiateSimple(eq(request), eq("u1"), eq("org1"), eq("de"), any());
    }

    // ─────────────── CASA batch B1 ───────────────

    @Test
    @DisplayName("LC-005: initiate hands the browser a per-flow cookie whose hash is what the service stores")
    void initiate_setsBindingCookie_matchingStoredHash() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "frontendUrl", "https://livecontext.ai");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "callbackUrl",
                "https://livecontext.ai/api/credentials/oauth2/callback");
        when(oAuth2Service.initiate(any(), eq("u1"), isNull(), isNull(), anyString()))
                .thenReturn(new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateResponse("https://idp/auth", "st-9"));

        controller.initiate(req, res, null, initiateRequest());

        org.mockito.ArgumentCaptor<String> hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(oAuth2Service).initiate(any(), eq("u1"), isNull(), isNull(), hash.capture());
        org.mockito.ArgumentCaptor<String> header = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(res).addHeader(eq("Set-Cookie"), header.capture());
        String cookie = header.getValue();
        String name = cookie.substring(0, cookie.indexOf('='));
        String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        assertThat(name).isEqualTo(com.apimarketplace.auth.credential.service.OAuth2BrowserBinding.cookieName("st-9", true));
        assertThat(cookie).contains("Secure").contains("HttpOnly").contains("SameSite=Lax");
        assertThat(com.apimarketplace.auth.credential.service.OAuth2BrowserBinding.hash(value)).isEqualTo(hash.getValue());
    }

    @Test
    @DisplayName("LC-005: a client_credentials connect has no browser leg and gets no cookie")
    void initiate_clientCredentials_noCookie() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(oAuth2Service.initiate(any(), eq("u1"), isNull(), isNull(), anyString()))
                .thenReturn(new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateResponse("/app?success=true", "client_credentials"));

        controller.initiate(req, res, null, initiateRequest());

        verify(res, never()).addHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    @DisplayName("LC-005: the callback hands the flow's cookie to the service and expires it")
    void callback_passesAndClearsBindingCookie() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);
        String name = com.apimarketplace.auth.credential.service.OAuth2BrowserBinding.cookieName("st-7", false);
        when(req.getCookies()).thenReturn(new jakarta.servlet.http.Cookie[]{
                new jakarta.servlet.http.Cookie("unrelated", "x"), new jakarta.servlet.http.Cookie(name, "the-value")});
        when(oAuth2Service.handleCallback("code", "st-7", "the-value")).thenReturn("https://app/ok");

        controller.callback(req, res, "code", null, "st-7", null, null, null);

        verify(oAuth2Service).handleCallback("code", "st-7", "the-value");
        verify(res).addHeader(eq("Set-Cookie"), org.mockito.ArgumentMatchers.argThat(h -> h.startsWith(name + "=") && h.contains("Max-Age=0")));
        verify(res).sendRedirect("https://app/ok");
    }

    @Test
    @DisplayName("LC-023: a provider error value is URL-encoded into the redirect, never injected raw")
    void callback_encodesProviderError() throws Exception {
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);

        controller.callback(mock(HttpServletRequest.class), res, null, null, "st", "denied&next=https://evil", null, null);

        verify(res).sendRedirect(org.mockito.ArgumentMatchers.argThat(u ->
                u.contains("error=denied%26next%3Dhttps%3A%2F%2Fevil") && !u.contains("&next=")));
    }

    @Test
    @DisplayName("LC-089: the callback log line carries a one-way reference, never the raw state")
    void callback_neverLogsRawState() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(OAuth2Controller.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String state = "9b2f6d0c-raw-state-value";
            when(oAuth2Service.handleCallback(anyString(), eq(state), any())).thenReturn("https://app/ok");

            controller.callback(mock(HttpServletRequest.class), mock(jakarta.servlet.http.HttpServletResponse.class),
                    "code", null, state, null, null, null);

            assertThat(appender.list).isNotEmpty()
                    .allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain(state));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("LC-028: a Drive request that resolves to a GMAIL credential is refused and nothing is minted")
    void pickerToken_403_whenResolvedCredentialIsNotAPickerIntegration() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn("org1");
        when(internalCredentialService.findActiveCredential("u1", "gmail", "org1"))
                .thenReturn(Optional.of(googleCredential("gmail")));

        ResponseEntity<?> response = controller.pickerToken(req, new PickerTokenRequest("google drive", "gmail"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(internalCredentialService, never()).getOrRefreshOAuth2AccessTokenById(any(), any(), any());
        verify(internalCredentialService, never()).refreshAccessToken(any(), any(), any());
    }

    @Test
    @DisplayName("LC-028: the token is minted from the SAME row that passed the check (by id), separator-tolerant")
    void pickerToken_mintsFromTheCheckedRow() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.findActiveCredential("u1", "My Drive", null))
                .thenReturn(Optional.of(googleCredential("google_drive")));
        when(internalCredentialService.getOrRefreshOAuth2AccessTokenById("u1", 10L, null)).thenReturn(Optional.of("ya29.d"));

        ResponseEntity<?> response = controller.pickerToken(req, new PickerTokenRequest("Google Drive", "My Drive"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((PickerTokenResponse) response.getBody()).accessToken()).isEqualTo("ya29.d");
    }

    // ─────────────── Audit follow-up: split host, cookie parity, picker scopes ───────────────

    private void splitHostConfig() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "frontendUrl", "https://app.example.com");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "callbackUrl",
                "https://api.example.com/api/credentials/oauth2/callback");
    }

    private void sameHostConfig() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "frontendUrl", "https://livecontext.ai");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "callbackUrl",
                "https://livecontext.ai/api/credentials/oauth2/callback");
    }

    @Test
    @DisplayName("split host: a callback without the cookie is bounced ONCE to the app host's API proxy, state untouched")
    void splitHost_hopsThroughAppProxy_withoutConsumingState() throws Exception {
        splitHostConfig();
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);

        controller.callback(mock(HttpServletRequest.class), res, "c/1", null, "st 2", null, null, null);

        verify(res).sendRedirect("https://app.example.com/api/proxy/credentials/oauth2/callback"
                + "?code=c%2F1&state=st+2&hop=1");
        verify(oAuth2Service, never()).handleCallback(any(), any(), any());
        verify(res, never()).addHeader(eq("Set-Cookie"), anyString());
    }

    @Test
    @DisplayName("split host, after the hop: the app-host cookie arrives through the proxy and the flow completes")
    void splitHost_afterHop_cookiePresent_completes() throws Exception {
        splitHostConfig();
        HttpServletRequest req = mock(HttpServletRequest.class);
        String name = com.apimarketplace.auth.credential.service.OAuth2BrowserBinding.cookieName("st-5", true);
        when(req.getCookies()).thenReturn(new jakarta.servlet.http.Cookie[]{new jakarta.servlet.http.Cookie(name, "v")});
        when(oAuth2Service.handleCallback("code", "st-5", "v")).thenReturn("https://app.example.com/ok");
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);

        controller.callback(req, res, "code", null, "st-5", null, null, "1");

        verify(res).sendRedirect("https://app.example.com/ok");
    }

    @Test
    @DisplayName("split host, hop=1 and still no cookie: no second bounce (loop guard), the service refuses")
    void splitHost_hopGuard_noLoop_refusedByService() throws Exception {
        splitHostConfig();
        when(oAuth2Service.handleCallback("code", "st-6", null)).thenReturn("https://app.example.com/x?error=invalid_state");
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);

        controller.callback(mock(HttpServletRequest.class), res, "code", null, "st-6", null, null, "1");

        verify(oAuth2Service).handleCallback("code", "st-6", null);
        verify(res).sendRedirect("https://app.example.com/x?error=invalid_state");
        verify(res, never()).sendRedirect(org.mockito.ArgumentMatchers.contains("hop=1"));
    }

    @Test
    @DisplayName("same host: a missing cookie never bounces; the service refuses it directly")
    void sameHost_missingCookie_noHop() throws Exception {
        sameHostConfig();
        when(oAuth2Service.handleCallback("code", "st-7", null)).thenReturn("https://livecontext.ai/x?error=invalid_state");
        jakarta.servlet.http.HttpServletResponse res = mock(jakarta.servlet.http.HttpServletResponse.class);

        controller.callback(mock(HttpServletRequest.class), res, "code", null, "st-7", null, null, null);

        verify(oAuth2Service).handleCallback("code", "st-7", null);
        verify(res, never()).sendRedirect(org.mockito.ArgumentMatchers.contains("/api/proxy/"));
    }

    @Test
    @DisplayName("CE default (localhost:3000 app, localhost:8080 API) is ONE host for cookies: no hop")
    void ceDefaultPortsAreSameHost() {
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "frontendUrl", "http://localhost:3000");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "callbackUrl",
                "http://localhost:8080/api/credentials/oauth2/callback");

        assertThat(controller.isSplitHost()).isFalse();
    }

    @Test
    @DisplayName("cookie name parity: the cookie initiate sets is exactly the one callback reads, value intact")
    void cookieNameParity_initiateToCallback() throws Exception {
        sameHostConfig();
        HttpServletRequest initReq = mock(HttpServletRequest.class);
        jakarta.servlet.http.HttpServletResponse initRes = mock(jakarta.servlet.http.HttpServletResponse.class);
        when(tenantResolver.resolve(initReq)).thenReturn("u1");
        when(oAuth2Service.initiate(any(), eq("u1"), isNull(), isNull(), anyString()))
                .thenReturn(new com.apimarketplace.auth.credential.domain.OAuth2Models.OAuth2InitiateResponse("https://idp", "st-parity"));
        controller.initiate(initReq, initRes, null, initiateRequest());
        org.mockito.ArgumentCaptor<String> setCookie = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(initRes).addHeader(eq("Set-Cookie"), setCookie.capture());
        String header = setCookie.getValue();
        String name = header.substring(0, header.indexOf('='));
        String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));

        HttpServletRequest cbReq = mock(HttpServletRequest.class);
        when(cbReq.getCookies()).thenReturn(new jakarta.servlet.http.Cookie[]{new jakarta.servlet.http.Cookie(name, value)});
        when(oAuth2Service.handleCallback("code", "st-parity", value)).thenReturn("https://livecontext.ai/ok");
        controller.callback(cbReq, mock(jakarta.servlet.http.HttpServletResponse.class), "code", null, "st-parity",
                null, null, null);

        verify(oAuth2Service).handleCallback("code", "st-parity", value);
    }

    @Test
    @DisplayName("picker: a Drive credential that ALSO holds a Gmail scope is refused, nothing minted")
    void pickerToken_refusesCredentialWithScopesBeyondThePicker() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        var drive = new com.apimarketplace.auth.credential.domain.CredentialModels.Credential(
                10L, "u1", null, "Drive", "google_drive",
                com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType.OAuth2,
                com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment.Production,
                com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus.active,
                null, Map.of(), java.util.List.of("https://www.googleapis.com/auth/drive.file",
                        "https://www.googleapis.com/auth/gmail.readonly"),
                null, null, null, false, null, null, null);
        when(internalCredentialService.findActiveCredential("u1", "Drive", null)).thenReturn(Optional.of(drive));

        ResponseEntity<?> response = controller.pickerToken(req, new PickerTokenRequest("google drive", "Drive"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "picker_scope_too_broad"));
        verify(internalCredentialService, never()).getOrRefreshOAuth2AccessTokenById(any(), any(), any());
    }

    @Test
    @DisplayName("picker scope families: drive/sheets/docs/slides and identity pass, anything else fails")
    void pickerScopeFamilies() {
        assertThat(OAuth2Controller.isPickerSafeScopeSet(java.util.List.of(
                "https://www.googleapis.com/auth/drive.file", "https://www.googleapis.com/auth/drive",
                "https://www.googleapis.com/auth/spreadsheets.readonly", "openid", "email"))).isTrue();
        assertThat(OAuth2Controller.isPickerSafeScopeSet(java.util.List.of("https://mail.google.com/"))).isFalse();
        assertThat(OAuth2Controller.isPickerSafeScopeSet(java.util.List.of(
                "https://www.googleapis.com/auth/drivex"))).isFalse();
    }

    @Test
    @DisplayName("LC-028/LC-072: a null or empty scope list is refused, never treated as safe")
    void pickerScopeFamilies_unknownGrantIsRefused() {
        // A credential whose scopes were never recorded may hold anything: unknown must not
        // default to "safe", or a Gmail-scoped credential with a null `scopes` column would mint
        // a browser-exposed token carrying full mail access.
        assertThat(OAuth2Controller.isPickerSafeScopeSet(null)).isFalse();
        assertThat(OAuth2Controller.isPickerSafeScopeSet(java.util.List.of())).isFalse();
    }

    @Test
    @DisplayName("LC-028/LC-072: picker-token refuses a credential whose scopes were never recorded (null), nothing minted")
    void pickerToken_refusesUnknownScopeGrant() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(tenantResolver.resolve(req)).thenReturn("u1");
        when(tenantResolver.resolveOrgId(req)).thenReturn(null);
        when(internalCredentialService.findActiveCredential("u1", "googlesheets", null))
                .thenReturn(Optional.of(googleCredential("googlesheets", null)));

        ResponseEntity<?> response = controller.pickerToken(req,
                new PickerTokenRequest("Google Sheets", "googlesheets"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "picker_scope_too_broad"));
        verify(internalCredentialService, never()).getOrRefreshOAuth2AccessTokenById(any(), any(), any());
    }
}
