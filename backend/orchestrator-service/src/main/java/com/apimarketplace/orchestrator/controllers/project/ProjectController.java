package com.apimarketplace.orchestrator.controllers.project;

import com.apimarketplace.orchestrator.domain.*;
import com.apimarketplace.orchestrator.services.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for project management.
 * All endpoints require authentication via X-User-ID header (injected by gateway from JWT).
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger logger = LoggerFactory.getLogger(ProjectController.class);

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    // ===================== Project CRUD =====================

    @PostMapping
    public ResponseEntity<?> createProject(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
        }
        String description = (String) body.get("description");
        String color = (String) body.get("color");
        String icon = (String) body.get("icon");

        ProjectEntity project = projectService.createProject(userId, name, description, color, icon, organizationId);
        return ResponseEntity.ok(mapProject(project, "OWNER"));
    }

    @GetMapping
    public ResponseEntity<?> listProjects(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        List<ProjectEntity> projects = projectService.getUserProjects(userId, orgId, orgRole);
        List<Map<String, Object>> result = projects.stream()
            .map(p -> {
                String role = p.getOwnerId().equals(userId) ? "OWNER" : "EDITOR";
                Map<String, Object> mapped = mapProject(p, role);
                mapped.put("resourceCounts", projectService.getResourceCounts(p.getId(), userId, orgId, orgRole));
                return mapped;
            })
            .toList();
        return ResponseEntity.ok(Map.of("projects", result, "count", result.size()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getProject(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id) {
        return projectService.getProject(id, userId, orgId, orgRole)
            .map(project -> {
                String role = project.getOwnerId().equals(userId) ? "OWNER" : "EDITOR";
                Map<String, Object> response = mapProject(project, role);
                response.put("resourceCounts", projectService.getResourceCounts(id, userId, orgId, orgRole));
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateProject(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id,
            @RequestBody Map<String, Object> body) {
        // 2026-05-18 - workspace-scoped: org-admin can update teammate projects;
        // personal caller cannot accidentally hit own org project.
        return projectService.updateProject(id, userId, orgId, orgRole, body)
            .map(project -> ResponseEntity.ok(mapProject(project, "OWNER")))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProject(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id) {
        if (projectService.deleteProject(id, userId, orgId, orgRole)) {
            return ResponseEntity.ok(Map.of("deleted", true));
        }
        return ResponseEntity.notFound().build();
    }

    // ===================== Resource Management =====================

    @PostMapping("/{id}/resources")
    public ResponseEntity<?> assignResource(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        String resourceType = body.get("resourceType");
        String resourceId = body.get("resourceId");
        if (resourceType == null || resourceId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "resourceType and resourceId are required"));
        }
        if (projectService.assignResource(id, resourceType, resourceId, userId, orgId, orgRole)) {
            return ResponseEntity.ok(Map.of("assigned", true));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Cannot assign resource"));
    }

    @DeleteMapping("/{id}/resources/{resourceType}/{resourceId}")
    public ResponseEntity<?> removeResource(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id,
            @PathVariable String resourceType,
            @PathVariable String resourceId) {
        if (projectService.removeResource(id, resourceType, resourceId, userId, orgId, orgRole)) {
            return ResponseEntity.ok(Map.of("removed", true));
        }
        return ResponseEntity.badRequest().body(Map.of("error", "Cannot remove resource"));
    }

    @GetMapping("/{id}/resources")
    public ResponseEntity<?> listResources(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id) {
        if (projectService.getProject(id, userId, orgId, orgRole).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(projectService.getResourceCounts(id, userId, orgId, orgRole));
    }

    @GetMapping("/{id}/resources/details")
    public ResponseEntity<?> getProjectResourceDetails(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id) {
        if (projectService.getProject(id, userId, orgId, orgRole).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(projectService.getProjectResources(id, userId, orgId, orgRole));
    }

    // ===================== Helpers =====================

    private Map<String, Object> mapProject(ProjectEntity project, String role) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", project.getId());
        map.put("name", project.getName());
        map.put("slug", project.getSlug());
        map.put("description", project.getDescription());
        map.put("color", project.getColor());
        map.put("icon", project.getIcon());
        map.put("ownerId", project.getOwnerId());
        map.put("isArchived", project.getIsArchived());
        map.put("createdAt", project.getCreatedAt());
        map.put("updatedAt", project.getUpdatedAt());
        map.put("currentUserRole", role);
        return map;
    }
}
