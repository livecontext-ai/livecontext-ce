package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.TaskStatusCategory;
import com.apimarketplace.agent.domain.TaskStatusEntity;
import com.apimarketplace.agent.service.TaskStatusService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link TaskStatusController}: board-column config HTTP surface. Verifies the
 * delete → relocation orchestration (no orphaned cards), create/reorder happy
 * paths, the 400 mapping for invalid input, and the empty-order guard.
 */
class TaskStatusControllerTest {

    private static final String TENANT = "tenant-1";
    private static final String ORG = "org-1";

    private TaskStatusService statusService;
    private TenantResolver tenantResolver;
    private TaskStatusController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        statusService = mock(TaskStatusService.class);
        tenantResolver = mock(TenantResolver.class);
        request = mock(HttpServletRequest.class);
        controller = new TaskStatusController(statusService, tenantResolver);
        when(tenantResolver.resolve(request)).thenReturn(TENANT);
        when(tenantResolver.resolveOrgId(request)).thenReturn(ORG);
    }

    private TaskStatusEntity entity(String key, TaskStatusCategory cat, boolean system) {
        TaskStatusEntity e = new TaskStatusEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(TENANT);
        e.setOrganizationId(ORG);
        e.setKey(key);
        e.setLabel(key);
        e.setCategory(cat.wireKey());
        e.setSystem(system);
        return e;
    }

    @Test
    @DisplayName("delete relocates the deleted column's tasks to the fallback and reports the count")
    void deleteRelocatesTasks() {
        UUID id = UUID.randomUUID();
        when(statusService.deleteStatusAndRelocate(TENANT, ORG, id))
                .thenReturn(new TaskStatusService.DeletedStatusResult("qa", "in_review", 4));

        ResponseEntity<?> resp = controller.delete(id, request);

        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(true, body.get("deleted"));
        assertEquals(4, body.get("moved_tasks"));
        assertEquals("in_review", body.get("fallback_status"));
        verify(statusService).deleteStatusAndRelocate(TENANT, ORG, id);
    }

    @Test
    @DisplayName("delete of a system column maps the service error to 400")
    void deleteSystemMapsTo400() {
        UUID id = UUID.randomUUID();
        when(statusService.deleteStatusAndRelocate(TENANT, ORG, id))
                .thenThrow(new IllegalArgumentException("the built-in 'in_review' column cannot be deleted; hide it instead"));

        ResponseEntity<?> resp = controller.delete(id, request);

        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("delete is NOT @Transactional, so a service validation failure can't poison a controller tx into a 500")
    void deleteIsNotTransactional() throws NoSuchMethodException {
        // Regression for the rollback-only 500: the controller used to be @Transactional and
        // caught the service's IllegalArgumentException, but the (joined) transaction was already
        // marked rollback-only, so the commit threw UnexpectedRollbackException -> HTTP 500.
        // The atomic delete+relocate now lives entirely inside the service; the controller must
        // stay non-transactional so a caught validation error surfaces as a clean 400.
        Method delete = TaskStatusController.class.getMethod("delete", UUID.class, HttpServletRequest.class);
        assertNull(delete.getAnnotation(Transactional.class),
                "TaskStatusController.delete must not be @Transactional (would re-introduce the rollback-only 500)");
    }

    @Test
    @DisplayName("create returns the new column; invalid input maps to 400")
    void createHappyAndError() {
        when(statusService.createStatus(eq(TENANT), eq(ORG), eq("QA"), eq("in_review"), any(), any()))
                .thenReturn(entity("qa", TaskStatusCategory.IN_REVIEW, false));
        ResponseEntity<?> ok = controller.create(
                new TaskStatusController.CreateStatusBody("QA", "in_review", null, null), request);
        assertEquals(200, ok.getStatusCode().value());

        when(statusService.createStatus(eq(TENANT), eq(ORG), eq("X"), eq("bogus"), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid category 'bogus'"));
        ResponseEntity<?> bad = controller.create(
                new TaskStatusController.CreateStatusBody("X", "bogus", null, null), request);
        assertEquals(400, bad.getStatusCode().value());
    }

    @Test
    @DisplayName("reorder rejects an empty body before touching the service")
    void reorderEmptyGuard() {
        ResponseEntity<?> resp = controller.reorder(new TaskStatusController.ReorderBody(List.of()), request);
        assertEquals(400, resp.getStatusCode().value());
        verify(statusService, never()).reorder(any(), any(), any());
    }

    @Test
    @DisplayName("reorder parses ids and returns the reordered board")
    void reorderHappy() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(statusService.reorder(eq(TENANT), eq(ORG), any()))
                .thenReturn(List.of(entity("a", TaskStatusCategory.PENDING, true)));
        ResponseEntity<?> resp = controller.reorder(
                new TaskStatusController.ReorderBody(List.of(a.toString(), b.toString())), request);
        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("list returns the board with a count envelope")
    void listReturnsBoard() {
        when(statusService.getBoard(TENANT, ORG)).thenReturn(List.of(
                entity("pending", TaskStatusCategory.PENDING, true),
                entity("qa", TaskStatusCategory.IN_REVIEW, false)));
        ResponseEntity<?> resp = controller.list(request);
        assertEquals(200, resp.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertEquals(2, body.get("count"));
        assertTrue(body.containsKey("statuses"));
    }

    @Test
    @DisplayName("update maps a service validation error to 400")
    void updateMapsTo400() {
        UUID id = UUID.randomUUID();
        when(statusService.updateStatus(eq(TENANT), eq(ORG), eq(id), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("a system status's category is fixed and cannot be changed"));
        ResponseEntity<?> resp = controller.update(id,
                new TaskStatusController.UpdateStatusBody(null, "done", null, null, null, null), request);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("reorder maps a malformed (non-UUID) id to 400 and never calls the service")
    void reorderMalformedIdMapsTo400() {
        ResponseEntity<?> resp = controller.reorder(
                new TaskStatusController.ReorderBody(List.of("not-a-uuid")), request);
        assertEquals(400, resp.getStatusCode().value());
        verify(statusService, never()).reorder(any(), any(), any());
    }
}
