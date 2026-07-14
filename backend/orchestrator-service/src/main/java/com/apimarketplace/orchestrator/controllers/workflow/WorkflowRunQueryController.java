package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.controllers.dto.ApplicationRunVersionSummary;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowRunSummary;
import com.apimarketplace.orchestrator.services.ApplicationRunVersionBatchService;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowStepDataSummary;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunSummaryProjection;
import com.apimarketplace.orchestrator.services.WorkflowRunStatusService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.stepdata.StepStatusFilter;
import com.apimarketplace.orchestrator.trigger.ProductionRunResolver;
import com.apimarketplace.orchestrator.utils.EdgeRefParser;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.common.storage.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for querying workflow runs and their steps.
 * Split from WorkflowQueryController for single responsibility.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowRunQueryController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowRunQueryController.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowStepDataRepository workflowStepDataRepository;
    private final WorkflowRunStatusService workflowRunStatusService;
    private final WorkflowEpochService workflowEpochService;
    private final ProductionRunResolver productionRunResolver;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final com.apimarketplace.orchestrator.repository.WorkflowEpochRepository workflowEpochRepository;
    private final ApplicationRunVersionBatchService applicationRunVersionBatchService;

    public WorkflowRunQueryController(WorkflowRunRepository workflowRunRepository,
                                      WorkflowStepDataRepository workflowStepDataRepository,
                                      WorkflowRunStatusService workflowRunStatusService,
                                      WorkflowEpochService workflowEpochService,
                                      ProductionRunResolver productionRunResolver,
                                      StorageService storageService,
                                      ObjectMapper objectMapper,
                                      com.apimarketplace.orchestrator.repository.WorkflowEpochRepository workflowEpochRepository,
                                      ApplicationRunVersionBatchService applicationRunVersionBatchService) {
        this.workflowRunRepository = workflowRunRepository;
        this.workflowStepDataRepository = workflowStepDataRepository;
        this.workflowRunStatusService = workflowRunStatusService;
        this.workflowEpochService = workflowEpochService;
        this.productionRunResolver = productionRunResolver;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.workflowEpochRepository = workflowEpochRepository;
        this.applicationRunVersionBatchService = applicationRunVersionBatchService;
    }

    @GetMapping("/{workflowId}/runs")
    public ResponseEntity<List<WorkflowRunSummary>> listRuns(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(value = "limit", defaultValue = "15") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        int safeLimit = Math.min(Math.max(limit, 1), 200);
        int safeOffset = Math.max(offset, 0);
        int page = safeOffset / safeLimit;

        logger.debug("GET /workflows/{}/runs - limit={}, offset={}, page={}, orgId={}", workflowId, safeLimit, safeOffset, page, orgId);

        Pageable pageable = PageRequest.of(page, safeLimit, Sort.by(Sort.Direction.DESC, "startedAt"));
        // Cross-tenant guard: a missing X-User-ID at this public endpoint means
        // the gateway didn't authenticate the caller - return 401 rather than
        // bypass the tenant filter.
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        // Route through owner-or-org scope when an active org is present (PR15/V209 contract).
        // Without orgId the InScope finder degenerates to the tenant-strict path.
        Page<WorkflowRunSummaryProjection> pageResult = workflowRunRepository.findRunSummariesByWorkflowIdInScope(
                workflowId, tenantId, orgId, pageable);
        var projections = pageResult.getContent();

        logger.debug("Found {} runs (total: {}, page: {}/{})",
            projections.size(),
            pageResult.getTotalElements(),
            pageResult.getNumber(),
            pageResult.getTotalPages()
        );

        // Batch-fetch max epoch + latest epoch start time per run (two single SQL queries)
        List<String> runIds = projections.stream()
            .map(WorkflowRunSummaryProjection::getRunIdPublic)
            .collect(Collectors.toList());
        Map<String, Integer> epochMap = workflowEpochRepository.getMaxEpochByRunIds(runIds);
        Map<String, Instant> lastFireMap =
            workflowEpochRepository.getLatestEpochStartedAtByRunIds(runIds);

        List<WorkflowRunSummary> response = projections.stream()
            .map(p -> mapRunFromProjection(p,
                    epochMap.get(p.getRunIdPublic()),
                    lastFireMap.get(p.getRunIdPublic())))
            .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{workflowId}/runs/latest")
    public ResponseEntity<?> getLatestRun(@PathVariable("workflowId") UUID workflowId,
                                          @RequestHeader(value = "X-User-ID", required = false) String tenantId,
                                          @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        Pageable pageable = PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "startedAt"));
        var projections = workflowRunRepository.findRunSummariesByWorkflowIdInScope(
                workflowId, tenantId, orgId, pageable).getContent();

        if (projections.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var p = projections.get(0);
        List<String> singleRun = List.of(p.getRunIdPublic());
        Map<String, Integer> epochMap = workflowEpochRepository.getMaxEpochByRunIds(singleRun);
        Map<String, Instant> lastFireMap =
            workflowEpochRepository.getLatestEpochStartedAtByRunIds(singleRun);
        WorkflowRunSummary latestRun = mapRunFromProjection(p,
                epochMap.get(p.getRunIdPublic()),
                lastFireMap.get(p.getRunIdPublic()));
        return ResponseEntity.ok(latestRun);
    }

    /**
     * Get the pinned (production) run for a workflow.
     * Delegates to ProductionRunResolver - same logic used by schedule, webhook,
     * workflow trigger, and all other production dispatch services.
     * Returns 404 if the workflow has no pinned version or no run at that version.
     */
    @GetMapping("/{workflowId}/runs/pinned")
    public ResponseEntity<?> getPinnedRun(
            @PathVariable("workflowId") UUID workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        // Cross-tenant guard: this endpoint resolved the pinned run purely by workflowId +
        // version + status with NO tenant filter, so any caller who knew a workflowId read
        // another tenant's pinned run (plan/metadata/triggerPayload) - an IDOR. Bind it to
        // the caller's scope below (isRunInScope), mirroring the sibling run reads.
        ProductionRunResolver.Resolution resolution = productionRunResolver.resolve(workflowId, com.apimarketplace.orchestrator.trigger.ProductionRunResolver.RunSelectionPolicy.LATEST_TRUSTED);
        if (!resolution.isFound()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowRunEntity entity = resolution.run().get();
        // isRunInScope also enforces the share binding (shareContextPermitsRun). The pinned run
        // is resolved by version/status, not by publicationId, so for an APPLICATION share visitor
        // it 404s unless that run happens to be publication-tagged; the shared-app viewer tolerates
        // this (getPinnedWorkflowRun swallows the error and the run it renders comes from
        // /runs/application). A same-tenant owner/team caller (no share context) is unaffected.
        if (!WorkflowControllerHelper.isRunInScope(entity, tenantId, orgId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(mapRunWithEpochLookup(entity));
    }

    /**
     * Find an application-dedicated run for a workflow + publication pair.
     * Returns the most recent run tagged with source="application" for the given publicationId.
     */
    @GetMapping("/{workflowId}/runs/application")
    public ResponseEntity<?> getApplicationRun(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam("publicationId") String publicationId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        // Cross-tenant guard: the publicationId filter is a functional selector, not a security
        // boundary (it is the marketplace/share resource token, not a secret), so without a tenant
        // check any caller who knew a workflowId + publicationId read that run - an IDOR. Bind it
        // to the caller's scope below (isRunInScope). The APPLICATION share viewer is unaffected: the
        // returned run always carries publicationId == the queried token == X-Share-Resource-Token,
        // so shareContextPermitsRun passes.
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository
            .findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                workflowId, "application", publicationId);

        if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(mapRunWithEpochLookup(runOpt.get()));
    }

    /**
     * Batch sibling of {@link #getApplicationRun}: resolves, per workflowId, the application run id +
     * last-executed timestamp + pinned version in a fixed handful of batched queries, so the
     * Applications page renders ~200 cards without firing two HTTP calls ({@code /runs/application} +
     * {@code /versions}) EACH. Body: {@code {workflowIds: [...]}}. Returns
     * {@code Map<workflowId, {applicationRunId, lastExecutedAt, pinnedVersion}>}; a workflowId with no
     * run/pinned row is simply absent (the card then hides the badge).
     *
     * <p>Scope mirrors the two endpoints it replaces, NOT a blanket open read: the run id +
     * last-executed fields match the unscoped {@link #getApplicationRun} (a caller could already fetch
     * those one by one), while {@code pinnedVersion} is strict-workspace scoped (the per-card
     * {@code /versions} endpoint was owner-gated) via {@code ScopeGuard.isInStrictScope}'s two branches:
     * an org caller ({@code X-Organization-ID} set) sees org-matching rows; a personal caller (org
     * header suppressed by the gateway) sees only their own org-less rows ({@code X-User-ID} =
     * tenantId). A workflow outside the active workspace reports no pinned version.
     */
    @PostMapping("/applications/run-version-batch")
    public ResponseEntity<Map<String, ApplicationRunVersionSummary>> getApplicationRunVersionBatch(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        Object raw = body.get("workflowIds");
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (Object o : rawList) {
            if (o == null) continue;
            try {
                ids.add(UUID.fromString(o.toString()));
            } catch (IllegalArgumentException ignored) { /* skip malformed id */ }
        }
        if (ids.isEmpty()) {
            return ResponseEntity.ok(Map.of());
        }
        Map<UUID, ApplicationRunVersionSummary> resolved = applicationRunVersionBatchService.resolve(ids, orgId, userId);
        Map<String, ApplicationRunVersionSummary> response = new HashMap<>();
        resolved.forEach((wfId, summary) -> response.put(wfId.toString(), summary));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/runs/{runIdPublic}")
    public ResponseEntity<?> getRunByPublicId(@PathVariable("runIdPublic") String runIdPublic,
                                              @RequestHeader(value = "X-User-ID", required = false) String tenantId,
                                              @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runIdPublic);
        if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(mapRunWithEpochLookup(runOpt.get()));
    }

    /**
     * Hard cap on unpaged {@code /steps} responses. Lightweight projection scalar columns
     * are ~1-2 KB each; with Xmx=1536m the default G1 region size is 1 MB and humongous
     * threshold 512 KB. A 1000-row response (~2 MB scalar payload) materialised into a
     * JSON byte[] crosses two regions but stays under the humongous threshold; the prior
     * 5000-row cap (~10 MB) crossed it. The frontend's {@code useInfiniteQuery} on
     * {@code /steps/paged} (500 cap) is the supported path; this unpaged listing remains
     * for legacy callers and is now defense-bounded.
     */
    static final int LIST_STEPS_HARD_CAP = 1000;

    @GetMapping("/runs/{runId}/steps")
    public ResponseEntity<List<WorkflowStepDataSummary>> listSteps(@PathVariable("runId") UUID runId,
                                                                    @RequestHeader(value = "X-User-ID", required = false) String tenantId,
                                                                    @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        // Resolve the run by primary key (UUID) and verify owner-or-org scope before
        // returning step data - same defense-in-depth pattern as the runIdPublic endpoints.
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findById(runId);
        if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
            return ResponseEntity.notFound().build();
        }
        // Lightweight projection: heavy JSONB (input_data, metadata, merge_received_branches,
        // merge_skipped_branches) returns as null. Frontend that needs the full payload of a
        // specific step must request it via the dedicated payload endpoint. Without this
        // projection, hitting this list under frontend polling on a 17 000-row run retained
        // 207 741 ManagedEntityImpl + ~800 MB byte[] in heap on prod OOM 2026-05-07 12:40 UTC.
        //
        // Post-2026-05-22 hardening: even with the projection, rowcount remained unbounded.
        // We now load N+1 rows; if more than LIST_STEPS_HARD_CAP exist we truncate and stamp
        // an X-Truncated header so callers can migrate to /steps/paged.
        List<WorkflowStepDataEntity> steps = workflowStepDataRepository.findByWorkflowRunIdLightweightAll(runId);
        boolean truncated = steps.size() > LIST_STEPS_HARD_CAP;
        if (truncated) {
            steps = steps.subList(0, LIST_STEPS_HARD_CAP);
        }
        List<WorkflowStepDataSummary> response = steps.stream()
            .map(this::mapStep)
            .collect(Collectors.toList());
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok();
        if (truncated) {
            builder.header("X-Truncated", "true");
            builder.header("X-Truncated-Cap", String.valueOf(LIST_STEPS_HARD_CAP));
        }
        return builder.body(response);
    }

    @GetMapping("/runs/{runId}/steps/paged")
    public ResponseEntity<?> listStepsPaged(
            @PathVariable("runId") UUID runId,
            @RequestParam("stepAlias") String stepAlias,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "1") int size,
            @RequestParam(value = "epoch", required = false) Integer epoch,
            @RequestParam(value = "status", required = false) String status,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {

        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findById(runId);
        if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
            return ResponseEntity.notFound().build();
        }

        int safePage = Math.max(page, 0);
        // Per-request page-size cap. Bumped 100 → 500 on 2026-05-13: the InspectorPanel
        // item navigator (input/output/params columns) and the logs StepDataTable need
        // to surface all items across all epochs on workflows like Daily Email Digest
        // which routinely hit 3000+ rows per stepAlias. The previous cap of 100 silently
        // truncated the view. The frontend now uses useInfiniteQuery / progressive fetch
        // so a single oversized request is never built - the cap bounds a single page,
        // not the total. 500 keeps each response well under the OOM 2026-05-07 threshold
        // (17k rows × heavy JSONB) and is what the audit-fixes deploy promises to honor.
        int safeSize = Math.min(Math.max(size, 1), 500);

        // Use LabelNormalizer (same as frontend normalizeLabel) for consistent matching
        String normalizedAlias = LabelNormalizer.normalizeLabel(stepAlias);

        // Push alias + epoch + canonical-status filtering into the JPQL query and let the DB
        // paginate. Replaces the previous "load all rows of the run + filter+paginate in
        // memory" pattern, which materialised the entire workflow_step_data set (~17 000 rows
        // on prod OOM 2026-05-07 12:40 UTC) just to keep one alias subset.
        // Two repository methods (one with status IN, one without) keep the JPQL simple -
        // an empty IN list is invalid JPQL and using a sentinel value would leak a dummy
        // parameter to the database planner.
        List<String> rawStatuses = StepStatusFilter.expandToRawList(status);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<WorkflowStepDataEntity> stepPage = rawStatuses.isEmpty()
            ? workflowStepDataRepository.findByWorkflowRunIdAndStepAliasPagedFiltered(
                runId, normalizedAlias, epoch, pageable)
            : workflowStepDataRepository.findByWorkflowRunIdAndStepAliasAndStatusInPaged(
                runId, normalizedAlias, epoch, rawStatuses, pageable);

        long totalElements = stepPage.getTotalElements();

        if (totalElements == 0) {
            logger.info("[StepsPaged] 0 matches for stepAlias='{}' (normalized='{}'), runId={}, epoch={}, status={}",
                stepAlias, normalizedAlias, runId, epoch, status);
        }

        List<WorkflowStepDataSummary> content = stepPage.getContent().stream()
                .map(this::mapStep)
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("content", content);
        response.put("totalElements", totalElements);
        response.put("totalPages", stepPage.getTotalPages());
        response.put("page", safePage);
        response.put("size", safeSize);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/runs/{runId}/status")
    public ResponseEntity<?> getRunStatus(@PathVariable("runId") UUID runId,
                                          @RequestHeader(value = "X-User-ID", required = false) String tenantId,
                                          @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        var statusOpt = workflowRunStatusService.findByRunId(runId);
        // Run-status reuses tenant column; cross-check the parent run for org-share visibility.
        if (statusOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        // Strict-isolation scope (2026-05-18, ScopeGuard alignment). The run
        // status table reuses the tenant column only; org tag lives on the
        // parent run, so we look it up once when orgId is set.
        String parentOrgId = null;
        if (orgId != null && !orgId.isBlank()) {
            parentOrgId = workflowRunRepository.findById(runId)
                    .map(WorkflowRunEntity::getOrganizationId)
                    .orElse(null);
        }
        if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                statusOpt.get().getTenantId(), parentOrgId)) {
            return ResponseEntity.notFound().build();
        }
        var entity = statusOpt.get();
        return ResponseEntity.ok(Map.of(
                "runId", entity.getRunId(),
                "workflowId", entity.getWorkflow().getId(),
                "tenantId", entity.getTenantId(),
                "status", entity.getStatus(),
                "payload", entity.getPayload(),
                "createdAt", entity.getCreatedAt(),
                "updatedAt", entity.getUpdatedAt()
        ));
    }

    /**
     * Optimized endpoint for polling status counts.
     * Returns aggregated node/edge status counts without loading full step data.
     */
    @GetMapping("/runs/{runIdPublic}/status-counts")
    public ResponseEntity<?> getStatusCounts(@PathVariable("runIdPublic") String runIdPublic,
                                             @RequestHeader(value = "X-User-ID", required = false) String tenantId,
                                             @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        logger.info("GET /runs/{}/status-counts - optimized polling", runIdPublic);

        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runIdPublic);
        if (runOpt.isEmpty() || !WorkflowControllerHelper.isRunInScope(runOpt.get(), tenantId, orgId)) {
            return ResponseEntity.notFound().build();
        }
        WorkflowRunEntity runEntity = runOpt.get();

        // Extract epoch metadata
        int currentEpoch = 0;
        Map<String, Object> metadata = runEntity.getMetadata();
        Map<String, Object> dagEpochs = null;
        if (metadata != null) {
            if (metadata.get("currentEpoch") instanceof Number) {
                currentEpoch = ((Number) metadata.get("currentEpoch")).intValue();
            }
            Object dagEpochsRaw = metadata.get("dagEpochs");
            if (dagEpochsRaw instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) dagEpochsRaw;
                dagEpochs = typed;
            }
        }

        // Always use accumulation: counts across ALL epochs for ALL node types.
        // Single-DAG and multi-DAG both accumulate (webhook fired 3 times = count 3).
        // Single repository call powers BOTH node and edge counts - the underlying
        // accumulated-counts SQL is heavy, so we share the result rather than running it twice.
        WorkflowEpochService.AccumulatedCounts accumulated =
                workflowEpochService.getAccumulatedCounts(runIdPublic);
        Map<String, Map<String, Long>> nodeCounts = accumulated.nodes();

        Map<String, Map<String, Long>> edgeCounts = deriveEdgeCounts(runEntity, accumulated.edges());

        logger.info("GET /runs/{}/status-counts - epoch={}, nodes={}, edges={}",
            runIdPublic, currentEpoch, nodeCounts, edgeCounts);

        Map<String, Object> response = new HashMap<>();
        response.put("runId", runIdPublic);
        response.put("status", runEntity.getStatus().name());
        response.put("epoch", currentEpoch);
        response.put("nodes", nodeCounts);
        response.put("edges", edgeCounts);
        response.put("updatedAt", runEntity.getUpdatedAt());
        if (dagEpochs != null) {
            response.put("dagEpochs", dagEpochs);
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/storage/{storageId}")
    public ResponseEntity<?> getStorage(@PathVariable("storageId") UUID storageId,
                                        @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
                                        @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
                                        @RequestParam(value = "tenantId", required = false) String tenantIdParam) {
        // Audit 2026-05-17 round-6 - Bug-#4 closed: header is the only source of truth.
        String tenantId = (userIdHeader != null && !userIdHeader.isBlank()) ? userIdHeader : null;
        if (tenantIdParam != null && !tenantIdParam.equals(tenantId)) {
            logger.warn("[SCOPE] Ignored client-supplied tenantId on getStorage: header={} param={}", tenantId, tenantIdParam);
        }
        if (tenantId == null) return ResponseEntity.status(401).build();
        return (organizationId != null && !organizationId.isBlank()
                ? storageService.getEntityByIdForScope(storageId, tenantId, organizationId)
                : storageService.getEntityById(storageId, tenantId))
            .<ResponseEntity<?>>map(entity -> {
                Map<String, Object> response = new HashMap<>();
                response.put("data", parseJson(entity.getData()));
                response.put("data_mapped", parseJson(entity.getDataMapped()));
                return ResponseEntity.ok(response);
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/storage/{storageId}")
    public ResponseEntity<?> updateStorage(@PathVariable("storageId") UUID storageId,
                                           @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
                                           @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
                                           @RequestParam(value = "tenantId", required = false) String tenantIdParam,
                                           @RequestBody Map<String, Object> body) {
        // Audit 2026-05-17 round-6 - CRITICAL Bug-#4 closed on WRITE path. Prior:
        // `?tenantId=victim` let any caller flip storage row content cross-tenant.
        String tenantId = (userIdHeader != null && !userIdHeader.isBlank()) ? userIdHeader : null;
        if (tenantIdParam != null && !tenantIdParam.equals(tenantId)) {
            logger.warn("[SCOPE] Ignored client-supplied tenantId on updateStorage: header={} param={}", tenantId, tenantIdParam);
        }
        if (tenantId == null) return ResponseEntity.status(401).build();
        Object data = body.get("data");
        Object dataMapped = body.get("data_mapped");
        return (organizationId != null && !organizationId.isBlank()
                ? storageService.updateJsonForScope(storageId, tenantId, organizationId, data, dataMapped)
                : storageService.updateJson(storageId, tenantId, data, dataMapped))
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Derives per-edge counts from {@code epoch_counts} (single source of truth, written at the
     * source side by {@code EdgeStatusService}). Each edge's count reflects the items that
     * actually traversed that specific edge.
     *
     * <p>Critically, an edge's count is NEVER inferred from its target node's accumulated counts:
     * for a multi-predecessor target (e.g. a shared merge with two trigger fan-ins), copying the
     * target's count onto every incoming edge attributes one trigger's executions to a sibling
     * trigger that never fired.
     *
     * <p>Iterates plan edges only; edges declared in the plan but never traversed are omitted
     * from the response (the frontend treats missing keys as zero). Runtime-only edges absent
     * from the plan (e.g. loop back-edges recorded by {@code BackEdgeHandler}) are intentionally
     * not surfaced here - they belong to the rebuild path, not the polling response.
     *
     * <p>Both the read key (built from plan-edge {@code from}/{@code to}) and the aggregated
     * key (built from {@code epoch_counts.entry_key}) go through the same port-stripping AND
     * label-normalization pipeline as {@code EdgeStatusService.normalizePreservingPort}. This
     * is critical: plan edges may carry raw, non-normalized labels (e.g. {@code trigger:My Webhook}),
     * while {@code epoch_counts} is keyed with normalized labels. Without symmetric
     * normalization on the read side, the lookup silently misses and edges appear at zero.
     *
     * <p>Plan edges may carry port suffixes (e.g. {@code core:option:choice_0}); the response
     * key strips the port to keep the edge id stable across branches that share a target node.
     * Multiple ported variants of the same plan edge are summed under that stripped key.
     */
    private Map<String, Map<String, Long>> deriveEdgeCounts(
            WorkflowRunEntity runEntity,
            Map<String, Map<String, Long>> rawEdgeCounts) {
        Map<String, Map<String, Long>> edgeCounts = new HashMap<>();
        Map<String, Object> planMap = runEntity.getPlan();
        if (planMap == null) {
            return edgeCounts;
        }

        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> edges = (List<Map<String, Object>>) planMap.get("edges");
            if (edges == null || edges.isEmpty()) {
                return edgeCounts;
            }

            Map<String, Map<String, Long>> aggregated = aggregateByNormalizedKey(rawEdgeCounts);

            for (Map<String, Object> edge : edges) {
                String from = (String) edge.get("from");
                String to = (String) edge.get("to");
                if (from == null || to == null) continue;

                String fromKey = normalizedNodeKey(from);
                String toKey = normalizedNodeKey(to);
                if (fromKey == null || toKey == null) continue;

                String edgeId = fromKey + "->" + toKey;
                Map<String, Long> counts = aggregated.get(edgeId);
                if (counts != null && !counts.isEmpty()) {
                    edgeCounts.put(edgeId, new HashMap<>(counts));
                }
            }
        } catch (Exception e) {
            logger.warn("Error deriving edge counts: {}", e.getMessage(), e);
        }

        return edgeCounts;
    }

    /**
     * Aggregates raw edge counts under normalized stripped-port edge ids. Both ports and
     * label casing/spacing are folded so the read key matches what the write side actually
     * stored - see {@code EdgeStatusService.normalizePreservingPort}.
     *
     * <p>For a classify node fanning to the same target via multiple ports, per-port counts
     * collapse into a single entry under the unported normalized key.
     */
    private Map<String, Map<String, Long>> aggregateByNormalizedKey(Map<String, Map<String, Long>> rawEdgeCounts) {
        Map<String, Map<String, Long>> aggregated = new HashMap<>();
        for (Map.Entry<String, Map<String, Long>> entry : rawEdgeCounts.entrySet()) {
            String rawKey = entry.getKey();
            int sep = rawKey.indexOf("->");
            if (sep <= 0) continue;

            String fromKey = normalizedNodeKey(rawKey.substring(0, sep));
            String toKey = normalizedNodeKey(rawKey.substring(sep + 2));
            if (fromKey == null || toKey == null) continue;

            String strippedId = fromKey + "->" + toKey;
            Map<String, Long> bucket = aggregated.computeIfAbsent(strippedId, k -> new HashMap<>());
            for (Map.Entry<String, Long> statusEntry : entry.getValue().entrySet()) {
                bucket.merge(statusEntry.getKey(), statusEntry.getValue(), Long::sum);
            }
        }
        return aggregated;
    }

    /**
     * Strips port and applies label normalization to a raw edge ref ({@code "trigger:My Webhook"}
     * → {@code "trigger:my_webhook"}). Returns null only when the ref cannot be parsed and has
     * no usable fallback.
     */
    private static String normalizedNodeKey(String ref) {
        if (ref == null) return null;
        EdgeRefParser.EdgeRef parsed = EdgeRefParser.parse(ref);
        if (parsed != null) {
            String normalizedLabel = LabelNormalizer.normalizeLabel(parsed.nodeLabel());
            if (normalizedLabel != null) {
                return parsed.nodeType() + ":" + normalizedLabel;
            }
        }
        return ref.isBlank() ? null : ref;
    }

    private WorkflowRunSummary mapRun(WorkflowRunEntity entity, Integer currentEpoch, Instant lastFireAt) {
        return new WorkflowRunSummary(
            entity.getId(),
            entity.getRunIdPublic(),
            entity.getTenantId(),
            entity.getStatus(),
            entity.getExecutionMode() != null ? entity.getExecutionMode().getValue() : "automatic",
            entity.getStartedAt(),
            entity.getEndedAt(),
            entity.getDurationMs(),
            entity.getTotalNodes(),
            entity.getTriggerPayload(),
            entity.getMetadata(),
            entity.getPlan(),
            entity.getPlanVersion(),
            currentEpoch,
            lastFireAt
        );
    }

    private WorkflowRunSummary mapRunWithEpochLookup(WorkflowRunEntity entity) {
        List<String> singleRun = List.of(entity.getRunIdPublic());
        Map<String, Integer> epochMap = workflowEpochRepository.getMaxEpochByRunIds(singleRun);
        Map<String, Instant> lastFireMap =
            workflowEpochRepository.getLatestEpochStartedAtByRunIds(singleRun);
        return mapRun(entity,
                epochMap.get(entity.getRunIdPublic()),
                lastFireMap.get(entity.getRunIdPublic()));
    }

    private WorkflowRunSummary mapRunFromProjection(WorkflowRunSummaryProjection projection,
                                                    Integer currentEpoch,
                                                    Instant lastFireAt) {
        return new WorkflowRunSummary(
            projection.getId(),
            projection.getRunIdPublic(),
            projection.getTenantId(),
            projection.getStatus(),
            projection.getExecutionMode() != null ? projection.getExecutionMode().getValue() : "automatic",
            projection.getStartedAt(),
            projection.getEndedAt(),
            projection.getDurationMs(),
            projection.getTotalNodes(),
            null,
            projection.getMetadata(),
            null,
            projection.getPlanVersion(),
            currentEpoch,
            lastFireAt
        );
    }

    private WorkflowStepDataSummary mapStep(WorkflowStepDataEntity entity) {
        return new WorkflowStepDataSummary(
            entity.getId(),
            entity.getWorkflowRunId(),
            entity.getRunId(),
            entity.getStepAlias(),
            entity.getToolId(),
            entity.getStatus(),
            entity.getStartTime(),
            entity.getEndTime(),
            entity.getHttpStatus(),
            entity.getOutputStorageId(),
            entity.getIteration(),
            entity.getItemIndex(),
            entity.getEpoch(),
            entity.getSpawn(),
            entity.getErrorMessage(),
            entity.getInputData(),
            entity.getMetadata(),
            entity.getTenantId(),
            // Node type identification
            entity.getNodeType() != null ? entity.getNodeType().name() : null,
            entity.getNormalizedKey(),
            // Decision node fields
            entity.getConditionExpression(),
            entity.getConditionResult(),
            entity.getSelectedBranch(),
            // Loop node fields
            entity.getLoopId(),
            entity.getLoopIteration(),
            entity.getLoopExitReason(),
            // Merge node fields
            entity.getMergeStrategy(),
            entity.getMergeReceivedBranches(),
            entity.getMergeSkippedBranches(),
            // Skip tracking fields
            entity.getSkipReason(),
            entity.getSkipSourceNode(),
            // Item tracking fields
            entity.getTriggerId(),
            entity.getItemId(),
            entity.getItemNumber()
        );
    }

    private Object parseJson(String json) {
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            logger.warn("Failed to parse JSON: {}", e.getMessage());
            return null;
        }
    }
}
