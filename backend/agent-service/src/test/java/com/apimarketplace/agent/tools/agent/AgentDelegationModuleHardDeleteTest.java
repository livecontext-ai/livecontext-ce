package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.AgentTaskRecurrenceService;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused coverage for {@link AgentDelegationModule}'s {@code task_delete} action
 * (handleHardDelete) and its organization-scope guard - the gap left by
 * {@code AgentDelegationModuleTest} (which never exercises task_delete) and a
 * complement to {@code AgentTaskServiceHardDeleteAuthorizationTest} (which covers
 * the service, not the module's param-parse + scope-gate + result shape).
 *
 * <p>The scope guard {@code assertTaskInScope} requires a non-blank org id
 * ({@code TenantResolver.requireOrgId}) and a strict org-scoped repository hit;
 * either failing throws and the module's catch maps it to EXECUTION_FAILED.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentDelegationModule.task_delete (hard delete + org scope)")
class AgentDelegationModuleHardDeleteTest {

    private static final String TENANT = "tenant-a";
    private static final String ORG = "org-a";

    @Mock private AgentTaskService taskService;
    @Mock private AgentTaskRecurrenceService recurrenceService;
    @Mock private AgentTaskRepository taskRepository;

    private AgentDelegationModule module;

    @BeforeEach
    void setUp() {
        module = new AgentDelegationModule(taskService, recurrenceService, taskRepository);
    }

    private ToolExecutionContext ctx(String orgId) {
        // task_delete needs no agent identity (humans may delete); only org scope matters.
        return new ToolExecutionContext(TENANT, new HashMap<>(), Map.of(), Set.of(), null, null, orgId, "owner");
    }

    private Optional<ToolExecutionResult> deleteTask(Map<String, Object> params, String orgId) {
        return module.execute("task_delete", params, TENANT, ctx(orgId));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult r) {
        return (Map<String, Object>) r.data();
    }

    @Test
    @DisplayName("canHandle('task_delete') is true")
    void canHandleTaskDelete() {
        assertThat(module.canHandle("task_delete")).isTrue();
    }

    @Test
    @DisplayName("an in-scope task is hard-deleted and the deleted_count is returned")
    void hardDeletesInScopeTask() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findByIdAndOrganizationIdStrict(eq(taskId), eq(ORG)))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(AgentTaskEntity.class)));
        when(taskService.hardDeleteTask(TENANT, taskId, TENANT)).thenReturn(1);

        Map<String, Object> params = new HashMap<>();
        params.put("task_id", taskId.toString());
        Optional<ToolExecutionResult> r = deleteTask(params, ORG);

        assertThat(r).isPresent();
        assertThat(r.get().success()).isTrue();
        assertThat(data(r.get()))
                .containsEntry("task_id", taskId.toString())
                .containsEntry("deleted_count", 1);
        verify(taskService).hardDeleteTask(TENANT, taskId, TENANT);
    }

    @Test
    @DisplayName("a read-mode agent is denied task_delete before any dispatch (write-access gate)")
    void readModeAgentDenied() {
        Map<String, Object> creds = new HashMap<>();
        creds.put("agentAccessMode", "read");
        ToolExecutionContext readCtx =
                new ToolExecutionContext(TENANT, creds, Map.of(), Set.of(), null, null, ORG, "owner");

        Map<String, Object> params = new HashMap<>();
        params.put("task_id", UUID.randomUUID().toString());
        Optional<ToolExecutionResult> r = module.execute("task_delete", params, TENANT, readCtx);

        assertThat(r).isPresent();
        assertThat(r.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        verify(taskRepository, never()).findByIdAndOrganizationIdStrict(any(), any());
        verify(taskService, never()).hardDeleteTask(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("a missing task_id → EXECUTION_FAILED and neither repo nor service is touched")
    void missingTaskId() {
        Optional<ToolExecutionResult> r = deleteTask(new HashMap<>(), ORG);
        assertThat(r).isPresent();
        assertThat(r.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.get().error()).contains("task_id is required");
        verify(taskRepository, never()).findByIdAndOrganizationIdStrict(any(), any());
        verify(taskService, never()).hardDeleteTask(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("a malformed (non-UUID) task_id is swallowed to null and treated as missing")
    void malformedTaskId() {
        Map<String, Object> params = new HashMap<>();
        params.put("task_id", "not-a-uuid");
        Optional<ToolExecutionResult> r = deleteTask(params, ORG);
        assertThat(r).isPresent();
        assertThat(r.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.get().error()).contains("task_id is required");
        verify(taskService, never()).hardDeleteTask(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("an unscoped caller (no org id) → EXECUTION_FAILED before any repo/service call")
    void unscopedCallerRejected() {
        UUID taskId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        params.put("task_id", taskId.toString());

        Optional<ToolExecutionResult> r = deleteTask(params, null);

        assertThat(r).isPresent();
        assertThat(r.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.get().error()).contains("organizationId required");
        verify(taskRepository, never()).findByIdAndOrganizationIdStrict(any(), any());
        verify(taskService, never()).hardDeleteTask(anyString(), any(), anyString());
    }

    @Test
    @DisplayName("a task outside the caller's org is reported as not found (no cross-tenant delete)")
    void crossTenantTaskBlocked() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findByIdAndOrganizationIdStrict(eq(taskId), eq(ORG)))
                .thenReturn(Optional.empty());

        Map<String, Object> params = new HashMap<>();
        params.put("task_id", taskId.toString());
        Optional<ToolExecutionResult> r = deleteTask(params, ORG);

        assertThat(r).isPresent();
        assertThat(r.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
        assertThat(r.get().error()).contains("task not found");
        verify(taskService, never()).hardDeleteTask(anyString(), any(), anyString());
    }
}
