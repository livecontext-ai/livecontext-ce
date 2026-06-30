package com.apimarketplace.orchestrator.services;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the Phase 6c authenticated-render gates added on
 * 2026-05-19. The gates close UUID-guess leaks on the
 * {@code /api/interfaces/*} authenticated REST endpoints that previously
 * funnelled through {@code resolveRunOwnerTenantId}, a helper that
 * intentionally swaps the caller's tenantId for the run owner's tenantId so
 * runtime / marketplace-preview callers can render across tenants. Public
 * REST endpoints must NOT inherit that tolerance.
 *
 * <p>Each test names the bug shape ({@code rejects*}, {@code accepts*}) and
 * exercises one branch of the gate predicate in isolation.
 */
@ExtendWith(MockitoExtension.class)
class InterfaceRenderServiceScopeGateTest {

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private InterfaceClient interfaceClient;

    @InjectMocks
    private InterfaceRenderService service;

    // ===== callerOwnsRun =====

    @Test
    @DisplayName("callerOwnsRun rejects null tenantId (unauthenticated caller)")
    void rejectsNullTenant() {
        assertThat(service.callerOwnsRun("run_x", null)).isFalse();
    }

    @Test
    @DisplayName("callerOwnsRun rejects blank tenantId (anonymous gateway path)")
    void rejectsBlankTenant() {
        assertThat(service.callerOwnsRun("run_x", "  ")).isFalse();
    }

    @Test
    @DisplayName("callerOwnsRun rejects null runId so 404 path stays consistent for malformed URLs")
    void rejectsNullRunId() {
        assertThat(service.callerOwnsRun(null, "tenantA")).isFalse();
    }

    @Test
    @DisplayName("callerOwnsRun rejects unknown runId (UUID-guess against a non-existent run)")
    void rejectsUnknownRun() {
        when(workflowRunRepository.findByRunIdPublic("run_missing")).thenReturn(Optional.empty());
        assertThat(service.callerOwnsRun("run_missing", "tenantA")).isFalse();
    }

    @Test
    @DisplayName("callerOwnsRun rejects cross-tenant runId - Phase 6c regression for UUID-guess leak")
    void rejectsCrossTenantRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("tenantB");
        when(workflowRunRepository.findByRunIdPublic("run_owned_by_b")).thenReturn(Optional.of(run));

        assertThat(service.callerOwnsRun("run_owned_by_b", "tenantA")).isFalse();
    }

    @Test
    @DisplayName("callerOwnsRun accepts same-tenant runId - happy path stays available")
    void acceptsSameTenantRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("tenantA");
        when(workflowRunRepository.findByRunIdPublic("run_owned_by_a")).thenReturn(Optional.of(run));

        assertThat(service.callerOwnsRun("run_owned_by_a", "tenantA")).isTrue();
    }

    @Test
    @DisplayName("regression: callerCanAccessRun accepts org teammate when run organization matches active workspace")
    void acceptsOrgScopedTeammateRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("ownerTenant");
        run.setOrganizationId("orgA");
        when(workflowRunRepository.findByRunIdPublic("run_org_a")).thenReturn(Optional.of(run));

        assertThat(service.callerCanAccessRun("run_org_a", "memberTenant", "orgA")).isTrue();
    }

    @Test
    @DisplayName("callerCanAccessRun rejects same tenant when active workspace differs from run organization")
    void rejectsSameTenantDifferentWorkspaceRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("tenantA");
        run.setOrganizationId("orgB");
        when(workflowRunRepository.findByRunIdPublic("run_org_b")).thenReturn(Optional.of(run));

        assertThat(service.callerCanAccessRun("run_org_b", "tenantA", "orgA")).isFalse();
    }

    @Test
    @DisplayName("countItems with organizationId returns zero when the run is outside the active workspace")
    void countItemsRejectsOutOfScopeOrganizationRun() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("ownerTenant");
        run.setOrganizationId("orgB");
        when(workflowRunRepository.findByRunIdPublic("run_org_b")).thenReturn(Optional.of(run));

        long count = service.countItems(UUID.randomUUID(), "run_org_b", "memberTenant", "orgA");

        assertThat(count).isZero();
    }

    // ===== callerOwnsInterface =====

    @Test
    @DisplayName("callerOwnsInterface rejects null tenantId so /render-datasource gate stays closed")
    void rejectsInterfaceNullTenant() {
        UUID id = UUID.randomUUID();
        assertThat(service.callerOwnsInterface(id, null)).isFalse();
    }

    @Test
    @DisplayName("callerOwnsInterface rejects blank tenantId")
    void rejectsInterfaceBlankTenant() {
        UUID id = UUID.randomUUID();
        assertThat(service.callerOwnsInterface(id, " ")).isFalse();
    }

    @Test
    @DisplayName("callerOwnsInterface rejects null interfaceId so a malformed URL stays at 404")
    void rejectsInterfaceNullId() {
        assertThat(service.callerOwnsInterface(null, "tenantA")).isFalse();
    }

    @Test
    @DisplayName("callerOwnsInterface rejects when interface-service returns null - cross-scope or unknown id, both at 404")
    void rejectsMissingInterface() {
        UUID id = UUID.randomUUID();
        when(interfaceClient.getInterface(id, "tenantA")).thenReturn(null);

        assertThat(service.callerOwnsInterface(id, "tenantA")).isFalse();
    }

    @Test
    @DisplayName("callerOwnsInterface accepts when interface-service returned the DTO - strict-scope gate already passed upstream")
    void acceptsWhenDtoReturned() {
        UUID id = UUID.randomUUID();
        // The strict-scope finder in interface-service already filtered on
        // (tenantId, orgId) - a non-null DTO means the caller is in-scope.
        // We MUST NOT re-compare iface.getTenantId() against the caller:
        // that would falsely reject org-teammate access to a workspace-
        // shared interface owned by a different teammate.
        InterfaceDto dto = new InterfaceDto();
        dto.setTenantId("teammate_user");
        when(interfaceClient.getInterface(id, "caller_user")).thenReturn(dto);

        assertThat(service.callerOwnsInterface(id, "caller_user")).isTrue();
    }

    @Test
    @DisplayName("callerOwnsInterface accepts personal-strict happy path (same tenant, no org workspace)")
    void acceptsPersonalStrictInterface() {
        UUID id = UUID.randomUUID();
        InterfaceDto dto = new InterfaceDto();
        dto.setTenantId("tenantA");
        when(interfaceClient.getInterface(id, "tenantA")).thenReturn(dto);

        assertThat(service.callerOwnsInterface(id, "tenantA")).isTrue();
    }
}
