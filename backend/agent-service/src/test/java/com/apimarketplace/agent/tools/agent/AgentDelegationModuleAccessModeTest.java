package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.AgentTaskRecurrenceService;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentDelegationModule access modes")
@ExtendWith(MockitoExtension.class)
class AgentDelegationModuleAccessModeTest {

    private static final String TENANT = "tenant-a";

    @Mock private AgentTaskService taskService;
    @Mock private AgentTaskRecurrenceService recurrenceService;
    @Mock private AgentTaskRepository taskRepository;

    private AgentDelegationModule module() {
        return new AgentDelegationModule(taskService, recurrenceService, taskRepository);
    }

    private ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, null, null);
    }

    private ToolExecutionContext orgCtx(Map<String, Object> credentials, String orgId) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(), null, null, orgId, null);
    }

    @Test
    @DisplayName("read-only agent access rejects task assignment before service mutation")
    void readOnlyAgentAccessRejectsAssign() {
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__agentId__", UUID.randomUUID().toString());
        credentials.put("turnId", "turn-read-only-assign");
        credentials.put("__agentAccessMode__", "read");

        Optional<ToolExecutionResult> result = module().execute("assign",
            Map.of("title", "x", "instructions", "y"),
            TENANT,
            ctx(credentials));

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.get().error()).contains("read-only").contains("assign");
        verify(taskService, never())
            .assignTask(anyString(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("namespaced allowedAgentIds rejects assignment to unlisted agent with permission denied")
    void namespacedAllowedAgentIdsRejectsUnlistedAssignWithPermissionDenied() {
        UUID callerAgentId = UUID.randomUUID();
        UUID allowedAgentId = UUID.randomUUID();
        UUID blockedAgentId = UUID.randomUUID();
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__agentId__", callerAgentId.toString());
        credentials.put("turnId", "turn-namespaced-allowed-assign");
        credentials.put("__allowedAgentIds__", List.of(allowedAgentId.toString()));

        Optional<ToolExecutionResult> result = module().execute("assign",
            Map.of(
                "agent_id", blockedAgentId.toString(),
                "title", "x",
                "instructions", "y"),
            TENANT,
            ctx(credentials));

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.get().error()).contains("not in your allowed agents list");
        verify(taskService, never())
            .assignTask(anyString(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("namespaced allowedAgentIds rejects unlisted reviewer with permission denied")
    void namespacedAllowedAgentIdsRejectsUnlistedReviewerWithPermissionDenied() {
        UUID callerAgentId = UUID.randomUUID();
        UUID allowedAgentId = UUID.randomUUID();
        UUID reviewerAgentId = UUID.randomUUID();
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__agentId__", callerAgentId.toString());
        credentials.put("turnId", "turn-namespaced-reviewer");
        credentials.put("__allowedAgentIds__", List.of(allowedAgentId.toString()));

        Optional<ToolExecutionResult> result = module().execute("assign",
            Map.of(
                "agent_id", allowedAgentId.toString(),
                "reviewer_agent_id", reviewerAgentId.toString(),
                "title", "x",
                "instructions", "y"),
            TENANT,
            ctx(credentials));

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.get().error()).contains("reviewer_agent_id").contains("not in your allowed agents list");
        verify(taskService, never())
            .assignTask(anyString(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("namespaced allowedAgentIds rejects task reassign to unlisted agent with permission denied")
    void namespacedAllowedAgentIdsRejectsUnlistedReassignWithPermissionDenied() {
        UUID callerAgentId = UUID.randomUUID();
        UUID allowedAgentId = UUID.randomUUID();
        UUID blockedAgentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String orgId = "org-1";
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__agentId__", callerAgentId.toString());
        credentials.put("turnId", "turn-namespaced-reassign");
        credentials.put("__allowedAgentIds__", List.of(allowedAgentId.toString()));
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, orgId))
            .thenReturn(Optional.of(new AgentTaskEntity()));

        Optional<ToolExecutionResult> result = module().execute("task_update",
            Map.of(
                "task_id", taskId.toString(),
                "agent_id", blockedAgentId.toString()),
            TENANT,
            orgCtx(credentials, orgId));

        assertThat(result).isPresent();
        assertThat(result.get().success()).isFalse();
        assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
        assertThat(result.get().error()).contains("not in your allowed agents list");
        verify(taskService, never())
            .updateTask(anyString(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("namespaced allowedAgentIds allows task reassign to a listed agent")
    void namespacedAllowedAgentIdsAllowsListedReassign() {
        UUID callerAgentId = UUID.randomUUID();
        UUID allowedAgentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        String orgId = "org-1";
        Map<String, Object> credentials = new HashMap<>();
        credentials.put("__agentId__", callerAgentId.toString());
        credentials.put("turnId", "turn-namespaced-reassign-allowed");
        credentials.put("__allowedAgentIds__", List.of(allowedAgentId.toString()));

        AgentTaskEntity existing = new AgentTaskEntity();
        existing.setId(taskId);
        existing.setOrganizationId(orgId);
        when(taskRepository.findByIdAndOrganizationIdStrict(taskId, orgId))
            .thenReturn(Optional.of(existing));

        AgentTaskEntity updated = new AgentTaskEntity();
        updated.setId(taskId);
        updated.setTenantId(TENANT);
        updated.setOrganizationId(orgId);
        updated.setAssignedToAgentId(allowedAgentId);
        updated.setTitle("Updated");
        updated.setPriority(AgentTaskEntity.PRIORITY_NORMAL);
        updated.setStatus(AgentTaskEntity.STATUS_PENDING);
        when(taskService.updateTask(anyString(), any(), any(), any(), any()))
            .thenReturn(updated);

        Optional<ToolExecutionResult> result = module().execute("task_update",
            Map.of(
                "task_id", taskId.toString(),
                "agent_id", allowedAgentId.toString()),
            TENANT,
            orgCtx(credentials, orgId));

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();

        ArgumentCaptor<UpdateTaskRequest> requestCaptor = ArgumentCaptor.forClass(UpdateTaskRequest.class);
        verify(taskService).updateTask(eq(TENANT), eq(taskId), eq(callerAgentId), any(), requestCaptor.capture());
        assertThat(requestCaptor.getValue().agentId()).isEqualTo(allowedAgentId);
    }

    @Test
    @DisplayName("read-only agent access still allows delegation read actions")
    void readOnlyAgentAccessAllowsBacklog() {
        Map<String, Object> credentials = Map.of("__agentAccessMode__", "read");
        when(taskService.getBacklog(eq(TENANT), eq(20))).thenReturn(List.of());

        Optional<ToolExecutionResult> result = module().execute("backlog",
            Map.of(),
            TENANT,
            ctx(credentials));

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();
        verify(taskService).getBacklog(TENANT, 20);
    }
}
