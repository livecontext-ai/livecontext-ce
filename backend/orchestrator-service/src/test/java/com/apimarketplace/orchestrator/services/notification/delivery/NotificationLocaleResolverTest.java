package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.auth.client.AuthClient;
import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("NotificationLocaleResolver - the recipient's language and zone, cached, never an exception")
class NotificationLocaleResolverTest {

    private final AuthClient auth = mock(AuthClient.class);
    private final AtomicLong nanos = new AtomicLong();
    private final Ticker ticker = nanos::get;
    private final NotificationLocaleResolver resolver =
            new NotificationLocaleResolver(auth, Duration.ofMinutes(10), ticker);

    @Test
    @DisplayName("Reads the user's values from auth-service")
    void reads() {
        when(auth.getLocaleContext("7")).thenReturn(new AuthClient.LocaleContext("fr", "Europe/Paris", false));

        NotificationLocale l = resolver.resolve("7");

        assertThat(l.locale()).isEqualTo("fr");
        assertThat(l.zone()).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    @DisplayName("A real answer is cached for the TTL, then asked again")
    void cachesUntilTtl() {
        when(auth.getLocaleContext("7")).thenReturn(new AuthClient.LocaleContext("de", "Europe/Berlin", false));

        resolver.resolve("7");
        nanos.addAndGet(Duration.ofMinutes(9).toNanos());
        resolver.resolve("7");
        verify(auth, times(1)).getLocaleContext("7");

        nanos.addAndGet(Duration.ofMinutes(2).toNanos());
        resolver.resolve("7");
        verify(auth, times(2)).getLocaleContext("7");
    }

    @Test
    @DisplayName("A fallback answer (auth unreachable) is not cached: the next message asks again")
    void fallbackNotCached() {
        when(auth.getLocaleContext("7")).thenReturn(AuthClient.LocaleContext.FALLBACK)
                .thenReturn(new AuthClient.LocaleContext("fr", "Europe/Paris", false));

        assertThat(resolver.resolve("7")).isEqualTo(NotificationLocale.DEFAULT);
        assertThat(resolver.resolve("7").locale()).as("recovered on the next message").isEqualTo("fr");
    }

    /**
     * An unknown recipient (auth-service answers 404) is deliberately NOT cached, like any other
     * fallback: every message addressed to it asks auth-service again (one bounded call per
     * message). Caching it would pin a just-created account to English and UTC for the whole TTL,
     * and a recipient that auth-service does not know is rare enough that the extra call is cheap.
     */
    @Test
    @DisplayName("An unknown user (auth-service 404) is English and UTC, and is not cached: each message asks again")
    void unknownUserFallsBackUncached() throws Exception {
        org.springframework.web.client.RestTemplate bounded = mock(org.springframework.web.client.RestTemplate.class);
        AuthClient realClient = new AuthClient(mock(org.springframework.web.client.RestTemplate.class), "http://auth.test");
        java.lang.reflect.Field field = AuthClient.class.getDeclaredField("boundedRestTemplate");
        field.setAccessible(true);
        field.set(realClient, bounded);
        when(bounded.exchange(anyString(), org.mockito.ArgumentMatchers.any(org.springframework.http.HttpMethod.class),
                org.mockito.ArgumentMatchers.any(org.springframework.http.HttpEntity.class),
                org.mockito.ArgumentMatchers.<org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>>any()))
                .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.NOT_FOUND));
        NotificationLocaleResolver real = new NotificationLocaleResolver(realClient, Duration.ofMinutes(10), ticker);

        assertThat(real.resolve("404")).isEqualTo(NotificationLocale.DEFAULT);
        assertThat(real.resolve("404")).isEqualTo(NotificationLocale.DEFAULT);

        assertThat(NotificationLocale.DEFAULT.locale()).isEqualTo("en");
        assertThat(NotificationLocale.DEFAULT.zone().getId()).isEqualTo("UTC");
        verify(bounded, times(2)).exchange(anyString(),
                org.mockito.ArgumentMatchers.any(org.springframework.http.HttpMethod.class),
                org.mockito.ArgumentMatchers.any(org.springframework.http.HttpEntity.class),
                org.mockito.ArgumentMatchers.<org.springframework.core.ParameterizedTypeReference<java.util.Map<String, Object>>>any());
    }

    @Test
    @DisplayName("Auth-client throwing (a broken contract) or answering null still yields English and UTC")
    void neverThrows() {
        when(auth.getLocaleContext("7")).thenThrow(new IllegalStateException("boom"));
        when(auth.getLocaleContext("8")).thenReturn(null);

        assertThat(resolver.resolve("7")).isEqualTo(NotificationLocale.DEFAULT);
        assertThat(resolver.resolve("8")).isEqualTo(NotificationLocale.DEFAULT);
        assertThat(NotificationLocale.DEFAULT.zone().getId()).isEqualTo("UTC");
    }

    @Test
    @DisplayName("No recipient id: no call, English and UTC")
    void noRecipient() {
        assertThat(resolver.resolve(null)).isEqualTo(NotificationLocale.DEFAULT);
        assertThat(resolver.resolve(" ")).isEqualTo(NotificationLocale.DEFAULT);
        verify(auth, never()).getLocaleContext(anyString());
    }

    @Test
    @DisplayName("Values auth-service might send that the catalog cannot use are normalized")
    void normalizes() {
        when(auth.getLocaleContext("7")).thenReturn(new AuthClient.LocaleContext("pt-BR", "Mars/Base", false));

        NotificationLocale l = resolver.resolve("7");

        assertThat(l.locale()).isEqualTo("pt");
        assertThat(l.zone().getId()).isEqualTo("UTC");
    }
}
