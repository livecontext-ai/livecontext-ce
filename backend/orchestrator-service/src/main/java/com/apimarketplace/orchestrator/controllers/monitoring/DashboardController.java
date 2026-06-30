package com.apimarketplace.orchestrator.controllers.monitoring;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.controllers.dto.HomeStatusDto;
import com.apimarketplace.orchestrator.domain.NotificationReadStateEntity;
import com.apimarketplace.orchestrator.repository.NotificationReadStateRepository;
import com.apimarketplace.orchestrator.services.ActiveAutomationsService;
import com.apimarketplace.orchestrator.services.notification.NotificationService;
import com.apimarketplace.orchestrator.services.notification.NotificationsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Read-only home-page dashboard endpoints.
 *
 * <p>Surfaces "what's running right now": pinned workflows / apps with
 * armed triggers, plus agents on schedule or webhook. The frontend renders one
 * pill per item in a horizontal strip on the chat welcome view.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

    private final ActiveAutomationsService activeAutomationsService;
    private final TenantResolver tenantResolver;
    private final NotificationService notificationService;
    private final NotificationReadStateRepository readStateRepository;

    public DashboardController(ActiveAutomationsService activeAutomationsService,
                               TenantResolver tenantResolver,
                               NotificationService notificationService,
                               NotificationReadStateRepository readStateRepository) {
        this.activeAutomationsService = activeAutomationsService;
        this.tenantResolver = tenantResolver;
        this.notificationService = notificationService;
        this.readStateRepository = readStateRepository;
    }

    /**
     * Active automations for the current tenant: pinned workflows / applications
     * with at least one enabled schedule or webhook, plus agents with the same.
     * Sorted by next-fire ascending (imminent first); webhooks tail the list.
     *
     * GET /api/dashboard/active-automations
     */
    @GetMapping("/active-automations")
    public ResponseEntity<List<ActiveAutomationDto>> getActiveAutomations(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);

        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");

        List<ActiveAutomationDto> items = activeAutomationsService.getActiveAutomations(tenantId, orgId, orgRole);
        logger.debug("GET /api/dashboard/active-automations - tenant={} returned {} items", tenantId, items.size());
        return ResponseEntity.ok(items);
    }

    /**
     * One-shot home-status payload - combines armed automations + bell items
     * + unread count in a single round-trip.
     *
     * <p>Org context (X-Organization-ID, X-Organization-Role) flows to both
     * the automations side and the notifications side. V220 added
     * {@code orchestrator.notifications.organization_id} so the bell routes
     * strict on workspace via {@code organization_id = :orgId}. Post-V261
     * (2026-05-19) the gateway always injects X-Organization-ID (personal
     * workspaces resolve to the user's default personal org), so every
     * workspace flows through the strict-org branch.
     *
     * GET /api/dashboard/home-status
     */
    @GetMapping("/home-status")
    public ResponseEntity<HomeStatusDto> getHomeStatus(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        tenantResolver.validate(tenantId);

        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");

        List<ActiveAutomationDto> automations =
                activeAutomationsService.getActiveAutomations(tenantId, orgId, orgRole);

        NotificationsResponse notifs = notificationService.getNotifications(tenantId, orgId);

        Instant lastSeenAt = readStateRepository.findByUserId(tenantId)
                .map(NotificationReadStateEntity::getLastSeenAt)
                .orElse(null);

        // Defense-in-depth: the wire contract (HomeStatusDto) guarantees
        // automations and items are always present (empty list when nothing
        // to show). Even though both upstream calls return non-null Lists in
        // the current happy path, a future refactor or mocked/intercepted
        // response could leak null here. Coalesce defensively so any frontend
        // consumer can trust `.length` / `.map` / `.some` without a null
        // check - the symmetric defense to the per-field `?? []` in the
        // useHomeStatus / useNotificationsPaged hooks.
        return ResponseEntity.ok(new HomeStatusDto(
                Objects.requireNonNullElseGet(automations, List::of),
                Objects.requireNonNullElseGet(notifs.items(), List::of),
                notifs.unreadCount(),
                lastSeenAt));
    }
}
