package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.BulkTaskRequest;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /api/tasks/bulk} - the task board's multi-select action endpoint.
 * Verifies the validation gate, per-item scope check, action routing, and the
 * per-item partial-success aggregation (one failing id must not abort the rest).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskController.bulkTaskAction")
class AgentTaskControllerBulkTest {

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
        // Every id in-scope by default → a live (pending) task carrying the queried id.
        lenient().when(taskRepository.findByIdAndOrganizationIdStrict(any(UUID.class), eq(ORG)))
                .thenAnswer(inv -> {
                    AgentTaskEntity t = new AgentTaskEntity();
                    t.setId(inv.getArgument(0));
                    return Optional.of(t); // default status = pending
                });
    }

    private void stubStatus(UUID id, String status) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(id);
        t.setStatus(status);
        when(taskRepository.findByIdAndOrganizationIdStrict(id, ORG)).thenReturn(Optional.of(t));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<?> res) {
        return (Map<String, Object>) res.getBody();
    }

    @Test
    @DisplayName("rejects an unknown action with 400")
    void rejectsUnknownAction() {
        ResponseEntity<?> res = controller.bulkTaskAction(
                new BulkTaskRequest(List.of(UUID.randomUUID()), "nuke", null), req);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an empty id list with 400")
    void rejectsEmptyIds() {
        ResponseEntity<?> res = controller.bulkTaskAction(new BulkTaskRequest(List.of(), "delete", null), req);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects a null action with 400")
    void rejectsNullAction() {
        ResponseEntity<?> res = controller.bulkTaskAction(new BulkTaskRequest(List.of(UUID.randomUUID()), null, null), req);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects null taskIds with 400")
    void rejectsNullIds() {
        ResponseEntity<?> res = controller.bulkTaskAction(new BulkTaskRequest(null, "delete", null), req);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("a null id inside the list is a per-item failure, not an abort")
    void nullIdInListIsPerItemFailure() {
        UUID ok = UUID.randomUUID();
        ResponseEntity<?> res = controller.bulkTaskAction(
                new BulkTaskRequest(java.util.Arrays.asList(ok, null), "delete", null), req);
        assertThat(body(res)).containsEntry("succeeded", 1).containsEntry("failed", 1);
        verify(taskService).softDeleteTask(TENANT, ok, null, TENANT);
    }

    @Test
    @DisplayName("routes 'delete' to softDeleteTask for each in-scope id and reports full success")
    void deleteRoutesToSoftDelete() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        ResponseEntity<?> res = controller.bulkTaskAction(new BulkTaskRequest(List.of(a, b), "delete", null), req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(body(res)).containsEntry("succeeded", 2).containsEntry("failed", 0).containsEntry("action", "delete");
        verify(taskService).softDeleteTask(TENANT, a, null, TENANT);
        verify(taskService).softDeleteTask(TENANT, b, null, TENANT);
    }

    @Test
    @DisplayName("routes restore / purge to their service calls")
    void restoreAndPurgeRouting() {
        UUID id = UUID.randomUUID();

        controller.bulkTaskAction(new BulkTaskRequest(List.of(id), "restore", null), req);
        verify(taskService).restoreTask(TENANT, id, null, TENANT);

        controller.bulkTaskAction(new BulkTaskRequest(List.of(id), "purge", null), req);
        verify(taskService).purgeDeletedTask(TENANT, id, TENANT);
    }

    @Test
    @DisplayName("'cancel' falls back to a status PATCH when the cascade no-ops (count 0)")
    void cancelFallsBackToPatchOnNoop() {
        UUID id = UUID.randomUUID();
        when(taskService.cancelTask(TENANT, id, null, TENANT, null)).thenReturn(0);

        controller.bulkTaskAction(new BulkTaskRequest(List.of(id), "cancel", null), req);

        verify(taskService).cancelTask(TENANT, id, null, TENANT, null);
        // Cascade no-op (terminal/stale) → requalify directly to cancelled.
        verify(taskService).updateTask(eq(TENANT), eq(id), eq(null), eq(TENANT), any());
    }

    @Test
    @DisplayName("'cancel' does NOT requalify when the cascade actually cancelled something")
    void cancelSkipsPatchWhenCascadeWorked() {
        UUID id = UUID.randomUUID();
        when(taskService.cancelTask(TENANT, id, null, TENANT, null)).thenReturn(1);

        controller.bulkTaskAction(new BulkTaskRequest(List.of(id), "cancel", null), req);

        verify(taskService).cancelTask(TENANT, id, null, TENANT, null);
        verify(taskService, never()).updateTask(anyString(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("'cancel' forwards the reason to the cascade")
    void cancelForwardsReason() {
        UUID id = UUID.randomUUID();
        when(taskService.cancelTask(TENANT, id, null, TENANT, "dup")).thenReturn(1);

        controller.bulkTaskAction(new BulkTaskRequest(List.of(id), "cancel", "dup"), req);

        verify(taskService).cancelTask(TENANT, id, null, TENANT, "dup");
    }

    @Test
    @DisplayName("a service exception on 'purge' is captured per-item (failed), not propagated")
    void purgeFailureCapturedPerItem() {
        UUID id = UUID.randomUUID();
        stubStatus(id, AgentTaskEntity.STATUS_DELETED);
        lenient().doThrow(new IllegalStateException("not owner"))
                .when(taskService).purgeDeletedTask(TENANT, id, TENANT);

        ResponseEntity<?> res = controller.bulkTaskAction(new BulkTaskRequest(List.of(id), "purge", null), req);

        assertThat(body(res)).containsEntry("succeeded", 0).containsEntry("failed", 1);
    }

    @Test
    @DisplayName("'cancel' on a trashed row is refused (would otherwise silently un-trash it)")
    void cancelOnTrashedRowRefused() {
        UUID trashed = UUID.randomUUID();
        stubStatus(trashed, AgentTaskEntity.STATUS_DELETED);

        ResponseEntity<?> res = controller.bulkTaskAction(new BulkTaskRequest(List.of(trashed), "cancel", null), req);

        assertThat(body(res)).containsEntry("succeeded", 0).containsEntry("failed", 1);
        verify(taskService, never()).cancelTask(anyString(), any(), any(), anyString(), any());
        verify(taskService, never()).updateTask(anyString(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("rejects a batch larger than the cap with 400")
    void rejectsOversizedBatch() {
        List<UUID> tooMany = java.util.stream.Stream.generate(UUID::randomUUID).limit(201).toList();
        ResponseEntity<?> res = controller.bulkTaskAction(new BulkTaskRequest(tooMany, "delete", null), req);
        assertThat(res.getStatusCode().value()).isEqualTo(400);
        verify(taskService, never()).softDeleteTask(anyString(), any(), any(), anyString());
    }

    @Test
    @DisplayName("accepts a batch at exactly the cap (200) - boundary is '>' not '>='")
    void acceptsBatchAtCap() {
        List<UUID> exactly = java.util.stream.Stream.generate(UUID::randomUUID).limit(200).toList();
        ResponseEntity<?> res = controller.bulkTaskAction(new BulkTaskRequest(exactly, "delete", null), req);
        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(body(res)).containsEntry("succeeded", 200);
    }

    @Test
    @DisplayName("a mixed restore set reports the live (non-trashed) row as failed while the trashed one succeeds")
    void mixedRestorePartialSuccess() {
        UUID trashed = UUID.randomUUID();
        UUID live = UUID.randomUUID();
        stubStatus(trashed, AgentTaskEntity.STATUS_DELETED); // restoreTask mock no-ops → ok
        // 'live' uses the default pending stub; a real restoreTask would reject it → simulate the throw.
        lenient().doThrow(new IllegalStateException("only a task in the Deleted column can be restored"))
                .when(taskService).restoreTask(TENANT, live, null, TENANT);

        ResponseEntity<?> res = controller.bulkTaskAction(
                new BulkTaskRequest(List.of(trashed, live), "restore", null), req);

        assertThat(body(res)).containsEntry("succeeded", 1).containsEntry("failed", 1);
    }

    @Test
    @DisplayName("partial success - an out-of-scope id fails without aborting the in-scope ones")
    void partialSuccess() {
        UUID inScope = UUID.randomUUID();
        UUID outOfScope = UUID.randomUUID();
        when(taskRepository.findByIdAndOrganizationIdStrict(outOfScope, ORG)).thenReturn(Optional.empty());

        ResponseEntity<?> res = controller.bulkTaskAction(
                new BulkTaskRequest(List.of(inScope, outOfScope), "delete", null), req);

        assertThat(body(res)).containsEntry("succeeded", 1).containsEntry("failed", 1);
        verify(taskService).softDeleteTask(TENANT, inScope, null, TENANT);
        verify(taskService, never()).softDeleteTask(TENANT, outOfScope, null, TENANT);
    }

    @Test
    @DisplayName("getStats surfaces the deleted bucket from the grouped status query")
    void statsIncludesDeletedBucket() {
        when(taskRepository.countByStatusGroupedByOrganizationIdStrict(ORG))
                .thenReturn(List.of(new Object[]{"deleted", 4L}, new Object[]{"pending", 2L}));
        when(taskRepository.countBacklogByOrganizationIdStrict(ORG)).thenReturn(1L);

        ResponseEntity<?> res = controller.getStats(req);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(body(res))
                .containsEntry("deleted", 4L)
                .containsEntry("pending", 2L)
                .containsEntry("backlog", 1L);
    }

    @Test
    @DisplayName("a service exception on one id is captured per-item, not propagated")
    void perItemErrorCaptured() {
        UUID ok = UUID.randomUUID();
        UUID boom = UUID.randomUUID();
        // Deterministic ordering via a LinkedHashSet-style list; stub the failing id.
        lenient().doThrow(new IllegalStateException("nope"))
                .when(taskService).softDeleteTask(TENANT, boom, null, TENANT);

        ResponseEntity<?> res = controller.bulkTaskAction(
                new BulkTaskRequest(List.of(ok, boom), "delete", null), req);

        assertThat(body(res)).containsEntry("succeeded", 1).containsEntry("failed", 1);
    }
}
