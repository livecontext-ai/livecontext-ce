package com.apimarketplace.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;

import java.io.IOException;

/**
 * PR25.2 - populates MDC with org/tenant/user context at request entry so every
 * log line in this request scope can be tagged via Logback pattern
 * (e.g. {@code %X{org:-personal} %X{tenant:--} %X{user:--}}).
 *
 * <p>Closes the cross-service observability gap flagged by the PR24 scope audit:
 * previously only {@code BillingMDC} (auth-service Stripe paths) wrote to MDC, so
 * cross-service debugging of org-context bugs required reading thread-name +
 * X-User-ID/X-Organization-ID headers manually from log lines.</p>
 *
 * <p>Reads from the same headers as {@link TenantResolver} so the MDC tag is
 * authoritative for the SAME context the request handlers see. Cleared in a
 * {@code finally} block so the values do not leak into the next request on the
 * same thread (Tomcat's pooled-thread model). This matters because Logback's
 * MDC is a {@link ThreadLocal} - a stale value would appear on a wholly
 * unrelated request's logs.</p>
 *
 * <p>Order: very early in the filter chain so any downstream filter logging
 * already sees the context. Lower than {@link Ordered#HIGHEST_PRECEDENCE} +
 * a small offset so a service can insert a request-ID filter before us if
 * needed.</p>
 */
public class MdcContextFilter implements Filter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(MdcContextFilter.class);

    public static final String MDC_USER = "user";
    public static final String MDC_TENANT = "tenant";
    public static final String MDC_ORG = "org";
    public static final String MDC_ORG_ROLE = "orgRole";
    public static final String MDC_REQUEST_PATH = "requestPath";
    public static final String MDC_REQUEST_ID = "requestId";

    /** Run very early so all downstream logging sees the context. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 50;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest httpReq)) {
            chain.doFilter(req, res);
            return;
        }

        String userId = httpReq.getHeader("X-User-ID");
        String organizationId = httpReq.getHeader("X-Organization-ID");
        String organizationRole = httpReq.getHeader("X-Organization-Role");
        String requestId = httpReq.getHeader("X-Request-Id");
        String requestPath = httpReq.getRequestURI();
        long startedAt = System.nanoTime();

        // tenant in this codebase is always the same string as user (the user
        // is the tenant). Keep them as distinct MDC keys so consumers can later
        // diverge them without touching log patterns.
        try {
            putIfPresent(MDC_USER, userId);
            putIfPresent(MDC_TENANT, userId);
            putIfPresent(MDC_ORG, organizationId);
            putIfPresent(MDC_ORG_ROLE, organizationRole);
            putIfPresent(MDC_REQUEST_ID, requestId);
            putIfPresent(MDC_REQUEST_PATH, requestPath);
            chain.doFilter(req, res);
        } finally {
            logRequest(httpReq, res, requestPath, requestId, startedAt);
            // ALWAYS clear in a finally block. Tomcat pools threads - a leaked
            // MDC tag would appear on every subsequent request handled by this
            // thread, including health checks and metrics scrapes.
            MDC.remove(MDC_USER);
            MDC.remove(MDC_TENANT);
            MDC.remove(MDC_ORG);
            MDC.remove(MDC_ORG_ROLE);
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_REQUEST_PATH);
        }
    }

    private static void logRequest(HttpServletRequest req, ServletResponse res, String path,
                                   String requestId, long startedAt) {
        if (path != null && path.startsWith("/actuator")) {
            return;
        }
        long durationMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
        int status = res instanceof HttpServletResponse httpRes ? httpRes.getStatus() : 0;
        log.info("HTTP request completed method={} path={} status={} durationMs={} requestId={}",
                req.getMethod(), path, status, durationMs, valueOrDash(requestId));
    }

    private static void putIfPresent(String key, String value) {
        if (value != null && !value.isBlank()) {
            MDC.put(key, value);
        }
    }

    private static String valueOrDash(String value) {
        return value != null && !value.isBlank() ? value : "-";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
