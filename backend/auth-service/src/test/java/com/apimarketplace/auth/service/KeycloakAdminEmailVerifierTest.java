package com.apimarketplace.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for 2026-09-19: the master-realm admin password was reset and only its
 * GitHub copy was updated, so this class, the last user of that password in the app,
 * failed every token request. isEmailVerified then answered false for everyone and
 * every new account, Google included, was forced into the email-code step for four
 * days. It now authenticates with the livecontext-admin-api service account, whose
 * secret the Keycloak deploy lane keeps in sync with the cluster.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeycloakAdminEmailVerifier")
class KeycloakAdminEmailVerifierTest {

    private static final String KC = "https://kc.test";
    private static final String PROVIDER_ID = "443d5f81-a351-4986-ae6d-1e3b0519acdd";
    private static final String USER_URL = KC + "/admin/realms/livecontext/users/" + PROVIDER_ID;

    @Mock private RestTemplate restTemplate;

    private KeycloakAdminEmailVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new KeycloakAdminEmailVerifier(restTemplate);
        ReflectionTestUtils.setField(verifier, "keycloakServerUrl", KC);
        ReflectionTestUtils.setField(verifier, "keycloakRealm", "livecontext");
        ReflectionTestUtils.setField(verifier, "adminClientId", "livecontext-admin-api");
        ReflectionTestUtils.setField(verifier, "adminClientSecret", "sa-secret");
        ReflectionTestUtils.setField(verifier, "keycloakAdminUsername", "admin");
        ReflectionTestUtils.setField(verifier, "keycloakAdminPassword", "master-password");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<HttpEntity> stubToken(String tokenUrl) {
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(tokenUrl), eq(HttpMethod.POST), request.capture(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("access_token", "sa-token"), HttpStatus.OK));
        return request;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubUser(Object emailVerified) {
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("emailVerified", emailVerified), HttpStatus.OK));
    }

    @Test
    @DisplayName("gets its token from the service account on the application realm, never the master admin")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void usesTheServiceAccount() {
        ArgumentCaptor<HttpEntity> request =
                stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        stubUser(true);

        assertThat(verifier.isEmailVerified(PROVIDER_ID)).isTrue();

        MultiValueMap<String, String> form = (MultiValueMap<String, String>) request.getValue().getBody();
        assertThat(form.getFirst("grant_type")).isEqualTo("client_credentials");
        assertThat(form.getFirst("client_id")).isEqualTo("livecontext-admin-api");
        assertThat(form.getFirst("client_secret")).isEqualTo("sa-secret");
        // The master password must not travel at all once the secret exists: that
        // dependency is what broke when only one of its three copies was rotated.
        assertThat(form.containsKey("password")).isFalse();
        verify(restTemplate, never()).exchange(
                eq(KC + "/realms/master/protocol/openid-connect/token"),
                any(HttpMethod.class), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("sends the service-account token to the admin user endpoint")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void presentsTheServiceToken() {
        stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        ArgumentCaptor<HttpEntity> call = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), call.capture(), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("emailVerified", true), HttpStatus.OK));

        verifier.isEmailVerified(PROVIDER_ID);

        assertThat(call.getValue().getHeaders().getFirst("Authorization")).isEqualTo("Bearer sa-token");
    }

    @Test
    @DisplayName("reports an unverified user as unverified")
    void unverifiedStaysUnverified() {
        stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        stubUser(false);

        assertThat(verifier.isEmailVerified(PROVIDER_ID)).isFalse();
    }

    @Test
    @DisplayName("a refused token answers false rather than throwing into the status endpoint")
    void refusedTokenIsFalse() {
        when(restTemplate.exchange(eq(KC + "/realms/livecontext/protocol/openid-connect/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

        assertThat(verifier.isEmailVerified(PROVIDER_ID)).isFalse();
        verify(restTemplate, never()).exchange(eq(USER_URL), any(HttpMethod.class), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("a token response without access_token is a failure, not a null bearer")
    void missingAccessTokenIsFalse() {
        when(restTemplate.exchange(eq(KC + "/realms/livecontext/protocol/openid-connect/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("error", "x"), HttpStatus.OK));

        assertThat(verifier.isEmailVerified(PROVIDER_ID)).isFalse();
        verify(restTemplate, never()).exchange(eq(USER_URL), any(HttpMethod.class), any(HttpEntity.class), eq(Map.class));
    }

    @Test
    @DisplayName("marking verified uses the same service account")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void markVerifiedUsesTheServiceAccount() {
        ArgumentCaptor<HttpEntity> request =
                stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        verifier.markEmailVerified(PROVIDER_ID);

        MultiValueMap<String, String> form = (MultiValueMap<String, String>) request.getValue().getBody();
        assertThat(form.getFirst("grant_type")).isEqualTo("client_credentials");
        verify(restTemplate).exchange(eq(USER_URL), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("local dev without a service-account secret falls back to the master admin login")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void localDevFallsBackToMasterAdmin() {
        ReflectionTestUtils.setField(verifier, "adminClientSecret", "");
        ArgumentCaptor<HttpEntity> request =
                stubToken(KC + "/realms/master/protocol/openid-connect/token");
        stubUser(true);

        assertThat(verifier.isEmailVerified(PROVIDER_ID)).isTrue();

        MultiValueMap<String, String> form = (MultiValueMap<String, String>) request.getValue().getBody();
        assertThat(form.getFirst("grant_type")).isEqualTo("password");
        assertThat(form.getFirst("client_id")).isEqualTo("admin-cli");
        assertThat(form.getFirst("username")).isEqualTo("admin");
    }
}
