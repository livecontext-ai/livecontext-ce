package com.apimarketplace.common.web;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
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
import java.util.Map;

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
            // Masked before it enters the MDC: every log line of the request carries it, and a
            // capability token can live in the path (see LogSafePath).
            putIfPresent(MDC_REQUEST_PATH, LogSafePath.of(requestPath));
            chain.doFilter(req, res);
        } finally {
            // On an async request (a StreamingResponseBody download, for one)
            // doFilter returns as soon as the async phase starts, long before the
            // body is written. Logging here would report the time to HAND OFF the
            // response, not to deliver it - a 32 MB download that really takes
            // 2.3s was being logged at 324ms, so the logs disagreed with the
            // latency metrics and quietly under-reported the slow path. Defer to
            // the async completion instead; the sync path is unchanged.
            if (!deferredToAsyncCompletion(httpReq, res, requestPath, requestId, startedAt)) {
                logRequest(httpReq, res, requestPath, requestId, startedAt);
            }
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

    /**
     * Hand the log line to the async completion when one is pending.
     *
     * @return true when logging was deferred, false when the caller should log now
     */
    private static boolean deferredToAsyncCompletion(HttpServletRequest req, ServletResponse res,
                                                     String path, String requestId, long startedAt) {
        if (!req.isAsyncStarted()) {
            return false;
        }
        // The completion runs on a container thread, AFTER this filter's finally
        // block cleared the MDC. Carry a snapshot so the deferred line keeps the
        // org/tenant/user tags the synchronous line has - and so it cannot
        // inherit the tags of whatever request last used that pooled thread.
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();
        try {
            req.getAsyncContext().addListener(new AsyncListener() {
                @Override public void onComplete(AsyncEvent event) {
                    Map<String, String> previous = MDC.getCopyOfContextMap();
                    if (mdcSnapshot != null) {
                        MDC.setContextMap(mdcSnapshot);
                    }
                    try {
                        logRequest(req, res, path, requestId, startedAt);
                    } finally {
                        if (previous != null) {
                            MDC.setContextMap(previous);
                        } else {
                            MDC.clear();
                        }
                    }
                }
                @Override public void onTimeout(AsyncEvent event) {
                    // onComplete still fires afterwards, so do not log twice here.
                }
                @Override public void onError(AsyncEvent event) {
                    // Same: the container completes the request after an error.
                }
                @Override public void onStartAsync(AsyncEvent event) {
                    // Per the Servlet spec a listener is NOT carried into a new
                    // async cycle, so re-register or the line is lost when a
                    // handler starts async again (a DeferredResult chain).
                    try {
                        event.getAsyncContext().addListener(this);
                    } catch (IllegalStateException ignored) {
                        // Nothing to re-arm; the line is simply not deferred again.
                    }
                }
            });
            return true;
        } catch (IllegalStateException alreadyDone) {
            // The async phase finished between doFilter returning and this call.
            // Nothing to defer to, so log normally rather than lose the line.
            return false;
        }
    }

    private static void logRequest(HttpServletRequest req, ServletResponse res, String path,
                                   String requestId, long startedAt) {
        if (path != null && path.startsWith("/actuator")) {
            return;
        }
        long durationMs = Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
        int status = res instanceof HttpServletResponse httpRes ? httpRes.getStatus() : 0;
        // The matched handler pattern (set by Spring MVC during dispatch) names the token
        // segments of this route; LogSafePath masks them, and known token prefixes besides.
        Object pattern = req.getAttribute(LogSafePath.BEST_MATCHING_PATTERN_ATTRIBUTE);
        log.info("HTTP request completed method={} path={} status={} durationMs={} requestId={}",
                req.getMethod(), LogSafePath.of(path, pattern instanceof String p ? p : null),
                status, durationMs, valueOrDash(requestId));
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
