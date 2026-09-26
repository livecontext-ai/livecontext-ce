package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class WorkflowPresenterTest {

    private static final String TENANT = "42";
    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final WorkflowPresenter presenter = new WorkflowPresenter(runs, workflows);

    private static final Map<String, Object> PLAN_WITH_INTERFACE =
        Map.of("interfaces", java.util.List.of(Map.of("id", "iface-1", "label", "Results")));
    private final ToolExecutionContext ctx = ToolExecutionContext.of(TENANT);

    /**
     * In production the run's workflow is a LAZY proxy read outside any session: only its
     * id works, getName() throws. The fixture reproduces that, so the name must come from
     * the repository.
     */
    private static WorkflowRunEntity run(String tenantId) {
        WorkflowEntity lazyProxy = mock(WorkflowEntity.class);
        when(lazyProxy.getId()).thenReturn(WORKFLOW_ID);
        when(lazyProxy.getName()).thenThrow(new IllegalStateException("could not initialize proxy - no Session"));
        when(lazyProxy.getPlan()).thenThrow(new IllegalStateException("could not initialize proxy - no Session"));
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflow(lazyProxy);
        run.setTenantId(tenantId);
        run.setRunIdPublic("run-1");
        return run;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> viz(ToolExecutionResult result) {
        return (Map<String, Object>) result.metadata().get("visualization");
    }

    private void storedWorkflow(Map<String, Object> plan) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setName("Lead Finder");
        workflow.setPlan(plan);
        when(workflows.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
    }

    @Test
    @DisplayName("view=application emits present_application keyed on the run's workflow, carrying the run")
    void presentsApplicationOfRun() {
        WorkflowRunEntity stored = run(TENANT);
        when(runs.findByRunIdPublic("run-1")).thenReturn(Optional.of(stored));
        storedWorkflow(PLAN_WITH_INTERFACE);

        ToolExecutionResult result = presenter.present(Map.of("view", "application", "run_id", "run-1"), TENANT, ctx);

        assertThat(result.success()).isTrue();
        assertThat(viz(result)).containsEntry("type", "present_application")
            .containsEntry("id", WORKFLOW_ID.toString())
            .containsEntry("runId", "run-1")
            // the chat labels the side-panel tab with it: the workflow, not the view
            .containsEntry("title", "Lead Finder");
    }

    @Test
    @DisplayName("view=application on a workflow with no interface fails instead of showing an empty tab")
    void refusesWorkflowWithoutInterface() {
        WorkflowRunEntity stored = run(TENANT);
        when(runs.findByRunIdPublic("run-1")).thenReturn(Optional.of(stored));
        storedWorkflow(Map.of("interfaces", java.util.List.of()));

        ToolExecutionResult result = presenter.present(Map.of("view", "application", "run_id", "run-1"), TENANT, ctx);

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(result.error()).contains("table(action='present'").contains("files(action='present'");
    }

    @Test
    @DisplayName("a title passed by the agent wins over the workflow name")
    void titleOverride() {
        WorkflowRunEntity stored = run(TENANT);
        when(runs.findByRunIdPublic("run-1")).thenReturn(Optional.of(stored));
        storedWorkflow(PLAN_WITH_INTERFACE);

        ToolExecutionResult result = presenter.present(
            Map.of("view", "app", "run_id", "run-1", "title", "Your leads"), TENANT, ctx);

        assertThat(viz(result)).containsEntry("title", "Your leads");
    }

    private static ToolExecutionContext restrictedTo(String key, java.util.List<?> ids) {
        return new ToolExecutionContext(TENANT, Map.of(key, ids), Map.of(), java.util.Set.of(), null, null, null, null);
    }

    @Test
    @DisplayName("an agent restricted to other workflows cannot present this run")
    void respectsWorkflowAllowList() {
        WorkflowRunEntity stored = run(TENANT);
        when(runs.findByRunIdPublic("run-1")).thenReturn(Optional.of(stored));

        ToolExecutionResult result = presenter.present(Map.of("view", "application", "run_id", "run-1"), TENANT,
            restrictedTo("allowedWorkflowIds", java.util.List.of(UUID.randomUUID().toString())));

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
    }

    @Test
    @DisplayName("view=application on a run of another tenant is not found and switches nothing")
    void refusesRunOutOfScope() {
        WorkflowRunEntity stored = run("99");
        when(runs.findByRunIdPublic("run-1")).thenReturn(Optional.of(stored));

        ToolExecutionResult result = presenter.present(Map.of("view", "application", "run_id", "run-1"), TENANT, ctx);

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("view=application without run_id is a missing parameter")
    void applicationNeedsRunId() {
        ToolExecutionResult result = presenter.present(Map.of("view", "application"), TENANT, ctx);

        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        verifyNoInteractions(runs);
    }

    @Test
    @DisplayName("an unreadable plan fails open: the presentation goes through")
    void unreadablePlanFailsOpen() {
        WorkflowRunEntity stored = run(TENANT);
        when(runs.findByRunIdPublic("run-1")).thenReturn(Optional.of(stored));
        storedWorkflow(Map.of("interfaces", "not a list"));

        assertThat(presenter.present(Map.of("view", "application", "run_id", "run-1"), TENANT, ctx).success()).isTrue();
    }

    @Test
    @DisplayName("a workflow row that cannot be loaded still presents, titled generically")
    void missingWorkflowRowStillPresents() {
        WorkflowRunEntity stored = run(TENANT);
        when(runs.findByRunIdPublic("run-1")).thenReturn(Optional.of(stored));
        when(workflows.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

        ToolExecutionResult result = presenter.present(Map.of("view", "application", "run_id", "run-1"), TENANT, ctx);

        assertThat(result.success()).isTrue();
        assertThat(viz(result)).containsEntry("title", "Workflow");
    }

    @Test
    @DisplayName("an unknown or missing view is refused with the allowed values")
    void refusesUnknownView() {
        assertThat(presenter.present(Map.of("view", "slides"), TENANT, ctx).errorCode())
            .isEqualTo(ToolErrorCode.INVALID_ENUM_VALUE);
        assertThat(presenter.present(Map.of(), TENANT, ctx).errorCode())
            .isEqualTo(ToolErrorCode.MISSING_PARAMETER);
    }

    @Test
    @DisplayName("a table, interface, agent or file view names the owning tool's present call and reads nothing")
    void resourceViewsPointToTheirOwningTool() {
        Map<String, String> expected = Map.of(
            "table", "table(action='present'",
            "data", "table(action='present'",
            "interface", "interface(action='present'",
            "page", "interface(action='present'",
            "agent", "agent(action='present'",
            "file", "files(action='present'",
            "FILE", "files(action='present'");
        expected.forEach((view, call) -> {
            ToolExecutionResult result = presenter.present(Map.of("view", view, "table_id", "7"), TENANT, ctx);
            assertThat(result.success()).as(view).isFalse();
            assertThat(result.errorCode()).as(view).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(result.error()).as(view).contains(call);
        });
        verifyNoInteractions(runs, workflows);
    }

    // ===== run, workflow =====

    private static ToolExecutionContext inOrg(String role) {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), java.util.Set.of(), null, null, "org-1", role);
    }

    @Test
    @DisplayName("view=run emits present_run (never execute's workflow_run) and needs no interface")
    void presentsRun() {
        WorkflowRunEntity stored = run(TENANT);
        when(runs.findByRunIdPublic("run-1")).thenReturn(Optional.of(stored));
        storedWorkflow(Map.of("interfaces", java.util.List.of()));

        ToolExecutionResult result = presenter.present(Map.of("view", "run", "run_id", "run-1"), TENANT, ctx);

        assertThat(viz(result)).containsEntry("type", "present_run").containsEntry("runId", "run-1")
            .containsEntry("id", WORKFLOW_ID.toString());
    }

    @Test
    @DisplayName("view=workflow presents a workflow of the caller")
    void presentsWorkflow() {
        storedWorkflowOwnedBy(TENANT, null);

        ToolExecutionResult result = presenter.present(
            Map.of("view", "workflow", "workflow_id", WORKFLOW_ID.toString()), TENANT, ctx);

        assertThat(viz(result)).containsEntry("type", "present_workflow").containsEntry("title", "Lead Finder");
    }

    @Test
    @DisplayName("view=workflow of a workflow owned by someone else is not found")
    void refusesForeignWorkflow() {
        storedWorkflowOwnedBy("99", null);

        assertThat(presenter.present(Map.of("view", "workflow", "workflow_id", WORKFLOW_ID.toString()), TENANT, ctx)
            .errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("a workspace member denied the workflow cannot have it presented")
    void respectsMemberDenyList() {
        storedWorkflowOwnedBy(TENANT, "org-1");
        OrgAccessGuard guard = mock(OrgAccessGuard.class);
        when(guard.canAccess("org-1", TENANT, "workflow", WORKFLOW_ID.toString(), "MEMBER")).thenReturn(false);
        presenter.setOrgAccessGuard(guard);

        assertThat(presenter.present(Map.of("view", "workflow", "workflow_id", WORKFLOW_ID.toString()), TENANT, inOrg("MEMBER"))
            .errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
    }

    private void storedWorkflowOwnedBy(String tenantId, String orgId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setName("Lead Finder");
        workflow.setTenantId(tenantId);
        workflow.setOrganizationId(orgId);
        when(workflows.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
    }
}
