package com.apimarketplace.orchestrator.services;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfacePlanExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private InterfacePlanExtractor interfacePlanExtractor;

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

    // ===== isInterfaceReferencedByRun - CASA LC-037 follow-up =====

    @Test
    @DisplayName("isInterfaceReferencedByRun rejects null interfaceId")
    void referencedByRunRejectsNullInterfaceId() {
        assertThat(service.isInterfaceReferencedByRun(null, "run_x")).isFalse();
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    @DisplayName("isInterfaceReferencedByRun rejects null runId")
    void referencedByRunRejectsNullRunId() {
        assertThat(service.isInterfaceReferencedByRun(UUID.randomUUID(), null)).isFalse();
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    @DisplayName("isInterfaceReferencedByRun rejects blank runId")
    void referencedByRunRejectsBlankRunId() {
        assertThat(service.isInterfaceReferencedByRun(UUID.randomUUID(), "  ")).isFalse();
        verifyNoInteractions(workflowRunRepository);
    }

    @Test
    @DisplayName("isInterfaceReferencedByRun returns false (fail closed) for an unknown runId")
    void referencedByRunRejectsUnknownRun() {
        when(workflowRunRepository.findByRunIdPublic("run_missing")).thenReturn(Optional.empty());

        assertThat(service.isInterfaceReferencedByRun(UUID.randomUUID(), "run_missing")).isFalse();
    }

    @Test
    @DisplayName("isInterfaceReferencedByRun returns true when the run's OWN workflow plan references the interface")
    void referencedByRunAcceptsReferencedInterface() {
        UUID interfaceId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(workflowId);
        when(workflow.getTenantId()).thenReturn("owner-tenant");
        when(workflow.getPlan()).thenReturn(Map.of(
                "interfaces", java.util.List.of(Map.of("id", interfaceId.toString(), "label", "Page"))));
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getWorkflow()).thenReturn(workflow);
        when(workflowRunRepository.findByRunIdPublic("run_x")).thenReturn(Optional.of(run));
        when(interfacePlanExtractor.extractInterfaceIds(any(com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.class))).thenReturn(Set.of(interfaceId));

        assertThat(service.isInterfaceReferencedByRun(interfaceId, "run_x")).isTrue();
    }

    @Test
    @DisplayName("pre-fix regression: isInterfaceReferencedByRun refuses an interface the run's plan does NOT "
            + "reference, even though the runId itself resolves fine - closes the share-token pivot where a "
            + "share holder pairs the shared runId with ANY other interface UUID the owner ever built")
    void referencedByRunRejectsUnreferencedInterface() {
        UUID requestedInterfaceId = UUID.randomUUID();
        UUID otherOwnerInterfaceId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(workflowId);
        when(workflow.getTenantId()).thenReturn("owner-tenant");
        when(workflow.getPlan()).thenReturn(Map.of(
                "interfaces", java.util.List.of(Map.of("id", otherOwnerInterfaceId.toString(), "label", "Other"))));
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getWorkflow()).thenReturn(workflow);
        when(workflowRunRepository.findByRunIdPublic("run_x")).thenReturn(Optional.of(run));
        when(interfacePlanExtractor.extractInterfaceIds(any(com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.class))).thenReturn(Set.of(otherOwnerInterfaceId));

        assertThat(service.isInterfaceReferencedByRun(requestedInterfaceId, "run_x")).isFalse();
    }

    // ===== isInterfaceReferencedByRun uses the RUN's frozen plan, not the live workflow plan
    //       (round-3 audit fix) =====

    @Test
    @DisplayName("isInterfaceReferencedByRun reads the run's OWN frozen plan: an interface removed from the "
            + "LIVE plan after the run started but still present in the run's cached plan is still referenced")
    void referencedByRunUsesFrozenPlanWhenInterfaceRemovedFromLivePlan() {
        UUID interfaceId = UUID.randomUUID();
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(UUID.randomUUID());
        when(workflow.getTenantId()).thenReturn("owner-tenant");
        // Live plan no longer references the interface (edited after the run started). Lenient:
        // the fix means this is never actually read (the run's own frozen plan below wins), which
        // is exactly the behavior under test - stubbing it strictly would flag the fixed code as
        // "not calling a mock" instead of proving the mock is correctly bypassed.
        org.mockito.Mockito.lenient().when(workflow.getPlan())
                .thenReturn(Map.of("interfaces", java.util.List.of()));

        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getWorkflow()).thenReturn(workflow);
        // The run's OWN frozen plan still references it.
        Map<String, Object> frozenPlan = Map.of(
                "interfaces", java.util.List.of(Map.of("id", interfaceId.toString(), "label", "Page")));
        when(run.getPlan()).thenReturn(frozenPlan);
        when(workflowRunRepository.findByRunIdPublic("run_x")).thenReturn(Optional.of(run));
        when(interfacePlanExtractor.extractInterfaceIds(any(com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.class))).thenReturn(Set.of(interfaceId));

        assertThat(service.isInterfaceReferencedByRun(interfaceId, "run_x")).isTrue();
    }

    @Test
    @DisplayName("pre-fix regression: isInterfaceReferencedByRun refuses an interface added to the LIVE plan "
            + "after the run started but absent from the run's own frozen plan - the old code read "
            + "workflow.getPlan() (live) instead of run.getPlan() (frozen) and would have wrongly accepted this")
    void referencedByRunRejectsInterfaceOnlyInLivePlan() {
        UUID interfaceId = UUID.randomUUID();
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(UUID.randomUUID());
        when(workflow.getTenantId()).thenReturn("owner-tenant");
        // Live plan now references the interface (added after the run started). Lenient: the fix
        // means this must never be read (the run's own frozen plan below, which does NOT
        // reference it, is authoritative) - see the note in the sibling test above.
        org.mockito.Mockito.lenient().when(workflow.getPlan()).thenReturn(Map.of(
                "interfaces", java.util.List.of(Map.of("id", interfaceId.toString(), "label", "Page"))));

        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getWorkflow()).thenReturn(workflow);
        // The run's OWN frozen plan does NOT reference it.
        Map<String, Object> frozenPlan = Map.of("interfaces", java.util.List.of());
        when(run.getPlan()).thenReturn(frozenPlan);
        when(workflowRunRepository.findByRunIdPublic("run_x")).thenReturn(Optional.of(run));
        when(interfacePlanExtractor.extractInterfaceIds(any(com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.class))).thenReturn(Set.of());

        assertThat(service.isInterfaceReferencedByRun(interfaceId, "run_x")).isFalse();
    }

    @Test
    @DisplayName("isInterfaceReferencedByRun falls back to the workflow's live plan when the run has no "
            + "cached plan (null) - legacy/corrupt state, same convention as WorkflowResumeService")
    void referencedByRunFallsBackToLivePlanWhenRunPlanNull() {
        UUID interfaceId = UUID.randomUUID();
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(UUID.randomUUID());
        when(workflow.getTenantId()).thenReturn("owner-tenant");
        when(workflow.getPlan()).thenReturn(Map.of(
                "interfaces", java.util.List.of(Map.of("id", interfaceId.toString(), "label", "Page"))));

        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getWorkflow()).thenReturn(workflow);
        when(run.getPlan()).thenReturn(null);
        when(workflowRunRepository.findByRunIdPublic("run_x")).thenReturn(Optional.of(run));
        when(interfacePlanExtractor.extractInterfaceIds(any(com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.class))).thenReturn(Set.of(interfaceId));

        assertThat(service.isInterfaceReferencedByRun(interfaceId, "run_x")).isTrue();
    }

    @Test
    @DisplayName("isInterfaceReferencedByRun falls back to the workflow's live plan when the run's cached "
            + "plan is an empty map (legacy/corrupt state)")
    void referencedByRunFallsBackToLivePlanWhenRunPlanEmpty() {
        UUID interfaceId = UUID.randomUUID();
        WorkflowEntity workflow = mock(WorkflowEntity.class);
        when(workflow.getId()).thenReturn(UUID.randomUUID());
        when(workflow.getTenantId()).thenReturn("owner-tenant");
        when(workflow.getPlan()).thenReturn(Map.of(
                "interfaces", java.util.List.of(Map.of("id", interfaceId.toString(), "label", "Page"))));

        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getWorkflow()).thenReturn(workflow);
        when(run.getPlan()).thenReturn(Map.of());
        when(workflowRunRepository.findByRunIdPublic("run_x")).thenReturn(Optional.of(run));
        when(interfacePlanExtractor.extractInterfaceIds(any(com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.class))).thenReturn(Set.of(interfaceId));

        assertThat(service.isInterfaceReferencedByRun(interfaceId, "run_x")).isTrue();
    }
}
