package com.apimarketplace.auth.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Stamps strict response headers on every ce-link endpoint:
 *
 * <ul>
 *   <li>{@code Content-Security-Policy} - locks down what a malicious response
 *       body could do if the API is somehow rendered as HTML (defense-in-depth).</li>
 *   <li>{@code X-Content-Type-Options: nosniff} - keeps the browser from
 *       guessing JSON/text content as scriptable.</li>
 *   <li>{@code Cache-Control: no-store} - register/revoke responses MUST NOT
 *       be cached by intermediate proxies (token-bearing).</li>
 *   <li>{@code Referrer-Policy: no-referrer} - recovery URLs leak the token
 *       segment in cross-site referrers otherwise.</li>
 * </ul>
 *
 * <p>Runs before {@link CeLinkConstantTimeFilter} so the pad measures the
 * combined response shape (headers stamped → small but constant overhead).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 200)
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
public class CeLinkSecurityHeadersFilter extends OncePerRequestFilter {

    private static final String CSP =
            "default-src 'none'; frame-ancestors 'none'; base-uri 'none'";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/ce-link/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Set headers BEFORE doFilter so a streaming response still gets them.
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
        chain.doFilter(request, response);
    }
}
