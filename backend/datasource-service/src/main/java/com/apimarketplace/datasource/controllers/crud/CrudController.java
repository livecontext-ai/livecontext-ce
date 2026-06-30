package com.apimarketplace.datasource.controllers.crud;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.datasource.crud.domain.CrudOperation;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.dto.*;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.services.DataSourceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

/**
 * REST controller for CRUD operations on datasources.
 *
 * <p>Uses centralized infrastructure:
 * <ul>
 *   <li>{@link TenantResolver} for X-User-ID header extraction</li>
 *   <li>GlobalExceptionHandler for error responses</li>
 * </ul>
 *
 * <p><b>Org per-resource write gate.</b> This is the user-facing CRUD entry
 * point. Before any WRITE operation (create/update/delete row, create column)
 * runs, a per-resource org restriction check ({@link OrgAccessGuard#canWrite})
 * is applied on the target datasource so an org member restricted to READ (or
 * DENY) on that table cannot mutate it. OWNER/ADMIN bypass via the guard
 * contract; read operations are never gated. The inter-service
 * workflow-execution path ({@code /api/internal/datasource/crud/execute}) is a
 * SEPARATE controller and intentionally stays ungated - it runs as the
 * workflow owner with no member context.
 */
@RestController
@RequestMapping("/api/crud")
public class CrudController {

    private static final Logger log = LoggerFactory.getLogger(CrudController.class);

    private static final String RESOURCE_TYPE = "datasource";

    private final CrudExecutorService crudExecutorService;
    private final TenantResolver tenantResolver;
    private final DataSourceService dataSourceService;
    private final OrgAccessGuard orgAccessGuard;

    public CrudController(CrudExecutorService crudExecutorService,
                          TenantResolver tenantResolver,
                          DataSourceService dataSourceService,
                          OrgAccessGuard orgAccessGuard) {
        this.crudExecutorService = crudExecutorService;
        this.tenantResolver = tenantResolver;
        this.dataSourceService = dataSourceService;
        this.orgAccessGuard = orgAccessGuard;
    }

    /**
     * Execute a CRUD operation (generic endpoint).
     */
    @PostMapping("/execute")
    public ResponseEntity<CrudResponse> execute(
            @Valid @RequestBody CrudRequest request,
            HttpServletRequest httpRequest) {

        return executeOperation(request, httpRequest);
    }

    /**
     * Create rows in a datasource.
     */
    @PostMapping("/create-row")
    public ResponseEntity<CrudResponse> createRow(
            @Valid @RequestBody CreateRowRequest request,
            HttpServletRequest httpRequest) {

        return executeOperation(request, httpRequest);
    }

    /**
     * Create columns in a datasource.
     */
    @PostMapping("/create-column")
    public ResponseEntity<CrudResponse> createColumn(
            @Valid @RequestBody CreateColumnRequest request,
            HttpServletRequest httpRequest) {

        return executeOperation(request, httpRequest);
    }

    /**
     * Read rows from a datasource.
     */
    @PostMapping("/read-row")
    public ResponseEntity<CrudResponse> readRow(
            @Valid @RequestBody ReadRowRequest request,
            HttpServletRequest httpRequest) {

        return executeOperation(request, httpRequest);
    }

    /**
     * Update rows in a datasource.
     */
    @PostMapping("/update-row")
    public ResponseEntity<CrudResponse> updateRow(
            @Valid @RequestBody UpdateRowRequest request,
            HttpServletRequest httpRequest) {

        return executeOperation(request, httpRequest);
    }

    /**
     * Delete rows from a datasource.
     */
    @PostMapping("/delete-row")
    public ResponseEntity<CrudResponse> deleteRow(
            @Valid @RequestBody DeleteRowRequest request,
            HttpServletRequest httpRequest) {

        return executeOperation(request, httpRequest);
    }

    /**
     * Common method to execute any CRUD operation.
     */
    private ResponseEntity<CrudResponse> executeOperation(CrudRequest request, HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);

        log.info("Executing CRUD operation: {} for tenant: {} on datasource: {}",
            request.getOperation(), tenantId, request.getDataSourceId());

        enforceWriteAccess(request, tenantId, httpRequest);
        enforceReadAccess(request, tenantId, httpRequest);

        CrudResult result = crudExecutorService.execute(request, tenantId);
        CrudResponse response = CrudResponse.fromDomain(result);

        if (result.success()) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Per-resource org write gate for the user-facing CRUD entry point.
     *
     * <p>Only WRITE operations ({@link CrudOperation#isWrite()}) are checked;
     * read operations pass through untouched. The datasource's own
     * {@code organizationId} is the scope key - a member with a READ-only or
     * DENY restriction on that table is refused with
     * {@link OrgAccessDeniedException} (HTTP 403). OWNER/ADMIN bypass via the
     * guard. A datasource with no org context (legacy / personal-only row)
     * skips the gate; the downstream {@code verifyDataSourceAccess} scope check
     * still enforces tenant ownership.
     */
    private void enforceWriteAccess(CrudRequest request, String tenantId, HttpServletRequest httpRequest) {
        if (!request.getOperation().isWrite()) {
            return;
        }
        Long dataSourceId = request.getDataSourceId();
        if (dataSourceId == null) {
            return; // bean validation / service layer reports the missing id
        }
        Optional<DataSource> dataSourceOpt = dataSourceService.getDataSource(dataSourceId);
        if (dataSourceOpt.isEmpty()) {
            return; // not found - service layer surfaces the error consistently
        }
        String entityOrgId = dataSourceOpt.get().organizationId();
        if (entityOrgId == null || entityOrgId.isBlank()) {
            return; // no org scope to enforce against
        }
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);
        if (!orgAccessGuard.canWrite(entityOrgId, tenantId, RESOURCE_TYPE, dataSourceId.toString(), orgRole)) {
            log.warn("[ORG-ACCESS] CRUD write blocked: op={} ds={} tenant={} org={} role={}",
                request.getOperation(), dataSourceId, tenantId, entityOrgId, orgRole);
            throw new OrgAccessDeniedException(RESOURCE_TYPE, dataSourceId.toString());
        }
    }

    /**
     * Per-resource org READ gate for the user-facing CRUD entry point.
     *
     * <p>Only READ operations are checked (writes go through {@link #enforceWriteAccess}). Uses
     * {@link OrgAccessGuard#canAccess}, which is false ONLY for a full DENY restriction - a
     * READ-only-restricted member still reads, an unrestricted member and OWNER/ADMIN pass. This
     * closes the leak where datasource METADATA (list/get) was deny-filtered but the row DATA
     * (read_row / query_rows) was not: a DENY-restricted member could still read the rows of a
     * table they were blocked from. No org context ⇒ skip (tenant ownership still enforced by the
     * downstream scope check).
     */
    private void enforceReadAccess(CrudRequest request, String tenantId, HttpServletRequest httpRequest) {
        if (request.getOperation().isWrite()) {
            return; // writes handled by enforceWriteAccess
        }
        Long dataSourceId = request.getDataSourceId();
        if (dataSourceId == null) {
            return;
        }
        Optional<DataSource> dataSourceOpt = dataSourceService.getDataSource(dataSourceId);
        if (dataSourceOpt.isEmpty()) {
            return;
        }
        String entityOrgId = dataSourceOpt.get().organizationId();
        if (entityOrgId == null || entityOrgId.isBlank()) {
            return;
        }
        String orgRole = tenantResolver.resolveOrgRole(httpRequest);
        if (!orgAccessGuard.canAccess(entityOrgId, tenantId, RESOURCE_TYPE, dataSourceId.toString(), orgRole)) {
            log.warn("[ORG-ACCESS] CRUD read blocked: op={} ds={} tenant={} org={} role={}",
                request.getOperation(), dataSourceId, tenantId, entityOrgId, orgRole);
            throw new OrgAccessDeniedException(RESOURCE_TYPE, dataSourceId.toString());
        }
    }
}
