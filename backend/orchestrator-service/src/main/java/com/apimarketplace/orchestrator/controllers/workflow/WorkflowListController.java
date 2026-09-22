package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.folder.FolderScope;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowSummary;
import com.apimarketplace.common.folder.AbstractResourceFolderController;
import com.apimarketplace.orchestrator.services.folder.WorkflowFolderService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import com.apimarketplace.orchestrator.services.NodeTypeFilters;
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
    private final WorkflowFolderService folderService;

    public WorkflowListController(WorkflowRepository workflowRepository,
                                  WorkflowRunRepository workflowRunRepository,
                                  SignalWaitRepository signalWaitRepository,
                                  TriggerClient triggerClient,
                                  PublicationClient publicationClient,
                                  WorkflowManagementService workflowService,
                                  WorkflowBoardService boardService,
                                  OrgAccessGuard orgAccessService,
                                  WorkflowFolderService folderService) {
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.signalWaitRepository = signalWaitRepository;
        this.triggerClient = triggerClient;
        this.publicationClient = publicationClient;
        this.workflowService = workflowService;
        this.boardService = boardService;
        this.orgAccessService = orgAccessService;
        this.folderService = folderService;
    }

    /**
     * The folder to narrow to, or {@code null} for the top level / no filter at all. An
     * unparseable id is treated as the top level rather than a 400: the filter is a view
     * preference, and a bad one must never take the list down.
     */
    private static UUID parseFolderId(String folderId) {
        if (folderId == null || folderId.isBlank() || "root".equalsIgnoreCase(folderId.trim())) return null;
        try {
            return UUID.fromString(folderId.trim());
        } catch (IllegalArgumentException e) {
            logger.warn("Ignoring unparseable folderId '{}' on GET /api/workflows", folderId);
            return null;
        }
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
            @RequestParam(value = "visibility", required = false) String visibility,
            @RequestParam(value = "nodeTypes", required = false) String nodeTypes,
            @RequestParam(value = "includeNodeTypeFacets", required = false, defaultValue = "false")
            boolean includeNodeTypeFacets,
            @RequestParam(value = "folderId", required = false) String folderId,
            @RequestParam(value = "includeFolders", required = false, defaultValue = "false")
            boolean includeFolders) {

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

        // NODE TYPES (V483). Applied here, next to the other whole-set filters and
        // before the folder logic, so the folder tiles below count what the filter
        // actually leaves - pick "Gmail" and the tiles tell you which folders hold a
        // Gmail workflow.
        //
        // The facets that populate the picker are counted after the folder narrowing
        // below - see the comment there.
        Set<String> requestedNodeTypes = NodeTypeFilters.parse(nodeTypes);
        // Kept for the facets, which are counted further down: they must describe the
        // set MINUS this filter (so a ticked option keeps its count) but PLUS the
        // folder narrowing that has not happened yet (so an option can never promise
        // rows that live in another folder).
        List<WorkflowEntity> beforeNodeTypeFilter = workflowEntities;
        if (!requestedNodeTypes.isEmpty()) {
            workflowEntities = workflowEntities.stream()
                .filter(w -> NodeTypeFilters.matches(w.getNodeTypes(), requestedNodeTypes))
                .collect(Collectors.toList());
        }

        // FOLDERS (V448). Two rules decide what the page shows:
        //   * A SEARCH looks everywhere. Someone typing a name wants the workflow, not a
        //     lesson about where they filed it - so an active `q` ignores the folder filter
        //     and the folder tiles step aside (they would advertise content the results
        //     already list).
        //   * Otherwise `folderId` narrows to one level: `root` = the workflows filed
        //     nowhere, an id = that folder's own workflows (its subfolders show as tiles).
        // The tiles are built from the set as it stands HERE - after search, visibility
        // and node types, before the folder narrowing - so a tile counts exactly what the
        // caller may see, and counts it over the folder's whole subtree.
        boolean searching = q != null && !q.isBlank();
        UUID folderFilter = parseFolderId(folderId);
        // Asked for a level but not for a folder id ("root", blank, or something
        // unparseable) = the top level. Only an ABSENT parameter means "no folder filter",
        // which is what every other caller of this endpoint (the board, the pickers) gets.
        boolean rootOnly = folderId != null && folderFilter == null;
        boolean folderMissing = false;
        FolderScope folderScope = new FolderScope(decodedTenantId, orgId);
        if (folderFilter != null && !folderService.existsInScope(folderFilter, folderScope)) {
            // The folder was deleted (or belongs to another workspace): show the top level
            // rather than an eternally empty page, and tell the caller to drop its filter.
            folderFilter = null;
            rootOnly = true;
            folderMissing = true;
        }
        List<WorkflowEntity> folderAggregateSource = workflowEntities;
        boolean narrowingToFolder = !searching && (rootOnly || folderFilter != null);
        final UUID wantedFolder = folderFilter;
        if (narrowingToFolder) {
            workflowEntities = workflowEntities.stream()
                .filter(w -> java.util.Objects.equals(w.getFolderId(), wantedFolder))
                .collect(Collectors.toList());
        }

        // The picker's options, counted HERE: after the folder narrowing, before the
        // node-type filter. Counting before the narrowing was wrong in a way that
        // never errors - inside a folder, an option whose workflows all live
        // elsewhere would advertise a count and then produce an empty grid, which is
        // exactly the promise the picker makes ("no choice can lead to an empty
        // page"). Asked for rather than always computed: this endpoint also serves
        // the node pickers and other callers with no filter UI, and counting walks
        // every workflow's plan.
        List<Map<String, Object>> nodeTypeFacets = null;
        if (includeNodeTypeFacets) {
            List<WorkflowEntity> facetSource = narrowingToFolder
                    ? beforeNodeTypeFilter.stream()
                        .filter(w -> java.util.Objects.equals(w.getFolderId(), wantedFolder))
                        .toList()
                    : beforeNodeTypeFilter;
            nodeTypeFacets = NodeTypeFilters.facets(
                    facetSource.stream().map(WorkflowEntity::getNodeTypes).toList());
        }

        // Order the full (filtered) set, then slice. name/lastModified/lastExecuted read entity
        // columns; runCount needs a batch run-count over the filtered set (one GROUP BY query, far
        // cheaper than the old per-row counts over EVERY workflow). Ordering matches the frontend
        // listSort.processList exactly: dates desc nulls-last, runCount desc (null->0), name A->Z.
        // Sort on a mutable copy so we never mutate the list the service handed back (which, for the
        // no-search/no-filter path, is its own return value and may be an immutable view).
        workflowEntities = new ArrayList<>(workflowEntities);
        String sortKey = sort == null ? "lastmodified" : sort.trim().toLowerCase();
        // Counted over the pre-folder set so the folder tiles and the rows are ordered by
        // the same numbers - one GROUP BY for both, and only when this sort needs it.
        Map<UUID, Long> runCounts = sortKey.equals("runcount") ? batchRunCounts(folderAggregateSource) : null;
        if (runCounts != null) {
            final Map<UUID, Long> counts = runCounts;
            workflowEntities.sort(Comparator
                .comparingLong((WorkflowEntity w) -> counts.getOrDefault(w.getId(), 0L))
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
        // The picker's options travel with the list they filter: one request, and the
        // counts can never describe a different set than the rows shown.
        if (nodeTypeFacets != null) {
            response.put("nodeTypeFacets", nodeTypeFacets);
        }
        if (includeFolders) {
            // Tiles for THIS level, each ordered by the same key as the rows below them, plus
            // the trail so the page can render the path it navigated into.
            response.put("folders", searching
                    ? List.of()
                    : folderService.listFolderSummaries(
                            folderScope, folderFilter, folderAggregateSource, sortKey, runCounts));
            response.put("folderTrail", folderFilter == null
                    ? List.of()
                    : folderService.breadcrumb(folderService.listAll(folderScope), folderFilter).stream()
                            .map(AbstractResourceFolderController::toBareMap)
                            .toList());
            if (folderMissing) {
                response.put("folderMissing", true);
            }
        }

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
        //
        // VALIDATED BEFORE ANYTHING IS MUTATED. Applying the cap first and
        // validating the cadence afterwards left a rejected request holding a
        // half-applied entity. Nothing was flushed (this method is not
        // transactional and open-in-view is off, so the entity is detached),
        // which is why it was not a live bug - but the 400 path and the success
        // path then disagree about what the object holds, and only ordering
        // makes that impossible rather than merely unlikely.
        java.math.BigDecimal requestedCap = null;
        boolean capRequested = request.containsKey("budgetCredits");
        if (capRequested) {
            Object budgetVal = request.get("budgetCredits");
            if (budgetVal != null && !(budgetVal instanceof String s2 && s2.isBlank())) {
                try {
                    java.math.BigDecimal b = new java.math.BigDecimal(budgetVal.toString());
                    requestedCap = b.signum() <= 0 ? null : b;
                } catch (NumberFormatException e) {
                    return ResponseEntity.badRequest().body(Map.of("error", "budget must be a number"));
                }
            }
        }
        // Reset cadence of the cap. Unknown values are rejected rather than
        // silently normalised: a typo that fell back to "never reset" would turn
        // the cap into a lifetime cap and stop the workflow for good.
        String requestedMode = null;
        boolean modeRequested = request.containsKey("budgetPeriodMode");
        if (modeRequested) {
            Object modeVal = request.get("budgetPeriodMode");
            String mode = modeVal == null ? null : modeVal.toString().trim().toLowerCase();
            if (mode == null || mode.isBlank()) {
                requestedMode = com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod.MODE_MONTHLY;
            } else if (List.of(
                    com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod.MODE_MONTHLY,
                    com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod.MODE_WEEKLY,
                    com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod.MODE_CUMULATIVE)
                    .contains(mode)) {
                requestedMode = mode;
            } else {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "budgetPeriodMode must be monthly, weekly or cumulative"));
            }
        }

        // Changing the TERMS of the cap restarts its period: judging a brand-new
        // cap against spend from before it existed, or re-filing a stored figure
        // under a period it does not belong to, both turn a setting into a trap.
        boolean hadCapBefore = workflow.getBudgetCredits() != null
                && workflow.getBudgetCredits().signum() > 0;
        String modeBefore = com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                .normaliseMode(workflow.getBudgetPeriodMode());
        boolean hasCapNow = capRequested
                ? requestedCap != null
                : hadCapBefore;
        String modeNow = modeRequested
                ? com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod.normaliseMode(requestedMode)
                : modeBefore;
        boolean periodWasReset = hasCapNow && (!hadCapBefore || !modeNow.equals(modeBefore));

        // The reset runs BEFORE the save, and that order is the recovery story.
        // It cannot join the save's transaction (the period columns are
        // insertable/updatable=false, so they are only ever written by explicit
        // SQL), so the two are separate statements either way. Resetting second
        // meant a failure between them persisted the cap with the pre-cap spend
        // still counting, and the retry could not repair it: the cap now exists,
        // so "the cap just appeared" is false and the reset never runs again.
        // The user is then blocked by spend from before their own cap, with no
        // way out. Resetting FIRST fails the request before the cap is stored,
        // so the retry sees the same starting state and does the right thing.
        if (periodWasReset) {
            Instant freshPeriodStart = com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                    .periodStart(modeNow, Instant.now());
            if (freshPeriodStart == null) {
                workflowRepository.resetBudgetPeriodCumulative(workflow.getId());
            } else {
                workflowRepository.resetBudgetPeriodAt(workflow.getId(), freshPeriodStart);
            }
        }

        if (capRequested) {
            workflow.setBudgetCredits(requestedCap);
        }
        if (modeRequested) {
            workflow.setBudgetPeriodMode(requestedMode);
        }
        workflow.setUpdatedAt(Instant.now());
        // Safe next to the period columns: they are insertable/updatable=false,
        // so this save cannot clobber the live spend with a stale in-memory copy.
        workflowRepository.save(workflow);

        Map<UUID, String> boardColumns = batchComputeBoardColumns(List.of(workflow));
        String publicationStatus = resolvePublicationStatus(workflowId, tenantId);
        return ResponseEntity.ok(mapWorkflow(workflow, "ACTIVE".equals(publicationStatus),
                publicationStatus, boardColumns.getOrDefault(workflow.getId(), "draft"),
                periodWasReset ? java.math.BigDecimal.ZERO : null));
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
        return mapWorkflow(entity, isPublished, publicationStatus, boardColumn, null);
    }

    /**
     * @param periodSpentOverride the period spend to report instead of the one
     *        derived from the entity, or {@code null} to derive it. Needed by
     *        the metadata update, which restarts the cap's period with explicit
     *        SQL: the period columns are {@code updatable=false}, so the managed
     *        entity still holds the PRE-reset figure and a response built from
     *        it tells the user they are over a cap they just created.
     */
    private WorkflowSummary mapWorkflow(WorkflowEntity entity, boolean isPublished,
                                        String publicationStatus, String boardColumn,
                                        java.math.BigDecimal periodSpentOverride) {
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
            entity.getBudgetCredits(),
            com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                    .normaliseMode(entity.getBudgetPeriodMode()),
            // Rolled over here rather than in the client: the stored figure may
            // belong to a period that has expired (the reset is lazy, it happens
            // on the next production cost), and a card must never show last
            // period's spend against this period's cap.
            periodSpentOverride != null
                    ? periodSpentOverride
                    : com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod.effectiveSpent(
                            entity.getBudgetPeriodMode(),
                            entity.getBudgetPeriodStartedAt(),
                            entity.getBudgetPeriodSpent(),
                            Instant.now()),
            com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                    .nextPeriodStart(entity.getBudgetPeriodMode(), Instant.now()),
            entity.getFolderId()
        );
    }
}
