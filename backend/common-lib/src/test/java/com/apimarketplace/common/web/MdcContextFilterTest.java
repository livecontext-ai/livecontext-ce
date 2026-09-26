package com.apimarketplace.common.web;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("MdcContextFilter - PR25.2 cross-service org tagging")
class MdcContextFilterTest {

    private MdcContextFilter filter;

    @BeforeEach
    void setUp() {
        filter = new MdcContextFilter();
    }

    @AfterEach
    void cleanup() {
        // Defensive: never leak MDC state between tests (would break log-tag
        // assertions in unrelated suites on the same thread).
        MDC.clear();
    }

    private HttpServletRequest mockRequest(Map<String, String> headers, String uri) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(uri);
        for (Map.Entry<String, String> e : headers.entrySet()) {
            when(req.getHeader(e.getKey())).thenReturn(e.getValue());
        }
        return req;
    }

    /**
     * Captures the MDC state DURING chain.doFilter (since the filter clears in
     * a finally block, post-filter inspection would see empty MDC).
     */
    private FilterChain capturingChain(AtomicReference<Map<String, String>> captured) {
        return (ServletRequest r, ServletResponse s) -> {
            Map<String, String> snapshot = new HashMap<>();
            // Read each known key directly - MDC.getCopyOfContextMap() can be null
            // when no key was set, which trips up the test framework on some builds.
            for (String key : new String[]{MdcContextFilter.MDC_USER, MdcContextFilter.MDC_TENANT,
                                            MdcContextFilter.MDC_ORG, MdcContextFilter.MDC_ORG_ROLE,
                                            MdcContextFilter.MDC_REQUEST_ID,
                                            MdcContextFilter.MDC_REQUEST_PATH}) {
                String v = MDC.get(key);
                if (v != null) snapshot.put(key, v);
            }
            captured.set(snapshot);
        };
    }

    @Test
    @DisplayName("Populates MDC with user/tenant/org/orgRole/path from request headers")
    void populatesMdcFromHeaders() throws IOException, ServletException {
        HttpServletRequest req = mockRequest(Map.of(
                "X-User-ID", "user-42",
                "X-Organization-ID", "org-acme",
                "X-Organization-Role", "MEMBER",
                "X-Request-Id", "req-123"
        ), "/api/workflows/123");
        HttpServletResponse res = mock(HttpServletResponse.class);
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();

        filter.doFilter(req, res, capturingChain(captured));

        assertThat(captured.get()).containsEntry(MdcContextFilter.MDC_USER, "user-42");
        assertThat(captured.get()).containsEntry(MdcContextFilter.MDC_TENANT, "user-42");
        assertThat(captured.get()).containsEntry(MdcContextFilter.MDC_ORG, "org-acme");
        assertThat(captured.get()).containsEntry(MdcContextFilter.MDC_ORG_ROLE, "MEMBER");
        assertThat(captured.get()).containsEntry(MdcContextFilter.MDC_REQUEST_ID, "req-123");
        assertThat(captured.get()).containsEntry(MdcContextFilter.MDC_REQUEST_PATH, "/api/workflows/123");
    }

    @Test
    @DisplayName("MDC keys are cleared AFTER doFilter - closes the Tomcat-pool leak")
    void clearsMdcAfterRequest() throws IOException, ServletException {
        // Load-bearing invariant: a pooled thread MUST NOT carry the previous
        // request's org tag into the next request. Without the finally-block
        // clear, every log line on the next request would be falsely tagged.
        HttpServletRequest req = mockRequest(Map.of(
                "X-User-ID", "user-42",
                "X-Organization-ID", "org-acme",
                "X-Request-Id", "req-123"
        ), "/api/x");
        HttpServletResponse res = mock(HttpServletResponse.class);

        filter.doFilter(req, res, (ServletRequest r, ServletResponse s) -> {});

        assertThat(MDC.get(MdcContextFilter.MDC_USER)).isNull();
        assertThat(MDC.get(MdcContextFilter.MDC_ORG)).isNull();
        assertThat(MDC.get(MdcContextFilter.MDC_TENANT)).isNull();
        assertThat(MDC.get(MdcContextFilter.MDC_ORG_ROLE)).isNull();
        assertThat(MDC.get(MdcContextFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(MdcContextFilter.MDC_REQUEST_PATH)).isNull();
    }

    @Test
    @DisplayName("MDC keys are cleared even when the chain throws - exception safety")
    void clearsMdcOnException() {
        HttpServletRequest req = mockRequest(Map.of("X-User-ID", "user-42"), "/api/x");
        HttpServletResponse res = mock(HttpServletResponse.class);

        // The exception is expected; we only care that MDC is clean afterwards.
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
                filter.doFilter(req, res, (ServletRequest r, ServletResponse s) -> {
                    throw new IOException("simulated downstream failure");
                }));

        assertThat(MDC.get(MdcContextFilter.MDC_USER)).isNull();
        assertThat(MDC.get(MdcContextFilter.MDC_TENANT)).isNull();
        assertThat(MDC.get(MdcContextFilter.MDC_REQUEST_ID)).isNull();
    }

    @Test
    @DisplayName("Skips MDC keys for missing/blank headers - personal scope path")
    void personalScopeSkipsOrgKeys() throws IOException, ServletException {
        HttpServletRequest req = mockRequest(Map.of("X-User-ID", "user-personal"), "/api/me");
        when(req.getHeader("X-Organization-ID")).thenReturn(null);
        when(req.getHeader("X-Organization-Role")).thenReturn(""); // blank treated as absent
        HttpServletResponse res = mock(HttpServletResponse.class);
        AtomicReference<Map<String, String>> captured = new AtomicReference<>();

        filter.doFilter(req, res, capturingChain(captured));

        assertThat(captured.get()).containsEntry(MdcContextFilter.MDC_USER, "user-personal");
        assertThat(captured.get()).doesNotContainKey(MdcContextFilter.MDC_ORG);
        assertThat(captured.get()).doesNotContainKey(MdcContextFilter.MDC_ORG_ROLE);
    }

    @Test
    @DisplayName("Non-HTTP requests pass through without MDC interaction")
    void nonHttpRequestPassThrough() throws IOException, ServletException {
        // Defensive: the filter is registered on the servlet container which
        // only routes HTTP requests, but the Filter contract allows other types.
        ServletRequest req = mock(ServletRequest.class);
        ServletResponse res = mock(ServletResponse.class);
        AtomicReference<Boolean> chainInvoked = new AtomicReference<>(false);

        filter.doFilter(req, res, (ServletRequest r, ServletResponse s) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isTrue();
        assertThat(MDC.get(MdcContextFilter.MDC_USER)).isNull();
    }

    private ListAppender<ILoggingEvent> appender;

    /** Captures what the filter actually logged, so assertions are on the OUTPUT. */
    private ListAppender<ILoggingEvent> attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(MdcContextFilter.class)).addAppender(appender);
        return appender;
    }

    @AfterEach
    void detachAppender() {
        // The logger is a JVM-wide singleton: leaving appenders attached makes
        // every later test in this JVM accumulate another copy of every line.
        if (appender != null) {
            ((Logger) LoggerFactory.getLogger(MdcContextFilter.class)).detachAppender(appender);
            appender.stop();
            appender = null;
        }
    }

    private static final String TOKEN = "wh_FAKEcapabilityTokenForTheLogTest";

    @Test
    @DisplayName("Regression 2026-09-25: a token path variable named by the matched pattern is masked in the access line")
    void accessLineMasksTokenNamedByPattern() throws Exception {
        ListAppender<ILoggingEvent> logged = attachAppender();
        HttpServletRequest req = mockRequest(Map.of(), "/api/internal/trigger/webhooks/by-token/" + TOKEN);
        when(req.getMethod()).thenReturn("GET");
        when(req.getAttribute(LogSafePath.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/internal/trigger/webhooks/by-token/{token}");

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});

        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.get(0).getFormattedMessage())
                .contains("path=/api/internal/trigger/webhooks/by-token/{token} ")
                .doesNotContain(TOKEN);
    }

    @Test
    @DisplayName("Regression 2026-09-25: the path enters the MDC already masked, so no line of the request carries the token")
    void mdcPathIsMaskedDuringTheRequest() throws Exception {
        HttpServletRequest req = mockRequest(Map.of(), "/webhook/" + TOKEN);
        when(req.getMethod()).thenReturn("POST");
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();

        filter.doFilter(req, mock(HttpServletResponse.class),
                (ServletRequest r, ServletResponse s) -> seen.set(MDC.get(MdcContextFilter.MDC_REQUEST_PATH)));

        assertThat(seen.get()).isEqualTo("/webhook/{token}");
    }

    @Test
    @DisplayName("A route whose variables are ids, not tokens, is logged unchanged")
    void idPathUnchanged() throws Exception {
        ListAppender<ILoggingEvent> logged = attachAppender();
        HttpServletRequest req = mockRequest(Map.of(), "/api/workflows/1234/runs/5678");
        when(req.getMethod()).thenReturn("GET");
        when(req.getAttribute(LogSafePath.BEST_MATCHING_PATTERN_ATTRIBUTE))
                .thenReturn("/api/workflows/{workflowId}/runs/{runId}");

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});

        assertThat(logged.list.get(0).getFormattedMessage()).contains("path=/api/workflows/1234/runs/5678 ");
    }

    @Test
    @DisplayName("Nothing is logged at the async hand-off, and the completion line carries the real duration")
    void asyncLineIsEmittedAtCompletionNotAtHandoff() throws Exception {
        ListAppender<ILoggingEvent> logged = attachAppender();
        HttpServletRequest req = mockRequest(Map.of(), "/api/catalog/public/bundles/latest");
        when(req.getMethod()).thenReturn("GET");
        when(req.isAsyncStarted()).thenReturn(true);
        AsyncContext async = mock(AsyncContext.class);
        when(req.getAsyncContext()).thenReturn(async);

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});

        assertThat(logged.list)
                .as("logging at hand-off is what reported a 2.3s download as 324ms")
                .isEmpty();

        ArgumentCaptor<AsyncListener> captor = ArgumentCaptor.forClass(AsyncListener.class);
        verify(async).addListener(captor.capture());
        Thread.sleep(15);
        captor.getValue().onComplete(new AsyncEvent(async));

        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.get(0).getFormattedMessage()).contains("HTTP request completed");
    }

    @Test
    @DisplayName("The deferred line keeps the request's own MDC tags, even though the MDC was cleared before it ran")
    void deferredLineKeepsMdcTags() throws Exception {
        ListAppender<ILoggingEvent> logged = attachAppender();
        HttpServletRequest req = mockRequest(Map.of("X-User-ID", "42", "X-Tenant-ID", "7"),
                "/api/catalog/public/bundles/latest");
        when(req.getMethod()).thenReturn("GET");
        when(req.isAsyncStarted()).thenReturn(true);
        AsyncContext async = mock(AsyncContext.class);
        when(req.getAsyncContext()).thenReturn(async);

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});
        ArgumentCaptor<AsyncListener> captor = ArgumentCaptor.forClass(AsyncListener.class);
        verify(async).addListener(captor.capture());
        // The completion runs on a container thread long after the filter cleared
        // the MDC; without a snapshot the line would lose every tag this filter
        // exists to attach, or inherit another request's.
        MDC.clear();
        captor.getValue().onComplete(new AsyncEvent(async));

        assertThat(logged.list).hasSize(1);
        assertThat(logged.list.get(0).getMDCPropertyMap())
                .containsEntry(MdcContextFilter.MDC_USER, "42")
                .containsEntry(MdcContextFilter.MDC_REQUEST_PATH, "/api/catalog/public/bundles/latest");
        assertThat(MDC.getCopyOfContextMap())
                .as("the listener must not leave tags behind on a pooled thread")
                .isNullOrEmpty();
    }

    @Test
    @DisplayName("An async context that finished before we could listen still emits the line rather than losing it")
    void asyncAlreadyCompletedStillLogs() throws Exception {
        ListAppender<ILoggingEvent> logged = attachAppender();
        HttpServletRequest req = mockRequest(Map.of(), "/api/catalog/public/bundles/latest");
        when(req.getMethod()).thenReturn("GET");
        when(req.isAsyncStarted()).thenReturn(true);
        AsyncContext async = mock(AsyncContext.class);
        when(req.getAsyncContext()).thenReturn(async);
        doThrow(new IllegalStateException("async already complete")).when(async).addListener(any());

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});

        assertThat(logged.list)
                .as("falling back must log, not drop the line")
                .hasSize(1);
    }

    @Test
    @DisplayName("The listener restores whatever MDC the completion thread was already carrying")
    void deferredLineRestoresTheThreadsPreviousTags() throws Exception {
        // The branch that stops this request's tags leaking onto a pooled thread
        // that is already serving someone else.
        attachAppender();
        HttpServletRequest req = mockRequest(Map.of("X-User-ID", "42"), "/api/x");
        when(req.getMethod()).thenReturn("GET");
        when(req.isAsyncStarted()).thenReturn(true);
        AsyncContext async = mock(AsyncContext.class);
        when(req.getAsyncContext()).thenReturn(async);

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});
        ArgumentCaptor<AsyncListener> captor = ArgumentCaptor.forClass(AsyncListener.class);
        verify(async).addListener(captor.capture());

        MDC.put(MdcContextFilter.MDC_USER, "someone-else");
        captor.getValue().onComplete(new AsyncEvent(async));

        assertThat(MDC.get(MdcContextFilter.MDC_USER))
                .as("the completion must hand the thread back exactly as it found it")
                .isEqualTo("someone-else");
    }

    @Test
    @DisplayName("A re-started async cycle re-registers the listener, so the line is not lost")
    void reStartedAsyncReRegisters() throws Exception {
        HttpServletRequest req = mockRequest(Map.of(), "/api/x");
        when(req.getMethod()).thenReturn("GET");
        when(req.isAsyncStarted()).thenReturn(true);
        AsyncContext first = mock(AsyncContext.class);
        when(req.getAsyncContext()).thenReturn(first);

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});
        ArgumentCaptor<AsyncListener> captor = ArgumentCaptor.forClass(AsyncListener.class);
        verify(first).addListener(captor.capture());

        AsyncContext restarted = mock(AsyncContext.class);
        captor.getValue().onStartAsync(new AsyncEvent(restarted));

        // The spec does not carry a listener into a new cycle; without this the
        // line silently disappears for a DeferredResult chain.
        verify(restarted).addListener(captor.getValue());
    }

    @Test
    @DisplayName("Timeout and error stay silent: the container always follows them with onComplete")
    void timeoutAndErrorDoNotLogTwice() throws Exception {
        ListAppender<ILoggingEvent> logged = attachAppender();
        HttpServletRequest req = mockRequest(Map.of(), "/api/x");
        when(req.getMethod()).thenReturn("GET");
        when(req.isAsyncStarted()).thenReturn(true);
        AsyncContext async = mock(AsyncContext.class);
        when(req.getAsyncContext()).thenReturn(async);

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});
        ArgumentCaptor<AsyncListener> captor = ArgumentCaptor.forClass(AsyncListener.class);
        verify(async).addListener(captor.capture());

        captor.getValue().onTimeout(new AsyncEvent(async));
        captor.getValue().onError(new AsyncEvent(async));
        captor.getValue().onComplete(new AsyncEvent(async));

        assertThat(logged.list).hasSize(1);
    }

    @Test
    @DisplayName("A plain request is unaffected: no async context is touched")
    void syncRequestDoesNotTouchAsync() throws Exception {
        HttpServletRequest req = mockRequest(Map.of(), "/api/catalog/public/bundles/latest");
        when(req.getMethod()).thenReturn("GET");
        when(req.isAsyncStarted()).thenReturn(false);

        filter.doFilter(req, mock(HttpServletResponse.class), (ServletRequest r, ServletResponse s) -> {});

        verify(req, never()).getAsyncContext();
    }
}
