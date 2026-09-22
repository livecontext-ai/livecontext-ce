package com.apimarketplace.conversation.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The one frame that still holds the HTTP status, so the one place the decision is cheap.
 *
 * <p>Prod, 2026-09-17: a workspace at -100.83 credits had an agent schedule on a
 * half-hourly cron. Every
 * tick wrote two ERROR lines, one here and one in the orchestrator frame above, for a product
 * refusing exactly as designed. Over 24 h that was 44 of orchestrator-service's 179 error lines,
 * one quarter: both lines land in the CALLING service's log, because this client is a library.
 *
 * <p>The server is attached to the INTERNAL {@code syncRestTemplate}, which the constructor builds
 * itself, so these drive the real {@code RestTemplate} error handler and the real
 * {@code HttpStatusCodeException} rather than a hand-made one.
 */
@DisplayName("ConversationClient.sendChatSync - a 402 is a refusal, not an error")
class ConversationClientSyncRefusalTest {

    private static final String BASE_URL = "http://conversation-service:8087";
    private static final String SYNC_URL = BASE_URL + "/api/internal/chat/sync";
    private static final String CONV_ID = "3dddb2d1-5c16-4b57-b5b1-534c20d739d7";

    private ConversationClient client;
    private MockRestServiceServer syncServer;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        client = new ConversationClient(new RestTemplate(), BASE_URL);
        RestTemplate sync = (RestTemplate) ReflectionTestUtils.getField(client, "syncRestTemplate");
        syncServer = MockRestServiceServer.createServer(sync);

        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ConversationClient.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    private Map<String, Object> callSync() {
        return client.sendChatSync("121", CONV_ID, "go", "agent-1", "claude-fable-5",
                "anthropic", "SCHEDULE", null, "org-1");
    }

    @Test
    @DisplayName("402 out of credits logs WARN and never ERROR")
    void paymentRequiredIsWarn() {
        syncServer.expect(requestTo(SYNC_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"conversationId\":\"" + CONV_ID
                            + "\",\"error\":\"Insufficient credits\",\"success\":false}"));

        Map<String, Object> result = callSync();

        assertThat(result.get("success")).isEqualTo(false);
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused").contains(CONV_ID);
        });
    }

    @Test
    @DisplayName("402 returns the service's own wording, not the transport sentence wrapped around it")
    void paymentRequiredUnwrapsTheBody() {
        // Pre-fix this was the whole RestTemplate sentence: status, the in-cluster URL and the raw
        // JSON. The widget and webhook paths hand this string back to a caller, so the internal
        // hostname travelled outwards with it - and no frame above could recognise the condition.
        syncServer.expect(requestTo(SYNC_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"conversationId\":\"" + CONV_ID
                            + "\",\"error\":\"Insufficient credits\",\"success\":false}"));

        Map<String, Object> result = callSync();

        assertThat(result.get("error")).isEqualTo("Insufficient credits");
        assertThat((String) result.get("error"))
                .doesNotContain("conversation-service:8087")
                .doesNotContain("402");
    }

    @Test
    @DisplayName("a 500 is a platform fault and keeps ERROR")
    void serverErrorStaysError() {
        syncServer.expect(requestTo(SYNC_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"NullPointerException in the agent loop\"}"));

        Map<String, Object> result = callSync();

        assertThat(result.get("success")).isEqualTo(false);
        // Scoped to the refusal line: a blanket "no WARN" would fail on any unrelated
        // future warning on this logger, for a reason that has nothing to do with this test.
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("refused"));
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            // A noise-reduction change must not make the one path that still needs debugging
            // harder to debug: the fault line keeps the status and the URL it always had, and
            // gains the conversation it never carried.
            assertThat(e.getFormattedMessage())
                    .contains("500")
                    .contains("/api/internal/chat/sync")
                    .contains(CONV_ID);
        });
    }

    @Test
    @DisplayName("a 402 with an unparseable body still warns, and falls back to the transport message")
    void unparseableBodyFallsBack() {
        // The level is decided on the STATUS, so a body we cannot read must not send the line
        // back to ERROR. Only the message degrades, to exactly what was reported before.
        syncServer.expect(requestTo(SYNC_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED)
                        .contentType(MediaType.TEXT_PLAIN).body("upstream cut the body"));

        Map<String, Object> result = callSync();

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(result.get("error")).isNotNull();
        assertThat((String) result.get("error")).contains("402");
    }

    @Test
    @DisplayName("a 402 with an empty body still warns and still reports the refusal")
    void emptyBodyStillWarns() {
        // An earlier version of this test claimed to guard a Map.of NPE. It did not:
        // HttpStatusCodeException.getMessage() is never null, so both of its assertions passed
        // on the pre-fix code too. What IS worth pinning is that the level survives a body we
        // cannot read, because the level is decided on the status and must not depend on it.
        syncServer.expect(requestTo(SYNC_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED).body(""));

        Map<String, Object> result = callSync();

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        assertThat(result.get("success")).isEqualTo(false);
        assertThat((String) result.get("error")).contains("402");
    }
}
