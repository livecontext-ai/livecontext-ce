package com.apimarketplace.orchestrator.controllers.activity;

import com.apimarketplace.common.recentactivity.RecentActivityResponseDto;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.activity.RecentActivityAggregatorService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ActivityController}.
 *
 * <p>Pin: the controller delegates to
 * {@link RecentActivityAggregatorService#getRecentActivity} with the
 * {@code X-User-ID} (resolved via {@link TenantResolver}) and
 * {@code X-Organization-ID} (read directly from the request) headers. No
 * body validation, no path params, no pagination - single
 * {@code GET /api/activities/recent} endpoint.
 */
@ExtendWith(MockitoExtension.class)
class ActivityControllerTest {

    @Mock RecentActivityAggregatorService aggregator;
    @Mock TenantResolver tenantResolver;
    @Mock HttpServletRequest request;

    private ActivityController controller;

    @BeforeEach
    void setUp() {
        controller = new ActivityController(aggregator, tenantResolver);
    }

    @Test
    @DisplayName("Org-scope request: forwards X-User-ID + X-Organization-ID to the aggregator")
    void orgScopeForwardsBothHeaders() {
        when(tenantResolver.resolve(request)).thenReturn("user1");
        when(request.getHeader("X-Organization-ID")).thenReturn("org-42");
        RecentActivityResponseDto expected = new RecentActivityResponseDto(Collections.emptyList(), 7, "Personal");
        when(aggregator.getRecentActivity("user1", "org-42")).thenReturn(expected);

        var response = controller.getRecentActivity(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(expected);
        verify(tenantResolver).validate("user1");
        verify(aggregator).getRecentActivity("user1", "org-42");
    }

    @Test
    @DisplayName("Personal-scope request: forwards null orgId (header absent)")
    void personalScopeForwardsNullOrgId() {
        when(tenantResolver.resolve(request)).thenReturn("user1");
        when(request.getHeader("X-Organization-ID")).thenReturn(null);
        RecentActivityResponseDto expected = new RecentActivityResponseDto(Collections.emptyList(), 0, null);
        when(aggregator.getRecentActivity("user1", null)).thenReturn(expected);

        var response = controller.getRecentActivity(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(expected);
        verify(aggregator).getRecentActivity("user1", null);
    }

    @Test
    @DisplayName("Tenant resolution failure: TenantResolver.validate throws → controller propagates (handled by GlobalExceptionHandler)")
    void invalidTenantPropagates() {
        when(tenantResolver.resolve(request)).thenReturn(null);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("missing X-User-ID"))
                .when(tenantResolver).validate(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.getRecentActivity(request))
                .isInstanceOf(IllegalArgumentException.class);
        verify(aggregator, org.mockito.Mockito.never()).getRecentActivity(any(), any());
    }
}
