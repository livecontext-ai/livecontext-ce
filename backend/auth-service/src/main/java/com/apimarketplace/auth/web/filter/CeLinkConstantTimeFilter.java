package com.apimarketplace.auth.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Pads the response of ce-link state-changing endpoints to a fixed budget so
 * the response time can't be used as an oracle to distinguish between
 * 201/409-ALREADY-BOUND/409-CROSS-USER paths (doc §1 #16 timing-side-channel).
 *
 * <p>Covers POST /api/ce-link/register, DELETE /api/ce-link/{installId}, and
 * POST /api/ce-link/{installId}/heartbeat. The /squat-recovery/{token} endpoint
 * is NOT covered - the consume path already returns the same 204/404 shape and
 * Redis GETDEL latency is bounded.
 *
 * <p>The filter measures elapsed time, computes how much pad is needed to hit
 * {@code cloud-link.constant-time.budget-ms}, then sleeps. Sleep uses
 * {@link Thread#sleep} (not LockSupport.parkNanos) because the auth-service is
 * Servlet stack - blocking the worker thread is the design.
 *
 * <p>If the underlying handler runs LONGER than the budget (DB hiccup,
 * filesystem stall), we log at WARN and ship the response immediately. Better
 * to leak a tail-latency outlier than to compound it.
 *
 * <p>Filter order: runs AFTER the global Spring Security chain (so the request
 * is already authenticated and Servlet-wired). Set high {@code @Order} so it
 * wraps the controller close to the dispatch boundary.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 100)
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkConstantTimeFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CeLinkConstantTimeFilter.class);

    private final long budgetMs;
    private final boolean enabled;

    public CeLinkConstantTimeFilter(
            @Value("${cloud-link.constant-time.budget-ms:400}") long budgetMs,
            @Value("${cloud-link.constant-time.enabled:true}") boolean enabled) {
        this.budgetMs = budgetMs;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        String path = request.getRequestURI();
        String method = request.getMethod();
        // Apply only to the state-changing ce-link endpoints. /mine GET is
        // intentionally NOT padded - it's a read with no security-sensitive branch.
        if (!path.startsWith("/api/ce-link/")) return true;
        if (path.startsWith("/api/ce-link/squat-recovery/")) return true;
        if (path.equals("/api/ce-link/mine")) return true;
        // /register (POST), /{id} (DELETE), /{id}/heartbeat (POST) all qualify.
        return !("POST".equals(method) || "DELETE".equals(method));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            long pad = budgetMs - elapsedMs;
            if (pad > 0) {
                try {
                    Thread.sleep(pad);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            } else {
                // Handler exceeded the budget - log so SRE notices, but ship anyway.
                log.warn("CeLinkConstantTimeFilter: handler exceeded budget on {} {} ({}ms > {}ms)",
                        request.getMethod(), request.getRequestURI(), elapsedMs, budgetMs);
            }
        }
    }
}
