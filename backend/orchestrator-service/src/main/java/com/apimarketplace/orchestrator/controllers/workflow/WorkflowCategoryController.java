package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowCategoryEntity;
import com.apimarketplace.orchestrator.services.WorkflowCategoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST Controller for workflow categories.
 */
@RestController
@RequestMapping("/api/categories")
public class WorkflowCategoryController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowCategoryController.class);

    private final WorkflowCategoryService categoryService;

    /**
     * Admin-token gate for category mutations. Audit 2026-05-16 round-2:
     * categories are a GLOBAL table (every tenant sees them) - comments
     * already said "admin only - requires auth" but the only check was
     * presence of {@code X-User-ID}. Any authenticated user could
     * create/update/delete global categories. Defense aligned with the
     * catalog Tool*Controller pattern: hash-compare against
     * {@code orchestrator.admin-token} property.
     */
    @org.springframework.beans.factory.annotation.Value("${orchestrator.admin-token:}")
    private String adminToken;

    private boolean isAdminCaller(String headerToken) {
        if (adminToken == null || adminToken.isBlank()) return false;
        if (headerToken == null || headerToken.isBlank()) return false;
        return java.security.MessageDigest.isEqual(
                adminToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headerToken.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public WorkflowCategoryController(WorkflowCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    /**
     * Get all active categories (public endpoint - no auth required)
     */
    @GetMapping
    public ResponseEntity<?> getCategories(@RequestParam(defaultValue = "true") boolean activeOnly) {
        try {
            List<WorkflowCategoryEntity> categories = activeOnly
                    ? categoryService.getActiveCategories()
                    : categoryService.getAllCategories();

            List<Map<String, Object>> response = categories.stream()
                    .map(this::toResponse)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "count", response.size(),
                    "categories", response
            ));
        } catch (Exception e) {
            logger.error("Error getting categories", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get categories"));
        }
    }

    /**
     * Get category by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<?> getCategoryById(@PathVariable String id) {
        try {
            UUID categoryId = UUID.fromString(id);
            return categoryService.getCategoryById(categoryId)
                    .map(category -> ResponseEntity.ok(toResponse(category)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid category ID"));
        } catch (Exception e) {
            logger.error("Error getting category by ID", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get category"));
        }
    }

    /**
     * Get category by slug
     */
    @GetMapping("/slug/{slug}")
    public ResponseEntity<?> getCategoryBySlug(@PathVariable String slug) {
        try {
            return categoryService.getCategoryBySlug(slug)
                    .map(category -> ResponseEntity.ok(toResponse(category)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error getting category by slug", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to get category"));
        }
    }

    /**
     * Create a new category (admin only - requires auth)
     */
    @PostMapping
    public ResponseEntity<?> createCategory(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminTokenHeader,
            @RequestBody CreateCategoryRequest request) {
        if (!isAdminCaller(adminTokenHeader)) {
            logger.warn("Refused unauthorized POST /api/categories by tenant={} - admin-token mismatch", tenantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            logger.info("Creating category: {} by tenant: {}", request.slug, tenantId);

            WorkflowCategoryEntity category = categoryService.createCategory(
                    request.slug,
                    request.name,
                    request.description,
                    request.iconSlug,
                    request.color,
                    request.displayOrder
            );

            return ResponseEntity.ok(toResponse(category));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request creating category: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error creating category", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to create category"));
        }
    }

    /**
     * Update a category (admin only - requires auth)
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateCategory(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminTokenHeader,
            @PathVariable String id,
            @RequestBody UpdateCategoryRequest request) {
        if (!isAdminCaller(adminTokenHeader)) {
            logger.warn("Refused unauthorized PUT /api/categories/{} by tenant={} - admin-token mismatch", id, tenantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            logger.info("Updating category: {} by tenant: {}", id, tenantId);

            UUID categoryId = UUID.fromString(id);
            WorkflowCategoryEntity category = categoryService.updateCategory(
                    categoryId,
                    request.name,
                    request.description,
                    request.iconSlug,
                    request.color,
                    request.displayOrder,
                    request.isActive
            );

            return ResponseEntity.ok(toResponse(category));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request updating category: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating category", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to update category"));
        }
    }

    /**
     * Delete a category (admin only - requires auth)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteCategory(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Internal-Admin-Token", required = false) String adminTokenHeader,
            @PathVariable String id) {
        if (!isAdminCaller(adminTokenHeader)) {
            logger.warn("Refused unauthorized DELETE /api/categories/{} by tenant={} - admin-token mismatch", id, tenantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        try {
            logger.info("Deleting category: {} by tenant: {}", id, tenantId);

            UUID categoryId = UUID.fromString(id);
            categoryService.deleteCategory(categoryId);

            return ResponseEntity.ok(Map.of("success", true, "message", "Category deleted"));
        } catch (IllegalArgumentException e) {
            logger.warn("Bad request deleting category: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error deleting category", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to delete category"));
        }
    }

    /**
     * Convert entity to response map
     */
    private Map<String, Object> toResponse(WorkflowCategoryEntity category) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", category.getId().toString());
        response.put("slug", category.getSlug());
        response.put("name", category.getName());
        response.put("description", category.getDescription());
        response.put("iconSlug", category.getIconSlug());
        response.put("color", category.getColor());
        response.put("displayOrder", category.getDisplayOrder());
        response.put("isActive", category.getIsActive());
        response.put("createdAt", category.getCreatedAt() != null ? category.getCreatedAt().toString() : null);
        response.put("updatedAt", category.getUpdatedAt() != null ? category.getUpdatedAt().toString() : null);
        return response;
    }

    // Request DTOs

    public static class CreateCategoryRequest {
        public String slug;
        public String name;
        public String description;
        public String iconSlug;
        public String color;
        public Integer displayOrder;
    }

    public static class UpdateCategoryRequest {
        public String name;
        public String description;
        public String iconSlug;
        public String color;
        public Integer displayOrder;
        public Boolean isActive;
    }
}
