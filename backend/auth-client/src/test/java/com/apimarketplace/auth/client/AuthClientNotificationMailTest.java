package com.apimarketplace.auth.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient.sendNotificationMail")
class AuthClientNotificationMailTest {

    private static final String URL = "http://auth-service/api/internal/auth/notification-mail";

    private final RestTemplate mailTemplate = mock(RestTemplate.class);
    private AuthClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new AuthClient(mock(RestTemplate.class), "http://auth-service");
        // Its own template (long read timeout for an SMTP send), not the injected one.
        java.lang.reflect.Field field = AuthClient.class.getDeclaredField("mailRestTemplate");
        field.setAccessible(true);
        field.set(client, mailTemplate);
    }

    @SuppressWarnings("unchecked")
    private void answer(Object body) {
        when(mailTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenReturn(ResponseEntity.ok(body));
    }

    @Test
    @DisplayName("Posts the message and the recipient, and reads SENT back")
    @SuppressWarnings("unchecked")
    void sent() {
        answer(Map.of("status", "SENT"));

        AuthClient.NotificationMailResult result = client.sendNotificationMail("42", "Subject",
                List.of("a", "b"), "/app", "Open");

        assertThat(result.sent()).isTrue();
        ArgumentCaptor<HttpEntity<Map<String, Object>>> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(mailTemplate).exchange(eq(URL), eq(HttpMethod.POST), entity.capture(), any(ParameterizedTypeReference.class));
        assertThat(entity.getValue().getBody()).containsEntry("userId", "42").containsEntry("lines", List.of("a", "b"))
                .containsEntry("actionPath", "/app");
        assertThat(entity.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("42");
    }

    @Test
    @DisplayName("A refusal keeps its status and reason; sent() is false")
    void refusal() {
        Map<String, Object> body = new HashMap<>();
        body.put("status", "NO_ADDRESS");
        body.put("detail", "email not verified");
        answer(body);

        AuthClient.NotificationMailResult result = client.sendNotificationMail("42", "s", List.of(), "/app", "Open");

        assertThat(result.sent()).isFalse();
        assertThat(result.status()).isEqualTo("NO_ADDRESS");
        assertThat(result.detail()).isEqualTo("email not verified");
    }

    @Test
    @DisplayName("An empty body is FAILED, never SENT")
    void emptyBody() {
        answer(null);

        assertThat(client.sendNotificationMail("42", "s", List.of(), "/app", "Open").status()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("auth-service unreachable: FAILED, no exception for the caller")
    @SuppressWarnings("unchecked")
    void unreachable() {
        when(mailTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenThrow(new ResourceAccessException("timeout"));

        AuthClient.NotificationMailResult result = client.sendNotificationMail("42", "s", List.of(), "/app", "Open");

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.detail()).isEqualTo("ResourceAccessException");
    }
}
