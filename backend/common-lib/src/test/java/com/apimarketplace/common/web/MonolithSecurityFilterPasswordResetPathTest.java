package com.apimarketplace.common.web;

import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the CE auth gate for the two password-reset endpoints.
 *
 * <p>This filter is the ONLY thing that makes them reachable in the monolith:
 * {@code MonolithSecurityConfig} is {@code anyRequest().permitAll()} and
 * {@code MonolithApplication} excludes auth-service's own {@code SecurityConfig}
 * from its component scan, so its {@code permitAll} entries never run. CE is
 * also the only edition where the reset flow exists at all
 * ({@code auth.mode=embedded}). Delete the two allow-list entries and every
 * reset attempt 401s: someone locked out of their own install has no route back
 * in, and before this test nothing in the build noticed (measured: removing both
 * lines left all 113 MonolithSecurityFilter tests green).
 *
 * <p><b>The probe has to carry an INVALID BEARER, and that is the whole trick of
 * testing this list.</b> Measured while writing this file: a request with NO
 * Authorization header reaches the controller on a GATED path too (status 200),
 * because this filter only rejects a bearer it cannot parse. So "an anonymous
 * request gets through" proves nothing about the allow-list, and a first version
 * of these tests asserted exactly that and survived the deletion. An invalid
 * bearer is what separates the two sides: public means anonymous fallback,
 * non-public means 401.
 *
 * <p>Two shapes of regression are covered: dropping an entry, and widening it to
 * a prefix, which the filter's own comment warns against because
 * {@code /api/auth/} carries endpoints that must stay gated.
 */
@DisplayName("MonolithSecurityFilter - password reset path allow-list")
class MonolithSecurityFilterPasswordResetPathTest {

    private static final String FORGOT_PATH = "/api/auth/forgot-password";
    private static final String RESET_PATH = "/api/auth/reset-password";

    /** Same package, and deliberately NOT public: widening the list to a prefix would open it. */
    private static final String GATED_SIBLING_PATH = "/api/auth/change-password";

    /** The only probe that tells the public side from the gated one. */
    private static final String INVALID_BEARER = "Bearer not-a-valid-jwt";

    @Test
    @DisplayName("forgot-password falls back to anonymous on an unparseable bearer (public route)")
    void forgotPasswordIsPublic() throws Exception {
        assertThat(statusFor(FORGOT_PATH, INVALID_BEARER)).isNotEqualTo(401);
        assertThat(reachedController(FORGOT_PATH, INVALID_BEARER))
                .as("someone who cannot authenticate must still reach the form's endpoint")
                .isTrue();
    }

    @Test
    @DisplayName("reset-password falls back to anonymous on an unparseable bearer (public route)")
    void resetPasswordIsPublic() throws Exception {
        assertThat(statusFor(RESET_PATH, INVALID_BEARER)).isNotEqualTo(401);
        assertThat(reachedController(RESET_PATH, INVALID_BEARER))
                .as("the reset token is the authorisation; a stale session must not block it")
                .isTrue();
    }

    @Test
    @DisplayName("the gate is NOT widened: a sibling under /api/auth/ still 401s, so the entries "
            + "cannot be 'simplified' into a prefix match")
    void siblingUnderTheSamePrefixStaysGated() throws Exception {
        assertThat(statusFor(GATED_SIBLING_PATH, INVALID_BEARER)).isEqualTo(401);
        assertThat(reachedController(GATED_SIBLING_PATH, INVALID_BEARER)).isFalse();
    }

    @Test
    @DisplayName("a near-miss path is not public either, so the match is exact and not a substring")
    void nearMissPathIsNotPublic() throws Exception {
        assertThat(statusFor("/api/auth/forgot-password-extra", INVALID_BEARER)).isEqualTo(401);
        assertThat(statusFor("/api/auth/reset-password/anything", INVALID_BEARER)).isEqualTo(401);
    }

    @Test
    @DisplayName("WHY THE BEARER MATTERS: with no Authorization header at all, even a GATED path "
            + "reaches the controller, so an anonymous probe cannot pin this list")
    void anonymousRequestsAreNotADiscriminatingProbe() throws Exception {
        // Kept as a test rather than a comment because it is the reason the
        // assertions above are shaped the way they are. If this ever starts
        // failing, the filter began rejecting anonymous requests and the probes
        // above could be simplified; until then, simplifying them makes them
        // pass on a deleted allow-list.
        assertThat(reachedController(GATED_SIBLING_PATH, null))
                .as("a gated path with no credentials is NOT blocked by this filter")
                .isTrue();
        assertThat(reachedController(FORGOT_PATH, null)).isTrue();
    }

    @Test
    @DisplayName("an external caller cannot self-identify on either path: forged trusted identity "
            + "headers are stripped before the controller sees them")
    void forgedIdentityHeadersAreStripped() throws Exception {
        for (String path : List.of(FORGOT_PATH, RESET_PATH)) {
            MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr("203.0.113.10");
            request.addHeader("X-User-ID", "999");
            request.addHeader("X-User-Roles", "ADMIN");
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicReference<ServletRequest> captured = new AtomicReference<>();

            filter.doFilter(request, response, capturingChain(captured));

            assertThat(captured.get()).as("%s must reach the controller", path).isNotNull();
            var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
            // The endpoints need no identity. Downstream must never see a forged
            // one: reset-password picks the account from the TOKEN, never from a
            // header, and forgot-password must not be able to name a victim.
            assertThat(forwarded.getHeader("X-User-ID")).isNull();
            assertThat(forwarded.getHeader("X-User-Roles")).isNull();
        }
    }

    private static int statusFor(String path, String authorization) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        run(path, authorization, response, new AtomicReference<>());
        return response.getStatus();
    }

    private static boolean reachedController(String path, String authorization) throws Exception {
        AtomicReference<ServletRequest> captured = new AtomicReference<>();
        run(path, authorization, new MockHttpServletResponse(), captured);
        return captured.get() != null;
    }

    private static void run(String path, String authorization, MockHttpServletResponse response,
                            AtomicReference<ServletRequest> captured) throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr("203.0.113.10");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        filter.doFilter(request, response, capturingChain(captured));
    }

    private static MockFilterChain capturingChain(AtomicReference<ServletRequest> captured) {
        return new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, jakarta.servlet.ServletResponse response) {
                captured.set(request);
            }
        };
    }
}
