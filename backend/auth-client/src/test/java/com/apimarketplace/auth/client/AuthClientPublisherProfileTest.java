package com.apimarketplace.auth.client;

import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient.getPublisherProfile")
class AuthClientPublisherProfileTest {

    private static final String BASE_URL = "http://auth-service";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final AuthClient client = new AuthClient(restTemplate, BASE_URL);

    @Test
    @DisplayName("Returns DTO when auth-service answers 2xx with body")
    void returnsDtoOnSuccess() {
        PublisherProfileDto expected = new PublisherProfileDto("42", "Real Name", "real@x.com", "avatar-uuid");
        when(restTemplate.exchange(
                eq(BASE_URL + "/api/internal/auth/users/42/publisher-profile"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(PublisherProfileDto.class)))
                .thenReturn(ResponseEntity.ok(expected));

        PublisherProfileDto actual = client.getPublisherProfile("42");

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("Returns null on transport failure (publish path can fail-fast on null)")
    void returnsNullOnTransportFailure() {
        when(restTemplate.exchange(
                any(String.class),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(PublisherProfileDto.class)))
                .thenThrow(new RestClientException("connection refused"));

        assertThat(client.getPublisherProfile("42")).isNull();
    }

    @Test
    @DisplayName("Null / blank userId short-circuits to null (no HTTP call)")
    void blankUserIdReturnsNullWithoutHttpCall() {
        assertThat(client.getPublisherProfile(null)).isNull();
        assertThat(client.getPublisherProfile("")).isNull();
        assertThat(client.getPublisherProfile("   ")).isNull();
    }
}
