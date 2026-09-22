package com.apimarketplace.conversation.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.common.credit.ChatCreditRefusal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same refusal, over a REAL socket.
 *
 * <p>The sibling test drives {@code MockRestServiceServer}, which substitutes the request
 * factory. That covers the error handler and the exception type, but not the transport: the
 * connection, the status line, the body bytes and the charset are all simulated. This one binds
 * a real server on a loopback port and lets the client's own
 * {@code SimpleClientHttpRequestFactory} talk to it, so what is asserted is the behaviour of the
 * deployed wiring rather than of a test double.
 *
 * <p>It exists because the branch it guards CANNOT be reached on the CE end-to-end stack:
 * {@code AppEditionProvider} pins {@code credit.unlimited=true} for the CE edition and enforces
 * it strictly, so a self-hosted monolith has no way to make the credit gate answer no. This is
 * the closest a checked-in test gets to the production path, and it is deliberately checked in
 * rather than run once by hand.
 */
@DisplayName("ConversationClient.sendChatSync over a real socket")
class ConversationClientRealSocketRefusalTest {

    private static final String CONV_ID = "3dddb2d1-5c16-4b57-b5b1-534c20d739d7";
    private static final String REFUSAL_BODY =
        "{\"conversationId\":\"" + CONV_ID + "\",\"error\":\"Insufficient credits\",\"success\":false}";

    private HttpServer server;
    private ConversationClient client;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger logger;

    @BeforeEach
    void setUp() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ConversationClient.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        if (server != null) {
            server.stop(0);
        }
    }

    /** Binds a loopback server that answers every request with {@code status} and {@code body}. */
    private void serve(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/internal/chat/sync", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        client = new ConversationClient(new RestTemplate(),
            "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private Map<String, Object> callSync() {
        return client.sendChatSync("121", CONV_ID, "go", "agent-1", "claude-fable-5",
            "anthropic", "SCHEDULE", null, "org-1");
    }

    @Test
    @DisplayName("a real 402 warns, does not error, and returns the service's own wording")
    void realFourZeroTwoIsAWarning() throws IOException {
        serve(402, REFUSAL_BODY);

        Map<String, Object> result = callSync();

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused").contains(CONV_ID);
        });
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isEqualTo(ChatCreditRefusal.MESSAGE);
        // The in-cluster address must not travel outwards with the message: the widget and
        // webhook paths hand this string to their caller.
        assertThat((String) result.get("error")).doesNotContain("127.0.0.1").doesNotContain("402");
    }

    @Test
    @DisplayName("a real 500 keeps ERROR and keeps the status and URL in the line")
    void realServerErrorStaysAnError() throws IOException {
        serve(500, "{\"error\":\"NullPointerException in the agent loop\"}");

        callSync();

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("refused"));
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.ERROR);
            assertThat(e.getFormattedMessage()).contains("500").contains("/api/internal/chat/sync");
        });
    }

    @Test
    @DisplayName("a connection that is refused outright is a fault, not a refusal")
    void unreachableServiceIsAFault() throws IOException {
        // No HttpStatusCodeException at all here: this lands in the generic catch, which must
        // still be an ERROR and must never produce the literal "null" as its message.
        serve(402, REFUSAL_BODY);
        int deadPort = server.getAddress().getPort();
        server.stop(0);
        server = null;
        client = new ConversationClient(new RestTemplate(), "http://127.0.0.1:" + deadPort);

        Map<String, Object> result = callSync();

        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("refused"));
        assertThat(result.get("error")).isNotNull().isNotEqualTo("null");
    }
}
