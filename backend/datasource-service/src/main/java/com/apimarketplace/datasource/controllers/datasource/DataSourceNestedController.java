package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.datasource.domain.DataSourceNestedModels.*;
import com.apimarketplace.datasource.services.DataSourceEnhancedService;
import com.apimarketplace.datasource.services.DataSourceNestedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * Controller for DataSource nested JSON navigation operations.
 *
 * Extracted from DataSourceController for Single Responsibility Principle.
 *
 * @see DataSourceController
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceNestedController {

    private final DataSourceNestedService nestedService;
    private final TenantIdResolver tenantIdResolver;
    private final DataSourceEnhancedService enhancedService;

    public DataSourceNestedController(
            DataSourceNestedService nestedService,
            TenantIdResolver tenantIdResolver,
            DataSourceEnhancedService enhancedService) {
        this.nestedService = nestedService;
        this.tenantIdResolver = tenantIdResolver;
        this.enhancedService = enhancedService;
    }

    /**
     * Nested item queries filter on the owner's tenantId (items carry the parent
     * DS owner's tenant - see CrudExecutorService.execute). For an org teammate
     * the raw caller tenant returns 0 rows, so resolve the effective tenant the
     * same way the flat items endpoint does (resolveAccessibleTenantId: caller
     * when owner, owner when org access is verified).
     */
    private String resolveEffectiveTenantId(Long dataSourceId, HttpServletRequest request, String tenantIdParam) {
        String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
        return enhancedService.resolveAccessibleTenantId(
                dataSourceId,
                tenantId,
                tenantIdResolver.resolveOrgId(request),
                request.getHeader("X-Organization-Role"));
    }

    /**
     * Get nested data at a specific JSON path with pagination.
     * GET /api/data-sources/{id}/items/nested
     */
    @GetMapping("/{id}/items/nested")
    public ResponseEntity<NestedPaginationResponse<DataSourceNestedRow>> getNestedData(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam("path") String jsonPath,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", required = false) String sortOrder) {

        try {
            String tenantId = resolveEffectiveTenantId(dataSourceId, httpRequest, tenantIdParam);

            // Validation
            if (limit > 500) limit = 500;
            if (limit < 1) limit = 20;
            if (page < 1) page = 1;

            // Create request
            NestedDataRequest request = new NestedDataRequest(
                jsonPath,
                page,
                limit,
                sortBy,
                sortOrder,
                null // cursor not used yet
            );

            // Get nested data
            NestedPaginationResponse<DataSourceNestedRow> response = nestedService.getNestedData(
                dataSourceId, tenantId, jsonPath, request
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get column definitions for nested data at a specific JSON path.
     * GET /api/data-sources/{id}/columns/nested
     */
    @GetMapping("/{id}/columns/nested")
    public ResponseEntity<List<NestedColumnDefinition>> getNestedColumnDefinitions(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam("path") String jsonPath) {

        try {
            String tenantId = resolveEffectiveTenantId(dataSourceId, request, tenantIdParam);
            List<NestedColumnDefinition> columns = nestedService.getNestedColumnDefinitions(
                dataSourceId, tenantId, jsonPath
            );
            return ResponseEntity.ok(columns);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
