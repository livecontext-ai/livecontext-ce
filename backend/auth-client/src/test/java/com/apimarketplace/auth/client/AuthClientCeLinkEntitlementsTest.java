package com.apimarketplace.auth.client;

import com.apimarketplace.auth.client.dto.CeLinkEntitlementsResult;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient CE link entitlements")
class AuthClientCeLinkEntitlementsTest {

    private static final String BASE_URL = "http://auth-service";
    private static final String INSTALL_ID = "11111111-2222-3333-4444-555555555555";
    private static final String URL =
            BASE_URL + "/api/internal/auth/ce-link/" + INSTALL_ID + "/entitlements";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AuthClient client = new AuthClient(restTemplate, BASE_URL);

    @Test
    @DisplayName("ceLinkEntitlements returns the plan and subscription flag from auth-service")
    void returnsPlanAndSubscriptionFlag() {
        when(restTemplate.exchange(
                eq(URL), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("planCode", "PRO", "hasSubscription", true)));

        CeLinkEntitlementsResult result = client.ceLinkEntitlements("42", INSTALL_ID);

        assertThat(result.planCode()).isEqualTo("PRO");
        assertThat(result.hasSubscription()).isTrue();
    }

    @Test
    @DisplayName("ceLinkEntitlements fail-closes to __NONE__ on transport error")
    void failClosesOnTransportError() {
        when(restTemplate.exchange(
                eq(URL), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new RestClientException("auth down"));

        CeLinkEntitlementsResult result = client.ceLinkEntitlements("42", INSTALL_ID);

        assertThat(result).isEqualTo(CeLinkEntitlementsResult.none());
    }

    @Test
    @DisplayName("ceLinkEntitlements fail-closes on malformed install id or missing user id without calling auth-service")
    void failClosesOnMalformedInputWithoutHttpCall() {
        assertThat(client.ceLinkEntitlements("42", "not-a-uuid"))
                .isEqualTo(CeLinkEntitlementsResult.none());
        assertThat(client.ceLinkEntitlements(null, INSTALL_ID))
                .isEqualTo(CeLinkEntitlementsResult.none());
        assertThat(client.ceLinkEntitlements("42", null))
                .isEqualTo(CeLinkEntitlementsResult.none());
        verify(restTemplate, never()).exchange(
                anyString(), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("ceLinkEntitlements fail-closes on an empty response body")
    void failClosesOnEmptyBody() {
        when(restTemplate.exchange(
                eq(URL), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(null));

        CeLinkEntitlementsResult result = client.ceLinkEntitlements("42", INSTALL_ID);

        assertThat(result).isEqualTo(CeLinkEntitlementsResult.none());
    }
}
