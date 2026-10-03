package com.apimarketplace.agent.tools.interceptor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
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
 * LC-034 (security audit 2026-08-13): the interceptor serialized {@code result.metadata()} raw and
 * uncapped on the success path, while three other metadata sinks in the repo strip the heavy media
 * bytes first and the interceptor's OWN error path already truncated.
 *
 * <p>The concrete damage: a {@code files.view} result carries the image the vision model must see
 * as base64 under {@code __media__}. Those bytes belong to the vision channel; writing them into a
 * log line copies megabytes of user content into the log store on every screenshot, and does it in
 * one unbounded line.
 *
 * <p>These tests pin the contract in BOTH flag states, because "we turned payload logging on to
 * debug something" is exactly the moment the old code did the most damage.
 */
@DisplayName("LoggingToolInterceptor media-strip contract")
class LoggingToolInterceptorMediaStripTest {

    /** Stands in for a screenshot: long enough that a truncation cannot hide a leak by accident. */
    private static final String IMAGE_BASE64 = "iVBORw0KGgoAAAANSUhEUg" + "QUJCRUFVQ09VUF9ERV9CWVRFUw".repeat(200);

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
        interceptorLogger.setLevel(originalLevel);
    }

    private String captured() {
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .collect(Collectors.joining("\n"));
    }

    /** A tool result shaped like files.view: a small text body plus heavy vision media. */
    private static ToolExecutionResult resultWithMedia() {
        return ToolExecutionResult.success(
                Map.of("fileName", "screenshot.png"),
                Map.of(
                        "mimeType", "image/png",
                        ToolMediaMetadata.MEDIA_KEY,
                        List.of(ToolMediaMetadata.imageDescriptor("image/png", IMAGE_BASE64))));
    }

    @Nested
    @DisplayName("with payload logging disabled (production default)")
    class Disabled {

        private final LoggingToolInterceptor interceptor = new LoggingToolInterceptor(false);

        @Test
        @DisplayName("never writes the media base64 to the log")
        void noBase64OnSuccess() {
            interceptor.beforeExecution("files", Map.of("action", "view"), ToolExecutionContext.of("t"));
            interceptor.afterExecution("files", resultWithMedia(), 12);

            assertThat(captured()).doesNotContain(IMAGE_BASE64);
            assertThat(captured()).doesNotContain(IMAGE_BASE64.substring(0, 64));
        }
    }

    @Nested
    @DisplayName("with payload logging enabled (local debugging)")
    class Enabled {

        private final LoggingToolInterceptor interceptor = new LoggingToolInterceptor(true);

        @Test
        @DisplayName("still never writes the media base64, because it belongs to the vision channel")
        void noBase64EvenWhenPayloadsAreLogged() {
            interceptor.beforeExecution("files", Map.of("action", "view"), ToolExecutionContext.of("t"));
            interceptor.afterExecution("files", resultWithMedia(), 12);

            assertThat(captured())
                    .as("pre-fix the success branch serialized metadata raw and uncapped")
                    .doesNotContain(IMAGE_BASE64.substring(0, 64));
        }

        @Test
        @DisplayName("replaces the stripped media with the shared summary marker, so nothing looks lost")
        void keepsTheMediaSummaryMarker() {
            interceptor.beforeExecution("files", Map.of("action", "view"), ToolExecutionContext.of("t"));
            interceptor.afterExecution("files", resultWithMedia(), 12);

            assertThat(captured()).contains(ToolMediaMetadata.MEDIA_SUMMARY_KEY);
            assertThat(captured()).contains("mimeType");
        }

        @Test
        @DisplayName("caps the metadata it does log, matching the error branch's existing cap")
        void capsMetadata() {
            String bulky = "x".repeat(4000);
            interceptor.beforeExecution("files", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution("files",
                    ToolExecutionResult.success(Map.of("ok", true), Map.of("note", bulky)), 12);

            assertThat(captured()).doesNotContain(bulky);
            assertThat(captured()).contains("TRUNCATED");
        }

        @Test
        @DisplayName("strips media out of the PARAMETER map too, not only out of the result")
        void stripsMediaFromParameters() {
            Map<String, Object> params = Map.of(
                    "action", "upload",
                    ToolMediaMetadata.MEDIA_KEY,
                    List.of(ToolMediaMetadata.imageDescriptor("image/png", IMAGE_BASE64)));

            interceptor.beforeExecution("files", params, ToolExecutionContext.of("t"));

            assertThat(captured()).doesNotContain(IMAGE_BASE64.substring(0, 64));
            assertThat(captured()).contains(ToolMediaMetadata.MEDIA_SUMMARY_KEY);
        }

        @Test
        @DisplayName("caps a huge parameter map, so debug logging cannot emit an unbounded line")
        void capsParameters() {
            String bulky = "y".repeat(5000);

            interceptor.beforeExecution("files", Map.of("blob", bulky), ToolExecutionContext.of("t"));

            assertThat(captured()).doesNotContain(bulky);
            assertThat(captured()).contains("TRUNCATED");
        }

        @Test
        @DisplayName("the error branch strips media as well - metadata there echoes the rejected value")
        void stripsMediaOnErrorBranch() {
            interceptor.beforeExecution("files", Map.of(), ToolExecutionContext.of("t"));
            interceptor.afterExecution("files",
                    ToolExecutionResult.failure(
                            ToolErrorCode.VALIDATION_ERROR, "too large",
                            Map.of(ToolMediaMetadata.MEDIA_KEY,
                                    List.of(ToolMediaMetadata.imageDescriptor("image/png", IMAGE_BASE64)))),
                    12);

            assertThat(captured()).doesNotContain(IMAGE_BASE64.substring(0, 64));
        }
    }
}
