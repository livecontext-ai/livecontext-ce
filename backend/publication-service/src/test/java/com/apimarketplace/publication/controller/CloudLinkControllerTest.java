package com.apimarketplace.publication.controller;

import com.apimarketplace.agent.cloud.CloudLlmSource;
import com.apimarketplace.publication.service.CloudLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CloudLinkController")
class CloudLinkControllerTest {

    @Mock
    private CloudLinkService cloudLinkService;

    private CloudLinkController controller;

    private static final Long TENANT_ID = 42L;

    @BeforeEach
    void setUp() {
        controller = new CloudLinkController(cloudLinkService, "http://localhost:14000");
    }

    @Nested
    @DisplayName("GET /status")
    class GetStatus {

        @Test
        @DisplayName("Should return linked status when account is linked")
        void shouldReturnLinkedStatus() {
            Map<String, Object> status = Map.of(
                    "linked", true,
                    "cloudUsername", "testuser",
                    "linkedAt", "2026-01-15T10:30:00Z"
            );
            when(cloudLinkService.getLinkStatus(TENANT_ID)).thenReturn(status);

            ResponseEntity<Map<String, Object>> response = controller.getStatus(TENANT_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("linked", true);
            assertThat(response.getBody()).containsEntry("cloudUsername", "testuser");
        }

        @Test
        @DisplayName("Should return unlinked status when no account linked")
        void shouldReturnUnlinkedStatus() {
            when(cloudLinkService.getLinkStatus(TENANT_ID)).thenReturn(Map.of("linked", false));

            ResponseEntity<Map<String, Object>> response = controller.getStatus(TENANT_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("linked", false);
        }
    }

    @Nested
    @DisplayName("LLM source")
    class LlmSource {

        @Test
        @DisplayName("GET /llm-source returns the current source")
        void shouldReturnLlmSource() {
            when(cloudLinkService.getLlmSource(TENANT_ID)).thenReturn(CloudLlmSource.CLOUD);

            ResponseEntity<Map<String, Object>> response = controller.getLlmSource(TENANT_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("source", "CLOUD");
        }

        @Test
        @DisplayName("PUT /llm-source persists the selected source")
        void shouldPersistLlmSource() {
            when(cloudLinkService.setLlmSource(TENANT_ID, CloudLlmSource.BYOK)).thenReturn(CloudLlmSource.BYOK);

            ResponseEntity<Map<String, Object>> response =
                    controller.setLlmSource(TENANT_ID, Map.of("source", "BYOK"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("source", "BYOK");
        }

        @Test
        @DisplayName("PUT /llm-source returns conflict when Cloud link is missing")
        void shouldReturnConflictWhenCloudLinkMissing() {
            when(cloudLinkService.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .thenThrow(new CloudLinkService.CloudAccountNotLinkedException("No cloud account linked"));

            ResponseEntity<Map<String, Object>> response =
                    controller.setLlmSource(TENANT_ID, Map.of("source", "CLOUD"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("error", "CLOUD_LINK_REQUIRED");
        }

        @Test
        @DisplayName("PUT /llm-source returns 409 CLOUD_LINK_NOT_READY when the link exists but cloud registration is unconfirmed")
        void shouldReturnConflictNotReadyWhenRegistrationFails() {
            // Distinct from CLOUD_LINK_REQUIRED (no link): here a link exists but registerWithCloud
            // could not confirm, so the service throws IllegalStateException → 409 CLOUD_LINK_NOT_READY.
            when(cloudLinkService.setLlmSource(TENANT_ID, CloudLlmSource.CLOUD))
                    .thenThrow(new IllegalStateException("Cloud link is not registered"));

            ResponseEntity<Map<String, Object>> response =
                    controller.setLlmSource(TENANT_ID, Map.of("source", "CLOUD"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("error", "CLOUD_LINK_NOT_READY");
        }

        @Test
        @DisplayName("PUT /llm-source rejects invalid source instead of silently switching to BYOK")
        void shouldRejectInvalidLlmSource() {
            ResponseEntity<Map<String, Object>> response =
                    controller.setLlmSource(TENANT_ID, Map.of("source", "anything"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "INVALID_LLM_SOURCE");
            verifyNoInteractions(cloudLinkService);
        }
    }

    @Nested
    @DisplayName("GET /auth-url")
    class GetAuthUrl {

        @Test
        @DisplayName("Should return auth URL and state")
        void shouldReturnAuthUrlAndState() {
            Map<String, String> authData = Map.of(
                    "authUrl", "https://keycloak.example.com/auth?client_id=test",
                    "state", "random-state-uuid"
            );
            when(cloudLinkService.generateAuthUrl(TENANT_ID, null)).thenReturn(authData);

            ResponseEntity<Map<String, String>> response = controller.getAuthUrl(TENANT_ID, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("authUrl");
            assertThat(response.getBody()).containsKey("state");
            assertThat(response.getBody().get("authUrl")).contains("keycloak.example.com");
        }

        @Test
        @DisplayName("Should pass onboarding return path to auth URL generation")
        void shouldPassOnboardingReturnPath() {
            Map<String, String> authData = Map.of(
                    "authUrl", "https://keycloak.example.com/auth?client_id=test",
                    "state", "random-state-uuid"
            );
            when(cloudLinkService.generateAuthUrl(TENANT_ID, "/en/ce-setup")).thenReturn(authData);

            ResponseEntity<Map<String, String>> response = controller.getAuthUrl(TENANT_ID, "/en/ce-setup");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(cloudLinkService).generateAuthUrl(TENANT_ID, "/en/ce-setup");
        }
    }

    @Nested
    @DisplayName("GET /callback")
    class Callback {

        @Test
        @DisplayName("Should store callback code server-side and redirect without reflecting the code")
        void shouldStoreCallbackCodeAndRedirectWithoutCode() {
            when(cloudLinkService.receiveCallback("auth-code-123", "state-uuid"))
                    .thenReturn("/app/settings/cloud-account");

            ResponseEntity<Void> response = controller.callback("auth-code-123", "state-uuid");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
            assertThat(response.getHeaders().getLocation().toString())
                    .isEqualTo("http://localhost:14000/app/settings/cloud-account?cloud_link_callback=1&state=state-uuid");
            assertThat(response.getHeaders().getLocation().toString()).doesNotContain("auth-code-123");
            assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
            assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
            verify(cloudLinkService).receiveCallback("auth-code-123", "state-uuid");
        }

        @Test
        @DisplayName("Should redirect OAuth callback back to CE setup when onboarding started the flow")
        void shouldRedirectCallbackBackToCeSetupWhenOnboardingStartedFlow() {
            when(cloudLinkService.receiveCallback("auth-code-123", "state-uuid"))
                    .thenReturn("/en/ce-setup");

            ResponseEntity<Void> response = controller.callback("auth-code-123", "state-uuid");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SEE_OTHER);
            assertThat(response.getHeaders().getLocation().toString())
                    .isEqualTo("http://localhost:14000/en/ce-setup?cloud_link_callback=1&state=state-uuid");
            assertThat(response.getHeaders().getLocation().toString()).doesNotContain("auth-code-123");
        }
    }

    @Nested
    @DisplayName("POST /connect")
    class Connect {

        @Test
        @DisplayName("Should ignore browser authCode and link account from server-side callback state")
        void shouldIgnoreBrowserAuthCodeAndLinkAccountFromState() {
            Map<String, String> body = Map.of("authCode", "auth-code-123", "state", "state-uuid");
            Map<String, Object> status = Map.of("linked", true, "cloudUsername", "testuser");
            when(cloudLinkService.getLinkStatus(TENANT_ID)).thenReturn(status);

            ResponseEntity<Map<String, Object>> response = controller.connect(TENANT_ID, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("linked", true);
            verify(cloudLinkService).linkAccount(eq(TENANT_ID), eq("state-uuid"));
        }

        @Test
        @DisplayName("Should accept missing authCode after backend callback stored it")
        void shouldAcceptMissingAuthCodeAfterBackendCallback() {
            Map<String, String> body = new HashMap<>();
            body.put("authCode", null);
            body.put("state", "state-uuid");
            Map<String, Object> status = Map.of("linked", true, "cloudUsername", "testuser");
            when(cloudLinkService.getLinkStatus(TENANT_ID)).thenReturn(status);

            ResponseEntity<Map<String, Object>> response = controller.connect(TENANT_ID, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(cloudLinkService).linkAccount(eq(TENANT_ID), eq("state-uuid"));
        }

        @Test
        @DisplayName("Should return 400 when state is missing")
        void shouldReturn400WhenStateMissing() {
            Map<String, String> body = new HashMap<>();
            body.put("authCode", "code");
            body.put("state", "");

            ResponseEntity<Map<String, Object>> response = controller.connect(TENANT_ID, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(cloudLinkService, never()).linkAccount(any(), any());
        }

        @Test
        @DisplayName("Should return 400 when state is invalid")
        void shouldReturn400WhenStateInvalid() {
            Map<String, String> body = Map.of("authCode", "code", "state", "bad-state");
            doThrow(new IllegalArgumentException("Invalid or expired state"))
                    .when(cloudLinkService).linkAccount(eq(TENANT_ID), eq("bad-state"));

            ResponseEntity<Map<String, Object>> response = controller.connect(TENANT_ID, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "Invalid or expired state");
        }

        @Test
        @DisplayName("Should return 500 when token exchange fails")
        void shouldReturn500WhenTokenExchangeFails() {
            Map<String, String> body = Map.of("authCode", "code", "state", "state");
            doThrow(new RuntimeException("Keycloak unreachable"))
                    .when(cloudLinkService).linkAccount(eq(TENANT_ID), eq("state"));

            ResponseEntity<Map<String, Object>> response = controller.connect(TENANT_ID, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody().get("error").toString()).contains("Keycloak unreachable");
        }

        @Test
        @DisplayName("Should return 502 cloud_tls_untrusted when the token exchange hits an untrusted intercepting CA")
        void shouldReturn502WhenTokenExchangeHitsUntrustedInterceptingCa() {
            // Regression: a TLS-intercepting AV/proxy with an untrusted root CA surfaces as a PKIX
            // failure on the token exchange. Pre-fix this returned a generic 500 (the UI mislabels
            // that "Invalid or expired state"). It must now be a DISTINCT 502 cloud_tls_untrusted so
            // the setup wizard can offer the one-click "trust this proxy CA" flow.
            Map<String, String> body = Map.of("state", "state");
            doThrow(new RuntimeException("Failed to exchange authorization code: I/O error on POST request "
                    + "for \"https://auth.livecontext.ai/...\": PKIX path building failed: "
                    + "unable to find valid certification path to requested target"))
                    .when(cloudLinkService).linkAccount(eq(TENANT_ID), eq("state"));

            ResponseEntity<Map<String, Object>> response = controller.connect(TENANT_ID, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).containsEntry("error", "cloud_tls_untrusted");
        }
    }

    @Nested
    @DisplayName("isTlsTrustFailure detection")
    class TlsTrustFailureDetection {

        @Test
        @DisplayName("Detects a PKIX 'path building failed' message anywhere in the cause chain")
        void detectsPkixMessage() {
            Throwable t = new RuntimeException("wrap",
                    new RuntimeException("PKIX path building failed: unable to find valid certification path"));
            assertThat(CloudLinkController.isTlsTrustFailure(t)).isTrue();
        }

        @Test
        @DisplayName("Detects an SSLException in the cause chain")
        void detectsSslException() {
            Throwable t = new RuntimeException("outer", new javax.net.ssl.SSLHandshakeException("handshake"));
            assertThat(CloudLinkController.isTlsTrustFailure(t)).isTrue();
        }

        @Test
        @DisplayName("Detects a CertPathBuilderException nested deep in the chain")
        void detectsCertPathBuilderException() {
            Throwable t = new RuntimeException("a",
                    new RuntimeException("b", new java.security.cert.CertPathBuilderException("no path")));
            assertThat(CloudLinkController.isTlsTrustFailure(t)).isTrue();
        }

        @Test
        @DisplayName("Does NOT flag an unrelated failure (e.g. host unreachable) as a TLS trust issue")
        void ignoresUnrelatedFailure() {
            assertThat(CloudLinkController.isTlsTrustFailure(
                    new RuntimeException("Connection refused"))).isFalse();
        }

        @Test
        @DisplayName("Returns false for null")
        void nullIsFalse() {
            assertThat(CloudLinkController.isTlsTrustFailure(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("DELETE /disconnect")
    class Disconnect {

        @Test
        @DisplayName("Should unlink account and return success")
        void shouldUnlinkAndReturnSuccess() {
            ResponseEntity<Map<String, Object>> response = controller.disconnect(TENANT_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("success", true);
            verify(cloudLinkService).unlinkAccount(TENANT_ID);
        }
    }
}
