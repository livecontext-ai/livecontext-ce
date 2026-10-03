package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.persistence.StepPayloadService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-004: a task is executed later by an agent whose model is not known when the task is
 * written, so a workflow run holding Gmail / Drive data must never create or update one - there
 * is no way to guarantee the eventual executor is on the restricted-data allow-list.
 */
@DisplayName("TaskNode restricted-data gate")
@ExtendWith(MockitoExtension.class)
class TaskNodeRestrictedDataTest {

    @Mock private WorkflowPlan plan;
    @Mock private AgentClient agentClient;
    @Mock private ServiceRegistry registry;
    @Mock private StepPayloadService stepPayloadService;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "workflow-run-1", "tenant-1", "item-1", 0,
                new HashMap<>(), plan);
        lenient().when(registry.getAgentClient()).thenReturn(agentClient);
        lenient().when(registry.getStepPayloadService()).thenReturn(stepPayloadService);
    }

    @AfterEach
    void resetPolicy() {
        RestrictedDataPolicy.setLlmAllowListEnforced(true);
    }

    private TaskNode node(Core.TaskConfig config) {
        TaskNode node = new TaskNode("core:task", config);
        node.acceptServices(registry);
        return node;
    }

    private static Core.TaskConfig config(String operation) {
        return new Core.TaskConfig(operation, "task-1", "Title", "Instructions",
                null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("restrictedTaskRefusal: null when the run is not restricted")
    void refusalIsNullWhenRunNotRestricted() {
        when(stepPayloadService.isRunRestricted("run-1")).thenReturn(false);

        assertThat(node(config("create_task")).restrictedTaskRefusal(context)).isNull();
    }

    @Test
    @DisplayName("restrictedTaskRefusal: null when stepPayloadService is unavailable (no wiring, no crash)")
    void refusalIsNullWithoutStepPayloadService() {
        when(registry.getStepPayloadService()).thenReturn(null);

        assertThat(node(config("create_task")).restrictedTaskRefusal(context)).isNull();
    }

    @Test
    @DisplayName("restrictedTaskRefusal: named, actionable message when the run is restricted")
    void refusalNamesTheReasonWhenRunIsRestricted() {
        when(stepPayloadService.isRunRestricted("run-1")).thenReturn(true);

        String refusal = node(config("create_task")).restrictedTaskRefusal(context);

        assertThat(refusal).contains(RestrictedDataPolicy.REFUSAL_CODE)
                .contains("Gmail").contains("Google Drive")
                .contains("Anthropic").contains("OpenAI");
    }

    @Test
    @DisplayName("restrictedTaskRefusal: not enforced on this install (CE default) -> null even when restricted")
    void refusalIsNullWhenAllowListNotEnforced() {
        // lenient: the allow-list check short-circuits BEFORE isRunRestricted is ever consulted
        // when the list is not enforced - this stub proves that path is never reached, not that
        // it is.
        lenient().when(stepPayloadService.isRunRestricted("run-1")).thenReturn(true);
        RestrictedDataPolicy.setLlmAllowListEnforced(false);

        assertThat(node(config("create_task")).restrictedTaskRefusal(context)).isNull();
    }

    @Test
    @DisplayName("create_task on a restricted run: the node fails, and AgentClient is never called")
    void createTaskFailsOnRestrictedRunWithoutCallingAgentClient() {
        when(stepPayloadService.isRunRestricted("run-1")).thenReturn(true);

        NodeExecutionResult result = node(config("create_task")).execute(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).hasValueSatisfying(m ->
                assertThat(m).contains(RestrictedDataPolicy.REFUSAL_CODE));
        verify(agentClient, never()).createTaskForWorkflow(anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("update_task on a restricted run: the node fails the same way as create_task")
    void updateTaskFailsOnRestrictedRun() {
        when(stepPayloadService.isRunRestricted("run-1")).thenReturn(true);

        NodeExecutionResult result = node(config("update_task")).execute(context);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage()).hasValueSatisfying(m ->
                assertThat(m).contains(RestrictedDataPolicy.REFUSAL_CODE));
    }

    @Test
    @DisplayName("LC-066: get_task of a RESTRICTED task (tagged body) tags the node output, so the run becomes restricted")
    void getOfRestrictedTaskTagsTheOutput() {
        when(agentClient.getTaskForWorkflow(org.mockito.ArgumentMatchers.eq("tenant-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("id", "11111111-1111-1111-1111-111111111111", "title", "Reply to the bank",
                        "__dataSensitivity__", "RESTRICTED"));

        NodeExecutionResult result = node(new Core.TaskConfig("get_task", "11111111-1111-1111-1111-111111111111",
                null, null, null, null, null, null, null, null, null)).execute(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).containsEntry("__dataSensitivity__", "RESTRICTED");
        assertThat(com.apimarketplace.common.classification.RestrictedDataPolicy.fromToolMetadata(result.output()).isRestricted())
                .as("the payload classification reads the lifted tag").isTrue();
    }

    @Test
    @DisplayName("LC-066: get_task of a NORMAL task (untagged body) leaves the output untagged, as before")
    void getOfNormalTaskLeavesTheOutputUntagged() {
        when(agentClient.getTaskForWorkflow(org.mockito.ArgumentMatchers.eq("tenant-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of("id", "11111111-1111-1111-1111-111111111111", "title", "Weekly report"));

        NodeExecutionResult result = node(new Core.TaskConfig("get_task", "11111111-1111-1111-1111-111111111111",
                null, null, null, null, null, null, null, null, null)).execute(context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.output()).doesNotContainKey("__dataSensitivity__");
    }

    @Test
    @DisplayName("get_task, delete_task and list_tasks are NOT gated: a restricted run can still read/manage existing tasks")
    void readOnlyOperationsAreNeverGated() {
        // lenient: the point of this test is that these operations never call
        // restrictedTaskRefusal at all, so isRunRestricted is never consulted for them.
        lenient().when(stepPayloadService.isRunRestricted(anyString())).thenReturn(true);

        // These operations don't write new agent-bound content, so the reasoning that gates
        // create/update (the eventual executor's model is unknown) does not apply.
        for (String operation : new String[] {"get_task", "delete_task", "list_tasks"}) {
            NodeExecutionResult result = node(config(operation)).execute(context);
            // Never refused FOR THIS REASON: any failure here must come from something else
            // (e.g. the mocked AgentClient returning nothing), never the restricted-data code.
            assertThat(result.errorMessage().orElse(""))
                    .as(operation).doesNotContain(RestrictedDataPolicy.REFUSAL_CODE);
        }
    }
}
