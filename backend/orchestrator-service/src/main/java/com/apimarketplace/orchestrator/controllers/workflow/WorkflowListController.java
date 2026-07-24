package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import com.apimarketplace.orchestrator.services.WorkflowIconExtractor;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.trigger.client.TriggerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

import com.apimarketplace.orchestrator.domain.workflow.RunStatus;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Controller for listing and retrieving workflows.
 * Split from WorkflowQueryController for single responsibility.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowListController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowListController.class);

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final SignalWaitRepository signalWaitRepository;
    private final TriggerClient triggerClient;
    private final PublicationClient publicationClient;
    private final WorkflowManagementService workflowService;
    private final WorkflowBoardService boardService;
    private final OrgAccessGuard orgAccessService;

    public WorkflowListController(WorkflowRepository workflowRepository,
                                  WorkflowRunRepository workflowRunRepository,
                                  SignalWaitRepository signalWaitRepository,
                                  TriggerClient triggerClient,
                                  PublicationClient publicationClient,
                                  WorkflowManagementService workflowService,
                                  WorkflowBoardService boardService,
                                  OrgAccessGuard orgAccessService) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.signalWaitRepository = signalWaitRepository;
        this.triggerClient = triggerClient;
        this.publicationClient = publicationClient;
        this.workflowService = workflowService;
        this.boardService = boardService;
        this.orgAccessService = orgAccessService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listWorkflows(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "visibility", required = false) String visibility) {

        // Audit 2026-05-17 round-5 - Bug-#4 closed. Header is the only source
        // of truth; the `?tenantId=` query param is no longer consulted, so a
        // caller cannot impersonate another tenant by appending it.
        String tenantId = (userIdHeader != null && !userIdHeader.isBlank()) ? userIdHeader : null;
        if (tenantIdParam != null && !tenantIdParam.equals(tenantId)) {
            logger.warn("[SCOPE] Ignored client-supplied tenantId query param: header={} param={}",
                    tenantId, tenantIdParam);
        }

        // Decode tenantId manually in case Spring didn't decode it properly
        String decodedTenantId = decodeTenantId(tenantId);

        // `size` is the canonical name; `limit` kept for back-compat. Default 25, max 100.
        int requestedSize = size != null ? size : (limit != null ? limit : 25);
        int safeSize = Math.min(Math.max(requestedSize, 1), 100);
        int safePage = Math.max(page, 0);

        logger.info("GET /api/workflows - tenantId: '{}' (decoded: '{}'), page: {}, size: {}",
                tenantId, decodedTenantId, safePage, safeSize);

        List<WorkflowEntity> workflowEntities;

        if (decodedTenantId != null && !decodedTenantId.isBlank()) {
            logger.info("Searching workflows for tenantId: '{}'", decodedTenantId);

            workflowEntities = workflowService.listWorkflows(decodedTenantId, orgId, orgRole);
            logger.info("Found {} workflows for tenantId '{}'", workflowEntities.size(), decodedTenantId);

            // Debug logging if no results
            if (workflowEntities.isEmpty()) {
                logDebugInfo(decodedTenantId);
            }
        } else {
            logger.info("No tenantId provided, fetching all active workflows");
            workflowEntities = workflowRepository.findByIsActiveTrue().stream()
                .filter(w -> w.getWorkflowType() != WorkflowEntity.WorkflowType.APPLICATION)
                .collect(Collectors.toList());
        }

        // Apply server-side name/description search BEFORE pagination so the search hits
        // the full tenant-scoped dataset (not just the displayed page).
        if (q != null && !q.isBlank()) {
            String needle = q.trim().toLowerCase();
            workflowEntities = workflowEntities.stream()
                .filter(w -> {
                    String name = w.getName();
                    String desc = w.getDescription();
                    return (name != null && name.toLowerCase().contains(needle))
                        || (desc != null && desc.toLowerCase().contains(needle));
                })
                .collect(Collectors.toList());
        }

        // Visibility filter derives from publication status (owned by publication-service, a different
        // schema - no SQL join). When active, resolve the whole-set status ONCE before paginating and
        // reuse it for the page badges below, so the filter spans ALL workflows (not just one page)
        // while still costing a single status round-trip. Mirrors AgentService.listAgentsPaged.
        String visFilter = visibility == null ? "all" : visibility.trim().toLowerCase();
        boolean filterByVisibility = visFilter.equals("public") || visFilter.equals("private");
        Map<UUID, String> fullSetStatuses = Map.of();
        if (filterByVisibility && !workflowEntities.isEmpty()) {
            List<UUID> allIds = workflowEntities.stream().map(WorkflowEntity::getId).collect(Collectors.toList());
            fullSetStatuses = publicationClient.findPublicationStatusesByWorkflowIds(allIds, decodedTenantId);
            boolean wantPublic = visFilter.equals("public");
            final Map<UUID, String> refs = fullSetStatuses;
            workflowEntities = workflowEntities.stream()
                .filter(w -> "ACTIVE".equals(refs.get(w.getId())) == wantPublic)
                .collect(Collectors.toList());
        }

        // Order the full (filtered) set, then slice. name/lastModified/lastExecuted read entity
        // columns; runCount needs a batch run-count over the filtered set (one GROUP BY query, far
        // cheaper than the old per-row counts over EVERY workflow). Ordering matches the frontend
        // listSort.processList exactly: dates desc nulls-last, runCount desc (null->0), name A->Z.
        // Sort on a mutable copy so we never mutate the list the service handed back (which, for the
        // no-search/no-filter path, is its own return value and may be an immutable view).
        workflowEntities = new ArrayList<>(workflowEntities);
        String sortKey = sort == null ? "lastmodified" : sort.trim().toLowerCase();
        if (sortKey.equals("runcount")) {
            Map<UUID, Long> runCounts = batchRunCounts(workflowEntities);
            workflowEntities.sort(Comparator
                .comparingLong((WorkflowEntity w) -> runCounts.getOrDefault(w.getId(), 0L))
                .reversed());
        } else {
            sortWorkflows(workflowEntities, sortKey);
        }

        int totalCount = workflowEntities.size();
        int from = Math.min(safePage * safeSize, totalCount);
        int to = Math.min(from + safeSize, totalCount);
        List<WorkflowEntity> pageSlice = workflowEntities.subList(from, to);

        // Batch-query the publication moderation state per workflow. The status
        // map (ACTIVE / PENDING_REVIEW / REJECTED) supersedes the older
        // ACTIVE-only boolean: isPublished is derived from it (status==ACTIVE,
        // identical to the previous findPublishedWorkflowIds result) while
        // publicationStatus carries the full state so the list can show a
        // distinct "shared · in review" badge. Reuse the whole-set statuses already
        // fetched for the visibility filter; otherwise fetch just the page's ids.
        Map<UUID, String> publicationStatuses;
        if (filterByVisibility) {
            publicationStatuses = fullSetStatuses;
        } else if (!pageSlice.isEmpty()) {
            List<UUID> ids = pageSlice.stream().map(WorkflowEntity::getId).collect(Collectors.toList());
            publicationStatuses = publicationClient.findPublicationStatusesByWorkflowIds(ids, decodedTenantId);
        } else {
            publicationStatuses = Map.of();
        }

        // Batch-compute board columns for pinned workflows
        Map<UUID, String> boardColumns = batchComputeBoardColumns(pageSlice);

        Map<UUID, String> finalPublicationStatuses = publicationStatuses;
        List<WorkflowSummary> summaries = pageSlice.stream()
            .map(e -> {
                String status = finalPublicationStatuses.get(e.getId());
                boolean isPublished = "ACTIVE".equals(status);
                return mapWorkflow(e, isPublished, status, boardColumns.getOrDefault(e.getId(), "draft"));
            })
            .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("workflows", summaries);
        response.put("count", summaries.size());
        response.put("totalCount", totalCount);
        response.put("page", safePage);
        response.put("size", safeSize);

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<?> getWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        Optional<WorkflowEntity> entityOpt = workflowRepository.findById(workflowId);
        if (entityOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowEntity entity = entityOpt.get();
        // Strict-isolation scope (2026-05-18, ScopeGuard alignment). CRITICAL
        // history: this handler used to shadow WorkflowCrudController.getWorkflow
        // (same @GetMapping path) and previously had NO security check at all -
        // any caller knowing the workflow id could read it cross-tenant. Audit
        // 2026-05-16 closed that via the lax owner-or-org pattern; 2026-05-18
        // now closes the workspace-mismatch leak too.
        if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                entity.getTenantId(), entity.getOrganizationId())) {
            logger.warn("Workflow {} access denied for tenant {} (owner={} org-of-wf={} caller-org={})",
                workflowId, tenantId, entity.getTenantId(), entity.getOrganizationId(), orgId);
            return ResponseEntity.notFound().build();
        }
        String workflowOrgId = entity.getOrganizationId();
        if (workflowOrgId != null
                && !orgAccessService.canAccess(workflowOrgId, tenantId, "workflow", workflowId.toString(), orgRole)) {
            logger.warn("OrgAccess deny-list: user {} restricted from reading workflow {} in org {}",
                    tenantId, workflowId, workflowOrgId);
            throw new OrgAccessDeniedException("workflow", workflowId.toString());
        }
        String publicationStatus = resolvePublicationStatus(workflowId, tenantId);
        Map<UUID, String> boardColumns = batchComputeBoardColumns(List.of(entity));
        return ResponseEntity.ok(mapWorkflow(entity, "ACTIVE".equals(publicationStatus),
                publicationStatus, boardColumns.getOrDefault(entity.getId(), "draft")));
    }

    /**
     * Updates the metadata (name, description) of an existing workflow without
     * touching the plan. Distinct from {@code PUT /api/v2/workflows/dag/{id}/plan}
     * which is the full builder save path. This rename endpoint serves the
     * "rename workflow from board/list" UX action invoked by both the owner
     * and any org-teammate with non-restricted access.
     *
     * <p>Audit 2026-05-16 MF (prod incident): renaming a workflow from a
     * teammate's session failed with 500 because the route didn't exist -
     * the frontend was hitting this path and Spring fell through to a
     * generic error. Adding the endpoint closes the gap.
     *
     * <p>Security: owner-or-org scope on read, {@code canWrite} gate on write
     * (same contract as {@link WorkflowCrudController#updateWorkflowPlan}).
     */
    @PutMapping("/{workflowId}")
    public ResponseEntity<?> updateWorkflowMetadata(
            @PathVariable("workflowId") UUID workflowId,
            @RequestHeader(value = "X-User-ID", required = false) String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> request) {
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        Optional<WorkflowEntity> entityOpt = workflowRepository.findById(workflowId);
        if (entityOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        WorkflowEntity workflow = entityOpt.get();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                workflow.getTenantId(), workflow.getOrganizationId())) {
            // 404 not 403: don't leak existence to cross-scope callers.
            return ResponseEntity.notFound().build();
        }
        // canWrite gate fires on every org-scoped write, including owner (PR-2.f invariant).
        String workflowOrgId = workflow.getOrganizationId();
        if (workflowOrgId != null && isViewerRole(orgRole)) {
            logger.warn("OrgAccess denied: VIEWER user {} attempted to rename workflow {} in org {}",
                    tenantId, workflowId, workflowOrgId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "VIEWER role cannot modify workflows"));
        }
        if (workflowOrgId != null
                && !orgAccessService.canWrite(workflowOrgId, tenantId, "workflow", workflowId.toString(), orgRole)) {
            logger.warn("OrgAccess denied: user {} restricted from renaming workflow {} in org {}",
                    tenantId, workflowId, workflowOrgId);
            throw new OrgAccessDeniedException("workflow", workflowId.toString());
        }
        // Apply name/description (only if present and non-blank for name)
        Object name = request.get("name");
        if (name instanceof String n && !n.isBlank()) {
            workflow.setName(n);
        }
        if (request.containsKey("description")) {
            Object desc = request.get("description");
            workflow.setDescription(desc == null ? null : desc.toString());
        }
        // Cost budget (Advanced). Always in CREDITS on the wire (1 credit =
        // $0.001); the frontend converts the CE dollar input to credits before
        // sending. null / 0 / negative clears the budget (= unlimited).
        if (request.containsKey("budgetCredits")) {
            Object budgetVal = request.get("budgetCredits");
            if (budgetVal == null || (budgetVal instanceof String s && s.isBlank())) {
                workflow.setBudgetCredits(null);
            } else {
                try {
                    java.math.BigDecimal b = new java.math.BigDecimal(budgetVal.toString());
                    workflow.setBudgetCredits(b.signum() <= 0 ? null : b);
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "budget must be a number"));
                }
            }
        }
        workflow.setUpdatedAt(Instant.now());
        workflowRepository.save(workflow);

        Map<UUID, String> boardColumns = batchComputeBoardColumns(List.of(workflow));
        String publicationStatus = resolvePublicationStatus(workflowId, tenantId);
        return ResponseEntity.ok(mapWorkflow(workflow, "ACTIVE".equals(publicationStatus),
                publicationStatus, boardColumns.getOrDefault(workflow.getId(), "draft")));
    }

    private String decodeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return tenantId;
        }
        try {
            if (tenantId.contains("%")) {
                String decoded = URLDecoder.decode(tenantId, StandardCharsets.UTF_8);
                logger.debug("Decoded tenantId from '{}' to '{}'", tenantId, decoded);
                return decoded;
            }
        } catch (Exception e) {
            logger.warn("Failed to decode tenantId '{}': {}", tenantId, e.getMessage());
        }
        return tenantId;
    }

    private static boolean isViewerRole(String orgRole) {
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }

    /**
     * Server-side equivalent of the frontend {@code listSort.processList} order, in place, for the
     * entity-column sort keys: {@code name} (case-insensitive A->Z), {@code lastExecuted}
     * (lastExecutedAt desc, nulls last), or by default {@code lastModified} (updatedAt desc, nulls
     * last). {@code runCount} is handled by the caller (needs a batch run-count). Uses
     * {@code nullsLast(reverseOrder())} so absent dates sort LAST (descending), matching
     * {@code compareDateDesc} - not {@code reversed()}, which would float nulls to the top.
     */
    private static void sortWorkflows(List<WorkflowEntity> list, String sortKey) {
        switch (sortKey) {
            case "name" -> list.sort(Comparator.comparing(
                w -> w.getName() == null ? "" : w.getName(), String.CASE_INSENSITIVE_ORDER));
            case "lastexecuted" -> list.sort(Comparator.comparing(
                WorkflowEntity::getLastExecutedAt, Comparator.nullsLast(Comparator.reverseOrder())));
            default -> list.sort(Comparator.comparing(
                WorkflowEntity::getUpdatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        }
    }

    /**
     * Batch run-count (workflowId -> count) over the given workflows in ONE GROUP BY query, for the
     * {@code runCount} sort. Workflows with no runs are simply absent (caller defaults them to 0).
     */
    private Map<UUID, Long> batchRunCounts(List<WorkflowEntity> workflows) {
        if (workflows.isEmpty()) return Map.of();
        List<UUID> ids = workflows.stream().map(WorkflowEntity::getId).collect(Collectors.toList());
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : workflowRunRepository.countByWorkflowIds(ids)) {
            if (row.length >= 2 && row[0] instanceof UUID id && row[1] instanceof Number n) {
                counts.put(id, n.longValue());
            }
        }
        return counts;
    }

    private void logDebugInfo(String decodedTenantId) {
        logger.warn("No workflows found for tenantId '{}'. Checking all workflows...", decodedTenantId);
        long totalCount = workflowRepository.count();
        logger.warn("Total workflows in DB: {}", totalCount);

        try {
            List<String> distinctTenantIds = workflowRepository.findAllDistinctTenantIds();
            logger.warn("Distinct tenantIds in DB (total: {}):", distinctTenantIds.size());
            distinctTenantIds.stream()
                .limit(10)
                .forEach(tid -> logger.warn("  - '{}' (length: {})", tid, tid != null ? tid.length() : 0));

            if (decodedTenantId.contains("|")) {
                String pattern = decodedTenantId.split("\\|")[0];
                logger.warn("Trying partial match with pattern: '{}'", pattern);
                List<WorkflowEntity> partialMatches = workflowRepository.findByTenantIdContaining(pattern);
                logger.warn("Found {} workflows with tenantId containing '{}'", partialMatches.size(), pattern);
            }
        } catch (Exception e) {
            logger.error("Error during debug queries: {}", e.getMessage(), e);
        }
    }

    private static final Collection<RunStatus> ACTIVE_STATUSES = List.of(
        RunStatus.RUNNING, RunStatus.PAUSED, RunStatus.WAITING_TRIGGER
    );

    /**
     * Batch-compute board column for each workflow using the same classification
     * logic as WorkflowBoardService (needsReview > paused > production > draft).
     */
    private Map<UUID, String> batchComputeBoardColumns(List<WorkflowEntity> workflows) {
        Map<UUID, String> result = new HashMap<>();

        List<WorkflowEntity> pinned = new ArrayList<>();
        for (var w : workflows) {
            if (w.getPinnedVersion() == null) {
                result.put(w.getId(), "draft");
            } else {
                pinned.add(w);
            }
        }

        if (pinned.isEmpty()) return result;

        // Batch-fetch production runs
        List<UUID> pinnedIds = pinned.stream().map(WorkflowEntity::getId).toList();
        Map<UUID, WorkflowRunEntity> productionRuns = workflowRunRepository.findProductionRunsBatch(pinnedIds)
                .stream()
                .collect(Collectors.toMap(r -> r.getWorkflow().getId(), r -> r));

        // Batch-fetch pending approvals
        Set<String> approvalRunIds = Set.of();
        if (!productionRuns.isEmpty()) {
            List<String> runIdPublics = productionRuns.values().stream()
                    .map(WorkflowRunEntity::getRunIdPublic).toList();
            approvalRunIds = new HashSet<>(signalWaitRepository.findRunIdsWithPendingApprovals(runIdPublics));
        }

        for (var w : pinned) {
            WorkflowRunEntity run = productionRuns.get(w.getId());
            result.put(w.getId(), boardService.classifyPinnedWorkflow(run, approvalRunIds));
        }

        return result;
    }

    private WorkflowSummary mapWorkflow(WorkflowEntity entity, boolean isPublished, String boardColumn) {
        return mapWorkflow(entity, isPublished, null, boardColumn);
    }

    /**
     * Single-workflow publication status (ACTIVE / PENDING_REVIEW / REJECTED, or
     * {@code null} when not shared / INACTIVE). Uses the SAME status map as the
     * list path so {@code isPublished} (derived as {@code ACTIVE.equals(status)})
     * and {@code publicationStatus} mean exactly the same thing on every workflow
     * endpoint - closing the prior divergence where the single GET reported
     * {@code isPublished} from an any-status existence check.
     */
    private String resolvePublicationStatus(UUID workflowId, String tenantId) {
        Map<UUID, String> statuses =
                publicationClient.findPublicationStatusesByWorkflowIds(List.of(workflowId), tenantId);
        return statuses != null ? statuses.get(workflowId) : null;
    }

    private WorkflowSummary mapWorkflow(WorkflowEntity entity, boolean isPublished,
                                        String publicationStatus, String boardColumn) {
        long runCount = 0;
        boolean hasActiveRun = false;
        try {
            runCount = workflowRunRepository.countByWorkflowId(entity.getId());
            hasActiveRun = workflowRunRepository.existsByWorkflowIdAndStatusIn(entity.getId(), ACTIVE_STATUSES);
        } catch (Exception e) {
            logger.warn("Unable to count workflow runs for {}: {}", entity.getId(), e.getMessage());
        }

        Map<String, String> tokens = triggerClient.getTokensForWorkflow(entity.getId());

        // Lazy-compute nodeIcons for workflows saved before the feature was deployed
        var nodeIcons = entity.getNodeIcons();
        if (nodeIcons == null && entity.getPlan() != null) {
            nodeIcons = WorkflowIconExtractor.extractNodeIcons(entity.getPlan());
        }

        return new WorkflowSummary(
            entity.getId(),
            entity.getName(),
            entity.getDescription(),
            entity.getTenantId(),
            entity.getStatus(),
            entity.getCreatedAt(),
            entity.getUpdatedAt(),
            entity.getLastExecutedAt(),
            runCount,
            entity.getMetadata(),
            entity.getPlan(),
            entity.getSchedule(),
            tokens,
            nodeIcons,
            entity.getSourcePublicationId(),
            entity.getAcquiredAt(),
            isPublished,
            publicationStatus,
            entity.getProjectId(),
            entity.getWorkflowType(),
            entity.getPinnedVersion(),
            hasActiveRun,
            boardColumn,
            entity.getBudgetCredits()
        );
    }
}
