package com.apimarketplace.auth.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient.getLocaleContext - the recipient's language and zone, never an exception")
class AuthClientLocaleContextTest {

    private static final String URL = "http://auth-service/api/internal/auth/users/42/locale-context";

    private final RestTemplate bounded = mock(RestTemplate.class);
    private AuthClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new AuthClient(mock(RestTemplate.class), "http://auth-service");
        java.lang.reflect.Field field = AuthClient.class.getDeclaredField("boundedRestTemplate");
        field.setAccessible(true);
        field.set(client, bounded);
    }

    @SuppressWarnings("unchecked")
    private void answer(ResponseEntity<?> response) {
        when(bounded.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenReturn((ResponseEntity<Object>) response);
    }

    @SuppressWarnings("unchecked")
    private void fail(RuntimeException ex) {
        when(bounded.exchange(eq(URL), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenThrow(ex);
    }

    @Test
    @DisplayName("Reads the user's locale and time zone, marked as not a fallback")
    void readsBoth() {
        answer(ResponseEntity.ok(Map.of("locale", "fr", "timeZone", "Europe/Paris")));

        AuthClient.LocaleContext ctx = client.getLocaleContext("42");

        assertThat(ctx).isEqualTo(new AuthClient.LocaleContext("fr", "Europe/Paris", false));
    }

    @Test
    @DisplayName("A missing value in the body is replaced by en / UTC, still an answer from auth")
    void blankValuesDefault() {
        Map<String, Object> body = new HashMap<>();
        body.put("locale", " ");
        body.put("timeZone", null);
        answer(ResponseEntity.ok(body));

        assertThat(client.getLocaleContext("42")).isEqualTo(new AuthClient.LocaleContext("en", "UTC", false));
    }

    @Test
    @DisplayName("Unknown user (404) answers the fallback instead of throwing")
    void unknownUser() {
        fail(new HttpClientErrorException(HttpStatus.NOT_FOUND));

        assertThat(client.getLocaleContext("42")).isEqualTo(AuthClient.LocaleContext.FALLBACK);
    }

    @Test
    @DisplayName("Auth-service unreachable answers the fallback (en, UTC, fallback=true)")
    void unreachable() {
        fail(new ResourceAccessException("connection refused"));

        AuthClient.LocaleContext ctx = client.getLocaleContext("42");

        assertThat(ctx.locale()).isEqualTo("en");
        assertThat(ctx.timeZone()).isEqualTo("UTC");
        assertThat(ctx.fallback()).isTrue();
    }

    @Test
    @DisplayName("An empty body answers the fallback")
    void emptyBody() {
        answer(ResponseEntity.ok().build());

        assertThat(client.getLocaleContext("42")).isEqualTo(AuthClient.LocaleContext.FALLBACK);
    }

    @Test
    @DisplayName("No user id: no call at all, fallback")
    void blankUser() {
        assertThat(client.getLocaleContext(" ")).isEqualTo(AuthClient.LocaleContext.FALLBACK);
        assertThat(client.getLocaleContext(null)).isEqualTo(AuthClient.LocaleContext.FALLBACK);
        verifyNoInteractions(bounded);
    }
}
