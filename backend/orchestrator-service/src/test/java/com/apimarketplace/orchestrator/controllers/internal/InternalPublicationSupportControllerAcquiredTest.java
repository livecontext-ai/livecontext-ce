package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Regression tests for {@code InternalPublicationSupportController.getAcquiredWorkflows}
 * - the 2026-05-21 user-reported Applications-page-404 fix.
 *
 * <p>Pre-fix: {@code organizationId} was declared {@code required=true}. When
 * the upstream caller didn't pass an orgId (personal-scope legacy paths,
 * missing X-Active-Organization-ID), Spring rejected the request with 404 →
 * the Applications page showed zero acquired apps.
 *
 * <p>Post-fix: {@code organizationId} is {@code required=false}. When present
 * and non-blank, the org-scoped finder is used (strict equality on
 * organization_id). When absent or blank, the new tenant-scoped finder is
 * used (filters on tenant_id + organization_id IS NULL - personal-scope
 * acquisitions only, never cross-org bleed for multi-org users).
 *
 * <p>Audit-A MEDIUM follow-up: the tenant-scoped finder MUST scope to
 * {@code organization_id IS NULL} to avoid leaking acquisitions from every
 * org the user belongs to when the personal-scope fallback fires.
 *
 * <p>This test layer is deliberately controller-only (Mockito on the repo)
 * - the @DataJpaIntegrationTest sibling class has a pre-existing systemic
 * issue with V263 OrgScopedEntity fail-loud during test setup that's
 * orthogonal to the routing/fallback contract we're pinning here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationSupportController.getAcquiredWorkflows")
class InternalPublicationSupportControllerAcquiredTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowPlanVersionService planVersionService;
    @Mock private com.apimarketplace.orchestrator.services.WorkflowManagementService workflowManagementService;

    private InternalPublicationSupportController controller;

    private static final String TENANT = "user-5";
    private static final String ORG_A = "org-a";
    private static final String ORG_B = "org-b";
    private static final WorkflowEntity.WorkflowType APPLICATION = WorkflowEntity.WorkflowType.APPLICATION;

    @BeforeEach
    void setUp() {
        // 19 collaborators on this controller - we mock only what the tested
        // endpoints read (WorkflowRepository, plan versions, management
        // service). The rest are null since the test methods don't touch
        // them. If Spring ever wires additional deps into these endpoints,
        // this test will surface the breakage at compile time (constructor
        // arity mismatch).
        controller = new InternalPublicationSupportController(
                workflowRepository, null, null, planVersionService, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                workflowManagementService, null);
    }

    @Test
    @DisplayName("with orgId present: uses org-scoped finder (back-compat - never falls through to tenant)")
    void withOrgIdUsesOrgScopedFinder() {
        WorkflowEntity orgWorkflow = workflowWith(ORG_A, "Org A Acquisition", UUID.randomUUID());
        when(workflowRepository.findAcquiredByOrganizationId(ORG_A, APPLICATION))
                .thenReturn(List.of(orgWorkflow));

        ResponseEntity<?> response = controller.getAcquiredWorkflows(TENANT, ORG_A);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertThat(body).hasSize(1);
        assertThat(body.get(0)).containsEntry("title", "Org A Acquisition");
        assertThat(body.get(0)).containsEntry("organizationId", ORG_A);

        // Strict back-compat: org-scoped path MUST NOT fall through to the
        // tenant finder when orgId is provided - that would leak acquisitions
        // from other orgs the same user belongs to.
        verify(workflowRepository).findAcquiredByOrganizationId(ORG_A, APPLICATION);
        verify(workflowRepository, never()).findAcquiredByTenantId(TENANT, APPLICATION);
    }

    @Test
    @DisplayName("A1: payload carries the clone's entry interface id (lean) so My-Purchases can fall back to the local clone")
    void payloadIncludesEntryInterfaceId() {
        WorkflowEntity withIface = workflowWith(ORG_A, "App With Interface", UUID.randomUUID());
        withIface.setPlan(Map.of("interfaces", List.of(
                Map.of("id", "iface-a", "isEntryInterface", false),
                Map.of("id", "iface-entry", "isEntryInterface", true))));
        WorkflowEntity workflowOnly = workflowWith(ORG_A, "Workflow Only", UUID.randomUUID());
        workflowOnly.setPlan(Map.of("triggers", List.of()));
        when(workflowRepository.findAcquiredByOrganizationId(ORG_A, APPLICATION))
                .thenReturn(List.of(withIface, workflowOnly));

        ResponseEntity<?> response = controller.getAcquiredWorkflows(TENANT, ORG_A);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertThat(body.get(0)).containsEntry("entryInterfaceId", "iface-entry");
        assertThat(body.get(1).get("entryInterfaceId")).isNull(); // workflow-only clone: nothing to render locally
    }

    @Test
    @DisplayName("2026-05-21 fix: with orgId NULL, falls back to tenant-scoped finder (Applications page no longer 404s on personal scope)")
    void withNullOrgIdFallsBackToTenantFinder() {
        WorkflowEntity personalWorkflow = workflowWith(null, "Personal Acquisition", UUID.randomUUID());
        when(workflowRepository.findAcquiredByTenantId(TENANT, APPLICATION))
                .thenReturn(List.of(personalWorkflow));

        ResponseEntity<?> response = controller.getAcquiredWorkflows(TENANT, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertThat(body).hasSize(1);
        assertThat(body.get(0)).containsEntry("title", "Personal Acquisition");
        // organizationId field is null in the response when the entity has none.
        assertThat(body.get(0).get("organizationId")).isNull();

        // Critical regression guard: tenant fallback fires, org finder NEVER
        // called with null. A future refactor that re-introduces required=true
        // would silently return 400/404 in the controller layer.
        verify(workflowRepository).findAcquiredByTenantId(TENANT, APPLICATION);
        verify(workflowRepository, never()).findAcquiredByOrganizationId(null, APPLICATION);
    }

    @Test
    @DisplayName("with orgId blank (whitespace): falls back to tenant finder, same as null")
    void withBlankOrgIdFallsBackToTenantFinder() {
        WorkflowEntity personalWorkflow = workflowWith(null, "Personal Acq", UUID.randomUUID());
        when(workflowRepository.findAcquiredByTenantId(TENANT, APPLICATION))
                .thenReturn(List.of(personalWorkflow));

        ResponseEntity<?> response = controller.getAcquiredWorkflows(TENANT, "   ");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(workflowRepository).findAcquiredByTenantId(TENANT, APPLICATION);
        // Critical: blank-string orgId must NOT be passed to the org finder
        // (it would WHERE-clause-match empty string instead of acting as
        // "no org" - a SQL gotcha specific to PostgreSQL where '' is NOT NULL).
        verify(workflowRepository, never()).findAcquiredByOrganizationId("   ", APPLICATION);
    }

    @Test
    @DisplayName("with orgId empty string: falls back to tenant finder (defense-in-depth on hasText)")
    void withEmptyOrgIdFallsBackToTenantFinder() {
        when(workflowRepository.findAcquiredByTenantId(TENANT, APPLICATION))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.getAcquiredWorkflows(TENANT, "");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(workflowRepository).findAcquiredByTenantId(TENANT, APPLICATION);
        verify(workflowRepository, never()).findAcquiredByOrganizationId("", APPLICATION);
    }

    @Test
    @DisplayName("with orgId present: empty result still returns 200 (not 404 - preserves the fix's contract)")
    void emptyOrgScopeStillReturns200() {
        when(workflowRepository.findAcquiredByOrganizationId(ORG_A, APPLICATION))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.getAcquiredWorkflows(TENANT, ORG_A);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> body = (List<Map<String, Object>>) response.getBody();
        assertThat(body).isEmpty();
    }

    @Test
    @DisplayName("acquired application creation writes an initial plan version so Live activation can pin it")
    void createApplicationWorkflowCreatesInitialPlanVersion() {
        UUID requestedId = UUID.randomUUID();
        Map<String, Object> plan = Map.of(
                "name", "Acquired app",
                "triggers", List.of(Map.of("id", "scheduled-start", "type", "schedule")));

        when(workflowRepository.existsById(requestedId)).thenReturn(false);
        when(workflowRepository.save(any(WorkflowEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(planVersionService.createVersion(eq(requestedId), any(), eq(TENANT), eq("Application acquisition")))
                .thenReturn(1);

        ResponseEntity<?> response = controller.createApplicationWorkflow(Map.of(
                "id", requestedId.toString(),
                "title", "Acquired app",
                "description", "Created from marketplace",
                "plan", plan,
                "basePlan", plan,
                "sourcePublicationId", UUID.randomUUID().toString(),
                "organizationId", ORG_A), TENANT, ORG_A);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(workflowRepository).save(argThat(workflow -> ORG_A.equals(workflow.getOrganizationId())));
        verify(planVersionService).createVersion(eq(requestedId), eq(plan), eq(TENANT), eq("Application acquisition"));
    }

    @Test
    @DisplayName("create-application without workflowType keeps stamping APPLICATION (root-clone default, back-compat)")
    void createApplicationWorkflowDefaultsToApplicationType() {
        when(workflowRepository.save(any(WorkflowEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createApplicationWorkflow(Map.of(
                "title", "Acquired root",
                "plan", Map.of("name", "Acquired root"),
                "basePlan", Map.of("name", "Acquired root"),
                "sourcePublicationId", UUID.randomUUID().toString(),
                "organizationId", ORG_A), TENANT, ORG_A);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(workflowRepository).save(argThat(workflow ->
                workflow.getWorkflowType() == WorkflowEntity.WorkflowType.APPLICATION));
    }

    @Test
    @DisplayName("V268 duplicate-key regression: workflowType=WORKFLOW creates a standard row so sub-workflow children never collide with the root APPLICATION")
    void createApplicationWorkflowHonorsWorkflowTypeForSubWorkflowClones() {
        when(workflowRepository.save(any(WorkflowEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<?> response = controller.createApplicationWorkflow(Map.of(
                "title", "Acquired sub-workflow",
                "plan", Map.of("name", "Acquired sub-workflow"),
                "basePlan", Map.of("name", "Acquired sub-workflow"),
                "sourcePublicationId", UUID.randomUUID().toString(),
                "workflowType", "WORKFLOW",
                "organizationId", ORG_A), TENANT, ORG_A);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(workflowRepository).save(argThat(workflow ->
                workflow.getWorkflowType() == WorkflowEntity.WorkflowType.WORKFLOW));
    }

    @Test
    @DisplayName("create-application rejects an unknown workflowType with 400 instead of persisting a mistyped row")
    void createApplicationWorkflowRejectsUnknownWorkflowType() {
        ResponseEntity<?> response = controller.createApplicationWorkflow(Map.of(
                "title", "Broken clone",
                "plan", Map.of("name", "Broken clone"),
                "basePlan", Map.of("name", "Broken clone"),
                "sourcePublicationId", UUID.randomUUID().toString(),
                "workflowType", "NOT_A_TYPE",
                "organizationId", ORG_A), TENANT, ORG_A);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(workflowRepository, never()).save(any(WorkflowEntity.class));
    }

    @Test
    @DisplayName("delete-acquired drops a clone tagged with the publication via the canonical cascade delete")
    void deleteAcquiredWorkflowDeletesMatchingClone() {
        UUID workflowId = UUID.randomUUID();
        UUID pubId = UUID.randomUUID();
        WorkflowEntity clone = new WorkflowEntity(TENANT, "Cloned child", TENANT);
        clone.setId(workflowId);
        clone.setSourcePublicationId(pubId);
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(clone));
        when(workflowManagementService.deleteWorkflow(workflowId, TENANT)).thenReturn(true);

        ResponseEntity<?> response = controller.deleteAcquiredWorkflow(workflowId, pubId, TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(workflowManagementService).deleteWorkflow(workflowId, TENANT);
    }

    @Test
    @DisplayName("delete-acquired refuses a workflow not tagged with the given publication (compensation cannot delete arbitrary workflows)")
    void deleteAcquiredWorkflowRefusesUnrelatedWorkflow() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity unrelated = new WorkflowEntity(TENANT, "User workflow", TENANT);
        unrelated.setId(workflowId);
        unrelated.setSourcePublicationId(null);
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(unrelated));

        ResponseEntity<?> response = controller.deleteAcquiredWorkflow(workflowId, UUID.randomUUID(), TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(workflowManagementService, never()).deleteWorkflow(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("delete-acquired refuses a clone owned by a different tenant")
    void deleteAcquiredWorkflowRefusesForeignTenant() {
        UUID workflowId = UUID.randomUUID();
        UUID pubId = UUID.randomUUID();
        WorkflowEntity clone = new WorkflowEntity("other-user", "Foreign clone", "other-user");
        clone.setId(workflowId);
        clone.setSourcePublicationId(pubId);
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(clone));

        ResponseEntity<?> response = controller.deleteAcquiredWorkflow(workflowId, pubId, TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(workflowManagementService, never()).deleteWorkflow(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("create-application stores provenance metadata (duplicatedFromApplicationId) for a decoupled editable duplicate")
    void createApplicationWorkflowStoresProvenanceMetadata() {
        when(workflowRepository.save(any(WorkflowEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        String appId = UUID.randomUUID().toString();

        ResponseEntity<?> response = controller.createApplicationWorkflow(new java.util.HashMap<>(Map.of(
                "title", "Editable twin",
                "plan", Map.of("name", "Editable twin"),
                "basePlan", Map.of("name", "Editable twin"),
                "workflowType", "WORKFLOW",
                "metadata", Map.of("duplicatedFromApplicationId", appId),
                "organizationId", ORG_A)), TENANT, ORG_A);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // No sourcePublicationId in the request -> the row stays decoupled; lineage rides in metadata.
        verify(workflowRepository).save(argThat(workflow ->
                workflow.getSourcePublicationId() == null
                        && workflow.getMetadata() != null
                        && appId.equals(workflow.getMetadata().get("duplicatedFromApplicationId"))));
    }

    @Test
    @DisplayName("delete-decoupled-duplicate drops a plain decoupled WORKFLOW (null source) via the canonical cascade delete")
    void deleteDecoupledDuplicateDeletesPlainDecoupledWorkflow() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity twin = new WorkflowEntity(TENANT, "Editable twin", TENANT);
        twin.setId(workflowId);
        twin.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
        twin.setSourcePublicationId(null);
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(twin));
        when(workflowManagementService.deleteWorkflow(workflowId, TENANT)).thenReturn(true);

        ResponseEntity<?> response = controller.deleteDecoupledDuplicateWorkflow(workflowId, TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(workflowManagementService).deleteWorkflow(workflowId, TENANT);
    }

    @Test
    @DisplayName("delete-decoupled-duplicate refuses an APPLICATION row (it has a source tag + uses deleteAcquiredWorkflow)")
    void deleteDecoupledDuplicateRefusesApplicationRow() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity app = new WorkflowEntity(TENANT, "Acquired app", TENANT);
        app.setId(workflowId);
        app.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        app.setSourcePublicationId(UUID.randomUUID());
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(app));

        ResponseEntity<?> response = controller.deleteDecoupledDuplicateWorkflow(workflowId, TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(workflowManagementService, never()).deleteWorkflow(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("delete-decoupled-duplicate refuses a source-tagged WORKFLOW (only a NULL source is a decoupled twin)")
    void deleteDecoupledDuplicateRefusesSourceTaggedWorkflow() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity sourced = new WorkflowEntity(TENANT, "Acquired sub-workflow", TENANT);
        sourced.setId(workflowId);
        sourced.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
        sourced.setSourcePublicationId(UUID.randomUUID());
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(sourced));

        ResponseEntity<?> response = controller.deleteDecoupledDuplicateWorkflow(workflowId, TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(workflowManagementService, never()).deleteWorkflow(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("delete-decoupled-duplicate refuses a twin owned by a different tenant")
    void deleteDecoupledDuplicateRefusesForeignTenant() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity twin = new WorkflowEntity("other-user", "Foreign twin", "other-user");
        twin.setId(workflowId);
        twin.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
        twin.setSourcePublicationId(null);
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(twin));

        ResponseEntity<?> response = controller.deleteDecoupledDuplicateWorkflow(workflowId, TENANT);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(workflowManagementService, never()).deleteWorkflow(any(UUID.class), any(String.class));
    }

    @Test
    @DisplayName("count-by-org returns the strict-org workflow count (the WORKFLOW-quota count the duplicate path bills against)")
    void countWorkflowsByOrgReturnsStrictOrgCount() {
        when(workflowRepository.countByOrganizationIdStrict(ORG_A)).thenReturn(7L);

        ResponseEntity<Long> response = controller.countWorkflowsByOrg(ORG_A);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(7L);
    }

    @Test
    @DisplayName("for-publication returns same-organization workflow owned by another tenant")
    void getWorkflowForPublicationAllowsSameOrganizationTeammate() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity("owner-1", "Shared workflow", "owner-1");
        workflow.setId(workflowId);
        workflow.setOrganizationId(ORG_A);
        workflow.setPlan(Map.of("nodes", List.of()));
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(workflow));

        ResponseEntity<?> response = controller.getWorkflowForPublication(workflowId, TENANT, ORG_A);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("tenantId", "owner-1")
                .containsEntry("organizationId", ORG_A)
                .containsEntry("name", "Shared workflow");
    }

    @Test
    @DisplayName("for-publication hides workflows outside the caller workspace")
    void getWorkflowForPublicationHidesOutOfScopeWorkflow() {
        UUID workflowId = UUID.randomUUID();
        WorkflowEntity workflow = new WorkflowEntity("owner-1", "Foreign workflow", "owner-1");
        workflow.setId(workflowId);
        workflow.setOrganizationId(ORG_B);
        workflow.setPlan(Map.of("nodes", List.of()));
        when(workflowRepository.findById(workflowId)).thenReturn(java.util.Optional.of(workflow));

        ResponseEntity<?> response = controller.getWorkflowForPublication(workflowId, TENANT, ORG_A);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    private WorkflowEntity workflowWith(String orgId, String name, UUID sourcePubId) {
        WorkflowEntity entity = new WorkflowEntity(TENANT, name, TENANT);
        entity.setId(UUID.randomUUID());
        if (orgId != null) entity.setOrganizationId(orgId);
        entity.setSourcePublicationId(sourcePubId);
        entity.setAcquiredAt(Instant.now());
        return entity;
    }
}
