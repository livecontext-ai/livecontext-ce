package com.apimarketplace.auth.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient.reportActivation - the lifecycle activation signal")
class AuthClientLifecycleActivationTest {

    private static final String BASE = "http://auth.test";
    private static final String URL = BASE + "/api/internal/auth/lifecycle/activation";

    private AuthClient client;
    private RestTemplate bounded;

    @BeforeEach
    void setUp() throws Exception {
        client = new AuthClient(mock(RestTemplate.class), BASE);
        // reportActivation uses the bounded (short-timeout) template AuthClient builds for
        // itself, swapped by reflection for a mock.
        bounded = mock(RestTemplate.class);
        Field field = AuthClient.class.getDeclaredField("boundedRestTemplate");
        field.setAccessible(true);
        field.set(client, bounded);
    }

    @Test
    @DisplayName("POSTs to the internal activation endpoint with the user in X-User-ID")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void postsWithUserHeader() {
        when(bounded.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build());

        boolean ok = client.reportActivation("42");

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(bounded).exchange(eq(URL), eq(HttpMethod.POST), entity.capture(), eq(Void.class));
        assertThat(ok).isTrue();
        assertThat(entity.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("42");
    }

    @Test
    @DisplayName("an auth-service error is swallowed: false, never an exception")
    void errorNeverThrows() {
        when(bounded.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(Void.class)))
                .thenThrow(new HttpServerErrorException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.reportActivation("42")).isFalse();
    }

    @Test
    @DisplayName("a blank user makes no call")
    void blankUserNoCall() {
        assertThat(client.reportActivation(" ")).isFalse();
        assertThat(client.reportActivation(null)).isFalse();
        verifyNoInteractions(bounded);
    }
}
