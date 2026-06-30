package com.apimarketplace.datasource.controllers.datasource;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.datasource.domain.DataSourceModels.*;
import com.apimarketplace.datasource.persistence.MappingSpecConverter;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.datasource.services.DataSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Controller for DataSource CRUD operations.
 *
 * Extracted from DataSourceController for Single Responsibility Principle.
 *
 * @see DataSourceController
 */
@RestController
@RequestMapping("/api/data-sources")
public class DataSourceCrudController {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceCrudController.class);

    private final DataSourceService dataSourceService;
    private final TenantIdResolver tenantIdResolver;
    private final ObjectMapper objectMapper;
    private final OrgAccessGuard orgAccessService;

    public DataSourceCrudController(
            DataSourceService dataSourceService,
            TenantIdResolver tenantIdResolver,
            ObjectMapper objectMapper,
            OrgAccessGuard orgAccessService) {
        this.dataSourceService = dataSourceService;
        this.tenantIdResolver = tenantIdResolver;
        this.objectMapper = objectMapper;
        this.orgAccessService = orgAccessService;
    }

    /**
     * Create a new DataSource.
     * POST /api/data-sources
     */
    @PostMapping
    public ResponseEntity<DataSource> createDataSource(
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> request) {
        try {
            String tenantId = tenantIdResolver.resolveTenantId(httpRequest, (String) request.get("tenantId"));
            if (isViewerRole(orgRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            String name = (String) request.get("name");
            String description = (String) request.get("description");
            String sourceTypeStr = (String) request.get("sourceType");
            DataSourceType sourceType = sourceTypeStr != null ? DataSourceType.valueOf(sourceTypeStr) : DataSourceType.INLINE;
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceConfig = (Map<String, Object>) request.get("sourceConfig");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) request.get("data");
            String createdBy = (String) request.get("createdBy");
            Map<String, ColumnMappingSpec> mappingSpec = MappingSpecConverter.fromUntyped(
                request.get("mappingSpec"), objectMapper
            );

            DataSource dataSource = dataSourceService.createDataSource(
                tenantId, name, description, sourceType, sourceConfig, data, createdBy, mappingSpec, orgId
            );
            return ResponseEntity.ok(dataSource);
        } catch (Exception e) {
            logger.error("Error creating data source: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get all DataSources for a tenant (org-aware).
     * GET /api/data-sources
     */
    @GetMapping
    public ResponseEntity<List<DataSource>> getAllDataSources(
            HttpServletRequest request,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            List<DataSource> dataSources = dataSourceService.getDataSources(tenantId, orgId, orgRole);
            return ResponseEntity.ok(dataSources);
        } catch (Exception e) {
            logger.error("Error getting data sources: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Paged, DB-searchable, server-sorted + server-visibility-filtered list. Returns
     * {@code { items, totalCount, page, size, rowCounts, sampleRows, publicationStatuses }}.
     * {@code rowCounts} / {@code sampleRows} map each datasource id on the page to its row count /
     * first-N row sample; {@code publicationStatuses} maps each shared id on the page to
     * {@code {status, rejectionReason?}} (absent = not shared) - all batched server-side so the card
     * needs no per-row publication call. {@code sort} = name | lastModified (default lastModified),
     * {@code visibility} = all | public | private (default all).
     * GET /api/data-sources/paged
     */
    @GetMapping("/paged")
    public ResponseEntity<java.util.Map<String, Object>> getAllDataSourcesPaged(
            HttpServletRequest request,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "visibility", required = false) String visibility) {
        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            com.apimarketplace.datasource.services.DataSourceService.DataSourcePage pageResult =
                    dataSourceService.getDataSourcesPaged(tenantId, orgId, orgRole, q, page, size, sort, visibility);

            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("items", pageResult.items());
            body.put("totalCount", pageResult.totalCount());
            body.put("page", pageResult.page());
            body.put("size", pageResult.size());
            // Per-datasource row counts for the current page (id -> count), batched server-side.
            body.put("rowCounts", pageResult.rowCounts());
            // Per-datasource first-N row sample (id -> [row data…]), batched server-side, for
            // each card's mini-table preview.
            body.put("sampleRows", pageResult.sampleRows());
            // Per-datasource publication badge for the page (id -> {status, rejectionReason?}),
            // batched server-side - replaces the former per-row is-resource-published fan-out.
            body.put("publicationStatuses", pageResult.publicationStatuses());
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            logger.error("Error getting paged data sources: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Get a DataSource by ID.
     * GET /api/data-sources/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<DataSource> getDataSource(
            @PathVariable("id") Long id,
            HttpServletRequest request,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            Optional<DataSource> dataSourceOpt = dataSourceService.getDataSource(id);
            if (dataSourceOpt.isPresent()) {
                DataSource dataSource = dataSourceOpt.get();
                if (ScopeGuard.isInStrictScope(tenantId, orgId, dataSource.tenantId(), dataSource.organizationId())) {
                    // Within strict scope - if org context, verify deny-list
                    if (orgId != null && !orgId.isBlank()) {
                        if (dataSourceService.canAccessViaOrg(orgId, tenantId, String.valueOf(dataSource.id()), orgRole)) {
                            return ResponseEntity.ok(dataSource);
                        }
                        return ResponseEntity.notFound().build();
                    }
                    return ResponseEntity.ok(dataSource);
                }
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error getting data source {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Update a DataSource.
     * PUT /api/data-sources/{id}
     */
    @PutMapping("/{id}")
    public ResponseEntity<DataSource> updateDataSource(
            @PathVariable("id") Long id,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            // Audit 2026-05-17 round-3 - owner-or-org scope check.
            // Prior: PUT had NO scope check at all - any caller could update any DS by id.
            String tenantId = tenantIdResolver.resolveTenantId(httpRequest, null);
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            if (isViewerRole(orgRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            Optional<DataSource> existing = dataSourceService.getDataSource(id);
            if (existing.isEmpty()) return ResponseEntity.notFound().build();
            DataSource cur = existing.get();
            if (cur.organizationId() != null && isViewerRole(orgRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, cur.tenantId(), cur.organizationId())) {
                logger.warn("[SCOPE] DataSource update cross-tenant blocked: id={} caller={} orgId={}",
                        id, tenantId, orgId);
                return ResponseEntity.notFound().build();
            }

            String name = (String) request.get("name");
            String description = (String) request.get("description");
            @SuppressWarnings("unchecked")
            Map<String, Object> sourceConfig = (Map<String, Object>) request.get("sourceConfig");

            DataSource dataSource = dataSourceService.updateDataSource(id, name, description, sourceConfig, tenantId, orgRole);
            if (dataSource != null) {
                return ResponseEntity.ok(dataSource);
            }
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error updating data source {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Clone a DataSource.
     * POST /api/data-sources/{id}/clone
     */
    @PostMapping("/{id}/clone")
    public ResponseEntity<DataSource> cloneDataSource(
            @PathVariable("id") Long id,
            HttpServletRequest request,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, null);
            if (tenantId == null || tenantId.isBlank()) {
                return ResponseEntity.status(401).build();
            }
            // Audit 2026-05-17 round-4 - owner-or-org scope check on SOURCE.
            // Prior: cloneDataSource accepted any source id and the service path
            // trusted the tenantId mapping. Verify source is visible to caller.
            if (isViewerRole(orgRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            Optional<DataSource> source = dataSourceService.getDataSource(id);
            if (source.isEmpty()) return ResponseEntity.notFound().build();
            DataSource src = source.get();
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, src.tenantId(), src.organizationId())) {
                logger.warn("[SCOPE] cloneDataSource cross-tenant blocked: id={} caller={} orgId={}",
                        id, tenantId, orgId);
                return ResponseEntity.notFound().build();
            }
            DataSource cloned = dataSourceService.cloneDataSource(id, tenantId, orgRole);
            return ResponseEntity.ok(cloned);
        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            // PR-2.c: let the global advice in auth-client map this to 403.
            logger.warn("OrgAccess denied on cloneDataSource {}: {}", id, e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            logger.error("Error cloning data source {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error cloning data source {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a DataSource.
     * DELETE /api/data-sources/{id}
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDataSource(
            @PathVariable("id") Long id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        try {
            String tenantId = tenantIdResolver.resolveTenantId(request, tenantIdParam);
            if (isViewerRole(orgRole)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            Optional<DataSource> dataSourceOpt = dataSourceService.getDataSource(id);
            if (dataSourceOpt.isPresent() && ScopeGuard.isInStrictScope(tenantId, orgId, dataSourceOpt.get().tenantId(), dataSourceOpt.get().organizationId())) {
                // PR-2.b: pass gateway-validated org-role to the service. The deny-list
                // check now lives in DataSourceService.deleteDataSource(id, tenantId, orgRole)
                // so internal/tool callers also get the protection (no controller-only bypass).
                dataSourceService.deleteDataSource(id, tenantId, orgRole);
                return ResponseEntity.ok().build();
            }
            return ResponseEntity.notFound().build();
        } catch (com.apimarketplace.auth.client.access.OrgAccessDeniedException e) {
            // Let the global advice in auth-client map this to 403 - do NOT swallow
            // it into the catch-all below which returns 400.
            throw e;
        } catch (Exception e) {
            logger.error("Error deleting data source {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    private static boolean isViewerRole(String orgRole) {
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }
}
