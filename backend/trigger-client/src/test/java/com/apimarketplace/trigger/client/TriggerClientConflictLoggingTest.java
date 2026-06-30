package com.apimarketplace.trigger.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the standalone-endpoint create log hygiene fix (audit 2026-06-14).
 *
 * <p><b>Context:</b> in prod, re-saving a workflow/app that already created its form/chat/
 * webhook trigger made trigger-service return HTTP 409 Conflict. {@code TriggerClient} caught
 * it in the generic {@code catch (Exception)} and logged at ERROR - ~33 ERROR/12h of pure
 * idempotency noise that polluted error metrics and masked real failures. The fix catches
 * {@link HttpClientErrorException.Conflict} first and logs at DEBUG (the desired state already
 * exists), while a genuine failure must still surface at ERROR.
 *
 * <p>No Mockito / spring-test on this module's classpath - the throwing RestTemplate is a tiny
 * hand-rolled subclass over the single exchange overload {@code TriggerClient} uses.
 */
@DisplayName("TriggerClient - 409 on standalone-endpoint create is idempotent (DEBUG), real failures stay ERROR")
class TriggerClientConflictLoggingTest {

    private Logger logbackLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logbackLogger = (Logger) LoggerFactory.getLogger(TriggerClient.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
        logbackLogger.setLevel(Level.DEBUG); // capture DEBUG so we can assert the downgrade
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    /** A RestTemplate whose POST exchange always throws - TriggerClient uses the
     * {@code exchange(String, HttpMethod, HttpEntity, Class)} overload. */
    private TriggerClient clientThrowing(RuntimeException toThrow) {
        RestTemplate rt = new RestTemplate() {
            @Override
            public <T> ResponseEntity<T> exchange(String url, HttpMethod method,
                                                  HttpEntity<?> requestEntity, Class<T> responseType,
                                                  Object... uriVariables) {
                throw toThrow;
            }
        };
        return new TriggerClient(rt, "http://trigger.test");
    }

    private long errorEvents() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    @Test
    @DisplayName("409 Conflict → graceful null, logged at DEBUG, NOT ERROR")
    void conflict_isIdempotent_noErrorLog() {
        TriggerClient client = clientThrowing(HttpClientErrorException.create(
                HttpStatus.CONFLICT, "Conflict", HttpHeaders.EMPTY, new byte[0], null));

        // createFormEndpoint is one of the real 409 sources in prod (the form + chat
        // standalone controllers return 409 on duplicate; webhook/schedule return 400, so
        // their Conflict catch is forward-compatible defensive code).
        StandaloneFormEndpointDto result = client.createFormEndpoint(
                "tenant-1", "free", null, "org-1");

        assertThat(result).as("409 = endpoint already exists → graceful null").isNull();
        assertThat(errorEvents()).as("a benign 409 must NOT be logged at ERROR").isZero();
        assertThat(appender.list)
                .as("the 409 is recorded at DEBUG as an idempotent no-op")
                .anyMatch(e -> e.getLevel() == Level.DEBUG
                        && e.getFormattedMessage().contains("already exists (409)"));
    }

    @Test
    @DisplayName("a genuine transport failure still logs at ERROR (no over-suppression)")
    void realFailure_stillLogsError() {
        TriggerClient client = clientThrowing(new ResourceAccessException("Connection refused"));

        StandaloneFormEndpointDto result = client.createFormEndpoint(
                "tenant-1", "free", null, "org-1");

        assertThat(result).isNull();
        assertThat(errorEvents())
                .as("a real failure (not 409) must still surface at ERROR")
                .isEqualTo(1);
    }
}
