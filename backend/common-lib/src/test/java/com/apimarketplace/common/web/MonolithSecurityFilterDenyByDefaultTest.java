package com.apimarketplace.common.web;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-036: without {@link MonolithSecurityFilter#withDenyByDefault}, a request with no usable
 * credential (no bearer token, no API key, no share token, no loopback trust) on a NON-public path
 * was passed through to the controller, which decided on its own whether auth was required.
 * {@code withDenyByDefault(true)} closes that gap by refusing such a request with 401 in the
 * filter itself, before any controller runs; a public path is untouched either way.
 */
@DisplayName("MonolithSecurityFilter deny-by-default (LC-036)")
class MonolithSecurityFilterDenyByDefaultTest {

    private static final class Result {
        int status;
        HttpServletRequest seen;
    }

    private static Result call(MonolithSecurityFilter filter, String path, String authHeader) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("203.0.113.10"); // never loopback
        if (authHeader != null) {
            request.addHeader("Authorization", authHeader);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, jakarta.servlet.ServletResponse res) {
                captured.set(req);
            }
        });
        Result r = new Result();
        r.status = response.getStatus();
        r.seen = (HttpServletRequest) captured.get();
        return r;
    }

    @Test
    @DisplayName("pre-fix / kill-switch default (off): a non-public path with no credential still reaches the controller")
    void offByDefaultPassesThrough() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        Result r = call(filter, "/api/workflows", null);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.seen).isNotNull();
    }

    @Test
    @DisplayName("armed: a non-public path with no Authorization header is refused before the controller runs")
    void armedRefusesNoCredential() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of()).withDenyByDefault(true);
        Result r = call(filter, "/api/workflows", null);
        assertThat(r.status).isEqualTo(401);
        assertThat(r.seen).isNull();
    }

    @Test
    @DisplayName("armed: a non-public path with a malformed/empty bearer token is refused, not passed through")
    void armedRefusesEmptyBearerToken() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of()).withDenyByDefault(true);
        Result r = call(filter, "/api/workflows", "Bearer ");
        assertThat(r.status).isEqualTo(401);
        assertThat(r.seen).isNull();
    }

    @Test
    @DisplayName("armed: a non-public path with a non-bearer Authorization scheme is refused")
    void armedRefusesNonBearerScheme() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of()).withDenyByDefault(true);
        Result r = call(filter, "/api/workflows", "Basic dXNlcjpwYXNz");
        assertThat(r.status).isEqualTo(401);
        assertThat(r.seen).isNull();
    }

    @Test
    @DisplayName("armed: an explicitly public path with no credential is unaffected")
    void armedStillAllowsPublicPath() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of()).withDenyByDefault(true);
        Result r = call(filter, "/health", null);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.seen).isNotNull();
    }

    @Test
    @DisplayName("armed: a custom public-path prefix passed to the constructor is unaffected")
    void armedStillAllowsConfiguredPublicPrefix() throws Exception {
        MonolithSecurityFilter filter =
                new MonolithSecurityFilter(() -> null, List.of("/api/catalog/public/")).withDenyByDefault(true);
        Result r = call(filter, "/api/catalog/public/apis", null);
        assertThat(r.status).isEqualTo(200);
        assertThat(r.seen).isNotNull();
    }
}
