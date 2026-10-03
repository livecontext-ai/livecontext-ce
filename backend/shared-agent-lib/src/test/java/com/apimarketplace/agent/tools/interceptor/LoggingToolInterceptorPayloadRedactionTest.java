package com.apimarketplace.agent.tools.interceptor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for LC-009 / LC-010 (security audit 2026-08-13): tool parameters and up to
 * 2000 characters of tool RESULT were written to application logs at INFO. Those logs ship to a
 * central store with a long retention and a far wider read audience than the data itself, which
 * is a standing human-access channel over end-user content. For Google restricted scopes such as
 * {@code gmail.readonly}, the Limited Use requirements forbid exactly that.
 *
 * <p>The payload used throughout is shaped like a Gmail message, because that is the case that
 * makes the requirement concrete.
 */
@DisplayName("LoggingToolInterceptor payload redaction")
class LoggingToolInterceptorPayloadRedactionTest {

    private static final String SECRET_BODY =
            "Hi Alice, the wire transfer of 45000 EUR is approved. Account BE71 0961 2345 6769.";
    private static final String SECRET_SUBJECT = "Re: Q3 acquisition, confidential";
    private static final String SECRET_QUERY = "from:ceo@acme.example subject:acquisition";

    private ListAppender<ILoggingEvent> appender;
    private Logger interceptorLogger;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        interceptorLogger = (Logger) LoggerFactory.getLogger(LoggingToolInterceptor.class);
        originalLevel = interceptorLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        interceptorLogger.addAppender(appender);
        interceptorLogger.setLevel(Level.TRACE);
    }

    @AfterEach
    void tearDown() {
        interceptorLogger.detachAppender(appender);
        appender.stop();
        // Restore the level: logback loggers are JVM-global, so leaving TRACE set here would
        // change logging behaviour for every test that runs after this class.
        interceptorLogger.setLevel(originalLevel);
    }

    /** Every log line the interceptor emitted, fully formatted, joined for easy assertion. */
    private String captured() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    private static ToolExecutionResult gmailResult() {
        return ToolExecutionResult.success(Map.of(
                "messages", List.of(Map.of(
                        "id", "18f9c2a1",
                        "subject", SECRET_SUBJECT,
                        "body", SECRET_BODY))));
    }

    @Nested
    @DisplayName("with payload logging disabled (production default)")
    class Disabled {

        private LoggingToolInterceptor interceptor;

        @BeforeEach
        void setUp() {
            interceptor = new LoggingToolInterceptor(false);
        }

        @Test
        @DisplayName("never writes tool parameter values to the log")
        void redactsParameterValues() {
            interceptor.beforeExecution(
                    "gmail_search",
                    Map.of("query", SECRET_QUERY, "maxResults", 25),
                    ToolExecutionContext.of("tenant-1"));

            assertThat(captured()).doesNotContain(SECRET_QUERY);
            assertThat(captured()).doesNotContain("ceo@acme.example");
        }

        @Test
        @DisplayName("still records the parameter names, the tool and the tenant")
        void keepsDiagnosticShape() {
            interceptor.beforeExecution(
                    "gmail_search",
                    Map.of("query", SECRET_QUERY, "maxResults", 25),
                    ToolExecutionContext.of("tenant-1"));

            String logged = captured();
            assertThat(logged).contains("gmail_search");
            assertThat(logged).contains("tenant-1");
            assertThat(logged).contains("maxResults");
            assertThat(logged).contains("query");
            assertThat(logged).contains("2 keys");
        }

        @Test
        @DisplayName("never writes the tool result body to the log")
        void redactsResultBody() {
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution("gmail_search", gmailResult(), 120);

            String logged = captured();
            assertThat(logged).doesNotContain(SECRET_BODY);
            assertThat(logged).doesNotContain(SECRET_SUBJECT);
            assertThat(logged).doesNotContain("BE71");
        }

        @Test
        @DisplayName("still records the result size, so an empty or runaway result stays visible")
        void keepsResultSize() {
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution("gmail_search", gmailResult(), 120);

            assertThat(captured()).containsPattern("data=<\\d+ chars>");
        }

        @Test
        @DisplayName("never writes success metadata values to the log")
        void redactsSuccessMetadata() {
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution(
                    "gmail_search",
                    ToolExecutionResult.success(Map.of("ok", true), Map.of("preview", SECRET_BODY)),
                    30);

            assertThat(captured()).doesNotContain(SECRET_BODY);
            assertThat(captured()).contains("preview");
        }

        @Test
        @DisplayName("never writes error metadata values, which echo the rejected input")
        void redactsErrorMetadata() {
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution(
                    "gmail_search",
                    ToolExecutionResult.failure(
                            ToolErrorCode.VALIDATION_ERROR,
                            "invalid query",
                            Map.of("rejectedValue", SECRET_QUERY)),
                    30);

            assertThat(captured()).doesNotContain(SECRET_QUERY);
        }

        @Test
        @DisplayName("keeps a short error message, which is what makes a failure diagnosable")
        void keepsErrorMessage() {
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution(
                    "gmail_search",
                    ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "no such tool"),
                    30);

            assertThat(captured()).contains("no such tool");
        }

        @Test
        @DisplayName("caps a long error message, because it is not always developer-authored")
        void capsLongErrorMessage() {
            // HttpExecutionService.extractErrorMessage returns the provider's RAW response body
            // when it cannot classify the failure, so an error string can carry user content
            // echoed back by the provider. It is capped rather than trusted.
            String providerBody = "{\"error\":\"bad request\",\"echo\":\"" + SECRET_BODY.repeat(6) + "\"}";
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution(
                    "gmail_search",
                    ToolExecutionResult.failure(ToolErrorCode.VALIDATION_ERROR, providerBody),
                    30);

            String logged = captured();
            assertThat(logged).doesNotContain(providerBody);
            assertThat(logged).contains("chars]");
            assertThat(logged).contains("bad request");
        }

        @Test
        @DisplayName("onError logs the exception type but not its stack trace")
        void onErrorRedactsStackTrace() {
            // A Jackson parse failure quotes the offending source inside both the message and
            // the trace, so the throwable is not attached when payload logging is off.
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.onError("gmail_search", new IllegalStateException(SECRET_BODY), 30);

            ILoggingEvent event = appender.list.get(appender.list.size() - 1);
            assertThat(event.getFormattedMessage()).contains("java.lang.IllegalStateException");
            assertThat(event.getThrowableProxy())
                    .as("no stack trace, it can quote the offending payload")
                    .isNull();
        }

        @Test
        @DisplayName("onError caps the exception message too")
        void onErrorCapsMessage() {
            String huge = SECRET_BODY.repeat(6);
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.onError("gmail_search", new IllegalStateException(huge), 30);

            assertThat(captured()).doesNotContain(huge);
            assertThat(captured()).contains("chars]");
        }

        @Test
        @DisplayName("a null result body is reported as null, not as the string \"null\" content")
        void describesNullResult() {
            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution("gmail_search", ToolExecutionResult.success(null), 10);

            assertThat(captured()).contains("data=<null>");
        }
    }

    @Nested
    @DisplayName("with payload logging explicitly enabled (local debugging only)")
    class Enabled {

        @Test
        @DisplayName("restores the verbose payload logging")
        void logsPayloads() {
            LoggingToolInterceptor interceptor = new LoggingToolInterceptor(true);

            interceptor.beforeExecution(
                    "gmail_search",
                    Map.of("query", SECRET_QUERY),
                    ToolExecutionContext.of("tenant-1"));
            interceptor.afterExecution("gmail_search", gmailResult(), 120);

            String logged = captured();
            assertThat(logged).contains(SECRET_QUERY);
            assertThat(logged).contains(SECRET_BODY);
        }

        @Test
        @DisplayName("restores error metadata and the stack trace as well")
        void logsErrorDetail() {
            LoggingToolInterceptor interceptor = new LoggingToolInterceptor(true);

            interceptor.beforeExecution("gmail_search", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution(
                    "gmail_search",
                    ToolExecutionResult.failure(
                            ToolErrorCode.VALIDATION_ERROR, "invalid query",
                            Map.of("rejectedValue", SECRET_QUERY)),
                    30);
            interceptor.onError("gmail_search", new IllegalStateException("boom"), 30);

            assertThat(captured()).contains(SECRET_QUERY);
            assertThat(appender.list.get(appender.list.size() - 1).getThrowableProxy())
                    .as("the stack trace is the point of enabling this flag while debugging")
                    .isNotNull();
        }

        @Test
        @DisplayName("still never prints a RESTRICTED (Gmail) result, even with the debug flag on")
        void neverPrintsRestrictedResult() {
            LoggingToolInterceptor interceptor = new LoggingToolInterceptor(true);

            interceptor.beforeExecution("catalog", Map.of(), ToolExecutionContext.of("tenant-1"));
            interceptor.afterExecution("catalog",
                    ToolExecutionResult.success(
                            Map.of("body", SECRET_BODY),
                            Map.of("iconSlug", "gmail", "subject", SECRET_SUBJECT)),
                    40);

            String logged = captured();
            assertThat(logged).doesNotContain(SECRET_BODY);
            assertThat(logged).doesNotContain(SECRET_SUBJECT);
            assertThat(logged).containsPattern("data=<\\d+ chars>");
        }
    }
}
