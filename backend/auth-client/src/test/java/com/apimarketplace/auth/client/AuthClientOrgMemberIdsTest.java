package com.apimarketplace.auth.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient.getOrganizationMemberIds")
class AuthClientOrgMemberIdsTest {

    private static final String BASE_URL = "http://auth-service";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AuthClient client = new AuthClient(restTemplate, BASE_URL);

    @Test
    @DisplayName("returns the member ids from the userIds array")
    void returnsMemberIds() {
        when(restTemplate.exchange(
                eq(BASE_URL + "/api/internal/auth/organizations/" + ORG + "/member-ids"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("userIds", List.of("1", "42", "7"))));

        assertThat(client.getOrganizationMemberIds(ORG)).containsExactlyInAnyOrder("1", "42", "7");
    }

    @Test
    @DisplayName("transport failure → empty set (caller fails closed)")
    void emptyOnTransportFailure() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET),
                any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new RestClientException("refused"));

        assertThat(client.getOrganizationMemberIds(ORG)).isEmpty();
    }

    @Test
    @DisplayName("blank org id short-circuits to empty (no HTTP call)")
    void blankOrgNoHttp() {
        assertThat(client.getOrganizationMemberIds(null)).isEmpty();
        assertThat(client.getOrganizationMemberIds("  ")).isEmpty();
        verifyNoInteractions(restTemplate);
    }
}
