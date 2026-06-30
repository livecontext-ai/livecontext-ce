package com.apimarketplace.orchestrator.controllers.activity;

import com.apimarketplace.common.recentactivity.RecentActivityResponseDto;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.activity.RecentActivityAggregatorService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read endpoint for the {@code /app/activity} (3rd bell tab) feed.
 * Routed through the gateway via the {@code orchestrator-activities} block
 * in {@code SimpleGatewayConfig} (alongside the existing
 * {@code orchestrator-notifications} route).
 *
 * <p>Resolves the active workspace from headers ({@code X-User-ID} +
 * {@code X-Organization-ID} - gateway-injected from JWT + the active-org
 * claim) then delegates to {@link RecentActivityAggregatorService}.
 *
 * <p>No pagination by design: returns the top-50 most-recently-edited
 * resources (auditor v2 chunk-1 decision - pagination over a cross-resource
 * merge has no useful semantics for this product surface).
 */
@RestController
@RequestMapping("/api/activities")
public class ActivityController {

    private static final Logger log = LoggerFactory.getLogger(ActivityController.class);

    private final RecentActivityAggregatorService aggregator;
    private final TenantResolver tenantResolver;

    public ActivityController(RecentActivityAggregatorService aggregator, TenantResolver tenantResolver) {
        this.aggregator = aggregator;
        this.tenantResolver = tenantResolver;
    }

    @GetMapping("/recent")
    public ResponseEntity<RecentActivityResponseDto> getRecentActivity(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = httpRequest.getHeader("X-Organization-ID");
        return ResponseEntity.ok(aggregator.getRecentActivity(tenantId, orgId));
    }
}
