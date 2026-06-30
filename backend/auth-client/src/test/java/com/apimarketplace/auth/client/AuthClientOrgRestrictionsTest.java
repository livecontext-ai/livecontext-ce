package com.apimarketplace.auth.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient org restriction calls")
class AuthClientOrgRestrictionsTest {

    private static final String BASE_URL = "http://auth-service";
    private static final String ORG = "org-1";
    private static final String MEMBER = "user-1";
    private static final String TYPE = "agent";
    private static final String RESOURCE = "agent-1";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AuthClient client = new AuthClient(restTemplate, BASE_URL);

    @Test
    @DisplayName("restriction lookup failure propagates instead of failing open")
    void restrictionLookupFailurePropagatesInsteadOfFailingOpen() {
        when(restTemplate.exchange(
                eq(BASE_URL + "/api/internal/auth/org-restrictions?orgId=" + ORG
                        + "&userId=" + MEMBER + "&resourceType=" + TYPE),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new RestClientException("auth down"));

        assertThatThrownBy(() -> client.getRestrictedResourceIds(ORG, MEMBER, TYPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to query org restrictions");
    }

    @Test
    @DisplayName("restrictAccess failure propagates instead of returning apparent success")
    void restrictAccessFailurePropagatesInsteadOfReturningApparentSuccess() {
        when(restTemplate.exchange(
                eq(BASE_URL + "/api/internal/auth/org-restrictions"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Void.class)))
                .thenThrow(new RestClientException("auth rejected"));

        assertThatThrownBy(() -> client.restrictAccess(ORG, MEMBER, TYPE, RESOURCE, "owner-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to restrict org resource access");
    }
}
