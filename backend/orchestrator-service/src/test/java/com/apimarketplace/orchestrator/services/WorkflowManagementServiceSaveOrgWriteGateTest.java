package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Org per-resource write-gate on the REST save path
 * {@code WorkflowManagementService.saveWorkflow(plan, dataInputs, workflowId, organizationId, orgRole)}.
 *
 * <p>An org admin may restrict a member to READ (or DENY) on a specific workflow. The
 * backend already enforces READ-blocks-write everywhere {@code OrgAccessGuard.canWrite}
 * is consulted (delete / clone / status / PUT plan), but POST {@code /api/v2/workflows/dag}
 * - the primary builder-save path for an EXISTING workflow - previously bypassed it. This
 * pins the new 5-arg overload's gate: a restricted member's save is rejected BEFORE any
 * persistence, and a NEW workflow (nothing to restrict) is never gated.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowManagementService.saveWorkflow - org per-resource write-gate")
class WorkflowManagementServiceSaveOrgWriteGateTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private OrgAccessGuard orgAccessService;

    private WorkflowManagementService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowManagementService();
        ReflectionTestUtils.setField(service, "workflowRepository", workflowRepository);
        ReflectionTestUtils.setField(service, "orgAccessService", orgAccessService);
        // reset-plan's allow path deep-copies basePlan via the ObjectMapper; the deny paths throw
        // before reaching it, but the allow test needs a real mapper.
        ReflectionTestUtils.setField(service, "objectMapper",
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /** Bind a request context carrying X-Organization-ID so TenantResolver's static org resolution
     *  (used by delete / status scope checks) sees the org and the deny-list gate is reached. */
    private void bindOrgRequestContext(String orgId) {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Organization-ID", orgId);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request));
    }

    @org.junit.jupiter.api.AfterEach
    void clearRequestContext() {
        org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
    }

    @Test
    @DisplayName("assertApplicationInstanceWritable throws for a member restricted on the org-scoped application")
    void assertApplicationInstanceWritableDeniesRestrictedMember() {
        WorkflowEntity app = new WorkflowEntity();
        app.setId(UUID.randomUUID());
        app.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        app.setOrganizationId("org-1");
        UUID pubId = UUID.randomUUID();
        app.setSourcePublicationId(pubId);
        // The deny-list is keyed on the SOURCE PUBLICATION id under the "application" type.
        when(orgAccessService.canWrite("org-1", "user-1", "application", pubId.toString(), "MEMBER"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.assertApplicationInstanceWritable(app, "user-1", "MEMBER"))
                .isInstanceOf(OrgAccessDeniedException.class)
                .extracting(e -> ((OrgAccessDeniedException) e).getResourceType())
                .isEqualTo("application");
    }

    @Test
    @DisplayName("assertApplicationInstanceWritable allows the owner of the org-scoped application (canWrite true)")
    void assertApplicationInstanceWritableAllowsOwner() {
        WorkflowEntity app = new WorkflowEntity();
        app.setId(UUID.randomUUID());
        app.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        app.setOrganizationId("org-1");
        UUID pubId = UUID.randomUUID();
        app.setSourcePublicationId(pubId);
        when(orgAccessService.canWrite("org-1", "user-1", "application", pubId.toString(), "OWNER"))
                .thenReturn(true);

        // No exception → the owner edit is authorized.
        service.assertApplicationInstanceWritable(app, "user-1", "OWNER");
    }

    @Test
    @DisplayName("assertApplicationInstanceWritable is a no-op for a non-APPLICATION workflow (gate never consulted)")
    void assertApplicationInstanceWritableNoOpForRegularWorkflow() {
        WorkflowEntity regular = new WorkflowEntity();
        regular.setId(UUID.randomUUID());
        regular.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
        regular.setOrganizationId("org-1");

        service.assertApplicationInstanceWritable(regular, "user-1", "MEMBER");

        verify(orgAccessService, never()).canWrite(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A member restricted on an EXISTING org workflow is blocked (403) before any save")
    void deniesRestrictedMemberOnExistingOrgWorkflow() {
        UUID id = UUID.randomUUID();
        WorkflowEntity existing = new WorkflowEntity();
        existing.setId(id);
        existing.setOrganizationId("org-1");
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getTenantId()).thenReturn("user-1");
        when(workflowRepository.findById(id)).thenReturn(Optional.of(existing));
        when(orgAccessService.canWrite("org-1", "user-1", "workflow", id.toString(), "MEMBER"))
                .thenReturn(false);

        assertThatThrownBy(() -> service.saveWorkflow(plan, Map.of(), id, "org-1", "MEMBER"))
                .isInstanceOf(OrgAccessDeniedException.class);

        // The gate fires BEFORE the workflow is ever persisted.
        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("An ALLOWED member (canWrite=true) is not blocked by the gate - guards against an inverted check")
    void allowedMemberIsNotBlockedByTheGate() {
        UUID id = UUID.randomUUID();
        WorkflowEntity existing = new WorkflowEntity();
        existing.setId(id);
        existing.setOrganizationId("org-1");
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getTenantId()).thenReturn("user-1");
        when(workflowRepository.findById(id)).thenReturn(Optional.of(existing));
        when(orgAccessService.canWrite("org-1", "user-1", "workflow", id.toString(), "MEMBER"))
                .thenReturn(true);

        // canWrite=true ⇒ the gate must NOT raise OrgAccessDeniedException. The delegated 4-arg save
        // then runs against mocked collaborators (and fails downstream for unrelated reasons), so we
        // assert specifically that whatever propagates is NOT the gate's deny - i.e. an authorized
        // member is never blocked here. An inverted `if (canWrite)` check would fail this test.
        assertThatThrownBy(() -> service.saveWorkflow(plan, Map.of(), id, "org-1", "MEMBER"))
                .isNotInstanceOf(OrgAccessDeniedException.class);
    }

    @Test
    @DisplayName("resetApplicationPlan: a member restricted on the application is blocked (403) before the plan is reset")
    void deniesRestrictedMemberOnResetPlan() {
        // reset-plan is the ONLY plan-write route for an acquired APPLICATION (restoreVersion refuses
        // applications), so without this gate it is a restricted member's open plan-write bypass.
        UUID id = UUID.randomUUID();
        WorkflowEntity existing = new WorkflowEntity();
        existing.setId(id);
        existing.setTenantId("user-1");        // caller == owner ⇒ strict scope trivially passes
        existing.setOrganizationId("org-1");
        when(workflowRepository.findById(id)).thenReturn(Optional.of(existing));
        // Gate fires AFTER the scope check but BEFORE the APPLICATION-type / basePlan checks, so the
        // member is denied regardless of those (role resolved from the request context → null here,
        // matched by any()).
        when(orgAccessService.canWrite(eq("org-1"), eq("user-1"), eq("workflow"), eq(id.toString()), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.resetApplicationPlan(id, "user-1", "org-1"))
                .isInstanceOf(OrgAccessDeniedException.class);

        verify(workflowRepository, never()).save(any());
    }

    // ── Application deny-list gate ─────────────────────────────────────────────────────────────
    // An acquired APPLICATION instance is deny-listed by its SOURCE PUBLICATION id under the
    // "application" type (the id the member-access modal writes and /app/applications uses), NOT by
    // its workflow id. The generic "workflow" gate never catches that, so reset / delete / status
    // must ALSO consult the "application" gate. These pin that: the workflow gate passes (true) yet
    // the member is still blocked because the application gate denies.

    private static final String PUB_ID = "11111111-1111-1111-1111-111111111111";

    private static WorkflowEntity acquiredApp(UUID id, UUID pubId) {
        WorkflowEntity app = new WorkflowEntity();
        app.setId(id);
        app.setTenantId("user-1");          // caller == owner ⇒ strict scope passes
        app.setOrganizationId("org-1");
        app.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        app.setSourcePublicationId(pubId);
        return app;
    }

    @Test
    @DisplayName("resetApplicationPlan: restricted on the APPLICATION (publication id) blocks even when the workflow-id gate passes")
    void resetPlanDeniesMemberRestrictedOnApplication() {
        UUID id = UUID.randomUUID();
        UUID pubId = UUID.fromString(PUB_ID);
        when(workflowRepository.findById(id)).thenReturn(Optional.of(acquiredApp(id, pubId)));
        when(orgAccessService.canWrite(eq("org-1"), eq("user-1"), eq("workflow"), eq(id.toString()), any()))
                .thenReturn(true);
        when(orgAccessService.canWrite(eq("org-1"), eq("user-1"), eq("application"), eq(pubId.toString()), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.resetApplicationPlan(id, "user-1", "org-1"))
                .isInstanceOf(OrgAccessDeniedException.class)
                .hasMessageContaining("application");

        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("resetApplicationPlan: an ALLOWED member (application gate passes) resets - guards against an inverted application check")
    void resetPlanAllowsWhenApplicationGatePasses() {
        UUID id = UUID.randomUUID();
        UUID pubId = UUID.fromString(PUB_ID);
        WorkflowEntity app = acquiredApp(id, pubId);
        app.setBasePlan(new java.util.HashMap<>(Map.of("steps", java.util.List.of())));
        when(workflowRepository.findById(id)).thenReturn(Optional.of(app));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orgAccessService.canWrite(eq("org-1"), eq("user-1"), eq("workflow"), eq(id.toString()), any()))
                .thenReturn(true);
        when(orgAccessService.canWrite(eq("org-1"), eq("user-1"), eq("application"), eq(pubId.toString()), any()))
                .thenReturn(true);

        service.resetApplicationPlan(id, "user-1", "org-1");

        verify(workflowRepository).save(app);
    }

    @Test
    @DisplayName("deleteWorkflow: restricted on the APPLICATION (publication id) blocks even when the workflow-id gate passes")
    void deleteDeniesMemberRestrictedOnApplication() {
        UUID id = UUID.randomUUID();
        UUID pubId = UUID.fromString(PUB_ID);
        bindOrgRequestContext("org-1");
        when(workflowRepository.findById(id)).thenReturn(Optional.of(acquiredApp(id, pubId)));
        when(orgAccessService.canWrite("org-1", "user-1", "workflow", id.toString(), "MEMBER")).thenReturn(true);
        when(orgAccessService.canWrite("org-1", "user-1", "application", pubId.toString(), "MEMBER")).thenReturn(false);

        assertThatThrownBy(() -> service.deleteWorkflow(id, "user-1", "MEMBER"))
                .isInstanceOf(OrgAccessDeniedException.class)
                .hasMessageContaining("application");

        verify(workflowRepository, never()).deleteById(any());
    }

    @Test
    @DisplayName("updateWorkflowStatus: an ALLOWED member (application gate passes) toggles status - symmetry with reset-allow")
    void statusAllowsWhenApplicationGatePasses() {
        UUID id = UUID.randomUUID();
        UUID pubId = UUID.fromString(PUB_ID);
        bindOrgRequestContext("org-1");
        when(workflowRepository.findById(id)).thenReturn(Optional.of(acquiredApp(id, pubId)));
        when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orgAccessService.canWrite("org-1", "user-1", "workflow", id.toString(), "MEMBER")).thenReturn(true);
        when(orgAccessService.canWrite("org-1", "user-1", "application", pubId.toString(), "MEMBER")).thenReturn(true);

        WorkflowEntity result = service.updateWorkflowStatus(
                id, "user-1", WorkflowEntity.WorkflowStatus.ACTIVE, "MEMBER");

        assertThat(result.getStatus()).isEqualTo(WorkflowEntity.WorkflowStatus.ACTIVE);
        verify(workflowRepository).save(any());
    }

    @Test
    @DisplayName("updateWorkflowStatus: restricted on the APPLICATION (publication id) blocks even when the workflow-id gate passes")
    void statusDeniesMemberRestrictedOnApplication() {
        UUID id = UUID.randomUUID();
        UUID pubId = UUID.fromString(PUB_ID);
        bindOrgRequestContext("org-1");
        when(workflowRepository.findById(id)).thenReturn(Optional.of(acquiredApp(id, pubId)));
        when(orgAccessService.canWrite("org-1", "user-1", "workflow", id.toString(), "MEMBER")).thenReturn(true);
        when(orgAccessService.canWrite("org-1", "user-1", "application", pubId.toString(), "MEMBER")).thenReturn(false);

        assertThatThrownBy(() -> service.updateWorkflowStatus(
                id, "user-1", WorkflowEntity.WorkflowStatus.ACTIVE, "MEMBER"))
                .isInstanceOf(OrgAccessDeniedException.class)
                .hasMessageContaining("application");

        verify(workflowRepository, never()).save(any());
    }
}
