package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KcAdminLogoutService")
class KcAdminLogoutServiceTest {

    @Mock private RestTemplate restTemplate;
    @Mock private UserRepository userRepository;

    private KcAdminLogoutService service;

    private static final Long USER_ID = 42L;
    private static final UUID INSTALL = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String PROVIDER_ID = "abc-keycloak-uuid";

    @BeforeEach
    void setUp() {
        service = new KcAdminLogoutService(restTemplate, userRepository);
        ReflectionTestUtils.setField(service, "keycloakServerUrl", "https://kc.test");
        ReflectionTestUtils.setField(service, "keycloakRealm", "livecontext");
        ReflectionTestUtils.setField(service, "adminClientId", "livecontext-admin-api");
        ReflectionTestUtils.setField(service, "adminClientSecret", "test-secret");
    }

    private User userWithProviderId(String providerId) {
        User u = new User("luck", "luck@test.io", AuthProvider.KEYCLOAK, providerId);
        return u;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTokenFetchOk(String accessToken) {
        when(restTemplate.exchange(
                contains("/protocol/openid-connect/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("access_token", accessToken)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubLogoutResponse(ResponseEntity<Void> response) {
        when(restTemplate.exchange(
                contains("/users/" + PROVIDER_ID + "/logout"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(response);
    }

    @Test
    @DisplayName("fetches a service-account token via client_credentials and calls /users/{providerId}/logout")
    void happy_path_calls_kc_logout() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithProviderId(PROVIDER_ID)));
        stubTokenFetchOk("bearer-abc");
        stubLogoutResponse(ResponseEntity.noContent().build());

        boolean ok = service.forceLogout(USER_ID, INSTALL);

        assertThat(ok).isTrue();
        verify(restTemplate).exchange(
                contains("/realms/livecontext/protocol/openid-connect/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class));
        verify(restTemplate).exchange(
                eq("https://kc.test/admin/realms/livecontext/users/" + PROVIDER_ID + "/logout"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class));
    }

    @Test
    @DisplayName("returnsFalseWhenUserHasNoProviderId - embedded-CE users have no KC session to invalidate")
    void returns_false_when_no_provider_id() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithProviderId(null)));

        boolean ok = service.forceLogout(USER_ID, INSTALL);

        assertThat(ok).isFalse();
        verify(restTemplate, never()).exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("returns false when the user has been deleted between revoke and listener fire")
    void returns_false_when_user_missing() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        boolean ok = service.forceLogout(USER_ID, INSTALL);

        assertThat(ok).isFalse();
        verify(restTemplate, never()).exchange(any(String.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("tolerates a KC token-fetch failure - never propagates, returns false (audit row is the durable contract)")
    void tolerates_token_fetch_failure() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithProviderId(PROVIDER_ID)));
        when(restTemplate.exchange(
                contains("/protocol/openid-connect/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new RestClientException("KC down"));

        boolean ok = service.forceLogout(USER_ID, INSTALL);

        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("tolerates a KC logout 5xx - never propagates, returns false")
    void tolerates_kc_logout_5xx() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithProviderId(PROVIDER_ID)));
        stubTokenFetchOk("bearer-abc");
        when(restTemplate.exchange(
                contains("/users/" + PROVIDER_ID + "/logout"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenThrow(new RestClientException("KC user-endpoint down"));

        boolean ok = service.forceLogout(USER_ID, INSTALL);

        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("onRevokeListenerSwallowsRuntimeExceptions - last-line safety net for the AFTER_COMMIT thread")
    void on_revoke_swallows_exceptions() {
        when(userRepository.findById(USER_ID)).thenThrow(new RuntimeException("DB hiccup"));

        // Must not throw.
        service.onRevoke(new CeLinkRevokedEvent(USER_ID, INSTALL));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("tolerates a KC token endpoint returning non-2xx - never propagates, returns false")
    void tolerates_token_non_2xx() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithProviderId(PROVIDER_ID)));
        when(restTemplate.exchange(
                contains("/protocol/openid-connect/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.status(401).body(Map.of("error", "invalid_client")));

        boolean ok = service.forceLogout(USER_ID, INSTALL);

        assertThat(ok).isFalse();
        verify(restTemplate, never()).exchange(
                contains("/users/" + PROVIDER_ID + "/logout"),
                any(HttpMethod.class), any(HttpEntity.class), eq(Void.class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    @DisplayName("tolerates a KC token response with missing/blank access_token field - never propagates, returns false")
    void tolerates_missing_access_token() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithProviderId(PROVIDER_ID)));
        when(restTemplate.exchange(
                contains("/protocol/openid-connect/token"),
                eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(ResponseEntity.ok(Map.of("expires_in", 60)));   // no access_token key

        boolean ok = service.forceLogout(USER_ID, INSTALL);

        assertThat(ok).isFalse();
    }
}
