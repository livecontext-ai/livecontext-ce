package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.controllers.dto.WorkflowBoardCard;
import com.apimarketplace.orchestrator.controllers.dto.WorkflowBoardResponse;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.client.PublicationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service that builds the workflow board by classifying workflows into 4 columns:
 * draft, production, needsReview, paused.
 *
 * Only production workflows (pinnedVersion != null) appear in the 3 non-draft columns.
 * Each card represents the production run (most recent run at the pinned version).
 */
@Service
public class WorkflowBoardService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowBoardService.class);

    private final WorkflowManagementService workflowService;
    private final WorkflowRunRepository runRepository;
    private final SignalWaitRepository signalWaitRepository;
    private final WorkflowEpochRepository epochRepository;
    private final PublicationClient publicationClient;
    private final OrgAccessGuard orgAccessGuard;

    public WorkflowBoardService(WorkflowManagementService workflowService,
                                WorkflowRunRepository runRepository,
                                SignalWaitRepository signalWaitRepository,
                                WorkflowEpochRepository epochRepository,
                                PublicationClient publicationClient,
                                OrgAccessGuard orgAccessGuard) {
        this.workflowService = workflowService;
        this.runRepository = runRepository;
        this.signalWaitRepository = signalWaitRepository;
        this.epochRepository = epochRepository;
        this.publicationClient = publicationClient;
        this.orgAccessGuard = orgAccessGuard;
    }

    /** Back-compat overload - no pagination (loads everything). Used by tests. */
    public WorkflowBoardResponse buildBoard(String tenantId, String orgId, String orgRole) {
        return buildBoard(tenantId, orgId, orgRole, 0, Integer.MAX_VALUE);
    }

    /** Back-compat overload - regular workflows (excludes APPLICATION-type). */
    public WorkflowBoardResponse buildBoard(String tenantId, String orgId, String orgRole, int page, int size) {
        return buildBoard(tenantId, orgId, orgRole, page, size, false);
    }

    /**
     * Build the board. The same classification (draft/production/needsReview/paused) and pagination
     * apply to either source: regular workflows (default) or APPLICATION-type workflows
     * ({@code applicationsOnly=true}) - the only difference is which list we start from.
     */
    public WorkflowBoardResponse buildBoard(String tenantId, String orgId, String orgRole,
                                            int page, int size, boolean applicationsOnly) {
        // 1. Load the source (regular workflows, or applications = acquired + own published apps),
        //    sort by updatedAt desc (most recent first).
        BoardSource source = loadSource(tenantId, orgId, orgRole, applicationsOnly);
        List<WorkflowEntity> all = new ArrayList<>(source.entities());
        all.sort(Comparator
                .comparing(WorkflowEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed());

        int totalCount = all.size();
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, totalCount);
        int to = Math.min(from + safeSize, totalCount);
        List<WorkflowEntity> workflows = all.subList(from, to);

        // 2. Partition into draft vs pinned (page only)
        List<WorkflowEntity> drafts = new ArrayList<>();
        List<WorkflowEntity> pinned = new ArrayList<>();
        for (var w : workflows) {
            if (w.getPinnedVersion() == null) {
                drafts.add(w);
            } else {
                pinned.add(w);
            }
        }

        // 3. Batch-fetch all workflow IDs for run counts (single GROUP BY query)
        List<UUID> allIds = workflows.stream().map(WorkflowEntity::getId).toList();
        Map<UUID, Long> runCounts = batchCountRuns(allIds);

        // 4. Batch-fetch production runs (single DISTINCT ON query)
        Map<UUID, WorkflowRunEntity> productionRuns = Map.of();
        if (!pinned.isEmpty()) {
            List<UUID> pinnedIds = pinned.stream().map(WorkflowEntity::getId).toList();
            productionRuns = runRepository.findProductionRunsBatch(pinnedIds)
                    .stream()
                    .collect(Collectors.toMap(
                            r -> r.getWorkflow().getId(),
                            Function.identity()
                    ));
        }

        // 5. Batch-fetch pending USER_APPROVAL signals (single query)
        Set<String> approvalRunIds = Set.of();
        Map<String, Long> productionEpochCounts = Map.of();
        if (!productionRuns.isEmpty()) {
            List<String> runIdPublics = productionRuns.values().stream()
                    .map(WorkflowRunEntity::getRunIdPublic)
                    .toList();
            approvalRunIds = new HashSet<>(
                    signalWaitRepository.findRunIdsWithPendingApprovals(runIdPublics)
            );
            // Epoch counts for the calendar badge on production cards. Single
            // batched COUNT(*) query; runs with zero epochs are absent → null
            // in the card (frontend hides the badge in that case).
            productionEpochCounts = epochRepository.getEpochCountByRunIds(runIdPublics);
        }

        // 6. Classify into columns
        Map<String, List<WorkflowBoardCard>> columns = new LinkedHashMap<>();
        columns.put("draft", new ArrayList<>());
        columns.put("production", new ArrayList<>());
        columns.put("needsReview", new ArrayList<>());
        columns.put("paused", new ArrayList<>());

        for (var w : drafts) {
            columns.get("draft").add(toCard(w, null, "draft", runCounts, productionEpochCounts, source));
        }

        for (var w : pinned) {
            WorkflowRunEntity run = productionRuns.get(w.getId());
            String column = classifyPinnedWorkflow(run, approvalRunIds);
            columns.get(column).add(toCard(w, run, column, runCounts, productionEpochCounts, source));
        }

        return new WorkflowBoardResponse(columns, totalCount, safePage, safeSize);
    }

    /** Single-column slice for per-column lazy loading on the kanban board. */
    public record WorkflowBoardColumnPage(
            String column,
            List<WorkflowBoardCard> items,
            int totalCount,
            int page,
            int size) {}

    /**
     * Load a single column slice for lazy loading.
     * Each kanban column scrolls independently; the frontend asks for the next page
     * when the user scrolls to the bottom of that column.
     */
    /** Back-compat overload - regular workflows (excludes APPLICATION-type). */
    public WorkflowBoardColumnPage loadColumn(String tenantId, String orgId, String orgRole,
                                                String column, int page, int size) {
        return loadColumn(tenantId, orgId, orgRole, column, page, size, false);
    }

    /**
     * Load a single column slice. Same classification + pagination for either source: regular
     * workflows (default) or APPLICATION-type workflows ({@code applicationsOnly=true}).
     */
    public WorkflowBoardColumnPage loadColumn(String tenantId, String orgId, String orgRole,
                                                String column, int page, int size, boolean applicationsOnly) {
        if (column == null
                || !(column.equals("draft") || column.equals("production")
                     || column.equals("needsReview") || column.equals("paused"))) {
            throw new IllegalArgumentException("Invalid column: " + column);
        }

        // 1. Load + sort the full tenant list (same source as buildBoard).
        BoardSource source = loadSource(tenantId, orgId, orgRole, applicationsOnly);
        List<WorkflowEntity> all = new ArrayList<>(source.entities());
        all.sort(Comparator
                .comparing(WorkflowEntity::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .reversed());

        // 2. Partition into draft vs pinned across the WHOLE list (we need to know what
        //    column each workflow belongs to before we can slice the requested column).
        List<WorkflowEntity> drafts = new ArrayList<>();
        List<WorkflowEntity> pinned = new ArrayList<>();
        for (var w : all) {
            if (w.getPinnedVersion() == null) drafts.add(w);
            else pinned.add(w);
        }

        // 3. Resolve column membership for the pinned workflows (single batch).
        Map<UUID, WorkflowRunEntity> productionRuns = Map.of();
        Set<String> approvalRunIds = Set.of();
        Map<String, Long> productionEpochCounts = Map.of();
        if (!pinned.isEmpty()) {
            List<UUID> pinnedIds = pinned.stream().map(WorkflowEntity::getId).toList();
            productionRuns = runRepository.findProductionRunsBatch(pinnedIds)
                    .stream()
                    .collect(Collectors.toMap(r -> r.getWorkflow().getId(), Function.identity()));
            if (!productionRuns.isEmpty()) {
                List<String> runIdPublics = productionRuns.values().stream()
                        .map(WorkflowRunEntity::getRunIdPublic).toList();
                approvalRunIds = new HashSet<>(signalWaitRepository.findRunIdsWithPendingApprovals(runIdPublics));
                productionEpochCounts = epochRepository.getEpochCountByRunIds(runIdPublics);
            }
        }

        // 4. Build the requested column's full list, then slice.
        List<WorkflowEntity> columnWorkflows = new ArrayList<>();
        Map<UUID, String> classifications = new HashMap<>();
        if (column.equals("draft")) {
            columnWorkflows.addAll(drafts);
            for (var w : drafts) classifications.put(w.getId(), "draft");
        } else {
            for (var w : pinned) {
                WorkflowRunEntity run = productionRuns.get(w.getId());
                String c = classifyPinnedWorkflow(run, approvalRunIds);
                if (c.equals(column)) {
                    columnWorkflows.add(w);
                    classifications.put(w.getId(), c);
                }
            }
        }

        int totalCount = columnWorkflows.size();
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, totalCount);
        int to = Math.min(from + safeSize, totalCount);
        List<WorkflowEntity> slice = columnWorkflows.subList(from, to);

        // 5. Run counts for the slice only.
        List<UUID> sliceIds = slice.stream().map(WorkflowEntity::getId).toList();
        Map<UUID, Long> runCounts = batchCountRuns(sliceIds);

        // 6. Build cards.
        List<WorkflowBoardCard> cards = new ArrayList<>();
        for (var w : slice) {
            WorkflowRunEntity run = w.getPinnedVersion() == null ? null : productionRuns.get(w.getId());
            cards.add(toCard(w, run, classifications.getOrDefault(w.getId(), column), runCounts, productionEpochCounts, source));
        }

        return new WorkflowBoardColumnPage(column, cards, totalCount, safePage, safeSize);
    }

    public String classifyPinnedWorkflow(WorkflowRunEntity run, Set<String> approvalRunIds) {
        if (run == null) {
            return "production";
        }

        // Priority: needsReview > paused > production
        if (approvalRunIds.contains(run.getRunIdPublic())) {
            return "needsReview";
        }
        if (run.getStatus() == RunStatus.CANCELLED) {
            return "paused";
        }
        return "production";
    }

    /**
     * The board's source rows plus the publication enrichment the cards need, computed once per
     * load so {@link #toCard} stays a pure mapping. {@code publicationIdByWorkflow} overrides a
     * card's {@code sourcePublicationId} (for own published apps whose entity has none);
     * {@code publicationStatusByWorkflow} feeds the "shared / in review / rejected" marker.
     */
    private record BoardSource(
            List<WorkflowEntity> entities,
            Map<UUID, UUID> publicationIdByWorkflow,
            Map<UUID, String> publicationStatusByWorkflow,
            // Own published-as-application rows only: the publication's showcase interface + run, so
            // the card renders the preview via the authenticated per-run path (valid at any
            // visibility). Empty for acquired rows, which keep the public showcase (publicationId).
            Map<UUID, UUID> showcaseInterfaceIdByWorkflow,
            Map<UUID, String> showcaseRunIdByWorkflow,
            // Own publications only: marketplace visibility (PUBLIC / PRIVATE / UNLISTED) keyed by
            // the source workflowId, so the card shows a public / private indicator and the board
            // can filter on it. Empty for acquired rows (publisher's visibility, not the viewer's).
            Map<UUID, String> visibilityByWorkflow,
            // Acquired rows whose source publication is absent from the LOCAL catalog = cloud-sourced
            // (remote) acquisitions on a cloud-linked CE. Their card must render the showcase through
            // the cloud proxy, not the local public render (which 404s on a cloud-only pub id). Empty
            // for own apps / local acquisitions / regular workflows (all local).
            Set<UUID> remoteAcquiredWorkflowIds) {}

    private BoardSource loadSource(String tenantId, String orgId, String orgRole, boolean applicationsOnly) {
        return applicationsOnly
                ? loadApplicationsSource(tenantId, orgId, orgRole)
                : loadWorkflowsSource(tenantId, orgId, orgRole);
    }

    /**
     * Regular workflows board: every WORKFLOW-type row, enriched with its publication moderation
     * status so a shared workflow shows the same "shared / in review / rejected" marker as the
     * {@code /workflows} list. Best-effort enrichment - a publication-service hiccup just drops the
     * marker (fail-open), never the board.
     */
    private BoardSource loadWorkflowsSource(String tenantId, String orgId, String orgRole) {
        List<WorkflowEntity> entities = workflowService.listWorkflows(tenantId, orgId, orgRole);
        Map<UUID, String> statuses = Collections.emptyMap();
        Map<UUID, String> visibilities = Collections.emptyMap();
        if (!entities.isEmpty()) {
            List<UUID> ids = entities.stream().map(WorkflowEntity::getId).toList();
            statuses = publicationClient.findPublicationStatusesByWorkflowIds(ids, tenantId);
            // Visibility marker + filter for shared workflows (best-effort; empty on a hiccup).
            visibilities = publicationClient.findPublicationVisibilitiesByWorkflowIds(ids, tenantId);
        }
        return new BoardSource(entities, Map.of(), statuses, Map.of(), Map.of(), visibilities, Set.of());
    }

    /**
     * Applications board: acquired APPLICATION-type rows (minus unpublished leftovers) PLUS the
     * publisher's OWN published-as-application workflows - the same union {@code /app/applications}
     * shows. Before this the board listed only acquired rows, so a self-published app was missing
     * here and lingered only as a plain workflow card. Deduped by publication id: when a publisher
     * acquired their own app, the own (WORKFLOW-type) row wins and the acquired clone is dropped,
     * matching {@code /app/applications} (published source wins). The own rows also stay on the
     * workflows board (the workflow keeps living) - they carry a sourcePublicationId here so the
     * card opens the application surface, and none there so it opens the builder.
     */
    private BoardSource loadApplicationsSource(String tenantId, String orgId, String orgRole) {
        Map<UUID, UUID> publicationIdByWorkflow = new HashMap<>();
        Map<UUID, String> publicationStatusByWorkflow = new HashMap<>();
        Map<UUID, UUID> showcaseInterfaceIdByWorkflow = new HashMap<>();
        Map<UUID, String> showcaseRunIdByWorkflow = new HashMap<>();
        Map<UUID, String> visibilityByWorkflow = new HashMap<>();
        Set<UUID> seenPublicationIds = new HashSet<>();
        List<WorkflowEntity> merged = new ArrayList<>();

        // 1. Own published-as-application workflows (WORKFLOW-type with a showcase publication).
        List<WorkflowEntity> ownWorkflows = workflowService.listWorkflows(tenantId, orgId, orgRole);
        if (!ownWorkflows.isEmpty()) {
            List<UUID> ids = ownWorkflows.stream().map(WorkflowEntity::getId).toList();
            Map<UUID, PublicationClient.ApplicationPublicationRef> appPubs =
                    publicationClient.findApplicationPublicationsByWorkflowIds(ids, tenantId);
            // Visibility marker + filter for own apps (best-effort; empty on a hiccup). Acquired
            // rows (added below) carry the publisher's visibility, not the viewer's → left unmarked.
            visibilityByWorkflow = publicationClient.findPublicationVisibilitiesByWorkflowIds(ids, tenantId);
            for (WorkflowEntity w : ownWorkflows) {
                PublicationClient.ApplicationPublicationRef ref = appPubs.get(w.getId());
                if (ref == null) {
                    continue; // not published as an application - stays on the workflows board only
                }
                merged.add(w);
                publicationIdByWorkflow.put(w.getId(), ref.publicationId());
                publicationStatusByWorkflow.put(w.getId(), ref.status());
                // Own app → carry the showcase ids so the card renders via the authenticated
                // per-run path (any visibility). Skipped when the publication has no showcase run.
                if (ref.showcaseInterfaceId() != null && ref.showcaseRunId() != null) {
                    showcaseInterfaceIdByWorkflow.put(w.getId(), ref.showcaseInterfaceId());
                    showcaseRunIdByWorkflow.put(w.getId(), ref.showcaseRunId());
                }
                seenPublicationIds.add(ref.publicationId());
            }
        }

        // 2. Acquired APPLICATION-type rows, skipping any whose source publication we already
        //    surfaced as an own published app (dedup by publication id). Their card resolves the
        //    publication via the entity's own sourcePublicationId (toCard fallback).
        List<WorkflowEntity> rawAcquired = workflowService.listApplications(tenantId, orgId, orgRole);
        // Resolve the LOCAL publication statuses of the acquired rows' source publications ONCE -
        // it serves two purposes: (a) drop unpublished (INACTIVE) leftovers, and (b) tell a LOCAL
        // acquired app (source publication present in the local catalog) from a cloud-sourced
        // (remote) one (absent locally). The lookup is keyed by publication id GLOBALLY (no tenant
        // scope), so a local acquired app's cross-tenant source publication is still resolved.
        Map<UUID, String> acquiredPubStatuses = acquiredPublicationStatuses(rawAcquired, tenantId);
        List<WorkflowEntity> acquired = excludeInactivePublicationApps(rawAcquired, acquiredPubStatuses);
        Set<UUID> remoteAcquiredWorkflowIds = new HashSet<>();
        for (WorkflowEntity w : acquired) {
            UUID sourcePub = w.getSourcePublicationId();
            if (sourcePub != null && seenPublicationIds.contains(sourcePub)) {
                continue;
            }
            merged.add(w);
            // Cloud-sourced acquisition: source publication absent from the local catalog. Flag it so
            // the card routes its showcase render to the cloud proxy (remote=true). A LOCAL acquired
            // app stays remote=false and renders via the receipt-gated authenticated showcase.
            if (sourcePub != null && !acquiredPubStatuses.containsKey(sourcePub)) {
                remoteAcquiredWorkflowIds.add(w.getId());
            }
        }

        // Org per-member deny-list: drop applications the member is fully restricted from, keyed on
        // the PUBLICATION id - the canonical "application" resource id, matching the
        // /app/applications list (publication-service) and ProjectService. The board's rows are
        // WorkflowEntity rows (workflow-id keyed), so the workflow-type filter in
        // listWorkflows/listApplications never catches an application restriction; this is where it
        // is enforced for the board. READ-restricted apps stay visible (the read set is DENY-only).
        // No-op in personal scope or for an OWNER/ADMIN (guard returns an empty set).
        if (orgId != null && !orgId.isBlank()) {
            Set<String> restrictedAppPubIds =
                    orgAccessGuard.getRestrictedResourceIds(orgId, tenantId, "application", orgRole);
            if (!restrictedAppPubIds.isEmpty()) {
                merged.removeIf(w -> {
                    UUID pubId = publicationIdByWorkflow.get(w.getId());   // own published app
                    if (pubId == null) {
                        pubId = w.getSourcePublicationId();                // acquired app
                    }
                    return pubId != null && restrictedAppPubIds.contains(pubId.toString());
                });
            }
        }

        return new BoardSource(merged, publicationIdByWorkflow, publicationStatusByWorkflow,
                showcaseInterfaceIdByWorkflow, showcaseRunIdByWorkflow, visibilityByWorkflow,
                remoteAcquiredWorkflowIds);
    }

    /**
     * Applications board only - drop APPLICATION-type rows whose source publication is INACTIVE
     * (i.e. unpublished). The raw {@link WorkflowManagementService#listApplications} has no
     * publication-status awareness (that state lives in publication-service), so without this an
     * unpublished app's leftover instance keeps showing on the board - rendered as a dot-grid
     * card because its INACTIVE showcase no longer renders publicly - while {@code /app/applications}
     * already filters it out. Best-effort: on a publication-service hiccup the status map is empty
     * and every app is kept (fail-open - never break the board over an enrichment call).
     *
     * <p>Scope note: this drops ONLY {@code INACTIVE}, matching the {@code /app/applications}
     * <em>published</em> stream (which keeps ACTIVE / PENDING_REVIEW / REJECTED). It deliberately
     * does NOT replicate the stricter <em>acquired</em> stream (ACTIVE-only) - an acquired app
     * whose source publication is in review/rejected still shows here, the conservative choice and
     * out of scope for the unpublished-app bug this fixes.
     */
    /**
     * Resolve the LOCAL publication status of each acquired row's source publication, keyed by
     * publication id (global, no tenant scope - an acquired app's source publication belongs to the
     * publisher). Empty when nothing resolves or the service is unreachable. The result is consumed
     * by both {@link #excludeInactivePublicationApps} (drop INACTIVE leftovers) and the remote-flag
     * derivation (a source publication absent from this map is a cloud-sourced acquisition).
     */
    private Map<UUID, String> acquiredPublicationStatuses(List<WorkflowEntity> apps, String tenantId) {
        if (apps.isEmpty()) {
            return Map.of();
        }
        List<UUID> pubIds = apps.stream()
                .map(WorkflowEntity::getSourcePublicationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (pubIds.isEmpty()) {
            return Map.of();
        }
        return publicationClient.findStatusesByPublicationIds(pubIds, tenantId);
    }

    private List<WorkflowEntity> excludeInactivePublicationApps(List<WorkflowEntity> apps,
                                                                Map<UUID, String> statuses) {
        if (apps.isEmpty() || statuses.isEmpty()) {
            return apps; // fail-open: service unreachable or nothing resolved
        }
        List<WorkflowEntity> kept = new ArrayList<>(apps.size());
        for (WorkflowEntity w : apps) {
            UUID pubId = w.getSourcePublicationId();
            // Keep apps without a source publication (legacy) and any whose publication is not
            // explicitly INACTIVE; drop only the unpublished leftovers.
            if (pubId == null || !"INACTIVE".equalsIgnoreCase(statuses.get(pubId))) {
                kept.add(w);
            }
        }
        return kept;
    }

    private Map<UUID, Long> batchCountRuns(List<UUID> workflowIds) {
        if (workflowIds.isEmpty()) return Map.of();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : runRepository.countByWorkflowIds(workflowIds)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return counts;
    }

    private WorkflowBoardCard toCard(WorkflowEntity w, WorkflowRunEntity run, String column,
                                     Map<UUID, Long> runCounts,
                                     Map<String, Long> productionEpochCounts,
                                     BoardSource source) {
        var nodeIcons = w.getNodeIcons();
        if (nodeIcons == null && w.getPlan() != null) {
            nodeIcons = WorkflowIconExtractor.extractNodeIcons(w.getPlan());
        }

        // Epoch count is only meaningful when a production run exists. Absence in
        // the map (e.g. the run has not fired yet) collapses to null so the
        // frontend can hide the badge cleanly without rendering "0 epochs".
        Long epochCount = run != null
                ? productionEpochCounts.get(run.getRunIdPublic())
                : null;

        // Source publication: the entity carries it for acquired APPLICATION-type rows; for a
        // publisher's OWN published-as-application WORKFLOW-type rows it comes from the override
        // map (their entity has none). Publication status drives the "shared" marker on the
        // workflows board, mirroring the /workflows list (isPublished == ACTIVE).
        UUID sourcePublicationId = source.publicationIdByWorkflow()
                .getOrDefault(w.getId(), w.getSourcePublicationId());
        String publicationStatus = source.publicationStatusByWorkflow().get(w.getId());
        boolean isPublished = "ACTIVE".equals(publicationStatus);
        // Own published-as-app rows carry the publication's showcase ids → the card renders the
        // preview via the authenticated per-run path (any visibility). Acquired rows have none here
        // and keep the public showcase (publicationId), which is cross-tenant-safe.
        UUID showcaseInterfaceId = source.showcaseInterfaceIdByWorkflow().get(w.getId());
        String showcaseRunId = source.showcaseRunIdByWorkflow().get(w.getId());
        // Marketplace visibility of the OWN publication (PUBLIC / PRIVATE / UNLISTED) → drives the
        // card's public / private indicator + the board's visibility filter. Null for acquired rows
        // (publisher's visibility) and unpublished workflows.
        String visibility = source.visibilityByWorkflow().get(w.getId());
        // Cloud-sourced (remote) acquisition: the card renders its showcase through the cloud proxy
        // instead of the local public render. False for own apps / local acquisitions / workflows.
        boolean remote = source.remoteAcquiredWorkflowIds().contains(w.getId());

        return new WorkflowBoardCard(
                w.getId(),
                w.getName(),
                w.getDescription(),
                nodeIcons,
                w.getPinnedVersion(),
                run != null ? run.getRunIdPublic() : null,
                run != null ? run.getStatus().name() : null,
                epochCount,
                w.getLastExecutedAt(),
                w.getUpdatedAt(),
                runCounts.getOrDefault(w.getId(), 0L),
                column,
                sourcePublicationId,
                isPublished,
                publicationStatus,
                showcaseInterfaceId,
                showcaseRunId,
                visibility,
                remote
        );
    }
}
