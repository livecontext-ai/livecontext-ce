package com.apimarketplace.common.web;

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
import org.slf4j.MDC;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
