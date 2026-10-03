package com.apimarketplace.common.web;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-032: loopback trust requires this boot's in-process secret, not just a 127.0.0.1 peer.
 * The code executor runs user code in the CE container and can open http://127.0.0.1:8080 with
 * no browser or proxy marker; without the secret it must get the external treatment.
 */
@DisplayName("MonolithSecurityFilter in-process secret (LC-032)")
class MonolithSecurityFilterInProcessSecretTest {

    private static final class Result {
        int status;
        HttpServletRequest seen;
    }

    private static Result call(MonolithSecurityFilter filter, String path, boolean withSecret) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-User-ID", "42");
        request.addHeader("X-User-Roles", "ADMIN");
        if (withSecret) {
            request.addHeader(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER, MonolithSecurityFilter.inProcessSecret());
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
    @DisplayName("positive control: an in-process call WITH the secret keeps its identity headers")
    void inProcessCallWithSecretKeepsTrust() throws Exception {
        Result r = call(new MonolithSecurityFilter(() -> null, List.of()), "/api/storage/quota", true);
        assertThat(r.seen).isNotNull();
        assertThat(r.seen.getHeader("X-User-ID")).isEqualTo("42");
        assertThat(r.seen.getHeader("X-User-Roles")).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("the secret is hidden from everything downstream of the filter")
    void secretIsHiddenDownstream() throws Exception {
        Result r = call(new MonolithSecurityFilter(() -> null, List.of()), "/api/storage/quota", true);
        assertThat(r.seen.getHeader(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER)).isNull();
        assertThat(Collections.list(r.seen.getHeaders(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER))).isEmpty();
        assertThat(Collections.list(r.seen.getHeaderNames()))
                .noneMatch(n -> n.equalsIgnoreCase(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER));
    }

    @Test
    @DisplayName("code in the container on 127.0.0.1 WITHOUT the secret: forged identity is stripped")
    void loopbackWithoutSecretIsExternal() throws Exception {
        Result r = call(new MonolithSecurityFilter(() -> null, List.of()), "/api/storage/quota", false);
        assertThat(r.seen).isNotNull();
        assertThat(r.seen.getHeader("X-User-ID")).isNull();
        assertThat(r.seen.getHeader("X-User-Roles")).isNull();
    }

    @Test
    @DisplayName("code in the container on 127.0.0.1 WITHOUT the secret cannot reach /api/internal (404)")
    void loopbackWithoutSecretCannotReachInternal() throws Exception {
        Result r = call(new MonolithSecurityFilter(() -> null, List.of()), "/api/internal/credentials/all", false);
        assertThat(r.status).isEqualTo(404);
        assertThat(r.seen).isNull();
    }

    @Test
    @DisplayName("a wrong secret is refused like a missing one")
    void wrongSecretRefused() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/credentials/all");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-User-ID", "42");
        request.addHeader(MonolithSecurityFilter.IN_PROCESS_SECRET_HEADER, "guess");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new MonolithSecurityFilter(() -> null, List.of()).doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(404);
    }

    @Test
    @DisplayName("kill switch auth.in-process-secret.required=false restores peer-address trust")
    void killSwitchRestoresPeerTrust() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of()).withInProcessSecretRequired(false);
        Result r = call(filter, "/api/storage/quota", false);
        assertThat(r.seen.getHeader("X-User-ID")).isEqualTo("42");
    }
}
