package com.apimarketplace.orchestrator.controllers.interfaces;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.InterfaceRenderResult;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.PaginationInfo;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.SingleItemResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wires the Phase 6c scope gates on the {@code /api/interfaces/*} render
 * endpoints. Each test exercises ONE branch: gate-pass returns 200 with the
 * service's body, gate-fail returns 404 without delegating to the service.
 *
 * <p>This complements {@code InterfaceRenderServiceScopeGateTest} (which
 * covers the predicate's internals): here we assert that the controller
 * actually invokes the gate BEFORE calling the heavy render methods, and
 * that gate-fail short-circuits without leaking partial data.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceController - Phase 6c scope gates")
class InterfaceControllerScopeGateTest {

    @Mock
    private InterfaceRenderService renderService;

    private InterfaceController controller;

    private static final UUID INTERFACE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String RUN_ID = "run_2026_a";
    private static final String CALLER_TENANT = "user-1";
    private static final String ORG_ID = "org-1";

    @BeforeEach
    void setUp() {
        controller = new InterfaceController(renderService, new TenantResolver());
    }

    private MockHttpServletRequest request(String userId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (userId != null) {
            req.addHeader("X-User-ID", userId);
        }
        return req;
    }

    private MockHttpServletRequest request(String userId, String organizationId) {
        MockHttpServletRequest req = request(userId);
        if (organizationId != null) {
            req.addHeader("X-Organization-ID", organizationId);
        }
        return req;
    }

    private InterfaceRenderResult emptyResult() {
        return new InterfaceRenderResult(
                "<html/>", "css", "js",
                List.of(),
                new PaginationInfo(0, 10, 0, 0),
                Map.of()
        );
    }

    // ===== /render =====

    @Test
    @DisplayName("GET /{id}/render returns 404 when caller does not own the run (cross-tenant UUID-guess)")
    void renderRejectsWhenCallerDoesNotOwnRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, null)).thenReturn(false);

        ResponseEntity<InterfaceRenderResult> response = controller.renderInterface(
                INTERFACE_ID, RUN_ID, 0, 10, null, null, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
        verify(renderService, never()).render(any(), any(), any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("GET /{id}/render returns 200 with body when caller owns the run")
    void renderAcceptsWhenCallerOwnsRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, null)).thenReturn(true);
        InterfaceRenderResult body = emptyResult();
        when(renderService.render(INTERFACE_ID, RUN_ID, CALLER_TENANT, 0, 10, null, java.util.Map.of())).thenReturn(body);

        ResponseEntity<InterfaceRenderResult> response = controller.renderInterface(
                INTERFACE_ID, RUN_ID, 0, 10, null, null, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(body);
    }

    @Test
    @DisplayName("GET /{id}/render accepts an org-scoped teammate run when X-Organization-ID matches")
    void renderAcceptsOrgScopedTeammateRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, ORG_ID)).thenReturn(true);
        InterfaceRenderResult body = emptyResult();
        when(renderService.render(INTERFACE_ID, RUN_ID, CALLER_TENANT, 0, 10, null, java.util.Map.of())).thenReturn(body);

        ResponseEntity<InterfaceRenderResult> response = controller.renderInterface(
                INTERFACE_ID, RUN_ID, 0, 10, null, null, request(CALLER_TENANT, ORG_ID), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(body);
        verify(renderService).callerCanAccessRun(RUN_ID, CALLER_TENANT, ORG_ID);
    }

    // ===== /items/{itemIndex} =====

    @Test
    @DisplayName("GET /{id}/items/{itemIndex} returns 404 when caller does not own the run")
    void itemRejectsWhenCallerDoesNotOwnRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, null)).thenReturn(false);

        ResponseEntity<SingleItemResult> response = controller.getInterfaceItem(
                INTERFACE_ID, 0, RUN_ID, 0, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(renderService, never()).renderItem(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("GET /{id}/items/{itemIndex} returns the item body when caller owns the run")
    void itemAcceptsWhenCallerOwnsRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, null)).thenReturn(true);
        SingleItemResult body = new SingleItemResult(0, 0, Map.of("k", "v"));
        when(renderService.renderItem(INTERFACE_ID, RUN_ID, CALLER_TENANT, 0, 0))
                .thenReturn(Optional.of(body));

        ResponseEntity<SingleItemResult> response = controller.getInterfaceItem(
                INTERFACE_ID, 0, RUN_ID, 0, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(body);
    }

    @Test
    @DisplayName("GET /{id}/items/{itemIndex} accepts an org-scoped teammate run when X-Organization-ID matches")
    void itemAcceptsOrgScopedTeammateRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, ORG_ID)).thenReturn(true);
        SingleItemResult body = new SingleItemResult(0, 0, Map.of("k", "v"));
        when(renderService.renderItem(INTERFACE_ID, RUN_ID, CALLER_TENANT, 0, 0))
                .thenReturn(Optional.of(body));

        ResponseEntity<SingleItemResult> response = controller.getInterfaceItem(
                INTERFACE_ID, 0, RUN_ID, 0, request(CALLER_TENANT, ORG_ID), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(body);
        verify(renderService).callerCanAccessRun(RUN_ID, CALLER_TENANT, ORG_ID);
    }

    // ===== /run-info =====

    @Test
    @DisplayName("GET /{id}/run-info returns 404 when caller does not own the run")
    void runInfoRejectsWhenCallerDoesNotOwnRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, null)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.getInterfaceRunInfo(
                INTERFACE_ID, RUN_ID, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(renderService, never()).getRunInfo(any(), any(), any());
    }

    @Test
    @DisplayName("GET /{id}/run-info returns the metadata when caller owns the run")
    void runInfoAcceptsWhenCallerOwnsRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, null)).thenReturn(true);
        Map<String, Object> info = Map.of("totalItems", 3L);
        when(renderService.getRunInfo(INTERFACE_ID, RUN_ID, CALLER_TENANT))
                .thenReturn(Optional.of(info));

        ResponseEntity<Map<String, Object>> response = controller.getInterfaceRunInfo(
                INTERFACE_ID, RUN_ID, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("totalItems", 3L);
    }

    @Test
    @DisplayName("GET /{id}/run-info accepts an org-scoped teammate run when X-Organization-ID matches")
    void runInfoAcceptsOrgScopedTeammateRun() {
        when(renderService.callerCanAccessRun(RUN_ID, CALLER_TENANT, ORG_ID)).thenReturn(true);
        Map<String, Object> info = Map.of("totalItems", 3L);
        when(renderService.getRunInfo(INTERFACE_ID, RUN_ID, CALLER_TENANT))
                .thenReturn(Optional.of(info));

        ResponseEntity<Map<String, Object>> response = controller.getInterfaceRunInfo(
                INTERFACE_ID, RUN_ID, request(CALLER_TENANT, ORG_ID), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("totalItems", 3L);
        verify(renderService).callerCanAccessRun(RUN_ID, CALLER_TENANT, ORG_ID);
    }

    // ===== /items-count =====

    @Test
    @DisplayName("GET /{id}/items-count returns the scoped count for an org workspace")
    void itemsCountPassesOrganizationScope() {
        when(renderService.countItems(INTERFACE_ID, RUN_ID, CALLER_TENANT, ORG_ID)).thenReturn(7L);

        ResponseEntity<Long> response = controller.getItemsCount(
                INTERFACE_ID, RUN_ID, request(CALLER_TENANT, ORG_ID), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(7L);
        verify(renderService).countItems(INTERFACE_ID, RUN_ID, CALLER_TENANT, ORG_ID);
    }

    @Test
    @DisplayName("GET /{id}/items-count returns 401 without a resolved caller")
    void itemsCountRejectsAnonymousCaller() {
        ResponseEntity<Long> response = controller.getItemsCount(
                INTERFACE_ID, RUN_ID, request(null, ORG_ID), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(renderService, never()).countItems(any(), any(), any(), any());
    }

    // ===== /render-datasource =====

    @Test
    @DisplayName("GET /{id}/render-datasource returns 404 when caller does not own the interface")
    void renderDatasourceRejectsWhenCallerDoesNotOwnInterface() {
        when(renderService.callerOwnsInterface(INTERFACE_ID, CALLER_TENANT)).thenReturn(false);

        ResponseEntity<InterfaceRenderResult> response = controller.renderInterfaceWithDatasource(
                INTERFACE_ID, 0, 0, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(renderService, never()).renderWithDatasource(any(), any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("GET /{id}/render-datasource returns the rendered body when caller owns the interface")
    void renderDatasourceAcceptsWhenCallerOwnsInterface() {
        when(renderService.callerOwnsInterface(INTERFACE_ID, CALLER_TENANT)).thenReturn(true);
        InterfaceRenderResult body = emptyResult();
        when(renderService.renderWithDatasource(INTERFACE_ID, CALLER_TENANT, 0, 0)).thenReturn(body);

        ResponseEntity<InterfaceRenderResult> response = controller.renderInterfaceWithDatasource(
                INTERFACE_ID, 0, 0, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(body);
    }

    // ===== /datasource-items-count =====

    @Test
    @DisplayName("GET /{id}/datasource-items-count returns 404 when caller does not own the interface")
    void datasourceItemsCountRejectsWhenCallerDoesNotOwnInterface() {
        when(renderService.callerOwnsInterface(INTERFACE_ID, CALLER_TENANT)).thenReturn(false);

        ResponseEntity<Long> response = controller.getDatasourceItemsCount(
                INTERFACE_ID, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(renderService, never()).countDatasourceItems(any(), any());
    }

    @Test
    @DisplayName("GET /{id}/datasource-items-count returns the count when caller owns the interface")
    void datasourceItemsCountAcceptsWhenCallerOwnsInterface() {
        when(renderService.callerOwnsInterface(INTERFACE_ID, CALLER_TENANT)).thenReturn(true);
        when(renderService.countDatasourceItems(INTERFACE_ID, CALLER_TENANT)).thenReturn(42L);

        ResponseEntity<Long> response = controller.getDatasourceItemsCount(
                INTERFACE_ID, request(CALLER_TENANT), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(42L);
    }
}
