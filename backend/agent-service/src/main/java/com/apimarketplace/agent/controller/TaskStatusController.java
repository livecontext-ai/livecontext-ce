package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.dto.TaskStatusResponse;
import com.apimarketplace.agent.service.TaskStatusService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Human REST API for configuring the task board's columns (statuses).
 * <p>
 * Tenant-scoped via {@code X-User-ID}, workspace-scoped via {@code X-Organization-ID}
 * (a NULL org is the personal board). Agents do not manage columns; the agent
 * delegation surface drives tasks, not board layout.
 */
@RestController
@RequestMapping("/api/tasks/statuses")
public class TaskStatusController {

    private static final Logger log = LoggerFactory.getLogger(TaskStatusController.class);

    private final TaskStatusService statusService;
    private final TenantResolver tenantResolver;

    public TaskStatusController(TaskStatusService statusService,
                                TenantResolver tenantResolver) {
        this.statusService = statusService;
        this.tenantResolver = tenantResolver;
    }

    public record CreateStatusBody(String label, String category, String color, Integer wipLimit) {}
    public record UpdateStatusBody(String label, String category, String color, Integer wipLimit,
                                   Boolean clearWipLimit, Boolean hidden) {}
    public record ReorderBody(List<String> orderedIds) {}

    /** List the board's columns in display order (lazily seeding defaults). */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        List<TaskStatusResponse> statuses = statusService.getBoard(tenantId, orgId).stream()
                .map(TaskStatusResponse::from).toList();
        return ResponseEntity.ok(Map.of("count", statuses.size(), "statuses", statuses));
    }

    /** Create a custom column. */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateStatusBody body, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            var created = statusService.createStatus(
                    tenantId, orgId, body.label(), body.category(), body.color(), body.wipLimit());
            return ResponseEntity.ok(TaskStatusResponse.from(created));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Rename / recolour / re-category / WIP-cap / hide a column. */
    @PatchMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody UpdateStatusBody body,
                                    HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            var updated = statusService.updateStatus(tenantId, orgId, id, body.label(), body.category(),
                    body.color(), body.wipLimit(), body.clearWipLimit(), body.hidden());
            return ResponseEntity.ok(TaskStatusResponse.from(updated));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Delete a custom column. Any task sitting in it is relocated to the
     * fallback status (its category's default) so no card is orphaned. The
     * delete and the relocation are one atomic transaction inside the service
     * ({@link TaskStatusService#deleteStatusAndRelocate}); a validation failure
     * (system column / unknown id) rolls back cleanly and surfaces here as a 400,
     * never a 500.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            TaskStatusService.DeletedStatusResult res = statusService.deleteStatusAndRelocate(tenantId, orgId, id);
            return ResponseEntity.ok(Map.of(
                    "deleted", true,
                    "moved_tasks", res.movedTasks(),
                    "fallback_status", res.fallbackKey()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Apply a new column order. Body lists status ids in the desired order. */
    @PutMapping("/order")
    public ResponseEntity<?> reorder(@RequestBody ReorderBody body, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        if (body == null || body.orderedIds() == null || body.orderedIds().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderedIds is required"));
        }
        try {
            List<UUID> ids = new ArrayList<>(body.orderedIds().size());
            for (String s : body.orderedIds()) ids.add(UUID.fromString(s));
            List<TaskStatusResponse> statuses = statusService.reorder(tenantId, orgId, ids).stream()
                    .map(TaskStatusResponse::from).toList();
            return ResponseEntity.ok(Map.of("count", statuses.size(), "statuses", statuses));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
