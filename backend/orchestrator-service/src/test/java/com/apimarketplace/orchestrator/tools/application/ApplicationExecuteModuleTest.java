package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApplicationExecuteModule}.
 *
 * <p>Post-V261 (Phase 7 strict-org) - these tests pin two regression
 * contracts: (1) callers without {@code orgId} are rejected with
 * {@code AUTHENTICATION_REQUIRED}, never reaching the repository - the
 * pre-fix code path would have dispatched a tenant-only finder and
 * leaked cross-workspace rows; (2) the strict-org finder
 * {@link WorkflowRepository#findByOrganizationIdAndSourcePublicationIdAndWorkflowType}
 * is the only repository surface invoked.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApplicationExecuteModule - post-V261 strict-org execute")
class ApplicationExecuteModuleTest {

    @Mock private WorkflowRepository workflowRepository;
    @Mock private AgentWorkflowFireService agentWorkflowFireService;
    @Mock private com.apimarketplace.orchestrator.services.agent.ConversationEventPublisher conversationEventPublisher;

    private ApplicationExecuteModule module;

    private static final String TENANT_ID = "tenant-123";
    private static final String CALLER_ORG_ID = "org-abc-def";
    private static final UUID PUB_ID = UUID.randomUUID();
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        module = new ApplicationExecuteModule(workflowRepository, agentWorkflowFireService, conversationEventPublisher,
                new com.apimarketplace.orchestrator.services.ApplicationLifecycleService(workflowRepository));
    }

    // ------------------------------------------------------------------
    // canHandle
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        @DisplayName("Returns true for the execute action only")
        void canHandleExecute() {
            assertThat(module.canHandle("execute")).isTrue();
        }

        @Test
        @DisplayName("Returns false for non-execute actions (create / list / acquire / unknown)")
        void rejectsOtherActions() {
            assertThat(module.canHandle("create")).isFalse();
            assertThat(module.canHandle("list")).isFalse();
            assertThat(module.canHandle("acquire")).isFalse();
            assertThat(module.canHandle("delete")).isFalse();
            assertThat(module.canHandle("")).isFalse();
        }
    }

    // ------------------------------------------------------------------
    // Input validation
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Input validation")
    class InputValidation {

        @Test
        @DisplayName("Missing application_id returns MISSING_PARAMETER")
        void missingApplicationIdReturnsMissingParameter() {
            ToolExecutionResult result = module.execute("execute", Map.of(),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verify(workflowRepository, never())
                    .findByOrganizationIdAndSourcePublicationIdAndWorkflowType(any(), any(), any());
        }

        @Test
        @DisplayName("Blank application_id returns MISSING_PARAMETER")
        void blankApplicationIdReturnsMissingParameter() {
            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", "   "),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("Malformed application_id (not a UUID) returns INVALID_PARAMETER_VALUE")
        void malformedApplicationIdReturnsInvalidParameter() {
            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", "not-a-uuid"),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        }
    }

    // ------------------------------------------------------------------
    // Org-scope gating (V261 regression coverage)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Org-scope gating (V261 regression)")
    class OrgScopeGating {

        @Test
        @DisplayName("Missing orgId rejects with AUTHENTICATION_REQUIRED - never hits repository (pre-fix: tenant-only finder leaked cross-workspace rows)")
        void missingOrgIdReturnsAuthRequired() {
            // Context with null orgId - simulates a daemon dispatch that didn't
            // propagate the workspace, or a pre-V261 client that never set the
            // X-Organization-ID header. Pre-fix the module fell through to the
            // tenant-only finder and any teammate could execute a different
            // org's acquired application. Post-fix the gate fails closed.
            ToolExecutionContext noOrgCtx = new ToolExecutionContext(
                    TENANT_ID, Map.of(), Map.of(), Set.of(),
                    null, null, /* orgId */ null, /* orgRole */ null);

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", PUB_ID.toString()),
                    TENANT_ID, noOrgCtx).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.AUTHENTICATION_REQUIRED);
            assertThat(result.error()).contains("organizationId required after V261");
            verify(workflowRepository, never())
                    .findByOrganizationIdAndSourcePublicationIdAndWorkflowType(any(), any(), any());
        }

        @Test
        @DisplayName("Blank orgId rejects with AUTHENTICATION_REQUIRED")
        void blankOrgIdReturnsAuthRequired() {
            ToolExecutionContext blankOrgCtx = new ToolExecutionContext(
                    TENANT_ID, Map.of(), Map.of(), Set.of(),
                    null, null, "   ", null);

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", PUB_ID.toString()),
                    TENANT_ID, blankOrgCtx).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.AUTHENTICATION_REQUIRED);
        }

        @Test
        @DisplayName("With valid orgId, repository is queried by ORG (NOT by tenant) - round-7 cross-workspace fix")
        void validOrgRoutesToStrictOrgFinder() {
            // No workflow found - short-circuits before agentWorkflowFireService is touched.
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    eq(CALLER_ORG_ID), eq(PUB_ID), eq(WorkflowEntity.WorkflowType.APPLICATION)))
                    .thenReturn(Optional.empty());

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            // The strict-org finder is the ONLY repository surface used. The
            // pre-fix tenant-only finder must NOT be reachable from this path.
            verify(workflowRepository).findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    eq(CALLER_ORG_ID), eq(PUB_ID), eq(WorkflowEntity.WorkflowType.APPLICATION));
        }

        @Test
        @DisplayName("workflowId passed as application_id → error echoes the real application_id (hardening 2026-06-05)")
        void executeWithWorkflowIdReturnsDisambiguationHint() {
            // Same foot-gun as get (prod 2026-06-05): my/search emit `id`
            // (application_id) and `workflowId` side by side. Pre-hardening, passing
            // the workflowId to execute dead-ended on the generic "No application
            // found for this ID" 404 - get had a hint, execute did not. Now both
            // share ApplicationIdDisambiguator and echo the correct application_id.
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    eq(CALLER_ORG_ID), eq(WORKFLOW_ID), eq(WorkflowEntity.WorkflowType.APPLICATION)))
                    .thenReturn(Optional.empty());
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(wf.getSourcePublicationId()).thenReturn(PUB_ID);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(wf));

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", WORKFLOW_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            // The corrective message names the real application_id so the agent
            // retries in a single rebound instead of looping on the wrong UUID.
            assertThat(result.error())
                    .contains("workflowId")
                    .contains(PUB_ID.toString());
        }
    }

    // ------------------------------------------------------------------
    // allowedApplicationIds restriction
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("allowedApplicationIds restriction")
    class AllowedAppsRestriction {

        @Test
        @DisplayName("read-only mode (applicationAccessMode='read') denies execute - execute is the application WRITE action")
        void readModeDeniesExecute() {
            Map<String, Object> credentials = new HashMap<>();
            credentials.put("applicationAccessMode", "read");
            ToolExecutionContext ctx = new ToolExecutionContext(
                    TENANT_ID, credentials, Map.of(), Set.of(), null, null, CALLER_ORG_ID, null);

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", PUB_ID.toString()), TENANT_ID, ctx).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("read-only");
            verify(workflowRepository, never())
                    .findByOrganizationIdAndSourcePublicationIdAndWorkflowType(any(), any(), any());
        }

        @Test
        @DisplayName("Application not in agent's allowed list returns PERMISSION_DENIED before scope check")
        void deniesIfNotInAllowedList() {
            Map<String, Object> credentials = new HashMap<>();
            credentials.put("allowedApplicationIds", List.of("some-other-uuid"));
            ToolExecutionContext ctx = new ToolExecutionContext(
                    TENANT_ID, credentials, Map.of(), Set.of(),
                    null, null, CALLER_ORG_ID, null);

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", PUB_ID.toString()),
                    TENANT_ID, ctx).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verify(workflowRepository, never())
                    .findByOrganizationIdAndSourcePublicationIdAndWorkflowType(any(), any(), any());
        }

        @Test
        @DisplayName("Namespaced allowedApplicationIds restriction is enforced before scope check")
        void deniesIfNotInNamespacedAllowedList() {
            Map<String, Object> credentials = new HashMap<>();
            credentials.put("__allowedApplicationIds__", List.of("some-other-uuid"));
            ToolExecutionContext ctx = new ToolExecutionContext(
                    TENANT_ID, credentials, Map.of(), Set.of(),
                    null, null, CALLER_ORG_ID, null);

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", PUB_ID.toString()),
                    TENANT_ID, ctx).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verify(workflowRepository, never())
                    .findByOrganizationIdAndSourcePublicationIdAndWorkflowType(any(), any(), any());
        }
    }

    // ------------------------------------------------------------------
    // Workflow has no plan
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Plan / trigger validation")
    class PlanValidation {

        @Test
        @DisplayName("Acquired workflow with null plan returns EXECUTION_FAILED")
        void nullPlanReturnsExecutionFailed() {
            WorkflowEntity workflow = buildAcquiredWorkflow();
            workflow.setPlan(null);
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    eq(CALLER_ORG_ID), eq(PUB_ID), eq(WorkflowEntity.WorkflowType.APPLICATION)))
                    .thenReturn(Optional.of(workflow));

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }

        @Test
        @DisplayName("Acquired workflow with empty plan returns EXECUTION_FAILED")
        void emptyPlanReturnsExecutionFailed() {
            WorkflowEntity workflow = buildAcquiredWorkflow();
            workflow.setPlan(Map.of());
            when(workflowRepository.findByOrganizationIdAndSourcePublicationIdAndWorkflowType(
                    eq(CALLER_ORG_ID), eq(PUB_ID), eq(WorkflowEntity.WorkflowType.APPLICATION)))
                    .thenReturn(Optional.of(workflow));

            ToolExecutionResult result = module.execute("execute",
                    Map.of("application_id", PUB_ID.toString()),
                    TENANT_ID, contextWithOrg()).orElseThrow();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private ToolExecutionContext contextWithOrg() {
        return new ToolExecutionContext(
                TENANT_ID, Map.of(), Map.of(), Set.of(),
                null, null, CALLER_ORG_ID, null);
    }

    private WorkflowEntity buildAcquiredWorkflow() {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setTenantId(TENANT_ID);
        workflow.setOrganizationId(CALLER_ORG_ID);
        workflow.setName("Test Application");
        workflow.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        workflow.setSourcePublicationId(PUB_ID);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", List.of(Map.of("type", "manual", "label", "Start")));
        plan.put("edges", List.of());
        workflow.setPlan(plan);
        return workflow;
    }
}
