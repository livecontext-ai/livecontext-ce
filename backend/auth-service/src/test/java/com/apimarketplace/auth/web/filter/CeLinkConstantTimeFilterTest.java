package com.apimarketplace.auth.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkConstantTimeFilter")
class CeLinkConstantTimeFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    @Test
    @DisplayName("padsResponseToBudget - fast handler is slowed up to the configured budget (timing-side-channel closure)")
    void pads_to_budget() throws Exception {
        CeLinkConstantTimeFilter filter = new CeLinkConstantTimeFilter(200, true);
        when(request.getRequestURI()).thenReturn("/api/ce-link/register");
        when(request.getMethod()).thenReturn("POST");

        long start = System.nanoTime();
        filter.doFilter(request, response, chain);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        verify(chain).doFilter(request, response);
        // ±50ms tolerance - Thread.sleep is the floor, JVM scheduling adds variance.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(195L);
    }

    @Test
    @DisplayName("skipsMineGetRequest - read-only endpoint with no security branch needs no pad")
    void skips_mine_get() throws Exception {
        CeLinkConstantTimeFilter filter = new CeLinkConstantTimeFilter(500, true);
        when(request.getRequestURI()).thenReturn("/api/ce-link/mine");
        when(request.getMethod()).thenReturn("GET");

        long start = System.nanoTime();
        filter.doFilter(request, response, chain);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        verify(chain).doFilter(request, response);
        // No pad - should be well under the 500ms budget.
        assertThat(elapsedMs).isLessThan(100L);
    }

    @Test
    @DisplayName("skipsSquatRecoveryEndpoint - atomic Redis GETDEL is already bounded")
    void skips_squat_recovery() throws Exception {
        CeLinkConstantTimeFilter filter = new CeLinkConstantTimeFilter(500, true);
        when(request.getRequestURI()).thenReturn("/api/ce-link/squat-recovery/abc");
        // method irrelevant - shouldNotFilter returns early on path

        long start = System.nanoTime();
        filter.doFilter(request, response, chain);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        verify(chain).doFilter(request, response);
        assertThat(elapsedMs).isLessThan(100L);
    }

    @Test
    @DisplayName("skipsAllWhenDisabled - feature flag off short-circuits cleanly")
    void skips_all_when_disabled() throws Exception {
        CeLinkConstantTimeFilter filter = new CeLinkConstantTimeFilter(500, false);
        // shouldNotFilter returns true unconditionally - chain still runs via the
        // outer OncePerRequestFilter dispatch, no pad applied.

        long start = System.nanoTime();
        filter.doFilter(request, response, chain);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        verify(chain).doFilter(request, response);
        assertThat(elapsedMs).isLessThan(100L);
    }
}
