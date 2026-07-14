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
 * Pins the CE auth gate for the anonymous avatar serve. The filter's public-path list is
 * what distinguishes the two sibling routes when a request carries an INVALID bearer
 * (public → anonymous fallback, non-public → 401): {@code /api/files/avatar/} must stay
 * on the public side (marketplace cards / shared apps / embeds load avatars via a plain
 * {@code <img>}), while {@code /api/files/by-id/} stays gated. A refactor that drops the
 * allow-list entry breaks every anonymous avatar; one that widens it to all of
 * {@code /api/files} would soften the by-id gate.
 *
 * <p>Also pins that an EXTERNAL caller cannot self-identify on the avatar path: forged
 * trusted identity headers are stripped before the controller (the endpoint needs no
 * identity, but downstream must never see a forged one).
 */
@DisplayName("MonolithSecurityFilter - anonymous avatar path allow-list")
class MonolithSecurityFilterAvatarPublicPathTest {

    private static final String AVATAR_PATH =
            "/api/files/avatar/123e4567-e89b-12d3-a456-426614174000";
    private static final String BY_ID_SIBLING_PATH =
            "/api/files/by-id/123e4567-e89b-12d3-a456-426614174000/raw";

    @Test
    @DisplayName("avatar path with an invalid bearer falls back to ANONYMOUS pass-through (public route)")
    void avatarPathInvalidBearerFallsBackToAnonymous() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", AVATAR_PATH);
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer not-a-valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(captured.get()).as("request must reach the controller").isNotNull();
        assertThat(response.getStatus()).isNotEqualTo(401);
    }

    @Test
    @DisplayName("the /api/files/by-id sibling 401s on the same invalid bearer (gate not widened)")
    void byIdSiblingStaysGated() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", BY_ID_SIBLING_PATH);
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("Authorization", "Bearer not-a-valid-jwt");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(captured.get()).as("gated sibling must not reach the controller").isNull();
    }

    @Test
    @DisplayName("an external caller cannot self-identify on the avatar path (forged trusted headers stripped)")
    void avatarPathStripsForgedIdentity() throws Exception {
        MonolithSecurityFilter filter = new MonolithSecurityFilter(() -> null, List.of());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", AVATAR_PATH);
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-User-ID", "999");
        request.addHeader("X-User-Roles", "ADMIN");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<ServletRequest> captured = new AtomicReference<>();

        filter.doFilter(request, response, capturingChain(captured));

        assertThat(captured.get()).isNotNull();
        var forwarded = (jakarta.servlet.http.HttpServletRequest) captured.get();
        assertThat(forwarded.getHeader("X-User-ID")).isNull();
        assertThat(forwarded.getHeader("X-User-Roles")).isNull();
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
