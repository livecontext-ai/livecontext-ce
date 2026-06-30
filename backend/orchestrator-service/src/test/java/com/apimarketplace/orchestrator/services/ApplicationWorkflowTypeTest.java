package com.apimarketplace.orchestrator.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.apimarketplace.common.storage.service.StorageBreakdownService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for APPLICATION workflow type functionality:
 * - listWorkflows excludes APPLICATION type
 * - listApplications returns only APPLICATION type
 * - resetApplicationPlan copies basePlan to plan
 * - resetApplicationPlan rejects non-APPLICATION workflows
 * - WorkflowEntity.isApplication() convenience method
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Application Workflow Type")
class ApplicationWorkflowTypeTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private StorageBreakdownService breakdownService;

    private WorkflowManagementService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowManagementService();

        // Inject mocks via reflection (service uses @Autowired fields)
        setField(service, "workflowRepository", workflowRepository);
        setField(service, "orgAccessService", orgAccessService);
        setField(service, "breakdownService", breakdownService);
        // v5: cloneWorkflow + resetPlan now use objectMapper for PlanStripUtils.deepCopyAndStrip
        // (F4 PUB-HIJACK fix). Inject a real ObjectMapper - no Jackson mocking needed.
        setField(service, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }

    // ========================================================================
    // WorkflowEntity tests
    // ========================================================================

    @Nested
    @DisplayName("WorkflowEntity")
    class WorkflowEntityTests {

        @Test
        @DisplayName("isApplication() returns true for APPLICATION type")
        void isApplication_returnsTrue_forApplicationType() {
            WorkflowEntity entity = new WorkflowEntity();
            entity.setWorkflowType(WorkflowType.APPLICATION);
            assertThat(entity.isApplication()).isTrue();
        }

        @Test
        @DisplayName("isApplication() returns false for WORKFLOW type")
        void isApplication_returnsFalse_forWorkflowType() {
            WorkflowEntity entity = new WorkflowEntity();
            entity.setWorkflowType(WorkflowType.WORKFLOW);
            assertThat(entity.isApplication()).isFalse();
        }

        @Test
        @DisplayName("default workflowType is WORKFLOW")
        void defaultWorkflowType_isWorkflow() {
            WorkflowEntity entity = new WorkflowEntity();
            assertThat(entity.getWorkflowType()).isEqualTo(WorkflowType.WORKFLOW);
        }

        @Test
        @DisplayName("basePlan can be set and retrieved")
        void basePlan_canBeSetAndRetrieved() {
            WorkflowEntity entity = new WorkflowEntity();
            Map<String, Object> basePlan = Map.of("triggers", List.of(), "steps", List.of());
            entity.setBasePlan(basePlan);
            assertThat(entity.getBasePlan()).isEqualTo(basePlan);
        }
    }

    // ========================================================================
    // saveWorkflow APPLICATION-immutability guard (2026-05-15)
    // ========================================================================

    @Nested
    @DisplayName("saveWorkflow APPLICATION immutability gate")
    class SaveWorkflowApplicationGate {

        @Test
        @DisplayName("saveWorkflow on an existing APPLICATION workflow throws ApplicationPlanImmutableException - closes POST /v2/workflows/dag bypass")
        void saveWorkflow_rejectsExistingApplication() {
            UUID id = UUID.randomUUID();
            WorkflowEntity existing = new WorkflowEntity();
            setField(existing, "id", id);
            existing.setTenantId("t");
            existing.setWorkflowType(WorkflowType.APPLICATION);
            when(workflowRepository.findById(id)).thenReturn(Optional.of(existing));

            Map<String, Object> planMap = new HashMap<>(Map.of(
                    "triggers", List.of(), "mcps", List.of(),
                    "cores", List.of(), "edges", List.of()));
            com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan plan =
                    com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.fromMap(planMap, id.toString(), "t");

            assertThatThrownBy(() -> service.saveWorkflow(plan, null, id))
                    .isInstanceOf(com.apimarketplace.orchestrator.exception.ApplicationPlanImmutableException.class)
                    .hasMessageContaining("APPLICATION")
                    .hasMessageContaining("reset-plan");

            // No save, no setPlan - partial-write window closed even on the rejection path.
            verify(workflowRepository, org.mockito.Mockito.never()).save(any());
        }

        @Test
        @DisplayName("saveDraft on an existing APPLICATION workflow throws - closes WorkflowDraftAutoSaver bypass on read_rows/find_rows")
        void saveDraft_rejectsExistingApplication() {
            UUID id = UUID.randomUUID();
            WorkflowEntity existing = new WorkflowEntity();
            setField(existing, "id", id);
            existing.setTenantId("t");
            existing.setWorkflowType(WorkflowType.APPLICATION);
            when(workflowRepository.findById(id)).thenReturn(Optional.of(existing));

            Map<String, Object> planMap = new HashMap<>(Map.of("name", "Acquired Draft"));

            assertThatThrownBy(() -> service.saveDraft(planMap, "t", id))
                    .isInstanceOf(com.apimarketplace.orchestrator.exception.ApplicationPlanImmutableException.class)
                    .hasMessageContaining("APPLICATION");

            // saveDraft never writes through to the frozen entity - updated_at not bumped,
            // no DB write. WorkflowDraftAutoSaver wraps this call in try/catch so the
            // throw is swallowed and the underlying read action result is unaffected.
            verify(workflowRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    // ========================================================================
    // listWorkflows tests
    // ========================================================================

    @Nested
    @DisplayName("listWorkflows")
    class ListWorkflowsTests {

        @Test
        @DisplayName("Phase 6 H-4: no orgId returns empty list (no tenant-only fallback that would bleed cross-workspace)")
        void listWorkflows_returnsEmpty_whenNoOrg() {
            // Phase 6 MIGRATION_ORG_ID_NOT_NULL (H-4, 2026-05-19): the legacy
            // tenant-only fallback (findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc)
            // was removed. With no orgId, the defensive empty-list path returns
            // without touching the repository - pre-fix the fallback leaked
            // cross-workspace rows for users in multiple orgs.
            String tenantId = "user1";

            List<WorkflowEntity> result = service.listWorkflows(tenantId, null, null);
            assertThat(result).isEmpty();

            verify(workflowRepository, never()).findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
            verify(workflowRepository, never()).findByOrganizationOrOwnerAndType(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("filters to WORKFLOW type with org")
        void listWorkflows_filtersToWorkflowType_withOrg() {
            String tenantId = "user1";
            String orgId = "org1";
            String orgRole = "member";

            WorkflowEntity wf = new WorkflowEntity(tenantId, "Test", tenantId);
            wf.setId(UUID.randomUUID());
            List<WorkflowEntity> workflows = List.of(wf);

            when(workflowRepository.findByOrganizationOrOwnerAndType(orgId, tenantId, WorkflowType.WORKFLOW))
                    .thenReturn(workflows);
            when(orgAccessService.filterAccessible(eq(workflows), eq(orgId), eq(tenantId),
                    eq("workflow"), eq(orgRole), any()))
                    .thenReturn(workflows);

            List<WorkflowEntity> result = service.listWorkflows(tenantId, orgId, orgRole);
            assertThat(result).hasSize(1);

            verify(workflowRepository).findByOrganizationOrOwnerAndType(orgId, tenantId, WorkflowType.WORKFLOW);
        }
    }

    // ========================================================================
    // listApplications tests
    // ========================================================================

    @Nested
    @DisplayName("listApplications")
    class ListApplicationsTests {

        @Test
        @DisplayName("Phase 6 H-4: no orgId returns empty list (no tenant-only fallback)")
        void listApplications_returnsEmpty_whenNoOrg() {
            String tenantId = "user1";

            List<WorkflowEntity> result = service.listApplications(tenantId, null, null);
            assertThat(result).isEmpty();

            verify(workflowRepository, never()).findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        }

        @Test
        @DisplayName("filters to APPLICATION type with org")
        void listApplications_filtersToApplicationType_withOrg() {
            String tenantId = "user1";
            String orgId = "org1";
            String orgRole = "member";

            WorkflowEntity app = new WorkflowEntity(tenantId, "App", tenantId);
            app.setId(UUID.randomUUID());
            app.setWorkflowType(WorkflowType.APPLICATION);
            List<WorkflowEntity> workflows = List.of(app);

            when(workflowRepository.findByOrganizationOrOwnerAndType(orgId, tenantId, WorkflowType.APPLICATION))
                    .thenReturn(workflows);
            when(orgAccessService.filterAccessible(eq(workflows), eq(orgId), eq(tenantId),
                    eq("workflow"), eq(orgRole), any()))
                    .thenReturn(workflows);

            List<WorkflowEntity> result = service.listApplications(tenantId, orgId, orgRole);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).isApplication()).isTrue();
        }
    }

    // ========================================================================
    // resetApplicationPlan tests
    // ========================================================================

    @Nested
    @DisplayName("resetApplicationPlan")
    class ResetApplicationPlanTests {

        @Test
        @DisplayName("copies basePlan to plan for APPLICATION workflow")
        void resetApplicationPlan_copiesBasePlanToPlan() {
            UUID workflowId = UUID.randomUUID();
            String tenantId = "user1";

            Map<String, Object> basePlan = new HashMap<>(Map.of(
                    "triggers", List.of(Map.of("type", "manual")),
                    "steps", List.of(Map.of("key", "mcp:original"))
            ));
            Map<String, Object> customizedPlan = new HashMap<>(Map.of(
                    "triggers", List.of(Map.of("type", "manual")),
                    "steps", List.of(Map.of("key", "mcp:customized"))
            ));

            WorkflowEntity app = new WorkflowEntity(tenantId, "App", tenantId);
            app.setId(workflowId);
            app.setWorkflowType(WorkflowType.APPLICATION);
            app.setBasePlan(basePlan);
            app.setPlan(customizedPlan);

            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(app));
            when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowEntity result = service.resetApplicationPlan(workflowId, tenantId);

            assertThat(result.getPlan()).containsEntry("steps", basePlan.get("steps"));
            verify(workflowRepository).save(app);
        }

        @Test
        @DisplayName("rejects non-APPLICATION workflows")
        void resetApplicationPlan_rejectsNonApplicationWorkflows() {
            UUID workflowId = UUID.randomUUID();
            String tenantId = "user1";

            WorkflowEntity wf = new WorkflowEntity(tenantId, "Workflow", tenantId);
            wf.setId(workflowId);
            wf.setWorkflowType(WorkflowType.WORKFLOW);

            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(wf));

            assertThatThrownBy(() -> service.resetApplicationPlan(workflowId, tenantId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Only APPLICATION workflows can be reset");
        }

        @Test
        @DisplayName("accepts org-teammate resetting a workspace-shared APPLICATION - Phase 6c post-audit regression")
        void resetApplicationPlan_acceptsOrgTeammate() {
            UUID workflowId = UUID.randomUUID();
            Map<String, Object> basePlan = new HashMap<>(Map.of(
                    "steps", List.of(Map.of("key", "mcp:base"))
            ));

            // Owner installed the app in ORG_X; their tenantId is stamped on
            // the row. Teammate is a different user in the same org.
            WorkflowEntity app = new WorkflowEntity("owner_user", "App", "owner_user");
            app.setId(workflowId);
            app.setWorkflowType(WorkflowType.APPLICATION);
            app.setBasePlan(basePlan);
            app.setOrganizationId("org_X");

            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(app));
            when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(orgAccessService.canWrite(eq("org_X"), eq("teammate_user"), eq("workflow"),
                    eq(workflowId.toString()), org.mockito.ArgumentMatchers.isNull()))
                    .thenReturn(true);

            // Caller is the teammate (different tenant) but in the same org.
            WorkflowEntity result = service.resetApplicationPlan(workflowId, "teammate_user", "org_X");

            assertThat(result.getPlan()).containsEntry("steps", basePlan.get("steps"));
            verify(workflowRepository).save(app);
        }

        @Test
        @DisplayName("rejects cross-workspace reset (caller's org differs from workflow's org)")
        void resetApplicationPlan_rejectsCrossWorkspace() {
            UUID workflowId = UUID.randomUUID();
            Map<String, Object> basePlan = new HashMap<>(Map.of("steps", List.of()));

            WorkflowEntity app = new WorkflowEntity("owner_user", "App", "owner_user");
            app.setId(workflowId);
            app.setWorkflowType(WorkflowType.APPLICATION);
            app.setBasePlan(basePlan);
            app.setOrganizationId("org_X");

            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(app));

            assertThatThrownBy(() -> service.resetApplicationPlan(workflowId, "teammate_user", "org_OTHER"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to caller's active workspace");
        }

        @Test
        @DisplayName("rejects workflow not owned by tenant")
        void resetApplicationPlan_rejectsWrongTenant() {
            UUID workflowId = UUID.randomUUID();

            // Phase 6c post-audit (2026-05-19) - predicate now uses
            // ScopeGuard.isInStrictScope; caller in personal scope cannot
            // reset a workflow owned by another personal-scope user (org
            // column is NULL on both sides → tenant equality required).
            WorkflowEntity app = new WorkflowEntity("other-user", "App", "other-user");
            app.setId(workflowId);
            app.setWorkflowType(WorkflowType.APPLICATION);

            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(app));

            assertThatThrownBy(() -> service.resetApplicationPlan(workflowId, "user1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("does not belong to caller's active workspace");
        }

        @Test
        @DisplayName("rejects APPLICATION with no basePlan")
        void resetApplicationPlan_rejectsNoBasePlan() {
            UUID workflowId = UUID.randomUUID();
            String tenantId = "user1";

            WorkflowEntity app = new WorkflowEntity(tenantId, "App", tenantId);
            app.setId(workflowId);
            app.setWorkflowType(WorkflowType.APPLICATION);
            app.setBasePlan(null);

            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(app));

            assertThatThrownBy(() -> service.resetApplicationPlan(workflowId, tenantId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no base plan");
        }

        @Test
        @DisplayName("rejects non-existent workflow")
        void resetApplicationPlan_rejectsNonExistentWorkflow() {
            UUID workflowId = UUID.randomUUID();
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resetApplicationPlan(workflowId, "user1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not found");
        }
    }

    // ========================================================================
    // cloneWorkflow tests
    // ========================================================================

    @Nested
    @DisplayName("cloneWorkflow")
    class CloneWorkflowTests {

        @Test
        @DisplayName("rejects cloning APPLICATION workflows")
        void cloneWorkflow_rejectsApplicationWorkflows() {
            UUID workflowId = UUID.randomUUID();
            String tenantId = "user1";

            WorkflowEntity app = new WorkflowEntity(tenantId, "My App", tenantId);
            app.setId(workflowId);
            app.setWorkflowType(WorkflowType.APPLICATION);
            app.setSourcePublicationId(UUID.randomUUID());

            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(app));

            assertThatThrownBy(() -> service.cloneWorkflow(workflowId, tenantId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Cannot clone APPLICATION workflows");
        }

        @Test
        @DisplayName("allows cloning regular WORKFLOW")
        void cloneWorkflow_allowsRegularWorkflows() {
            UUID workflowId = UUID.randomUUID();
            String tenantId = "user1";

            WorkflowEntity wf = new WorkflowEntity(tenantId, "My Workflow", tenantId);
            wf.setId(workflowId);
            wf.setWorkflowType(WorkflowType.WORKFLOW);
            wf.setPlan(new HashMap<>(Map.of("steps", List.of())));

            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(wf));
            when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            WorkflowEntity cloned = service.cloneWorkflow(workflowId, tenantId);

            assertThat(cloned.getName()).isEqualTo("My Workflow (Copy)");
            assertThat(cloned.getWorkflowType()).isEqualTo(WorkflowType.WORKFLOW);
            assertThat(cloned.getBasePlan()).isNull();
            assertThat(cloned.getSourcePublicationId()).isNull();
        }

        // Removed post-V261: the 4-arg cloneWorkflow(UUID, tenantId, orgId, orgRole)
        // overload that passed orgId explicitly is gone - the 3-arg overload now derives
        // the active org from TenantResolver.currentRequestOrganizationId(), which a plain
        // Mockito unit test cannot set. Org-scoped clone is covered end-to-end by
        // WorkflowCrudControllerIntegrationTest$CloneWorkflow.
    }
}
