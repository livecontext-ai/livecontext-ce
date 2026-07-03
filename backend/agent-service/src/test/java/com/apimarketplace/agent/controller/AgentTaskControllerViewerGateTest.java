package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.dto.AddNoteRequest;
import com.apimarketplace.agent.dto.BulkTaskRequest;
import com.apimarketplace.agent.dto.CreateRecurrenceRequest;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.dto.UpdateRecurrenceRequest;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.AgentTaskRecurrenceService;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.TaskResponseEnricher;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the VIEWER write gate on the task board.
 * <p>
 * Bug: no task endpoint enforced the org role. The controller javadoc claimed
 * the gate was "enforced upstream by the gateway", but the gateway (cloud) and
 * the CE monolith org filter only RESOLVE and inject {@code X-Organization-Role};
 * neither blocks writes. Every other service (skills, datasources, workflows)
 * gates VIEWER in its own controller, so a VIEWER could create, edit, drag,
 * bulk-delete and purge tasks - and, in CE where there is no gateway at all,
 * this was the only possible enforcement point.
 * <p>
 * These tests fail on the pre-fix controller (endpoints reached the service)
 * and pass post-fix (403, service untouched). Reads stay open to VIEWER.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Task board VIEWER write gate")
class AgentTaskControllerViewerGateTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-A";
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Mock private AgentTaskService taskService;
    @Mock private TenantResolver tenantResolver;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskEventRepository taskEventRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskResponseEnricher taskEnricher;
    @Mock private AgentTaskRecurrenceService recurrenceService;

    private AgentTaskController controller;
    private AgentTaskRecurrenceController recurrenceController;
    private final MockHttpServletRequest req = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        controller = new AgentTaskController(taskService, tenantResolver, agentRepository,
                taskRepository, taskEventRepository, noteRepository, executionRepository, taskEnricher);
        recurrenceController = new AgentTaskRecurrenceController(recurrenceService, tenantResolver, agentRepository);
        lenient().when(tenantResolver.resolve(any())).thenReturn(TENANT);
        lenient().when(tenantResolver.resolveOrgId(any())).thenReturn(ORG);
    }

    private void asViewer() {
        when(tenantResolver.resolveOrgRole(any())).thenReturn("VIEWER");
    }

    private static void assertForbidden(ResponseEntity<?> res) {
        assertThat(res.getStatusCode().value()).isEqualTo(403);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) res.getBody();
        assertThat(body).isNotNull();
        assertThat(String.valueOf(body.get("error"))).containsIgnoringCase("read-only");
    }

    @Nested
    @DisplayName("every mutating endpoint returns 403 for a VIEWER and never reaches the service")
    class MutationsBlocked {

        @Test
        @DisplayName("PUT /tasks/rank")
        void reorder() {
            asViewer();
            assertForbidden(controller.reorderTasks(
                    new AgentTaskController.ReorderTasksRequest(List.of(TASK_ID.toString())), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("PUT /tasks/{id}/estimate")
        void estimate() {
            asViewer();
            assertForbidden(controller.setEstimate(TASK_ID,
                    new AgentTaskController.EstimateBody(30, false, null, false), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("PUT /tasks/{id}/blockers")
        void blockers() {
            asViewer();
            assertForbidden(controller.setBlockers(TASK_ID,
                    new AgentTaskController.BlockersBody(List.of()), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("PUT /tasks/{id}/checklist")
        void checklist() {
            asViewer();
            assertForbidden(controller.setChecklist(TASK_ID,
                    new AgentTaskController.ChecklistBody(List.of()), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("PUT /tasks/{id}/attachments")
        void attachments() {
            asViewer();
            assertForbidden(controller.setAttachments(TASK_ID,
                    new AgentTaskController.AttachmentsBody(List.of()), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks")
        void create() {
            asViewer();
            assertForbidden(controller.createTask(
                    new CreateTaskRequest(null, null, "t", "i", null, null, null, null, null), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("PATCH /tasks/{id}")
        void update() {
            asViewer();
            assertForbidden(controller.updateTask(TASK_ID,
                    new UpdateTaskRequest(null, "t", null, null), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("DELETE /tasks/{id} (soft and hard)")
        void delete() {
            asViewer();
            assertForbidden(controller.cancelOrDeleteTask(TASK_ID, null, false, req));
            assertForbidden(controller.cancelOrDeleteTask(TASK_ID, null, true, req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks/bulk")
        void bulk() {
            asViewer();
            assertForbidden(controller.bulkTaskAction(
                    new BulkTaskRequest(List.of(TASK_ID), "delete", null), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks/{id}/complete")
        void complete() {
            asViewer();
            assertForbidden(controller.completeTask(TASK_ID,
                    Map.of("as_agent_id", AGENT_ID.toString(), "result", "done"), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks/{id}/reject")
        void reject() {
            asViewer();
            assertForbidden(controller.rejectTask(TASK_ID,
                    Map.of("as_agent_id", AGENT_ID.toString()), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks/{id}/approve")
        void approve() {
            asViewer();
            assertForbidden(controller.approveTask(TASK_ID, Map.of(), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks/{id}/reject-review")
        void rejectReview() {
            asViewer();
            assertForbidden(controller.rejectReview(TASK_ID, Map.of("reason", "nope"), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks/{id}/claim")
        void claim() {
            asViewer();
            assertForbidden(controller.claimTask(TASK_ID,
                    Map.of("as_agent_id", AGENT_ID.toString()), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks/{id}/stop-agent")
        void stopAgent() {
            asViewer();
            assertForbidden(controller.stopAgent(TASK_ID, Map.of("role", "assignee"), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("POST /tasks/{id}/notes")
        void addNote() {
            asViewer();
            assertForbidden(controller.addNote(TASK_ID, new AddNoteRequest("hello"), req));
            verifyNoInteractions(taskService);
        }
    }

    @Nested
    @DisplayName("recurrence mutations are gated too")
    class RecurrenceBlocked {

        @Test
        @DisplayName("POST /recurrences")
        void create() {
            asViewer();
            assertForbidden(recurrenceController.create(
                    new CreateRecurrenceRequest("t", "i", "0 0 * * *", "UTC", null, null, null), req));
            verifyNoInteractions(recurrenceService);
        }

        @Test
        @DisplayName("PUT /recurrences/{id}")
        void update() {
            asViewer();
            assertForbidden(recurrenceController.update(UUID.randomUUID(),
                    new UpdateRecurrenceRequest(false, null, null, null, null), req));
            verifyNoInteractions(recurrenceService);
        }

        @Test
        @DisplayName("DELETE /recurrences/{id}")
        void delete() {
            asViewer();
            assertForbidden(recurrenceController.delete(UUID.randomUUID(), req));
            verifyNoInteractions(recurrenceService);
        }
    }

    @Nested
    @DisplayName("gate boundaries")
    class Boundaries {

        @Test
        @DisplayName("role match is case-insensitive (defensive vs header casing drift)")
        void lowercaseViewerBlocked() {
            when(tenantResolver.resolveOrgRole(any())).thenReturn("viewer");
            assertForbidden(controller.createTask(
                    new CreateTaskRequest(null, null, "t", "i", null, null, null, null, null), req));
            verifyNoInteractions(taskService);
        }

        @Test
        @DisplayName("MEMBER role passes through to the service")
        void memberNotBlocked() {
            when(tenantResolver.resolveOrgRole(any())).thenReturn("MEMBER");
            when(taskService.assignTask(any(), any(), any(), any()))
                    .thenAnswer(inv -> {
                        com.apimarketplace.agent.domain.AgentTaskEntity t =
                                new com.apimarketplace.agent.domain.AgentTaskEntity();
                        t.setId(TASK_ID);
                        return t;
                    });
            ResponseEntity<?> res = controller.createTask(
                    new CreateTaskRequest(null, null, "t", "i", null, null, null, null, null), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).assignTask(any(), any(), any(), any());
        }

        @Test
        @DisplayName("no org role at all (personal workspace) passes through")
        void noRoleNotBlocked() {
            when(tenantResolver.resolveOrgRole(any())).thenReturn(null);
            when(taskService.assignTask(any(), any(), any(), any()))
                    .thenAnswer(inv -> {
                        com.apimarketplace.agent.domain.AgentTaskEntity t =
                                new com.apimarketplace.agent.domain.AgentTaskEntity();
                        t.setId(TASK_ID);
                        return t;
                    });
            ResponseEntity<?> res = controller.createTask(
                    new CreateTaskRequest(null, null, "t", "i", null, null, null, null, null), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("VIEWER can still read (GET /tasks/{id}?as=...)")
        void viewerCanRead() {
            // lenient: the read path never consults the org role - that absence
            // of a role check on reads is exactly what this test pins.
            lenient().when(tenantResolver.resolveOrgRole(any())).thenReturn("VIEWER");
            lenient().when(agentRepository.existsByIdAndTenantId(any(UUID.class), any())).thenReturn(true);
            com.apimarketplace.agent.domain.AgentTaskEntity entity =
                    new com.apimarketplace.agent.domain.AgentTaskEntity();
            entity.setId(TASK_ID);
            when(taskService.getInboxTask(any(String.class), any(String.class), any(UUID.class), any(UUID.class)))
                    .thenReturn(com.apimarketplace.agent.dto.TaskResponse.from(entity));
            ResponseEntity<?> res = controller.getTask(TASK_ID, AGENT_ID, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
        }
    }
}
