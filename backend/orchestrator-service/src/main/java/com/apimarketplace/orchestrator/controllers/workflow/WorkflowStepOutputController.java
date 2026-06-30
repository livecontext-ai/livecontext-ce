package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.StorageNestedModels.*;
import com.apimarketplace.orchestrator.services.StorageNestedService;
import com.apimarketplace.orchestrator.services.StorageSkeletonService;
import com.apimarketplace.orchestrator.services.StepOutputService;
import com.apimarketplace.orchestrator.services.WorkflowStepService;
import com.apimarketplace.orchestrator.stepdata.DetailedStepDataResponse;
import com.apimarketplace.orchestrator.stepdata.DetailedStepDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for workflow step output navigation.
 * Provides endpoints to navigate nested JSON data stored in step output storage.
 *
 * Business logic is delegated to {@link StepOutputService}.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowStepOutputController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowStepOutputController.class);

    private final WorkflowStepService workflowStepService;
    private final StorageNestedService storageNestedService;
    private final StorageSkeletonService storageSkeletonService;
    private final StepOutputService stepOutputService;
    private final DetailedStepDataService detailedStepDataService;
    private final com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository;

    public WorkflowStepOutputController(
            WorkflowStepService workflowStepService,
            StorageNestedService storageNestedService,
            StorageSkeletonService storageSkeletonService,
            StepOutputService stepOutputService,
            DetailedStepDataService detailedStepDataService,
            com.apimarketplace.orchestrator.repository.WorkflowRunRepository runRepository) {
        this.workflowStepService = workflowStepService;
        this.storageNestedService = storageNestedService;
        this.storageSkeletonService = storageSkeletonService;
        this.stepOutputService = stepOutputService;
        this.detailedStepDataService = detailedStepDataService;
        this.runRepository = runRepository;
    }

    /**
     * Get nested data from step output at a specific JSON path with pagination.
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/items/nested
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/items/nested")
    public ResponseEntity<NestedPaginationResponse<StorageNestedRow>> getStepOutputNestedData(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "path", defaultValue = "") String jsonPath,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "sortOrder", required = false) String sortOrder) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();
            limit = clampLimit(limit, 500, 20);
            page = Math.max(page, 1);

            Optional<UUID> outputStorageIdOpt = workflowStepService.getOutputStorageId(stepId, runId, tenantId);

            if (outputStorageIdOpt.isEmpty()) {
                logger.warn("Step output not found: stepId={}, runId={}, tenantId={}", stepId, runId, tenantId);
                return ResponseEntity.notFound().build();
            }

            NestedDataRequest request = new NestedDataRequest(jsonPath, page, limit, sortBy, sortOrder, null);
            NestedPaginationResponse<StorageNestedRow> response = storageNestedService.getNestedData(
                outputStorageIdOpt.get(), tenantId, jsonPath, request
            );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting step output nested data: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all items from step output (root level, no nested path).
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/items
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/items")
    public ResponseEntity<NestedPaginationResponse<StorageNestedRow>> getStepOutputItems(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", defaultValue = "1000") Integer limit,
            @RequestParam(value = "page", defaultValue = "1") Integer page) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();
            limit = clampLimit(limit, 10000, 1000);
            page = Math.max(page, 1);

            Optional<UUID> outputStorageIdOpt = workflowStepService.getOutputStorageId(stepId, runId, tenantId);

            if (outputStorageIdOpt.isEmpty()) {
                logger.warn("Step output not found: stepId={}, runId={}, tenantId={}", stepId, runId, tenantId);
                return ResponseEntity.notFound().build();
            }

            UUID outputStorageId = outputStorageIdOpt.get();
            NestedDataRequest request = new NestedDataRequest("", page, limit, null, null, null);
            NestedPaginationResponse<StorageNestedRow> response = storageNestedService.getNestedData(
                outputStorageId, tenantId, "", request
            );

            // Auto-detect arrays in nested structure
            if (response.rowData().size() == 1) {
                StorageNestedRow firstRow = response.rowData().get(0);
                Map<String, Object> data = firstRow.data();

                String arrayPath = stepOutputService.findArrayPath(data, "");
                if (arrayPath != null && !arrayPath.isEmpty()) {
                    try {
                        NestedPaginationResponse<StorageNestedRow> arrayResponse = storageNestedService.getNestedData(
                            outputStorageId, tenantId, arrayPath, request
                        );
                        if (!arrayResponse.rowData().isEmpty()) {
                            logger.info("Found array at path: {}, returning {} items", arrayPath, arrayResponse.rowData().size());
                            return ResponseEntity.ok(arrayResponse);
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to extract array from path {}: {}", arrayPath, e.getMessage());
                    }
                }

                // Try to extract useful data from nested structure
                Map<String, Object> extractedData = stepOutputService.extractUsefulData(data);
                if (extractedData != null && !extractedData.isEmpty()) {
                    StorageNestedRow extractedRow = new StorageNestedRow(
                        firstRow.id(),
                        firstRow.storageId(),
                        firstRow.tenantId(),
                        firstRow.jsonPath(),
                        firstRow.parentPath(),
                        extractedData,
                        firstRow.updatedAt()
                    );
                    return ResponseEntity.ok(new NestedPaginationResponse<>(
                        List.of(extractedRow), 1, null, false, 1, "", null, List.of()
                    ));
                }
            }

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting step output items: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all merged outputs for all steps with a given alias.
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/alias/{stepAlias}/output/items/merged
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/alias/{stepAlias}/output/items/merged")
    public ResponseEntity<NestedPaginationResponse<StorageNestedRow>> getMergedStepOutputItems(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepAlias") String stepAlias,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", defaultValue = "1000") Integer limit,
            @RequestParam(value = "page", defaultValue = "1") Integer page) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();
            // BATCH-B (2026-05-20) - also resolve the run's orgId so step rows
            // are scoped strictly to the run's workspace.
            String orgId = resolveOrgIdInRunScope(httpRequest, runId);
            limit = clampLimit(limit, 10000, 1000);
            page = Math.max(page, 1);

            List<StorageNestedRow> allRows = stepOutputService.getMergedOutputsForAlias(runId, stepAlias, tenantId, orgId);

            if (allRows.isEmpty()) {
                return ResponseEntity.ok(new NestedPaginationResponse<>(
                    List.of(), 0, null, false, 0, "", null, List.of()
                ));
            }

            return ResponseEntity.ok(stepOutputService.paginateRows(allRows, page, limit));

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting merged step output items: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get detailed step data with node-specific columns.
     * The columns are ordered and typed by the backend based on nodeType.
     * This is the source of truth for frontend column display.
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/alias/{stepAlias}/output/detailed
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/alias/{stepAlias}/output/detailed")
    public ResponseEntity<DetailedStepDataResponse> getDetailedStepData(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepAlias") String stepAlias,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "epoch", required = false) Integer epoch) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();
            // BATCH-B (2026-05-20) - also resolve run's orgId for strict-org row scoping.
            String orgId = resolveOrgIdInRunScope(httpRequest, runId);
            limit = clampLimit(limit, 500, 20);
            page = Math.max(page, 1);

            DetailedStepDataResponse response = detailedStepDataService.getDetailedStepData(
                    runId, stepAlias, tenantId, orgId, page, limit, status, epoch
            );

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting detailed step data: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get detailed step data for a specific step ID.
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/detailed
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/detailed")
    public ResponseEntity<DetailedStepDataResponse> getDetailedStepDataById(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();

            DetailedStepDataResponse response = detailedStepDataService.getDetailedStepDataById(
                    stepId, runId, tenantId
            );

            if (response == null) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting detailed step data by ID: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get column definitions for step output nested data at a specific JSON path.
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/columns/nested
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/columns/nested")
    public ResponseEntity<List<StorageColumnDefinition>> getStepOutputColumnDefinitions(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "path", defaultValue = "") String jsonPath) {

        try {
            String tenantId = resolveTenantId(request, tenantIdParam);

            Optional<UUID> outputStorageIdOpt = workflowStepService.getOutputStorageId(stepId, runId, tenantId);

            if (outputStorageIdOpt.isEmpty()) {
                logger.warn("Step output not found: stepId={}, runId={}, tenantId={}", stepId, runId, tenantId);
                return ResponseEntity.notFound().build();
            }

            List<StorageColumnDefinition> columns = storageSkeletonService.getColumnDefinitions(
                outputStorageIdOpt.get(), tenantId, jsonPath
            );

            return ResponseEntity.ok(columns);

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid request: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            logger.error("Error getting step output column definitions: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ========================================================================
    // SKELETON & LAZY LOADING ENDPOINTS (paginated data preview)
    // ========================================================================

    /**
     * Get only the structure skeleton of a step output (no data payload).
     * Optimized for frontend tree view - returns ~200 bytes instead of full payload.
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/skeleton
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/skeleton")
    public ResponseEntity<JsonNode> getStepOutputSkeleton(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();

            Optional<UUID> outputStorageIdOpt = workflowStepService.getOutputStorageId(stepId, runId, tenantId);
            if (outputStorageIdOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Optional<JsonNode> skeleton = storageSkeletonService.getSkeletonOnly(outputStorageIdOpt.get(), tenantId);
            return skeleton.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.noContent().build());

        } catch (Exception e) {
            logger.error("Error getting step output skeleton: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get a value at a specific JSON path from step output (lazy loading).
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/value?path=output.users.0.name
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/value")
    public ResponseEntity<String> getStepOutputValueAtPath(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam("path") String path) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();

            Optional<UUID> outputStorageIdOpt = workflowStepService.getOutputStorageId(stepId, runId, tenantId);
            if (outputStorageIdOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Optional<String> value = storageSkeletonService.getValueAtPath(outputStorageIdOpt.get(), tenantId, path);
            return value.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.noContent().build());

        } catch (Exception e) {
            logger.error("Error getting value at path '{}': {}", path, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get a JSON object at a specific path from step output (lazy loading sub-objects).
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/object?path=output.users.0
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/object")
    public ResponseEntity<JsonNode> getStepOutputObjectAtPath(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam("path") String path) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();

            Optional<UUID> outputStorageIdOpt = workflowStepService.getOutputStorageId(stepId, runId, tenantId);
            if (outputStorageIdOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Optional<JsonNode> obj = storageSkeletonService.getObjectAtPath(outputStorageIdOpt.get(), tenantId, path);
            return obj.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.noContent().build());

        } catch (Exception e) {
            logger.error("Error getting object at path '{}': {}", path, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get a specific array item by index (for item navigation).
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/array-item?path=output.users&index=0
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/array-item")
    public ResponseEntity<JsonNode> getStepOutputArrayItem(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "path", defaultValue = "") String arrayPath,
            @RequestParam("index") int index) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();

            if (index < 0) {
                return ResponseEntity.badRequest().build();
            }

            Optional<UUID> outputStorageIdOpt = workflowStepService.getOutputStorageId(stepId, runId, tenantId);
            if (outputStorageIdOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Optional<JsonNode> item = storageSkeletonService.getArrayItemAtIndex(
                    outputStorageIdOpt.get(), tenantId, arrayPath, index);
            return item.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.noContent().build());

        } catch (Exception e) {
            logger.error("Error getting array item at index {}: {}", index, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get metadata about an array (length, for pagination).
     * GET /api/workflows/{workflowId}/runs/{runId}/steps/{stepId}/output/array-info?path=output.users
     */
    @GetMapping("/{workflowId}/runs/{runId}/steps/{stepId}/output/array-info")
    public ResponseEntity<Map<String, Object>> getStepOutputArrayInfo(
            @PathVariable("workflowId") String workflowId,
            @PathVariable("runId") String runId,
            @PathVariable("stepId") Long stepId,
            HttpServletRequest httpRequest,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "path", defaultValue = "") String arrayPath) {

        try {
            // Audit 2026-05-17 round-4 - owner-or-org scope: org-mate sees the run.
            String tenantId = resolveTenantIdInRunScope(httpRequest, runId);
            if (tenantId == null) return ResponseEntity.notFound().build();

            Optional<UUID> outputStorageIdOpt = workflowStepService.getOutputStorageId(stepId, runId, tenantId);
            if (outputStorageIdOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            int length = storageSkeletonService.getArrayLength(outputStorageIdOpt.get(), tenantId, arrayPath);

            return ResponseEntity.ok(Map.of(
                    "path", arrayPath,
                    "length", length,
                    "hasItems", length > 0
            ));

        } catch (Exception e) {
            logger.error("Error getting array info at path '{}': {}", arrayPath, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ========================================================================
    // HELPER METHODS
    // ========================================================================

    /**
     * Resolve tenantId from the gateway-injected X-User-ID header ONLY.
     * Audit 2026-05-17 round-3 - the prior implementation fell back to a
     * client-supplied {@code tenantId} query param when the header was
     * missing, which let any caller impersonate any tenant by appending
     * {@code ?tenantId=victim}. The query param is now ignored entirely;
     * if {@code X-User-ID} is absent, scope resolution returns null and
     * the caller fails closed (404 on the storage lookup).
     * <p>
     * The {@code tenantIdParam} arg is retained for binary back-compat
     * with the existing method signature but is no longer consulted.
     * <p>
     * Audit 2026-05-17 round-4 - for owner-or-org scope expansion, the
     * caller-provided runId is now resolved via the run's tenantId/orgId
     * after a `WorkflowRunRepository.findByRunIdPublic` lookup; if the
     * caller is in scope (owner OR org-mate), we substitute the run's
     * tenantId so the legacy tenant-strict service path still works for
     * org-mates. {@link #resolveTenantIdInRunScope} is the new entry point.
     */
    @SuppressWarnings("unused")
    private String resolveTenantId(HttpServletRequest request, String tenantIdParam) {
        String userIdHeader = request.getHeader("X-User-ID");
        if (userIdHeader != null && !userIdHeader.isBlank()) {
            return userIdHeader;
        }
        return null;
    }

    /**
     * Audit 2026-05-17 round-4 - owner-or-org scope resolution for runId-
     * pathed step-output endpoints. Returns the run's tenantId when the
     * caller is in scope (owner OR org-mate). Returns null when out of
     * scope so the caller can fail closed with 404.
     */
    private String resolveTenantIdInRunScope(HttpServletRequest request, String runId) {
        String callerUserId = request.getHeader("X-User-ID");
        if (callerUserId == null || callerUserId.isBlank()) return null;
        String callerOrgId = request.getHeader("X-Organization-ID");
        return runRepository.findByRunIdPublic(runId)
                .filter(run -> com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper
                        .isRunInScope(run, callerUserId, callerOrgId))
                .map(com.apimarketplace.orchestrator.domain.WorkflowRunEntity::getTenantId)
                .orElse(null);
    }

    /**
     * BATCH-B (2026-05-20) - owner-or-org scope resolution that returns the
     * run's {@code organizationId} (instead of tenantId). Used to route
     * step-output queries through the strict-org repository overloads so
     * an org-mate query stays scoped to the run's workspace rather than
     * everything the underlying userId owns across orgs.
     * <p>Returns null when the caller is out of scope (404 fail-closed).
     */
    private String resolveOrgIdInRunScope(HttpServletRequest request, String runId) {
        String callerUserId = request.getHeader("X-User-ID");
        if (callerUserId == null || callerUserId.isBlank()) return null;
        String callerOrgId = request.getHeader("X-Organization-ID");
        return runRepository.findByRunIdPublic(runId)
                .filter(run -> com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper
                        .isRunInScope(run, callerUserId, callerOrgId))
                .map(com.apimarketplace.orchestrator.domain.WorkflowRunEntity::getOrganizationId)
                .orElse(null);
    }

    /**
     * Clamp limit to a valid range.
     */
    private int clampLimit(int limit, int max, int defaultValue) {
        if (limit > max) return max;
        if (limit < 1) return defaultValue;
        return limit;
    }
}
