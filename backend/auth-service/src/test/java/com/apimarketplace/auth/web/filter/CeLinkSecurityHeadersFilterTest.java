package com.apimarketplace.auth.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeLinkSecurityHeadersFilter")
class CeLinkSecurityHeadersFilterTest {

    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain chain;

    @Test
    @DisplayName("stamps strict CSP + nosniff + no-store + no-referrer on every /api/ce-link/ response")
    void stamps_required_headers() throws Exception {
        CeLinkSecurityHeadersFilter filter = new CeLinkSecurityHeadersFilter();
        when(request.getRequestURI()).thenReturn("/api/ce-link/register");

        filter.doFilter(request, response, chain);

        verify(response).setHeader("Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
        verify(response).setHeader("X-Content-Type-Options", "nosniff");
        verify(response).setHeader("Cache-Control", "no-store");
        verify(response).setHeader("Referrer-Policy", "no-referrer");
        verify(chain).doFilter(request, response);
    }

    @Test
    @DisplayName("doesNotStampHeadersOnNonCeLinkPaths - other endpoints keep their own response shape")
    void skips_non_ce_link_paths() throws Exception {
        CeLinkSecurityHeadersFilter filter = new CeLinkSecurityHeadersFilter();
        when(request.getRequestURI()).thenReturn("/api/auth/me");

        filter.doFilter(request, response, chain);

        verify(response, never()).setHeader(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(chain).doFilter(request, response);
    }
}
