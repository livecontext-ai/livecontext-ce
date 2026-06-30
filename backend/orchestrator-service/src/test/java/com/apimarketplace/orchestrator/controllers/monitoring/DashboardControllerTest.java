package com.apimarketplace.orchestrator.controllers.monitoring;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.controllers.dto.HomeStatusDto;
import com.apimarketplace.orchestrator.repository.NotificationReadStateRepository;
import com.apimarketplace.orchestrator.services.ActiveAutomationsService;
import com.apimarketplace.orchestrator.services.notification.NotificationService;
import com.apimarketplace.orchestrator.services.notification.NotificationsResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DashboardController} - verifies the org headers are
 * forwarded to the service layer (so org-scoped agents are visible) and
 * that the tenant resolution + validation is invoked before the service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardController")
class DashboardControllerTest {

    @Mock private ActiveAutomationsService activeAutomationsService;
    @Mock private TenantResolver tenantResolver;
    @Mock private NotificationService notificationService;
    @Mock private NotificationReadStateRepository readStateRepository;

    @InjectMocks
    private DashboardController controller;

    @Test
    @DisplayName("Forwards X-Organization-ID + X-Organization-Role headers to service")
    void forwardsOrgHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Organization-ID", "org-7");
        req.addHeader("X-Organization-Role", "MEMBER");

        when(tenantResolver.resolve(any(HttpServletRequest.class))).thenReturn("user-42");
        when(activeAutomationsService.getActiveAutomations("user-42", "org-7", "MEMBER"))
                .thenReturn(List.of());

        ResponseEntity<List<ActiveAutomationDto>> res = controller.getActiveAutomations(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(tenantResolver).validate("user-42");
        verify(activeAutomationsService).getActiveAutomations("user-42", "org-7", "MEMBER");
    }

    @Test
    @DisplayName("Missing org headers → service called with nulls (personal scope)")
    void allowsMissingOrgHeaders() {
        MockHttpServletRequest req = new MockHttpServletRequest();

        when(tenantResolver.resolve(any(HttpServletRequest.class))).thenReturn("solo-user");
        when(activeAutomationsService.getActiveAutomations("solo-user", null, null))
                .thenReturn(List.of());

        ResponseEntity<List<ActiveAutomationDto>> res = controller.getActiveAutomations(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(activeAutomationsService).getActiveAutomations("solo-user", null, null);
    }

    @Test
    @DisplayName("getHomeStatus - coalesces null automations + null items to empty lists (regression for NotificationBell crash 2026-05-11)")
    void getHomeStatusCoalescesNullsToEmptyLists() {
        // Reproduce the bug class: a future refactor or mocked layer returns
        // null for items / automations. The pre-fix controller passed those
        // nulls straight into HomeStatusDto, and a record-level
        // @JsonInclude(NON_NULL) stripped them from the wire - frontend then
        // crashed on .some() / .length / .map(). The defense is now in the
        // controller (Objects.requireNonNullElseGet). This test proves that
        // contract: any null upstream is coerced to an empty list.
        MockHttpServletRequest req = new MockHttpServletRequest();

        when(tenantResolver.resolve(any(HttpServletRequest.class))).thenReturn("user-7");
        when(activeAutomationsService.getActiveAutomations("user-7", null, null))
                .thenReturn(null); // upstream null
        // NotificationsResponse with null items() - atypical but possible
        // under future refactor / partial cache responses. V220 - 2-arg
        // overload (userId, orgId) replaces the single-arg legacy call.
        when(notificationService.getNotifications("user-7", null))
                .thenReturn(new NotificationsResponse(null, 0, 0, 0, false));
        when(readStateRepository.findByUserId("user-7")).thenReturn(Optional.empty());

        ResponseEntity<HomeStatusDto> res = controller.getHomeStatus(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        HomeStatusDto body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.automations()).isNotNull().isEmpty();
        assertThat(body.items()).isNotNull().isEmpty();
        assertThat(body.unreadCount()).isZero();
        assertThat(body.lastSeenAt()).isNull();
    }

    @Test
    @DisplayName("getHomeStatus - passes through real automations + items unchanged when upstream returns non-null")
    void getHomeStatusPassesThroughHappyPath() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        // All-null record fields are fine for this test - we only verify the
        // controller does not transform / replace the upstream list.
        ActiveAutomationDto automation = new ActiveAutomationDto(
                null, null, "test-wf", null, null, null, null, null, null, null, null);

        when(tenantResolver.resolve(any(HttpServletRequest.class))).thenReturn("user-7");
        when(activeAutomationsService.getActiveAutomations("user-7", null, null))
                .thenReturn(List.of(automation));
        when(notificationService.getNotifications("user-7", null))
                .thenReturn(new NotificationsResponse(List.of(), 5, 0, 15, false));
        when(readStateRepository.findByUserId("user-7")).thenReturn(Optional.empty());

        ResponseEntity<HomeStatusDto> res = controller.getHomeStatus(req);

        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().automations()).containsExactly(automation);
        assertThat(res.getBody().unreadCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("getHomeStatus - V220: X-Organization-ID flows into NotificationService so the bell is scope-aware (regression for tenant-only deferral)")
    void getHomeStatusThreadsOrgIdIntoNotificationService() {
        // Pre-V220: getHomeStatus called notificationService.getNotifications(userId)
        // and explicitly deferred org fan-out via a comment. Bug: org teammates
        // saw zero bell rows for runs that failed in the shared workspace.
        // This test pins the new 2-arg contract - orgId from the header MUST
        // flow into the service call so the scope-aware read predicate fires.
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Organization-ID", "org-99");
        req.addHeader("X-Organization-Role", "MEMBER");

        when(tenantResolver.resolve(any(HttpServletRequest.class))).thenReturn("user-7");
        when(activeAutomationsService.getActiveAutomations("user-7", "org-99", "MEMBER"))
                .thenReturn(List.of());
        when(notificationService.getNotifications("user-7", "org-99"))
                .thenReturn(new NotificationsResponse(List.of(), 0, 0, 15, false));
        when(readStateRepository.findByUserId("user-7")).thenReturn(Optional.empty());

        controller.getHomeStatus(req);

        // Verifies the org-aware overload was invoked with the exact header
        // value. If the controller silently falls back to the legacy
        // single-arg call, this verification fails - pinning the contract.
        verify(notificationService).getNotifications("user-7", "org-99");
    }
}
