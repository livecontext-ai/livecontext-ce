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
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AuthClient#getUserRoles(String)}. agent-service calls
 * this at CLI session start to resolve the caller's platform roles server-side
 * (the agent-cli MCP bridge bypasses the gateway, so no X-User-Roles header
 * exists). Returned CSV is stamped into session credentials as __userRoles__
 * and consumed by AdminRoleGuard.isAdmin (which splits on ',').
 */
@DisplayName("AuthClient.getUserRoles")
class AuthClientUserRolesTest {

    private static final String BASE_URL = "http://auth-service";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AuthClient client = new AuthClient(restTemplate, BASE_URL);

    @SuppressWarnings("unchecked")
    private void stubRolesResponse(Map<String, Object> body) {
        when(restTemplate.exchange(
                eq(BASE_URL + "/api/internal/auth/users/42/roles"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @Test
    @DisplayName("Joins the roles array into a comma-separated CSV (no spaces, AdminRoleGuard-compatible)")
    void joinsRolesIntoCsv() {
        stubRolesResponse(Map.of("userId", "42", "roles", List.of("USER", "ADMIN")));

        String csv = client.getUserRoles("42");

        // Order follows the array; both roles present and comma-delimited with no spaces.
        assertThat(csv).contains("USER", "ADMIN");
        assertThat(csv).doesNotContain(", ");
        assertThat(csv.split(",")).containsExactlyInAnyOrder("USER", "ADMIN");
    }

    @Test
    @DisplayName("Empty roles array → empty string")
    void emptyRolesArrayReturnsEmptyString() {
        stubRolesResponse(Map.of("userId", "42", "roles", List.of()));

        assertThat(client.getUserRoles("42")).isEmpty();
    }

    @Test
    @DisplayName("Missing roles key → empty string (treated as non-admin)")
    void missingRolesKeyReturnsEmptyString() {
        stubRolesResponse(Map.of("userId", "42"));

        assertThat(client.getUserRoles("42")).isEmpty();
    }

    @Test
    @DisplayName("Transport failure → empty string (caller treats as non-admin, never throws)")
    @SuppressWarnings("unchecked")
    void transportFailureReturnsEmptyString() {
        when(restTemplate.exchange(
                any(String.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new RestClientException("connection refused"));

        assertThat(client.getUserRoles("42")).isEmpty();
    }

    @Test
    @DisplayName("Null / blank userId short-circuits to empty string (no HTTP call)")
    void blankUserIdReturnsEmptyWithoutHttpCall() {
        assertThat(client.getUserRoles(null)).isEmpty();
        assertThat(client.getUserRoles("")).isEmpty();
        assertThat(client.getUserRoles("   ")).isEmpty();
    }
}
