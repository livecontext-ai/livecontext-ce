package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentExecutionEntity;
import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.domain.AgentTaskEventEntity;
import com.apimarketplace.agent.domain.AgentTaskNoteEntity;
import com.apimarketplace.agent.dto.AddNoteRequest;
import com.apimarketplace.agent.dto.CreateTaskRequest;
import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.agent.dto.UpdateTaskRequest;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.TaskResponseEnricher;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentTaskController}, the human-facing task-board REST API.
 * <p>
 * The bulk action + stats path is covered by {@link AgentTaskControllerBulkTest};
 * this suite covers the gaps surfaced by the 2026-06-23 test-strategy audit: every
 * single-task transition (the user-vs-agent reviewer fork), the {@code stopAgent}
 * 409 contract (the only endpoint that emits 409), the scope-404 pre-check on every
 * mutation, the membership-403 guard, and the read/list endpoints (clamping, the
 * inbox{@literal ->}outbox fallback, the notes DESC{@literal ->}ASC reversal, and the
 * org-strict execution finder selection).
 * <p>
 * Pure Mockito: the controller is built directly with mocked collaborators, the
 * static {@code TenantResolver.currentRequestOrganizationId()} returns null off-request
 * so the membership check routes through {@code existsByIdAndTenantId}, and
 * {@code TenantResolver.requireOrgId} passes because the bound org is non-blank.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskController")
class AgentTaskControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-A";

    @Mock private AgentTaskService taskService;
    @Mock private TenantResolver tenantResolver;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskEventRepository taskEventRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskResponseEnricher taskEnricher;

    private AgentTaskController controller;
    private final MockHttpServletRequest req = new MockHttpServletRequest();

    @BeforeEach
    void setUp() {
        controller = new AgentTaskController(taskService, tenantResolver, agentRepository,
                taskRepository, taskEventRepository, noteRepository, executionRepository, taskEnricher);
        lenient().when(tenantResolver.resolve(any())).thenReturn(TENANT);
        lenient().when(tenantResolver.resolveOrgId(any())).thenReturn(ORG);
        // Default: every task is in the caller's workspace scope (both lookup paths).
        lenient().when(taskRepository.findByIdAndOrganizationIdStrict(any(UUID.class), eq(ORG)))
                .thenAnswer(inv -> Optional.of(taskWithId(inv.getArgument(0))));
        lenient().when(taskService.findTaskForScope(any(UUID.class), eq(TENANT), eq(ORG)))
                .thenAnswer(inv -> Optional.of(taskWithId(inv.getArgument(0))));
        // Default: the as_agent_id belongs to the caller's tenant.
        lenient().when(agentRepository.existsByIdAndTenantId(any(UUID.class), eq(TENANT))).thenReturn(true);
        // Enricher is an identity passthrough in tests.
        lenient().when(taskEnricher.enrich(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(taskEnricher.enrichAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static AgentTaskEntity taskWithId(UUID id) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(id);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    // ====================================================================
    // complete
    // ====================================================================

    @Nested
    @DisplayName("POST /tasks/{id}/complete")
    class Complete {

        @Test
        @DisplayName("happy path returns 200 and forwards (tenant, taskId, asAgent, result, force=false)")
        void happy() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.completeTask(TENANT, taskId, agent, "done", false))
                    .thenReturn(taskWithId(taskId));

            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", agent.toString());
            b.put("result", "done");
            ResponseEntity<?> res = controller.completeTask(taskId, b, req);

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody()).isInstanceOf(TaskResponse.class);
            verify(taskService).completeTask(TENANT, taskId, agent, "done", false);
        }

        @Test
        @DisplayName("missing as_agent_id -> 400 and service untouched")
        void missingAgent() {
            ResponseEntity<?> res = controller.completeTask(
                    UUID.randomUUID(), Map.of("result", "done"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(taskService, never()).completeTask(any(), any(), any(), any(), eq(false));
        }

        @Test
        @DisplayName("blank result -> 400")
        void blankResult() {
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", UUID.randomUUID().toString());
            b.put("result", "  ");
            ResponseEntity<?> res = controller.completeTask(UUID.randomUUID(), b, req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("out-of-scope task -> 404 before any mutation")
        void outOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", UUID.randomUUID().toString());
            b.put("result", "done");
            ResponseEntity<?> res = controller.completeTask(taskId, b, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
            verify(taskService, never()).completeTask(any(), any(), any(), any(), eq(false));
        }

        @Test
        @DisplayName("as_agent_id not in tenant -> 403")
        void membershipDenied() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(agentRepository.existsByIdAndTenantId(agent, TENANT)).thenReturn(false);
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", agent.toString());
            b.put("result", "done");
            ResponseEntity<?> res = controller.completeTask(taskId, b, req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(taskService, never()).completeTask(any(), any(), any(), any(), eq(false));
        }

        @Test
        @DisplayName("service IllegalArgumentException -> 404, IllegalStateException -> 403")
        void serviceErrorsMap() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", agent.toString());
            b.put("result", "done");

            when(taskService.completeTask(TENANT, taskId, agent, "done", false))
                    .thenThrow(new IllegalArgumentException("missing"))
                    .thenThrow(new IllegalStateException("not assignee"));

            assertThat(controller.completeTask(taskId, b, req).getStatusCode().value()).isEqualTo(404);
            assertThat(controller.completeTask(taskId, b, req).getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("force=true (Boolean) is forwarded to the service")
        void forceFlagBoolean() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.completeTask(TENANT, taskId, agent, "done", true)).thenReturn(taskWithId(taskId));
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", agent.toString());
            b.put("result", "done");
            b.put("force", true);
            controller.completeTask(taskId, b, req);
            verify(taskService).completeTask(TENANT, taskId, agent, "done", true);
        }

        @Test
        @DisplayName("force=\"true\" (String) is parsed and forwarded as true")
        void forceFlagString() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.completeTask(TENANT, taskId, agent, "done", true)).thenReturn(taskWithId(taskId));
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", agent.toString());
            b.put("result", "done");
            b.put("force", "true");
            controller.completeTask(taskId, b, req);
            verify(taskService).completeTask(TENANT, taskId, agent, "done", true);
        }
    }

    // ====================================================================
    // reject
    // ====================================================================

    @Nested
    @DisplayName("POST /tasks/{id}/reject")
    class Reject {

        @Test
        @DisplayName("happy path forwards (tenant, taskId, asAgent, reason)")
        void happy() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.rejectTask(TENANT, taskId, agent, "no")).thenReturn(taskWithId(taskId));
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", agent.toString());
            b.put("reason", "no");
            ResponseEntity<?> res = controller.rejectTask(taskId, b, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).rejectTask(TENANT, taskId, agent, "no");
        }

        @Test
        @DisplayName("missing as_agent_id -> 400")
        void missingAgent() {
            ResponseEntity<?> res = controller.rejectTask(UUID.randomUUID(), Map.of("reason", "x"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("out-of-scope -> 404")
        void outOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", UUID.randomUUID().toString());
            ResponseEntity<?> res = controller.rejectTask(taskId, b, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ====================================================================
    // approve - user-vs-agent reviewer fork
    // ====================================================================

    @Nested
    @DisplayName("POST /tasks/{id}/approve")
    class Approve {

        @Test
        @DisplayName("with as_agent_id -> reviewer path approveTask + membership check")
        void reviewerAgentPath() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.approveTask(TENANT, taskId, agent)).thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.approveTask(
                    taskId, Map.of("as_agent_id", agent.toString()), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).approveTask(TENANT, taskId, agent);
            verify(agentRepository).existsByIdAndTenantId(agent, TENANT);
            verify(taskService, never()).approveTaskByUser(any(), any());
        }

        @Test
        @DisplayName("without as_agent_id -> user path approveTaskByUser, no membership check")
        void userPath() {
            UUID taskId = UUID.randomUUID();
            when(taskService.approveTaskByUser(TENANT, taskId)).thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.approveTask(taskId, Map.of(), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).approveTaskByUser(TENANT, taskId);
            verify(taskService, never()).approveTask(any(), any(), any());
            verify(agentRepository, never()).existsByIdAndTenantId(any(), any());
        }

        @Test
        @DisplayName("out-of-scope -> 404")
        void outOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.approveTask(taskId, Map.of(), req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("service IllegalStateException -> 403")
        void stateErrorMaps() {
            UUID taskId = UUID.randomUUID();
            when(taskService.approveTaskByUser(TENANT, taskId))
                    .thenThrow(new IllegalStateException("not in review"));
            ResponseEntity<?> res = controller.approveTask(taskId, Map.of(), req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
        }
    }

    // ====================================================================
    // reject-review - user-vs-agent reviewer fork
    // ====================================================================

    @Nested
    @DisplayName("POST /tasks/{id}/reject-review")
    class RejectReview {

        @Test
        @DisplayName("with as_agent_id -> reviewer path rejectReview + membership check + reason forwarded")
        void reviewerAgentPath() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.rejectReview(TENANT, taskId, agent, "fix it")).thenReturn(taskWithId(taskId));
            Map<String, Object> b = new HashMap<>();
            b.put("as_agent_id", agent.toString());
            b.put("reason", "fix it");
            ResponseEntity<?> res = controller.rejectReview(taskId, b, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).rejectReview(TENANT, taskId, agent, "fix it");
            verify(taskService, never()).rejectReviewByUser(any(), any(), any());
        }

        @Test
        @DisplayName("without as_agent_id -> user path rejectReviewByUser")
        void userPath() {
            UUID taskId = UUID.randomUUID();
            when(taskService.rejectReviewByUser(TENANT, taskId, "redo")).thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.rejectReview(taskId, Map.of("reason", "redo"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).rejectReviewByUser(TENANT, taskId, "redo");
            verify(taskService, never()).rejectReview(any(), any(), any(), any());
        }

        @Test
        @DisplayName("out-of-scope -> 404")
        void outOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.rejectReview(taskId, Map.of(), req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ====================================================================
    // claim
    // ====================================================================

    @Nested
    @DisplayName("POST /tasks/{id}/claim")
    class Claim {

        @Test
        @DisplayName("successful claim -> claimed:true + task")
        void claimed() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.claimTask(TENANT, ORG, agent, taskId))
                    .thenReturn(Optional.of(taskWithId(taskId)));
            ResponseEntity<?> res = controller.claimTask(
                    taskId, Map.of("as_agent_id", agent.toString()), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("claimed", true);
            assertThat(body(res)).containsKey("task");
        }

        @Test
        @DisplayName("empty Optional -> claimed:false with reason already_claimed_or_missing")
        void notClaimed() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.claimTask(TENANT, ORG, agent, taskId)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.claimTask(
                    taskId, Map.of("as_agent_id", agent.toString()), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("claimed", false)
                    .containsEntry("reason", "already_claimed_or_missing");
        }

        @Test
        @DisplayName("missing as_agent_id -> 400")
        void missingAgent() {
            ResponseEntity<?> res = controller.claimTask(UUID.randomUUID(), Map.of(), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("membership denied -> 403, claim never attempted")
        void membershipDenied() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(agentRepository.existsByIdAndTenantId(agent, TENANT)).thenReturn(false);
            ResponseEntity<?> res = controller.claimTask(
                    taskId, Map.of("as_agent_id", agent.toString()), req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(taskService, never()).claimTask(any(), any(), any(), any());
        }

        @Test
        @DisplayName("out-of-scope -> 404")
        void outOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.claimTask(
                    taskId, Map.of("as_agent_id", UUID.randomUUID().toString()), req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ====================================================================
    // stop-agent - the ONLY endpoint that emits 409
    // ====================================================================

    @Nested
    @DisplayName("POST /tasks/{id}/stop-agent")
    class StopAgent {

        @Test
        @DisplayName("happy path (role assignee) -> 200")
        void happyAssignee() {
            UUID taskId = UUID.randomUUID();
            when(taskService.stopAgentExecution(TENANT, ORG, taskId, "assignee"))
                    .thenReturn(TaskResponse.from(taskWithId(taskId)));
            ResponseEntity<?> res = controller.stopAgent(taskId, Map.of("role", "assignee"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).stopAgentExecution(TENANT, ORG, taskId, "assignee");
        }

        @Test
        @DisplayName("role reviewer is accepted and the role string is forwarded verbatim")
        void roleReviewer() {
            UUID taskId = UUID.randomUUID();
            when(taskService.stopAgentExecution(TENANT, ORG, taskId, "reviewer"))
                    .thenReturn(TaskResponse.from(taskWithId(taskId)));
            ResponseEntity<?> res = controller.stopAgent(taskId, Map.of("role", "reviewer"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).stopAgentExecution(TENANT, ORG, taskId, "reviewer");
        }

        @Test
        @DisplayName("missing/invalid role -> 400, service untouched")
        void invalidRole() {
            UUID taskId = UUID.randomUUID();
            assertThat(controller.stopAgent(taskId, Map.of(), req).getStatusCode().value()).isEqualTo(400);
            assertThat(controller.stopAgent(taskId, Map.of("role", "owner"), req)
                    .getStatusCode().value()).isEqualTo(400);
            verify(taskService, never()).stopAgentExecution(any(), any(), any(), any());
        }

        @Test
        @DisplayName("IllegalArgumentException -> 404")
        void notFound() {
            UUID taskId = UUID.randomUUID();
            when(taskService.stopAgentExecution(TENANT, ORG, taskId, "assignee"))
                    .thenThrow(new IllegalArgumentException("task not found"));
            ResponseEntity<?> res = controller.stopAgent(taskId, Map.of("role", "assignee"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("IllegalStateException -> 409 (the unique 409 source)")
        void conflict409() {
            UUID taskId = UUID.randomUUID();
            when(taskService.stopAgentExecution(TENANT, ORG, taskId, "assignee"))
                    .thenThrow(new IllegalStateException("no running execution"));
            ResponseEntity<?> res = controller.stopAgent(taskId, Map.of("role", "assignee"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(409);
        }

        @Test
        @DisplayName("out-of-scope -> 404")
        void outOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.stopAgent(taskId, Map.of("role", "assignee"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ====================================================================
    // create / update / delete / note
    // ====================================================================

    @Nested
    @DisplayName("mutations: create / update / delete / addNote")
    class Mutations {

        @Test
        @DisplayName("createTask happy -> 200 and forwards (tenant, null, tenant, request)")
        void createHappy() {
            CreateTaskRequest request = new CreateTaskRequest(
                    null, null, "Title", "Do it", "normal", null, null, null);
            when(taskService.assignTask(TENANT, null, TENANT, request)).thenReturn(taskWithId(UUID.randomUUID()));
            ResponseEntity<?> res = controller.createTask(request, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody()).isInstanceOf(TaskResponse.class);
            verify(taskService).assignTask(TENANT, null, TENANT, request);
        }

        @Test
        @DisplayName("createTask IllegalArgumentException -> 400 (not 500)")
        void createBadRequest() {
            CreateTaskRequest request = new CreateTaskRequest(
                    null, null, null, null, null, null, null, null);
            when(taskService.assignTask(TENANT, null, TENANT, request))
                    .thenThrow(new IllegalArgumentException("title is required"));
            ResponseEntity<?> res = controller.createTask(request, req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("updateTask happy -> 200, forwards the request")
        void updateHappy() {
            UUID taskId = UUID.randomUUID();
            UpdateTaskRequest request = new UpdateTaskRequest(
                    null, null, null, null, null, null, null, null);
            when(taskService.updateTask(TENANT, taskId, null, TENANT, request)).thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.updateTask(taskId, request, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).updateTask(TENANT, taskId, null, TENANT, request);
        }

        @Test
        @DisplayName("updateTask out-of-scope -> 404, service untouched")
        void updateOutOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            UpdateTaskRequest request = new UpdateTaskRequest(null, null, null, null, null, null, null, null);
            ResponseEntity<?> res = controller.updateTask(taskId, request, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
            verify(taskService, never()).updateTask(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("updateTask IllegalArgumentException -> 404, IllegalStateException -> 403")
        void updateErrorsMap() {
            UUID taskId = UUID.randomUUID();
            UpdateTaskRequest request = new UpdateTaskRequest(null, null, null, null, null, null, null, null);
            when(taskService.updateTask(TENANT, taskId, null, TENANT, request))
                    .thenThrow(new IllegalArgumentException("nope"))
                    .thenThrow(new IllegalStateException("forbidden"));
            assertThat(controller.updateTask(taskId, request, req).getStatusCode().value()).isEqualTo(404);
            assertThat(controller.updateTask(taskId, request, req).getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("DELETE soft (hard=false) routes to cancelTask and returns cancelled_count")
        void deleteSoft() {
            UUID taskId = UUID.randomUUID();
            when(taskService.cancelTask(TENANT, taskId, null, TENANT, "dup")).thenReturn(1);
            ResponseEntity<?> res = controller.cancelOrDeleteTask(taskId, "dup", false, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("cancelled_count", 1);
            verify(taskService).cancelTask(TENANT, taskId, null, TENANT, "dup");
            verify(taskService, never()).hardDeleteTask(any(), any(), any());
        }

        @Test
        @DisplayName("DELETE hard (hard=true) routes to hardDeleteTask and returns deleted_count")
        void deleteHard() {
            UUID taskId = UUID.randomUUID();
            when(taskService.hardDeleteTask(TENANT, taskId, TENANT)).thenReturn(1);
            ResponseEntity<?> res = controller.cancelOrDeleteTask(taskId, null, true, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("deleted_count", 1);
            verify(taskService).hardDeleteTask(TENANT, taskId, TENANT);
            verify(taskService, never()).cancelTask(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("DELETE out-of-scope -> 404")
        void deleteOutOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.cancelOrDeleteTask(taskId, null, false, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("DELETE soft: service IllegalArgumentException -> 404")
        void deleteSoftIaeMaps404() {
            UUID taskId = UUID.randomUUID();
            when(taskService.cancelTask(TENANT, taskId, null, TENANT, null))
                    .thenThrow(new IllegalArgumentException("task not found"));
            ResponseEntity<?> res = controller.cancelOrDeleteTask(taskId, null, false, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("DELETE soft: service IllegalStateException -> 403")
        void deleteSoftIseMaps403() {
            UUID taskId = UUID.randomUUID();
            when(taskService.cancelTask(TENANT, taskId, null, TENANT, null))
                    .thenThrow(new IllegalStateException("not owner"));
            ResponseEntity<?> res = controller.cancelOrDeleteTask(taskId, null, false, req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("addNote happy -> 200 with note_id + task_id")
        void addNoteHappy() {
            UUID taskId = UUID.randomUUID();
            UUID noteId = UUID.randomUUID();
            AgentTaskNoteEntity note = new AgentTaskNoteEntity();
            note.setId(noteId);
            when(taskService.addNote(TENANT, taskId, null, TENANT, "hi", null)).thenReturn(note);
            ResponseEntity<?> res = controller.addNote(taskId, new AddNoteRequest("hi"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("note_id", noteId.toString())
                    .containsEntry("task_id", taskId.toString());
        }

        @Test
        @DisplayName("addNote blank content -> 400")
        void addNoteBlank() {
            ResponseEntity<?> res = controller.addNote(UUID.randomUUID(), new AddNoteRequest("  "), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(taskService, never()).addNote(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("addNote out-of-scope -> 404")
        void addNoteOutOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByIdAndOrganizationIdStrict(taskId, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.addNote(taskId, new AddNoteRequest("hi"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("addNote service IllegalArgumentException -> 404 (distinct from the scope pre-check)")
        void addNoteServiceIaeMaps404() {
            UUID taskId = UUID.randomUUID();
            when(taskService.addNote(TENANT, taskId, null, TENANT, "hi", null))
                    .thenThrow(new IllegalArgumentException("task not found"));
            ResponseEntity<?> res = controller.addNote(taskId, new AddNoteRequest("hi"), req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ====================================================================
    // getTask - inbox-then-outbox fallback
    // ====================================================================

    @Nested
    @DisplayName("GET /tasks/{id}?as=")
    class GetTask {

        @Test
        @DisplayName("inbox hit -> 200 from inbox perspective")
        void inboxHit() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.getInboxTask(TENANT, ORG, agent, taskId))
                    .thenReturn(TaskResponse.from(taskWithId(taskId)));
            ResponseEntity<?> res = controller.getTask(taskId, agent, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService, never()).getOutboxTask(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("inbox denied (ISE) -> falls back to outbox perspective")
        void outboxFallback() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.getInboxTask(TENANT, ORG, agent, taskId))
                    .thenThrow(new IllegalStateException("not assignee"));
            when(taskService.getOutboxTask(TENANT, ORG, agent, null, taskId))
                    .thenReturn(TaskResponse.from(taskWithId(taskId)));
            ResponseEntity<?> res = controller.getTask(taskId, agent, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).getOutboxTask(TENANT, ORG, agent, null, taskId);
        }

        @Test
        @DisplayName("both perspectives denied (ISE) -> 403")
        void bothDenied() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.getInboxTask(TENANT, ORG, agent, taskId))
                    .thenThrow(new IllegalStateException("not assignee"));
            when(taskService.getOutboxTask(TENANT, ORG, agent, null, taskId))
                    .thenThrow(new IllegalStateException("not creator"));
            ResponseEntity<?> res = controller.getTask(taskId, agent, req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
        }

        @Test
        @DisplayName("inbox IllegalArgumentException -> 404 (not caught by the outbox fallback)")
        void inboxNotFound() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(taskService.getInboxTask(TENANT, ORG, agent, taskId))
                    .thenThrow(new IllegalArgumentException("task not found"));
            ResponseEntity<?> res = controller.getTask(taskId, agent, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
            verify(taskService, never()).getOutboxTask(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("membership denied -> 403")
        void membershipDenied() {
            UUID taskId = UUID.randomUUID();
            UUID agent = UUID.randomUUID();
            when(agentRepository.existsByIdAndTenantId(agent, TENANT)).thenReturn(false);
            ResponseEntity<?> res = controller.getTask(taskId, agent, req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(taskService, never()).getInboxTask(any(), any(), any(), any());
        }
    }

    // ====================================================================
    // listing endpoints
    // ====================================================================

    @Nested
    @DisplayName("listing: inbox / outbox / reviews / backlog")
    class Listing {

        @Test
        @DisplayName("listInbox returns count+tasks and clamps limit<=0 to 20")
        void inboxClampsLowLimit() {
            UUID agent = UUID.randomUUID();
            when(taskService.getInboxList(TENANT, ORG, agent, 20)).thenReturn(List.of());
            ResponseEntity<?> res = controller.listInbox(agent, 0, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("count", 0).containsKey("tasks");
            verify(taskService).getInboxList(TENANT, ORG, agent, 20);
        }

        @Test
        @DisplayName("listInbox clamps a huge limit down to 200")
        void inboxClampsHighLimit() {
            UUID agent = UUID.randomUUID();
            when(taskService.getInboxList(TENANT, ORG, agent, 200)).thenReturn(List.of());
            controller.listInbox(agent, 5000, req);
            verify(taskService).getInboxList(TENANT, ORG, agent, 200);
        }

        @Test
        @DisplayName("listInbox membership denied -> 403")
        void inboxMembershipDenied() {
            UUID agent = UUID.randomUUID();
            when(agentRepository.existsByIdAndTenantId(agent, TENANT)).thenReturn(false);
            ResponseEntity<?> res = controller.listInbox(agent, 50, req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(taskService, never()).getInboxList(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("listOutbox forwards the status filter")
        void outboxForwardsStatus() {
            UUID agent = UUID.randomUUID();
            when(taskService.getOutbox(TENANT, ORG, agent, "completed", 50)).thenReturn(List.of());
            controller.listOutbox(agent, "completed", 50, req);
            verify(taskService).getOutbox(TENANT, ORG, agent, "completed", 50);
        }

        @Test
        @DisplayName("listOutbox membership denied -> 403")
        void outboxMembershipDenied() {
            UUID agent = UUID.randomUUID();
            when(agentRepository.existsByIdAndTenantId(agent, TENANT)).thenReturn(false);
            ResponseEntity<?> res = controller.listOutbox(agent, null, 50, req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(taskService, never()).getOutbox(any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("listReviewInbox membership denied -> 403")
        void reviewInboxMembershipDenied() {
            UUID agent = UUID.randomUUID();
            when(agentRepository.existsByIdAndTenantId(agent, TENANT)).thenReturn(false);
            ResponseEntity<?> res = controller.listReviewInbox(agent, 50, req);
            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(taskService, never()).getReviewInbox(any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("listReviewInbox returns count+tasks")
        void reviewInbox() {
            UUID agent = UUID.randomUUID();
            when(taskService.getReviewInbox(TENANT, ORG, agent, 50)).thenReturn(List.of());
            ResponseEntity<?> res = controller.listReviewInbox(agent, 50, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("count", 0);
        }

        @Test
        @DisplayName("listBacklog needs no agent perspective and returns count+tasks")
        void backlog() {
            when(taskService.getBacklog(TENANT, ORG, 50)).thenReturn(List.of());
            ResponseEntity<?> res = controller.listBacklog(50, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("count", 0);
            verify(agentRepository, never()).existsByIdAndTenantId(any(), any());
        }
    }

    // ====================================================================
    // board: listTasks
    // ====================================================================

    @Nested
    @DisplayName("GET /tasks (board)")
    class Board {

        @BeforeEach
        void stubFinders() {
            lenient().when(taskRepository.findAllFilteredByOrganizationIdStrict(
                    any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any(), any(),
                    any(), anyInt(), anyInt())).thenReturn(List.of());
            lenient().when(taskRepository.countAllFilteredByOrganizationIdStrict(
                    any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any(), any()))
                    .thenReturn(0L);
        }

        @Test
        @DisplayName("'unassigned' sentinel sets the backlog flag and nulls the assignedTo UUID filter")
        void unassignedSentinel() {
            controller.listTasks(null, "unassigned", null, null, null, null, "updated_at", 0, 50, req);
            verify(taskRepository).findAllFilteredByOrganizationIdStrict(
                    eq(ORG), eq(null), eq(true), eq(null), eq(null),
                    eq(null), eq(null), eq(null), eq("updated_at"), eq(50), eq(0));
        }

        @Test
        @DisplayName("an unknown sort falls back to updated_at; a non-UUID assignedTo becomes null")
        void invalidSortAndAssignee() {
            controller.listTasks(null, "not-a-uuid", null, null, null, null, "bogus", 0, 50, req);
            verify(taskRepository).findAllFilteredByOrganizationIdStrict(
                    eq(ORG), eq(null), eq(false), eq(null), eq(null),
                    eq(null), eq(null), eq(null), eq("updated_at"), eq(50), eq(0));
        }

        @Test
        @DisplayName("a whitelisted sort is kept and size/page are clamped (size 500->200, page -3->0)")
        void clampsAndKeepsSort() {
            controller.listTasks(null, null, null, null, null, null, "priority", -3, 500, req);
            verify(taskRepository).findAllFilteredByOrganizationIdStrict(
                    eq(ORG), eq(null), eq(false), eq(null), eq(null),
                    eq(null), eq(null), eq(null), eq("priority"), eq(200), eq(0));
        }

        @Test
        @DisplayName("returns the enriched page envelope (total/page/size)")
        void pageEnvelope() {
            when(taskRepository.countAllFilteredByOrganizationIdStrict(
                    any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any(), any(), any(), any()))
                    .thenReturn(7L);
            ResponseEntity<Map<String, Object>> res =
                    controller.listTasks(null, null, null, null, null, null, "updated_at", 2, 25, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(res.getBody()).containsEntry("total", 7L)
                    .containsEntry("page", 2).containsEntry("size", 25);
        }
    }

    // ====================================================================
    // detail / events / children / executions
    // ====================================================================

    @Nested
    @DisplayName("reads: detail / events / children / executions")
    class Reads {

        @Test
        @DisplayName("getTaskDetail caps to 50 newest notes (DESC) then reverses to ASC for display")
        void detailReversesNotes() {
            UUID taskId = UUID.randomUUID();
            AgentTaskNoteEntity newest = new AgentTaskNoteEntity();
            newest.setId(UUID.randomUUID());
            AgentTaskNoteEntity oldest = new AgentTaskNoteEntity();
            oldest.setId(UUID.randomUUID());
            // Repository returns DESC: [newest, oldest].
            when(noteRepository.findByTaskIdOrderByCreatedAtDesc(eq(taskId), eq(PageRequest.of(0, 50))))
                    .thenReturn(new PageImpl<>(List.of(newest, oldest)));

            ResponseEntity<?> res = controller.getTaskDetail(taskId, req);

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            TaskResponse out = (TaskResponse) res.getBody();
            assertThat(out.notes()).extracting(TaskResponse.NoteView::id)
                    .containsExactly(oldest.getId(), newest.getId());
        }

        @Test
        @DisplayName("getTaskDetail out-of-scope -> 404")
        void detailOutOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskService.findTaskForScope(taskId, TENANT, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.getTaskDetail(taskId, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("getTaskEvents clamps size>100 to 100 and uses the DESC finder")
        void eventsClampHigh() {
            UUID taskId = UUID.randomUUID();
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(taskEventRepository.findByTaskIdOrderByCreatedAtDesc(eq(taskId), pageable.capture()))
                    .thenReturn(Page.empty());
            ResponseEntity<?> res = controller.getTaskEvents(taskId, 0, 999, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("getTaskEvents clamps size<1 to 1")
        void eventsClampLow() {
            UUID taskId = UUID.randomUUID();
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(taskEventRepository.findByTaskIdOrderByCreatedAtDesc(eq(taskId), pageable.capture()))
                    .thenReturn(Page.empty());
            controller.getTaskEvents(taskId, -2, 0, req);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(0);
        }

        @Test
        @DisplayName("getTaskEvents out-of-scope -> 404")
        void eventsOutOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskService.findTaskForScope(taskId, TENANT, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.getTaskEvents(taskId, 0, 30, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("getTaskChildren returns the org-strict children list")
        void childrenHappy() {
            UUID taskId = UUID.randomUUID();
            when(taskRepository.findByParentTaskIdAndOrganizationIdStrict(ORG, taskId)).thenReturn(List.of());
            ResponseEntity<?> res = controller.getTaskChildren(taskId, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskRepository).findByParentTaskIdAndOrganizationIdStrict(ORG, taskId);
        }

        @Test
        @DisplayName("getTaskChildren out-of-scope -> 404")
        void childrenOutOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskService.findTaskForScope(taskId, TENANT, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.getTaskChildren(taskId, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("getTaskExecutions uses the ORG-strict paged finder when an org is bound (never the tenant finder)")
        void executionsOrgStrict() {
            UUID taskId = UUID.randomUUID();
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            when(executionRepository.findByTaskIdAndOrganizationIdStrictOrderByStartedAtDesc(
                    eq(taskId), eq(ORG), pageable.capture())).thenReturn(Page.empty());
            ResponseEntity<?> res = controller.getTaskExecutions(taskId, 0, 999, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
            verify(executionRepository, never())
                    .findByTaskIdAndTenantIdOrderByStartedAtDesc(any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("getTaskExecutions falls back to the TENANT finder when no org scope is bound")
        void executionsTenantFallback() {
            UUID taskId = UUID.randomUUID();
            when(tenantResolver.resolveOrgId(any())).thenReturn(null);
            when(taskService.findTaskForScope(taskId, TENANT, null))
                    .thenReturn(Optional.of(taskWithId(taskId)));
            when(executionRepository.findByTaskIdAndTenantIdOrderByStartedAtDesc(
                    eq(taskId), eq(TENANT), any(Pageable.class))).thenReturn(Page.empty());
            ResponseEntity<?> res = controller.getTaskExecutions(taskId, 0, 20, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(executionRepository, never())
                    .findByTaskIdAndOrganizationIdStrictOrderByStartedAtDesc(any(), any(), any(Pageable.class));
        }

        @Test
        @DisplayName("getTaskExecutions out-of-scope -> 404")
        void executionsOutOfScope() {
            UUID taskId = UUID.randomUUID();
            when(taskService.findTaskForScope(taskId, TENANT, ORG)).thenReturn(Optional.empty());
            ResponseEntity<?> res = controller.getTaskExecutions(taskId, 0, 20, req);
            assertThat(res.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ====================================================================
    // board metadata endpoints (F1 rank, F12 estimate, F9 blockers, F10 checklist/attachments)
    // ====================================================================

    @Nested
    @DisplayName("board metadata: rank / estimate / blockers / checklist / attachments")
    class BoardMetadata {

        @Test
        @DisplayName("reorderTasks stamps the new order and returns count+tasks")
        void reorderHappy() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(taskService.reorderBoardTasks(eq(TENANT), org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(List.of(taskWithId(a), taskWithId(b)));
            ResponseEntity<?> res = controller.reorderTasks(
                    new AgentTaskController.ReorderTasksRequest(List.of(a.toString(), b.toString())), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            assertThat(body(res)).containsEntry("count", 2).containsKey("tasks");
        }

        @Test
        @DisplayName("reorderTasks empty / null orderedTaskIds -> 400, service untouched")
        void reorderEmpty() {
            assertThat(controller.reorderTasks(
                    new AgentTaskController.ReorderTasksRequest(List.of()), req)
                    .getStatusCode().value()).isEqualTo(400);
            assertThat(controller.reorderTasks(null, req).getStatusCode().value()).isEqualTo(400);
            verify(taskService, never()).reorderBoardTasks(any(), any());
        }

        @Test
        @DisplayName("reorderTasks with a non-UUID id -> 400 (parse failure), service untouched")
        void reorderInvalidUuid() {
            ResponseEntity<?> res = controller.reorderTasks(
                    new AgentTaskController.ReorderTasksRequest(List.of("not-a-uuid")), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
            verify(taskService, never()).reorderBoardTasks(any(), any());
        }

        @Test
        @DisplayName("reorderTasks: service IllegalArgumentException -> 400")
        void reorderServiceError() {
            UUID a = UUID.randomUUID();
            when(taskService.reorderBoardTasks(eq(TENANT), org.mockito.ArgumentMatchers.anyList()))
                    .thenThrow(new IllegalArgumentException("cross-column reorder"));
            ResponseEntity<?> res = controller.reorderTasks(
                    new AgentTaskController.ReorderTasksRequest(List.of(a.toString())), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("setEstimate forwards minutes and false clear-flags when unset")
        void estimateHappy() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskEstimate(TENANT, taskId, null, TENANT, 120, false, 30, false))
                    .thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.setEstimate(
                    taskId, new AgentTaskController.EstimateBody(120, null, 30, null), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).setTaskEstimate(TENANT, taskId, null, TENANT, 120, false, 30, false);
        }

        @Test
        @DisplayName("setEstimate coerces clearEstimate/clearTimeSpent=TRUE via Boolean.TRUE.equals")
        void estimateClearCoercion() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskEstimate(TENANT, taskId, null, TENANT, null, true, null, true))
                    .thenReturn(taskWithId(taskId));
            controller.setEstimate(taskId, new AgentTaskController.EstimateBody(null, true, null, true), req);
            verify(taskService).setTaskEstimate(TENANT, taskId, null, TENANT, null, true, null, true);
        }

        @Test
        @DisplayName("setEstimate null body -> null minutes + false clear-flags")
        void estimateNullBody() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskEstimate(TENANT, taskId, null, TENANT, null, false, null, false))
                    .thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.setEstimate(taskId, null, req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).setTaskEstimate(TENANT, taskId, null, TENANT, null, false, null, false);
        }

        @Test
        @DisplayName("setEstimate: service IllegalArgumentException -> 400")
        void estimateServiceError() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskEstimate(eq(TENANT), eq(taskId), any(), eq(TENANT),
                    any(), org.mockito.ArgumentMatchers.anyBoolean(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenThrow(new IllegalArgumentException("negative estimate"));
            ResponseEntity<?> res = controller.setEstimate(
                    taskId, new AgentTaskController.EstimateBody(-1, null, null, null), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("setBlockers forwards the blocker id list")
        void blockersHappy() {
            UUID taskId = UUID.randomUUID();
            List<String> ids = List.of(UUID.randomUUID().toString());
            when(taskService.setTaskBlockers(TENANT, taskId, null, TENANT, ids)).thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.setBlockers(
                    taskId, new AgentTaskController.BlockersBody(ids), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).setTaskBlockers(TENANT, taskId, null, TENANT, ids);
        }

        @Test
        @DisplayName("setBlockers null body -> empty list forwarded")
        void blockersNullBody() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskBlockers(TENANT, taskId, null, TENANT, List.of()))
                    .thenReturn(taskWithId(taskId));
            controller.setBlockers(taskId, null, req);
            verify(taskService).setTaskBlockers(TENANT, taskId, null, TENANT, List.of());
        }

        @Test
        @DisplayName("setBlockers: service IllegalArgumentException -> 400")
        void blockersServiceError() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskBlockers(eq(TENANT), eq(taskId), any(), eq(TENANT),
                    org.mockito.ArgumentMatchers.anyList())).thenThrow(new IllegalArgumentException("cycle"));
            ResponseEntity<?> res = controller.setBlockers(
                    taskId, new AgentTaskController.BlockersBody(List.of(UUID.randomUUID().toString())), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("setChecklist forwards the items")
        void checklistHappy() {
            UUID taskId = UUID.randomUUID();
            List<Map<String, Object>> items = List.of(Map.of("text", "step 1", "done", false));
            when(taskService.setTaskChecklist(TENANT, taskId, null, TENANT, items)).thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.setChecklist(
                    taskId, new AgentTaskController.ChecklistBody(items), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).setTaskChecklist(TENANT, taskId, null, TENANT, items);
        }

        @Test
        @DisplayName("setChecklist null body -> null items forwarded")
        void checklistNullBody() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskChecklist(TENANT, taskId, null, TENANT, null)).thenReturn(taskWithId(taskId));
            controller.setChecklist(taskId, null, req);
            verify(taskService).setTaskChecklist(TENANT, taskId, null, TENANT, null);
        }

        @Test
        @DisplayName("setChecklist: service IllegalArgumentException -> 400")
        void checklistServiceError() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskChecklist(eq(TENANT), eq(taskId), any(), eq(TENANT), any()))
                    .thenThrow(new IllegalArgumentException("too many items"));
            ResponseEntity<?> res = controller.setChecklist(
                    taskId, new AgentTaskController.ChecklistBody(List.of(Map.of("text", "x"))), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("setAttachments forwards the attachments")
        void attachmentsHappy() {
            UUID taskId = UUID.randomUUID();
            List<Map<String, Object>> atts = List.of(Map.of("key", "tenant/abc.png", "name", "abc.png"));
            when(taskService.setTaskAttachments(TENANT, taskId, null, TENANT, atts)).thenReturn(taskWithId(taskId));
            ResponseEntity<?> res = controller.setAttachments(
                    taskId, new AgentTaskController.AttachmentsBody(atts), req);
            assertThat(res.getStatusCode().value()).isEqualTo(200);
            verify(taskService).setTaskAttachments(TENANT, taskId, null, TENANT, atts);
        }

        @Test
        @DisplayName("setAttachments null body -> null list forwarded")
        void attachmentsNullBody() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskAttachments(TENANT, taskId, null, TENANT, null)).thenReturn(taskWithId(taskId));
            controller.setAttachments(taskId, null, req);
            verify(taskService).setTaskAttachments(TENANT, taskId, null, TENANT, null);
        }

        @Test
        @DisplayName("setAttachments: service IllegalArgumentException -> 400")
        void attachmentsServiceError() {
            UUID taskId = UUID.randomUUID();
            when(taskService.setTaskAttachments(eq(TENANT), eq(taskId), any(), eq(TENANT), any()))
                    .thenThrow(new IllegalArgumentException("bad key"));
            ResponseEntity<?> res = controller.setAttachments(
                    taskId, new AgentTaskController.AttachmentsBody(List.of(Map.of("key", "x"))), req);
            assertThat(res.getStatusCode().value()).isEqualTo(400);
        }
    }
}
