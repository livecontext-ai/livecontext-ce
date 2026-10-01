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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verifyNoInteractions;
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

    // ===== setUserLocale: the write that makes Keycloak render in the person's language =====

    @Test
    @DisplayName("writes back the WHOLE representation, so the declared root fields survive")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setLocaleRoundTripsTheRepresentation() {
        stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        Map<String, Object> existing = new java.util.LinkedHashMap<>();
        existing.put("id", PROVIDER_ID);
        existing.put("username", "alice");
        existing.put("email", "alice@example.com");
        existing.put("firstName", "Alice");
        existing.put("lastName", "Doe");
        existing.put("emailVerified", Boolean.TRUE);
        existing.put("requiredActions", java.util.List.of("VERIFY_EMAIL"));
        existing.put("attributes", Map.of("someOtherThing", java.util.List.of("keep-me")));
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(existing, HttpStatus.OK));
        ArgumentCaptor<HttpEntity> put = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.PUT), put.capture(), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        verifier.setUserLocale(PROVIDER_ID, "fr");

        Map<String, Object> body = (Map<String, Object>) put.getValue().getBody();
        Map<String, Object> attributes = (Map<String, Object>) body.get("attributes");
        assertThat(attributes.get("locale")).isEqualTo(java.util.List.of("fr"));
        assertThat(attributes.get("someOtherThing")).isEqualTo(java.util.List.of("keep-me"));

        // The part that is easy to get wrong and invisible in production until somebody loses
        // their name: sending an `attributes` map switches Keycloak into "remove what is not in
        // this body", and username / email / firstName / lastName are DECLARED attributes of the
        // realm's user profile. A body carrying only `attributes` would either be rejected -
        // making this method a silent no-op - or succeed and clear all four.
        assertThat(body).containsEntry("username", "alice")
                .containsEntry("email", "alice@example.com")
                .containsEntry("firstName", "Alice")
                .containsEntry("lastName", "Doe");

        // And the fields another writer owns are NOT sent back: markEmailVerified writes
        // emailVerified on the same user around signup, so echoing a stale value here would
        // undo it and bounce the person to the verification step again. Keycloak leaves a root
        // field alone when the representation omits it.
        assertThat(body).doesNotContainKey("emailVerified").doesNotContainKey("requiredActions");
    }

    @Test
    @DisplayName("a user Keycloak will not describe is never overwritten with a partial body")
    void setLocaleSkipsWhenTheUserCannotBeRead() {
        stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        verifier.setUserLocale(PROVIDER_ID, "fr");

        verify(restTemplate, never())
                .exchange(eq(USER_URL), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class));
    }

    @Test

    @DisplayName("sends the code KEYCLOAK spells, not the app one")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setLocaleUsesKeycloakSpelling() {
        stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of(), HttpStatus.OK));
        ArgumentCaptor<HttpEntity> put = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.PUT), put.capture(), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        verifier.setUserLocale(PROVIDER_ID, "pt");

        Map<String, Object> attributes =
                (Map<String, Object>) ((Map<String, Object>) put.getValue().getBody()).get("attributes");
        // An unknown code makes Keycloak fall back to the realm default SILENTLY, so "pt" here
        // would look like it worked and mail Brazilian readers in English forever.
        assertThat(attributes.get("locale")).isEqualTo(java.util.List.of("pt-BR"));
    }

    @Test
    @DisplayName("writes nothing when the stored locale already says the same thing")
    void setLocaleSkipsAnIdenticalValue() {
        stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(
                        Map.of("attributes", Map.of("locale", java.util.List.of("fr"))), HttpStatus.OK));

        verifier.setUserLocale(PROVIDER_ID, "fr");

        verify(restTemplate, never()).exchange(eq(USER_URL), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("an unsupported locale, or no provider id, never touches Keycloak at all")
    void setLocaleRefusesWhatItCannotMap() {
        verifier.setUserLocale(PROVIDER_ID, "it");
        verifier.setUserLocale("", "fr");
        verifier.setUserLocale(null, "fr");

        // Not even a token is fetched: an unmappable value must not become a write that stores a
        // code Keycloak ignores over one it was already using.
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("a Keycloak failure is swallowed: a lagging language must not fail the request")
    void setLocaleSwallowsFailure() {
        stubToken(KC + "/realms/livecontext/protocol/openid-connect/token");
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RuntimeException("KC down"));

        assertThatCode(() -> verifier.setUserLocale(PROVIDER_ID, "fr")).doesNotThrowAnyException();
    }

    // ===== identityExists / createPasswordUser (resurrection guard + sign-up canary) =====

    private static final String TOKEN_URL = KC + "/realms/livecontext/protocol/openid-connect/token";
    private static final String USERS_URL = KC + "/admin/realms/livecontext/users";

    @Test
    @DisplayName("identityExists: 200 is true, 404 is false (the identity is gone)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void identityExistsMapsFoundAndGone() {
        stubToken(TOKEN_URL);
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(Map.of("id", PROVIDER_ID), HttpStatus.OK))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        assertThat(verifier.identityExists(PROVIDER_ID)).contains(true);
        assertThat(verifier.identityExists(PROVIDER_ID)).contains(false);
    }

    @Test
    @DisplayName("identityExists: any other failure is UNKNOWN, never 'gone' (a Keycloak outage must not look like a deletion)")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void identityExistsUnknownOnOtherFailures() {
        stubToken(TOKEN_URL);
        when(restTemplate.exchange(eq(USER_URL), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "down", null, null, null));

        assertThat(verifier.identityExists(PROVIDER_ID)).isEmpty();
    }

    @Test
    @DisplayName("createPasswordUser: 201 is CREATED, with the e-mail as username, unverified, a non-temporary password")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void createPasswordUserCreates() {
        stubToken(TOKEN_URL);
        ArgumentCaptor<HttpEntity> call = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(USERS_URL), eq(HttpMethod.POST), call.capture(), eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.CREATED));

        assertThat(verifier.createPasswordUser("canary@example.test", "pw-pw-pw-pw-pw-pw"))
                .isEqualTo(KeycloakAdminEmailVerifier.CreateOutcome.CREATED);

        Map<String, Object> body = (Map<String, Object>) call.getValue().getBody();
        assertThat(body).containsEntry("username", "canary@example.test")
                .containsEntry("email", "canary@example.test")
                .containsEntry("enabled", true)
                .containsEntry("emailVerified", false);
        Map<String, Object> credential = ((java.util.List<Map<String, Object>>) body.get("credentials")).get(0);
        assertThat(credential).containsEntry("temporary", false).containsEntry("value", "pw-pw-pw-pw-pw-pw");
    }

    @Test
    @DisplayName("createPasswordUser: 409 is ALREADY_EXISTS, any other failure throws")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void createPasswordUserConflictAndFailure() {
        stubToken(TOKEN_URL);
        when(restTemplate.exchange(eq(USERS_URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        HttpStatus.CONFLICT, "Conflict", null, null, null))
                .thenThrow(org.springframework.web.client.HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null));

        assertThat(verifier.createPasswordUser("canary@example.test", "pw"))
                .isEqualTo(KeycloakAdminEmailVerifier.CreateOutcome.ALREADY_EXISTS);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> verifier.createPasswordUser("canary@example.test", "pw"))
                .isInstanceOf(IllegalStateException.class);
    }
}
