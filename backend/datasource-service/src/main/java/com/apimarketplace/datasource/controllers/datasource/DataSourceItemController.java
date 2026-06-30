package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.datasource.domain.DataSourceEnhancedModels.*;
import com.apimarketplace.datasource.exception.ResourceNotFoundException;
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
 * Controller for DataSource item operations (pagination, CRUD, bulk).
 *
 * Extracted from DataSourceController for Single Responsibility Principle.
 *
 * @see DataSourceController
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceItemController {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceItemController.class);

    private final DataSourceEnhancedService dataSourceEnhancedService;
    private final TenantIdResolver tenantIdResolver;
    private final DataSourceRequestParser requestParser;

    public DataSourceItemController(
            DataSourceEnhancedService dataSourceEnhancedService,
            TenantIdResolver tenantIdResolver,
            DataSourceRequestParser requestParser) {
        this.dataSourceEnhancedService = dataSourceEnhancedService;
        this.tenantIdResolver = tenantIdResolver;
        this.requestParser = requestParser;
    }

    /**
     * Get items with server-side pagination, filtering, and sorting.
     * GET /api/data-sources/{id}/items
     */
    @GetMapping("/{id}/items")
    public ResponseEntity<PaginationResponse<DataSourceItemRow>> getItems(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", required = false) String sortOrder,
            @RequestParam(value = "limit", defaultValue = "300") Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "page", defaultValue = "1") Integer page) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(httpRequest, tenantIdParam);

            // Validate parameters
            if (limit > 500) limit = 500;
            if (limit < 1) limit = 300;
            if (page < 1) page = 1;

            // Parse sort parameter (priority to new sortBy/sortOrder parameters)
            List<SortRequest> sortRequests;
            if (sortBy != null) {
                sortRequests = requestParser.parseSortParameters(sortBy, sortOrder);
            } else {
                sortRequests = requestParser.parseSortParameter(sort);
            }

            // Parse filter parameter
            Map<String, Object> filterMap = requestParser.parseFilterParameter(filter);

            // Calculate startRow based on page
            Integer startRow = (page - 1) * limit;
            Integer endRow = startRow + limit;

            // Create pagination request
            PaginationRequest request = new PaginationRequest(
                startRow, endRow, limit, cursor, sortRequests, filterMap, query
            );

            PaginationResponse<DataSourceItemRow> response = dataSourceEnhancedService
                .getItemsWithPagination(
                    dataSourceId,
                    tenantId,
                    tenantIdResolver.resolveOrgId(httpRequest),
                    httpRequest.getHeader("X-Organization-Role"),
                    request);

            return ResponseEntity.ok(response);

        } catch (ResourceNotFoundException e) {
            return ResponseEntity.notFound()
                .header("X-Error-Message", e.getMessage())
                .build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .header("X-Error-Message", e.getMessage())
                .body(new PaginationResponse<>(List.of(), 0, null, false, 0));
        } catch (Exception e) {
            String errorMessage = e.getMessage() != null ? e.getMessage() : "Internal server error";
            return ResponseEntity.badRequest()
                .header("X-Error-Message", errorMessage)
                .body(new PaginationResponse<>(List.of(), 0, null, false, 0));
        }
    }

    /**
     * Add a new item to a DataSource.
     * POST /api/data-sources/{id}/items
     */
    @PostMapping("/{id}/items")
    public ResponseEntity<DataSourceItemRow> addItem(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody Map<String, Object> requestBody) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            if (isViewerRole(request)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) requestBody.get("data");
            Integer priority = (Integer) requestBody.get("priority");

            if (data == null) {
                data = new java.util.HashMap<>();
            }
            if (priority == null) {
                priority = 1;
            }

            DataSourceItemRow newItem = dataSourceEnhancedService.addItem(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(request),
                request.getHeader("X-Organization-Role"),
                data,
                priority);
            return ResponseEntity.ok(newItem);
        } catch (Exception e) {
            logger.error("Error adding item to dataSourceId={}: {}", dataSourceId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Apply JSON patch to a single item.
     * PUT /api/data-sources/{id}/items/{itemId}
     */
    @PutMapping("/{id}/items/{itemId}")
    public ResponseEntity<DataSourceItemRow> updateItem(
            @PathVariable("id") Long dataSourceId,
            @PathVariable("itemId") Long itemId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestBody Map<String, Object> requestBody) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            if (isViewerRole(request)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> patchMaps = (List<Map<String, Object>>) requestBody.get("patch");

            if (patchMaps == null || patchMaps.isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            // Convert to JsonPatchOperation objects
            List<JsonPatchOperation> patches = patchMaps.stream()
                .map(requestParser::mapToJsonPatchOperation)
                .toList();

            DataSourceItemRow updatedItem = dataSourceEnhancedService.applyJsonPatch(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(request),
                request.getHeader("X-Organization-Role"),
                itemId,
                patches);

            return ResponseEntity.ok(updatedItem);

        } catch (Exception e) {
            logger.error("Error updating item dataSourceId={}, itemId={}: {}", dataSourceId, itemId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a single item.
     * DELETE /api/data-sources/{id}/items/{itemId}
     */
    @DeleteMapping("/{id}/items/{itemId}")
    public ResponseEntity<Void> deleteItem(
            @PathVariable("id") Long dataSourceId,
            @PathVariable("itemId") Long itemId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            if (isViewerRole(request)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            dataSourceEnhancedService.deleteItem(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(request),
                request.getHeader("X-Organization-Role"),
                itemId);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Execute bulk operations.
     * POST /api/data-sources/{id}/bulk
     */
    @PostMapping("/{id}/bulk")
    public ResponseEntity<BulkOperationResult> executeBulkOperation(
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
            @SuppressWarnings("unchecked")
            List<Object> rawIds = (List<Object>) requestBody.get("ids");
            List<Long> ids = rawIds.stream()
                .map(id -> id instanceof Integer ? ((Integer) id).longValue() : (Long) id)
                .toList();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> patches = (List<Map<String, Object>>) requestBody.get("patches");

            BulkOperationRequest request = new BulkOperationRequest(
                BulkOperationType.valueOf(operation.toUpperCase()),
                ids,
                patches != null ? patches.stream()
                    .map(requestParser::mapToJsonPatchOperation)
                    .toList() : List.of()
            );

            BulkOperationResult result = dataSourceEnhancedService.executeBulkOperation(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(httpRequest),
                httpRequest.getHeader("X-Organization-Role"),
                request);

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Bulk operation error for dataSourceId={}: {}", dataSourceId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    private static boolean isViewerRole(HttpServletRequest request) {
        String orgRole = request.getHeader("X-Organization-Role");
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }
}
