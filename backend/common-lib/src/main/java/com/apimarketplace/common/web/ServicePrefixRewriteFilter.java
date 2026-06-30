package com.apimarketplace.common.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import java.io.IOException;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servlet filter for CE monolith mode that strips service-name prefixes from request URIs.
 *
 * <p>In EE (microservice) mode, the API gateway has {@code rewritePath} rules that strip
 * these prefixes before forwarding to individual services. In CE monolith mode there is
 * no gateway, so this filter performs the equivalent rewrite.</p>
 *
 * <p>Example rewrites:</p>
 * <ul>
 *   <li>{@code /api/auth-service/api/onboarding/status} → {@code /api/onboarding/status}</li>
 *   <li>{@code /api/catalog-service/api/tools} → {@code /api/tools}</li>
 * </ul>
 *
 * <p>Activated by: {@code deployment.mode=monolith}</p>
 */
@Order(-10) // Before MonolithSecurityFilter (Order 0)
public class ServicePrefixRewriteFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ServicePrefixRewriteFilter.class);

    /**
     * Matches /api/{service-name}/api/... and captures the trailing path.
     * Group 1 = service name, Group 2 = the real path after the second /api/.
     */
    private static final Pattern SERVICE_PREFIX_PATTERN =
            Pattern.compile("^/api/([a-z]+-service)/api/(.*)$");

    private static final Set<String> KNOWN_SERVICES = Set.of(
            "auth-service",
            "catalog-service",
            "agent-service",
            "conversation-service",
            "datasource-service",
            "interface-service",
            "trigger-service",
            "publication-service",
            "orchestrator-service",
            "storage-service"
    );

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String uri = httpRequest.getRequestURI();

        String rewrittenPath = rewriteServicePrefixedApiPath(uri);
        if (rewrittenPath == null) {
            rewrittenPath = rewritePublicGatewayPath(uri);
        }

        if (rewrittenPath != null) {
            log.debug("Rewriting {} → {}", uri, rewrittenPath);
            chain.doFilter(new RewrittenPathRequestWrapper(httpRequest, rewrittenPath), response);
            return;
        }

        chain.doFilter(request, response);
    }

    private static String rewriteServicePrefixedApiPath(String uri) {
        Matcher matcher = SERVICE_PREFIX_PATTERN.matcher(uri);
        if (matcher.matches() && KNOWN_SERVICES.contains(matcher.group(1))) {
            return "/api/" + matcher.group(2);
        }
        return null;
    }

    private static String rewritePublicGatewayPath(String uri) {
        if (uri.equals("/widget.js")) {
            return "/api/internal/widget/loader.js";
        }
        if (uri.startsWith("/widget/")) {
            return "/api/internal/widget/" + uri.substring("/widget/".length());
        }
        if (uri.startsWith("/share/")) {
            return "/api/public/share/" + uri.substring("/share/".length());
        }
        if (uri.startsWith("/c/")) {
            return "/api/shared/c/" + uri.substring("/c/".length());
        }
        if (uri.startsWith("/webhook/")) {
            return "/api/internal/webhook/" + uri.substring("/webhook/".length());
        }
        if (uri.startsWith("/chat/")) {
            return "/api/internal/chat/" + uri.substring("/chat/".length());
        }
        if (uri.startsWith("/form/")) {
            return "/api/internal/form/" + uri.substring("/form/".length());
        }
        if (uri.startsWith("/app/public/")) {
            return "/api/internal/app/public/" + uri.substring("/app/public/".length());
        }
        return null;
    }

    private static final class RewrittenPathRequestWrapper extends HttpServletRequestWrapper {
        private final String rewrittenPath;

        private RewrittenPathRequestWrapper(HttpServletRequest request, String rewrittenPath) {
            super(request);
            this.rewrittenPath = rewrittenPath;
        }

        @Override
        public String getRequestURI() {
            return rewrittenPath;
        }

        @Override
        public StringBuffer getRequestURL() {
            StringBuffer url = new StringBuffer();
            url.append(getScheme()).append("://").append(getServerName());
            int port = getServerPort();
            if (("http".equals(getScheme()) && port != 80)
                    || ("https".equals(getScheme()) && port != 443)) {
                url.append(':').append(port);
            }
            url.append(rewrittenPath);
            return url;
        }

        @Override
        public String getServletPath() {
            return rewrittenPath;
        }

        @Override
        public String getPathInfo() {
            return null;
        }
    }
}
