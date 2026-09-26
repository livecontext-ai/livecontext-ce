package com.apimarketplace.auth.client;

import com.apimarketplace.auth.client.AuthClient.LifecycleEventResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AuthClient.emitLifecycleEvent - trophies and monthly recap, never throws")
class AuthClientLifecycleEventTest {

    private static final String BASE = "http://auth.test";
    private static final String URL = BASE + "/api/internal/auth/lifecycle/events";

    private AuthClient client;
    private RestTemplate bounded;

    @BeforeEach
    void setUp() throws Exception {
        client = new AuthClient(mock(RestTemplate.class), BASE);
        bounded = mock(RestTemplate.class);
        Field field = AuthClient.class.getDeclaredField("boundedRestTemplate");
        field.setAccessible(true);
        field.set(client, bounded);
    }

    private void answer(RuntimeException error) {
        when(bounded.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), eq(Void.class)))
                .thenThrow(error);
    }

    @Test
    @DisplayName("POSTs {event, payload} to the internal events endpoint with the user in X-User-ID")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void postsEventAndPayload() {
        when(bounded.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.accepted().build());

        LifecycleEventResult result = client.emitLifecycleEvent("42", "badge.unlocked",
                Map.of("kind", "top_tier", "badge_code", "builder_50", "tier", "GOLD"));

        ArgumentCaptor<HttpEntity> entity = ArgumentCaptor.forClass(HttpEntity.class);
        verify(bounded).exchange(eq(URL), eq(HttpMethod.POST), entity.capture(), eq(Void.class));
        assertThat(result).isEqualTo(LifecycleEventResult.ACCEPTED);
        assertThat(entity.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("42");
        assertThat((Map<String, Object>) entity.getValue().getBody()).containsEntry("event", "badge.unlocked")
                .containsEntry("payload", Map.of("kind", "top_tier", "badge_code", "builder_50", "tier", "GOLD"));
    }

    @Test
    @DisplayName("Regression (month consumed by the kill switch): 204 (lifecycle emails off) is INACTIVE, 202 stays ACCEPTED")
    void noContentIsInactive() {
        when(bounded.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)))
                .thenReturn(ResponseEntity.noContent().build(), ResponseEntity.accepted().build());

        assertThat(client.emitLifecycleEvent("42", "recap.monthly", Map.of())).isEqualTo(LifecycleEventResult.INACTIVE);
        assertThat(client.emitLifecycleEvent("42", "recap.monthly", Map.of())).isEqualTo(LifecycleEventResult.ACCEPTED);
    }

    @Test
    @DisplayName("400 is REFUSED (do not retry): an off-list event or payload")
    void clientErrorIsRefused() {
        answer(new HttpClientErrorException(HttpStatus.BAD_REQUEST));
        assertThat(client.emitLifecycleEvent("42", "credits.exhausted", Map.of())).isEqualTo(LifecycleEventResult.REFUSED);
    }

    /** Runs the call and returns the levels AuthClient logged during it. */
    private java.util.List<String> levelsLoggedBy(Runnable call) {
        org.slf4j.Logger slf4j = org.slf4j.LoggerFactory.getLogger(AuthClient.class);
        assertThat(slf4j).as("the test classpath binds slf4j to logback").isInstanceOf(ch.qos.logback.classic.Logger.class);
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) slf4j;
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            call.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().map(e -> e.getLevel().toString() + " " + e.getFormattedMessage()).toList();
    }

    @Test
    @DisplayName("404 (unknown user) is REFUSED, quietly")
    void notFoundIsRefused() {
        answer(new HttpClientErrorException(HttpStatus.NOT_FOUND));
        LifecycleEventResult[] result = new LifecycleEventResult[1];

        java.util.List<String> logged = levelsLoggedBy(
                () -> result[0] = client.emitLifecycleEvent("42", "recap.monthly", Map.of()));

        assertThat(result[0]).isEqualTo(LifecycleEventResult.REFUSED);
        assertThat(logged).noneMatch(line -> line.startsWith("WARN"));
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} is RETRY_LATER and logged at WARN")
    @org.junit.jupiter.params.provider.EnumSource(value = HttpStatus.class,
            names = {"UNAUTHORIZED", "FORBIDDEN", "UNPROCESSABLE_ENTITY", "CONFLICT"})
    @DisplayName("Regression (recap dropped for good): a 4xx other than 400/404 says nothing about the event, so it is retried, loudly")
    void otherClientErrorsAreRetriedAndWarned(HttpStatus status) {
        answer(new HttpClientErrorException(status));
        LifecycleEventResult[] result = new LifecycleEventResult[1];

        java.util.List<String> logged = levelsLoggedBy(
                () -> result[0] = client.emitLifecycleEvent("42", "recap.monthly", Map.of()));

        assertThat(result[0]).isEqualTo(LifecycleEventResult.RETRY_LATER);
        assertThat(logged).anyMatch(line -> line.startsWith("WARN") && line.contains("recap.monthly")
                && line.contains(String.valueOf(status.value())));
    }

    @Test
    @DisplayName("503 (Resend queue full) is RETRY_LATER, never an exception")
    void busyIsRetryLater() {
        answer(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(client.emitLifecycleEvent("42", "recap.monthly", Map.of())).isEqualTo(LifecycleEventResult.RETRY_LATER);
    }

    @Test
    @DisplayName("429 is RETRY_LATER even though it is a 4xx")
    void tooManyRequestsIsRetryLater() {
        answer(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(client.emitLifecycleEvent("42", "recap.monthly", Map.of())).isEqualTo(LifecycleEventResult.RETRY_LATER);
    }

    @Test
    @DisplayName("auth-service unreachable is RETRY_LATER, never an exception")
    void unreachableIsRetryLater() {
        answer(new ResourceAccessException("connection refused"));
        assertThat(client.emitLifecycleEvent("42", "recap.monthly", null)).isEqualTo(LifecycleEventResult.RETRY_LATER);
    }

    @Test
    @DisplayName("a blank user or event makes no call")
    void blankInputsNoCall() {
        assertThat(client.emitLifecycleEvent(" ", "badge.unlocked", Map.of())).isEqualTo(LifecycleEventResult.REFUSED);
        assertThat(client.emitLifecycleEvent("42", null, Map.of())).isEqualTo(LifecycleEventResult.REFUSED);
        verifyNoInteractions(bounded);
    }
}
