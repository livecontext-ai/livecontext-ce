package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.controllers.dto.ApplicationRunVersionSummary;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowEpochRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the Applications-page card grid's per-workflow metadata in a fixed handful of BATCHED
 * queries instead of two HTTP calls (application run + pinned version) PER card (~200 cards = ~400
 * round-trips). Mirrors the workflow board's batch-enrichment pattern
 * ({@link WorkflowBoardService} combines {@code findProductionRunsBatch} with the same epoch batch
 * lookups). Three queries total, independent of N:
 * <ol>
 *   <li>{@link WorkflowRunRepository#findApplicationRunsBatch} - most-recent application run per workflow,</li>
 *   <li>{@link WorkflowEpochRepository#getLatestEpochStartedAtByRunIds} - lastFireAt for those runs,</li>
 *   <li>{@link WorkflowRepository#findPinnedVersionScopeRows} - pinned version + scope columns per workflow.</li>
 * </ol>
 */
@Service
public class ApplicationRunVersionBatchService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowEpochRepository epochRepository;

    public ApplicationRunVersionBatchService(WorkflowRunRepository runRepository,
                                             WorkflowRepository workflowRepository,
                                             WorkflowEpochRepository epochRepository) {
        this.runRepository = runRepository;
        this.workflowRepository = workflowRepository;
        this.epochRepository = epochRepository;
    }

    /**
     * @param workflowIds the cards' resolved workflow ids
     * @param orgId       the caller's active-workspace org id ({@code X-Organization-ID}); the
     *                    pinned-version lookup is strict-workspace scoped to it (the per-card
     *                    {@code /versions} endpoint was owner-gated)
     * @param userId      the caller's user id ({@code X-User-ID}); used for the personal-workspace
     *                    scope branch when {@code orgId} is blank (the gateway suppresses the org
     *                    header for personal-org users), so a personal caller sees only their own
     *                    org-less rows. Outside the active workspace, a workflow reports no pinned
     *                    version - the run fields stay unscoped, matching the unscoped run lookups.
     */
    @Transactional(readOnly = true)
    public Map<UUID, ApplicationRunVersionSummary> resolve(Collection<UUID> workflowIds, String orgId, String userId) {
        if (workflowIds == null || workflowIds.isEmpty()) {
            return Map.of();
        }
        String scopedOrgId = (orgId == null || orgId.isBlank()) ? null : orgId;
        String scopedUserId = (userId == null || userId.isBlank()) ? null : userId;

        // 1. Most-recent application run per workflow (one DISTINCT ON query; one row per workflow).
        Map<UUID, WorkflowRunEntity> runByWorkflow = new HashMap<>();
        for (WorkflowRunEntity r : runRepository.findApplicationRunsBatch(workflowIds)) {
            UUID wfId = r.getWorkflow() != null ? r.getWorkflow().getId() : null;
            if (wfId != null) {
                runByWorkflow.putIfAbsent(wfId, r); // DISTINCT ON already yields 1 row/workflow; defensive
            }
        }

        // 2. lastFireAt for those runs in ONE batched epoch query (falls back to startedAt below).
        List<String> runIdPublics = runByWorkflow.values().stream()
                .map(WorkflowRunEntity::getRunIdPublic)
                .filter(Objects::nonNull)
                .toList();
        Map<String, Instant> lastFireByRun = runIdPublics.isEmpty()
                ? Map.of()
                : epochRepository.getLatestEpochStartedAtByRunIds(runIdPublics);

        // 3. Pinned version per workflow, filtered to the caller's ACTIVE workspace via the SAME
        //    strict-isolation predicate the per-card /versions endpoint used
        //    (ScopeGuard.isInStrictScope: org caller -> row.organizationId == orgId; personal caller
        //    -> row.tenantId == userId AND row.organizationId == null). Row =
        //    [id, pinnedVersion, tenantId, organizationId]. Reusing the helper (vs a duplicated SQL
        //    predicate) keeps SQL/Java from diverging and stays unit-testable for the org-null branch.
        Map<UUID, Integer> pinnedByWorkflow = new HashMap<>();
        for (Object[] row : workflowRepository.findPinnedVersionScopeRows(workflowIds)) {
            if (!(row[0] instanceof UUID id)) {
                continue;
            }
            String rowTenantId = row[2] instanceof String t ? t : null;
            String rowOrgId = row[3] instanceof String o ? o : null;
            if (!ScopeGuard.isInStrictScope(scopedUserId, scopedOrgId, rowTenantId, rowOrgId)) {
                continue; // outside the caller's active workspace -> no pinned version (no leak)
            }
            pinnedByWorkflow.put(id, row[1] instanceof Integer v ? v : null);
        }

        // Emit an entry for every workflow that has a run OR a pinned-version row (i.e. exists). A
        // workflow absent here reads as "load failed" on the client (badge hidden), matching the old
        // per-item failure path.
        Set<UUID> keys = new HashSet<>();
        keys.addAll(runByWorkflow.keySet());
        keys.addAll(pinnedByWorkflow.keySet());

        Map<UUID, ApplicationRunVersionSummary> out = new HashMap<>();
        for (UUID wfId : keys) {
            WorkflowRunEntity run = runByWorkflow.get(wfId);
            String runId = run != null ? run.getRunIdPublic() : null;
            Instant lastExecuted = null;
            if (run != null) {
                // Prefer the most recent epoch fire (reusable triggers), else the run's birth - the
                // exact "lastFireAt ?? startedAt" the Applications page used per-item.
                Instant lastFire = runId != null ? lastFireByRun.get(runId) : null;
                lastExecuted = lastFire != null ? lastFire : run.getStartedAt();
            }
            out.put(wfId, new ApplicationRunVersionSummary(runId, lastExecuted, pinnedByWorkflow.get(wfId)));
        }
        return out;
    }
}
