package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.dto.TaskLabelResponse;
import com.apimarketplace.agent.dto.TaskResponse;
import com.apimarketplace.agent.service.AgentTaskService;
import com.apimarketplace.agent.service.TaskLabelService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Human REST API for the board label catalog (F2) and setting a task's labels.
 * Tenant + workspace scoped via {@code X-User-ID} / {@code X-Organization-ID}.
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskLabelController {

    private final TaskLabelService labelService;
    private final AgentTaskService taskService;
    private final TenantResolver tenantResolver;

    public TaskLabelController(TaskLabelService labelService,
                               AgentTaskService taskService,
                               TenantResolver tenantResolver) {
        this.labelService = labelService;
        this.taskService = taskService;
        this.tenantResolver = tenantResolver;
    }

    public record LabelBody(String name, String color) {}
    public record SetLabelsBody(List<String> labelIds) {}

    @GetMapping("/labels")
    public ResponseEntity<?> list(HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        List<TaskLabelResponse> labels = labelService.list(tenantId, orgId).stream()
                .map(TaskLabelResponse::from).toList();
        return ResponseEntity.ok(Map.of("count", labels.size(), "labels", labels));
    }

    @PostMapping("/labels")
    public ResponseEntity<?> create(@RequestBody LabelBody body, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            var created = labelService.create(tenantId, orgId, body.name(), body.color());
            return ResponseEntity.ok(TaskLabelResponse.from(created));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PatchMapping("/labels/{id}")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody LabelBody body, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            var updated = labelService.update(tenantId, orgId, id, body.name(), body.color());
            return ResponseEntity.ok(TaskLabelResponse.from(updated));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/labels/{id}")
    public ResponseEntity<?> delete(@PathVariable UUID id, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        try {
            labelService.delete(tenantId, orgId, id);
            return ResponseEntity.ok(Map.of("deleted", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Replace a task's label set. */
    @PutMapping("/{taskId}/labels")
    public ResponseEntity<?> setLabels(@PathVariable UUID taskId, @RequestBody SetLabelsBody body,
                                       HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        tenantResolver.resolveOrgId(request);
        try {
            List<String> ids = body == null ? List.of() : body.labelIds();
            AgentTaskEntity updated = taskService.setTaskLabels(tenantId, taskId, null, tenantId, ids);
            return ResponseEntity.ok(TaskResponse.from(updated));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
