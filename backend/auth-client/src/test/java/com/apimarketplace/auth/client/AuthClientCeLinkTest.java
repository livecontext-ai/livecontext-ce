package com.apimarketplace.auth.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient CE link checks")
class AuthClientCeLinkTest {

    private static final String BASE_URL = "http://auth-service";
    private static final String INSTALL_ID = "11111111-2222-3333-4444-555555555555";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AuthClient client = new AuthClient(restTemplate, BASE_URL);

    @Test
    @DisplayName("userOwnsActiveCeLink returns true only when auth-service confirms active ownership")
    void userOwnsActiveCeLinkReturnsTrueForActiveOwnership() {
        when(restTemplate.exchange(
                eq(BASE_URL + "/api/internal/auth/ce-link/" + INSTALL_ID + "/active"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("active", true)));

        assertThat(client.userOwnsActiveCeLink("42", INSTALL_ID)).isTrue();
    }

    @Test
    @DisplayName("userOwnsActiveCeLink fail-closes on inactive response, malformed install id, or transport error")
    void userOwnsActiveCeLinkFailCloses() {
        when(restTemplate.exchange(
                eq(BASE_URL + "/api/internal/auth/ce-link/" + INSTALL_ID + "/active"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new RestClientException("auth down"));

        assertThat(client.userOwnsActiveCeLink("42", INSTALL_ID)).isFalse();
        assertThat(client.userOwnsActiveCeLink("42", "not-a-uuid")).isFalse();
        assertThat(client.userOwnsActiveCeLink(null, INSTALL_ID)).isFalse();
        verify(restTemplate, never()).exchange(
                eq(BASE_URL + "/api/internal/auth/ce-link/not-a-uuid/active"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class));
    }
}
