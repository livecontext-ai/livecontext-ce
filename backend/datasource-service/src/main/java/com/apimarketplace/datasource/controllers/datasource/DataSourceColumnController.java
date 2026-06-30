package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import com.apimarketplace.datasource.services.DataSourceEnhancedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

/**
 * Controller for DataSource column operations.
 *
 * Extracted from DataSourceController for Single Responsibility Principle.
 *
 * @see DataSourceController
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceColumnController {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceColumnController.class);

    private final DataSourceEnhancedService dataSourceEnhancedService;
    private final TenantIdResolver tenantIdResolver;

    public DataSourceColumnController(
            DataSourceEnhancedService dataSourceEnhancedService,
            TenantIdResolver tenantIdResolver) {
        this.dataSourceEnhancedService = dataSourceEnhancedService;
        this.tenantIdResolver = tenantIdResolver;
    }

    /**
     * Get column definitions for AG Grid.
     * GET /api/data-sources/{id}/columns
     */
    @GetMapping("/{id}/columns")
    public ResponseEntity<List<ColumnDefinition>> getColumnDefinitions(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            List<ColumnDefinition> columns = dataSourceEnhancedService.getColumnDefinitions(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(request),
                request.getHeader("X-Organization-Role"));
            logger.debug("Found {} columns for dataSourceId={}", columns.size(), dataSourceId);
            return ResponseEntity.ok(columns);
        } catch (Exception e) {
            logger.error("Error getting column definitions for dataSourceId={}: {}", dataSourceId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Add a new column to a DataSource.
     * POST /api/data-sources/{id}/columns
     */
    @PostMapping("/{id}/columns")
    public ResponseEntity<Map<String, Object>> addColumn(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody Map<String, Object> requestBody) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            if (isViewerRole(request)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String columnName = (String) requestBody.get("name");
            String columnType = (String) requestBody.get("type");
            String columnStructure = (String) requestBody.getOrDefault("structure", "scalar");
            @SuppressWarnings("unchecked")
            Map<String, Object> displayConfig = (Map<String, Object>) requestBody.getOrDefault("display", Map.of());
            Object defaultValue = requestBody.get("defaultValue");

            if (columnName == null || columnName.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "Column name is required"));
            }

            boolean success = dataSourceEnhancedService.addColumn(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(request),
                request.getHeader("X-Organization-Role"),
                columnName,
                columnType,
                columnStructure,
                displayConfig,
                defaultValue
            );

            if (success) {
                return ResponseEntity.ok(Map.of("success", true, "message", "Column added successfully"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Failed to add column"));
            }
        } catch (Exception e) {
            logger.error("Error adding column to dataSourceId={}: {}", dataSourceId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Error adding column: " + e.getMessage()));
        }
    }

    /**
     * Manage columns (add, remove, rename).
     * PATCH /api/data-sources/{id}/columns
     */
    @PatchMapping("/{id}/columns")
    public ResponseEntity<ColumnManagementResult> manageColumn(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody Map<String, Object> requestBody) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(httpRequest, tenantIdParam);
            if (isViewerRole(httpRequest)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String operation = (String) requestBody.get("op");
            String key = (String) requestBody.get("key");
            String newKey = (String) requestBody.get("new_key");
            Object defaultValue = requestBody.get("default_value");
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> display = (java.util.Map<String, Object>) requestBody.get("display");

            ColumnManagementRequest request = new ColumnManagementRequest(
                ColumnOperation.valueOf(operation.toUpperCase()),
                key,
                newKey,
                defaultValue,
                display
            );

            ColumnManagementResult result = dataSourceEnhancedService.manageColumn(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(httpRequest),
                httpRequest.getHeader("X-Organization-Role"),
                request);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Save column order for a DataSource.
     * PUT /api/data-sources/{id}/column-order
     */
    @PutMapping("/{id}/column-order")
    public ResponseEntity<Map<String, Object>> saveColumnOrder(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody List<Map<String, Object>> columnOrder) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            if (isViewerRole(request)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            boolean success = dataSourceEnhancedService.saveColumnOrder(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(request),
                request.getHeader("X-Organization-Role"),
                columnOrder);

            if (success) {
                return ResponseEntity.ok(Map.of("success", true, "message", "Column order saved successfully"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Failed to save column order"));
            }
        } catch (Exception e) {
            logger.error("Error saving column order for dataSourceId={}: {}", dataSourceId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Error saving column order: " + e.getMessage()));
        }
    }

    private static boolean isViewerRole(HttpServletRequest request) {
        String orgRole = request.getHeader("X-Organization-Role");
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }
}
