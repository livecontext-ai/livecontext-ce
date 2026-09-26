package com.apimarketplace.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KeycloakOtpCredentialClient")
class KeycloakOtpCredentialClientTest {

    private static final String KC = "https://kc.test";
    private static final String USER_URL = KC + "/admin/realms/livecontext/users/kc-123";
    private static final String TOKEN_URL = KC + "/realms/livecontext/protocol/openid-connect/token";

    @Mock private RestTemplate restTemplate;

    private KeycloakOtpCredentialClient client;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        client = new KeycloakOtpCredentialClient(restTemplate);
        ReflectionTestUtils.setField(client, "keycloakServerUrl", KC);
        ReflectionTestUtils.setField(client, "keycloakRealm", "livecontext");
        ReflectionTestUtils.setField(client, "adminClientId", "livecontext-admin-api");
        ReflectionTestUtils.setField(client, "adminClientSecret", "sa-secret");
        when(restTemplate.exchange(eq(TOKEN_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("access_token", "sa-token"), HttpStatus.OK));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void credentialsAre(List<Map<String, Object>> body) {
        when(restTemplate.exchange(eq(USER_URL + "/credentials"), eq(HttpMethod.GET), any(HttpEntity.class), eq(List.class)))
                .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    @Test
    @DisplayName("reads the authenticator apps with their label and creation date, ignoring the password")
    void readsAuthenticatorApps() {
        credentialsAre(List.of(
                Map.of("id", "pw", "type", "password", "createdDate", 1L),
                Map.of("id", "c1", "type", "otp", "userLabel", "Phone", "createdDate", 1_790_000_000_000L),
                Map.of("id", "c2", "type", "otp", "userLabel", "  ")));

        KeycloakOtpCredentialClient.SecondFactors factors = client.listSecondFactors("kc-123");

        assertThat(factors.otp()).containsExactly(
                new KeycloakOtpCredentialClient.OtpCredential("c1", "Phone", Instant.ofEpochMilli(1_790_000_000_000L)),
                new KeycloakOtpCredentialClient.OtpCredential("c2", null, null));
        assertThat(factors.recoveryCodes()).isNull();
    }

    @Test
    @DisplayName("reads the recovery codes left from Keycloak's credentialData JSON string")
    void readsRecoveryCodes() {
        credentialsAre(List.of(Map.of("id", "rc", "type", "recovery-authn-codes",
                "credentialData", "{\"algorithm\":\"SHA-512\",\"totalCodes\":12,\"remainingCodes\":11}")));

        assertThat(client.listSecondFactors("kc-123").recoveryCodes())
                .isEqualTo(new KeycloakOtpCredentialClient.RecoveryCodes("rc", 11, 12));
    }

    @Test
    @DisplayName("reports unknown counts, never zero, when the recovery codes' data is unreadable")
    void unreadableRecoveryData() {
        credentialsAre(List.of(Map.of("id", "rc", "type", "recovery-authn-codes", "credentialData", "not json")));

        assertThat(client.listSecondFactors("kc-123").recoveryCodes())
                .isEqualTo(new KeycloakOtpCredentialClient.RecoveryCodes("rc", null, null));
    }

    @Test
    @DisplayName("deletes one credential through the admin credentials endpoint")
    void deletesACredential() {
        when(restTemplate.exchange(eq(USER_URL + "/credentials/rc"), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        client.deleteCredential("kc-123", "rc");

        verify(restTemplate).exchange(eq(USER_URL + "/credentials/rc"), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("tolerates a credential without label and with an unreadable creation date")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void toleratesPartialCredential() {
        Map<String, Object> partial = new HashMap<>();
        partial.put("id", "c3");
        partial.put("type", "otp");
        partial.put("userLabel", null);
        partial.put("createdDate", "yesterday");
        when(restTemplate.exchange(eq(USER_URL + "/credentials"), eq(HttpMethod.GET), any(HttpEntity.class), eq(List.class)))
                .thenReturn(new ResponseEntity<>(List.of(partial), HttpStatus.OK));

        assertThat(client.listSecondFactors("kc-123").otp())
                .containsExactly(new KeycloakOtpCredentialClient.OtpCredential("c3", null, null));
    }

    @Test
    @DisplayName("throws when Keycloak fails, so a caller never reads 'no factor' out of an outage")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void throwsOnKeycloakFailure() {
        when(restTemplate.exchange(eq(USER_URL + "/credentials"), eq(HttpMethod.GET), any(HttpEntity.class), eq(List.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.listSecondFactors("kc-123")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("reads the pending required actions, empty when the user has none")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void readsRequiredActions() {
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", "kc-123"), HttpStatus.OK));

        assertThat(client.getRequiredActions("kc-123")).isEmpty();
    }

    @Test
    @DisplayName("throws when Keycloak answers the credentials read without a body")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void throwsOnEmptyCredentialsBody() {
        when(restTemplate.exchange(eq(USER_URL + "/credentials"), eq(HttpMethod.GET), any(HttpEntity.class), eq(List.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.OK));

        assertThatThrownBy(() -> client.listSecondFactors("kc-123")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("throws when Keycloak refuses a credential deletion")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void throwsWhenDeletionRefused() {
        when(restTemplate.exchange(eq(USER_URL + "/credentials/rc"), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> client.deleteCredential("kc-123", "rc")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("throws when Keycloak refuses to end the sessions")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void throwsWhenLogoutRefused() {
        when(restTemplate.exchange(eq(USER_URL + "/logout"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.logoutAllSessions("kc-123")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("throws when Keycloak refuses the required-action write")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void throwsWhenWriteRefused() {
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> client.setRequiredActions("kc-123", List.of("CONFIGURE_TOTP")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("ends every session of the user through the admin logout endpoint")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void logsOutAllSessions() {
        when(restTemplate.exchange(eq(USER_URL + "/logout"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        client.logoutAllSessions("kc-123");

        verify(restTemplate).exchange(eq(USER_URL + "/logout"), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("throws on a token response without access_token instead of calling Keycloak unauthenticated")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void throwsWithoutAccessToken() {
        when(restTemplate.exchange(eq(TOKEN_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("error", "invalid_client"), HttpStatus.OK));

        assertThatThrownBy(() -> client.getRequiredActions("kc-123")).isInstanceOf(IllegalStateException.class);
        verify(restTemplate, never()).exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("falls back to the master admin login only when no service-account secret is set (local dev)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void masterFallbackWithoutSecret() {
        ReflectionTestUtils.setField(client, "adminClientSecret", "");
        ReflectionTestUtils.setField(client, "keycloakAdminUsername", "admin");
        ReflectionTestUtils.setField(client, "keycloakAdminPassword", "admin");
        String masterTokenUrl = KC + "/realms/master/protocol/openid-connect/token";
        ArgumentCaptor<HttpEntity> tokenRequest = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(masterTokenUrl), eq(HttpMethod.POST), tokenRequest.capture(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("access_token", "master-token"), HttpStatus.OK));
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("requiredActions", List.of("CONFIGURE_TOTP")), HttpStatus.OK));

        assertThat(client.getRequiredActions("kc-123")).containsExactly("CONFIGURE_TOTP");
        MultiValueMap<String, String> form = (MultiValueMap<String, String>) tokenRequest.getValue().getBody();
        assertThat(form.getFirst("grant_type")).isEqualTo("password");
        assertThat(form.getFirst("client_id")).isEqualTo("admin-cli");
    }

    @Test
    @DisplayName("writes the full required-action list with the service-account token")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void writesRequiredActions() {
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.PUT), request.capture(), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        client.setRequiredActions("kc-123", List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP"));

        assertThat(request.getValue().getBody())
                .isEqualTo(Map.of("requiredActions", List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP")));
        assertThat(request.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer sa-token");
        verify(restTemplate).exchange(eq(TOKEN_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
    }
}
