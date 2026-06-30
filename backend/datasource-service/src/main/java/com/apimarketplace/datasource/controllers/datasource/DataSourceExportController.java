package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.datasource.exception.ResourceNotFoundException;
import com.apimarketplace.datasource.services.DataSourceExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Controller for DataSource export operations (CSV, JSON, XLSX).
 *
 * Extracted from DataSourceController for Single Responsibility Principle.
 *
 * @see DataSourceController
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceExportController {

    private final DataSourceExportService exportService;
    private final TenantIdResolver tenantIdResolver;

    public DataSourceExportController(
            DataSourceExportService exportService,
            TenantIdResolver tenantIdResolver) {
        this.exportService = exportService;
        this.tenantIdResolver = tenantIdResolver;
    }

    /**
     * Export data in various formats (CSV, JSON, XLSX) with compression.
     * GET /api/data-sources/{id}/export
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<?> exportData(
            @PathVariable("id") Long dataSourceId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam("format") String format,
            @RequestParam(value = "ids", required = false) String ids,
            @RequestParam(value = "columns", required = false) String columns,
            @RequestParam(value = "search", required = false) String search,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "cursor", required = false) String cursor) {

        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);

            // Parse format
            DataSourceExportService.ExportFormat exportFormat;
            try {
                exportFormat = DataSourceExportService.ExportFormat.fromString(format);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invalid format. Supported formats: csv, json, xlsx"));
            }

            // Parse IDs
            List<Long> idList = null;
            if (ids != null && !ids.trim().isEmpty()) {
                try {
                    idList = Arrays.stream(ids.split(","))
                        .map(String::trim)
                        .map(Long::parseLong)
                        .toList();
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest()
                        .body(Map.of("error", "Invalid IDs format"));
                }
            }

            // Parse columns
            List<String> columnList = null;
            if (columns != null && !columns.trim().isEmpty()) {
                columnList = Arrays.stream(columns.split(","))
                    .map(String::trim)
                    .toList();
            }

            // Call export service with org context - closes the gap where an org teammate could
            // not export a datasource created by their admin (different tenant_id, same workspace).
            String orgId = tenantIdResolver.resolveOrgId(request);
            String orgRole = request.getHeader("X-Organization-Role");
            DataSourceExportService.ExportResult result = exportService.exportData(
                dataSourceId, tenantId, orgId, orgRole, exportFormat, idList, columnList,
                search, sort, limit, cursor
            );

            return ResponseEntity.ok()
                .headers(result.headers())
                .body(result.data());

        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(404)
                .body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to export data", "message", e.getMessage()));
        }
    }
}
