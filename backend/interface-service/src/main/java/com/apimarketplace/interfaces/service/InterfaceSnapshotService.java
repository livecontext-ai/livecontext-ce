package com.apimarketplace.interfaces.service;

import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.domain.InterfaceRunSnapshotEntity;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import com.apimarketplace.interfaces.repository.InterfaceRunSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Manages interface run snapshots.
 * Snapshots freeze interface templates at workflow execution time.
 */
@Service
@Transactional
public class InterfaceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(InterfaceSnapshotService.class);

    private final InterfaceRunSnapshotRepository snapshotRepository;
    private final InterfaceRepository interfaceRepository;

    public InterfaceSnapshotService(InterfaceRunSnapshotRepository snapshotRepository,
                                     InterfaceRepository interfaceRepository) {
        this.snapshotRepository = snapshotRepository;
        this.interfaceRepository = interfaceRepository;
    }

    /**
     * Create a snapshot for a specific interface and workflow run.
     */
    public InterfaceRunSnapshotEntity createSnapshot(UUID interfaceId, UUID workflowRunId,
                                                       Map<String, String> variableMappings,
                                                       Map<String, String> actionMappings,
                                                       String tenantId) {
        // Skip if snapshot already exists (idempotent)
        if (snapshotRepository.existsByInterfaceIdAndWorkflowRunId(interfaceId, workflowRunId)) {
            log.debug("Snapshot already exists for interface={}, run={}", interfaceId, workflowRunId);
            return snapshotRepository.findByInterfaceIdAndWorkflowRunId(interfaceId, workflowRunId).orElse(null);
        }

        InterfaceEntity iface = interfaceRepository.findById(interfaceId).orElse(null);
        if (iface == null) {
            log.warn("Interface {} not found, cannot create snapshot", interfaceId);
            return null;
        }

        InterfaceRunSnapshotEntity snapshot;
        if (actionMappings != null && !actionMappings.isEmpty()) {
            // Strip surrounding quotes from action mapping keys
            Map<String, String> cleanedActionMappings = new LinkedHashMap<>();
            actionMappings.forEach((key, value) -> {
                String cleanKey = key;
                if ((cleanKey.startsWith("'") && cleanKey.endsWith("'")) ||
                    (cleanKey.startsWith("\"") && cleanKey.endsWith("\""))) {
                    cleanKey = cleanKey.substring(1, cleanKey.length() - 1);
                }
                cleanedActionMappings.put(cleanKey, value);
            });
            snapshot = InterfaceRunSnapshotEntity.fromInterfaceWithMappings(
                    iface, workflowRunId, variableMappings, cleanedActionMappings);
        } else if (variableMappings != null && !variableMappings.isEmpty()) {
            snapshot = InterfaceRunSnapshotEntity.fromInterfaceWithMapping(iface, workflowRunId, variableMappings);
        } else {
            snapshot = InterfaceRunSnapshotEntity.fromInterface(iface, workflowRunId);
        }

        InterfaceRunSnapshotEntity saved = snapshotRepository.save(snapshot);
        log.info("Created snapshot for interface={}, run={}", interfaceId, workflowRunId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<InterfaceRunSnapshotEntity> getSnapshot(UUID interfaceId, UUID workflowRunId) {
        return getSnapshot(interfaceId, workflowRunId, null);
    }

    /**
     * Org-aware snapshot lookup. When {@code organizationId} is non-blank, scopes the lookup to
     * snapshots whose parent {@link com.apimarketplace.interfaces.domain.InterfaceEntity}
     * belongs to that org - closing the cross-org UUID-guess on the internal endpoint that
     * previously took (interfaceId, workflowRunId) without any tenant/org check. When null,
     * falls back to the legacy unscoped path + WARN log so the missing org context surfaces.
     */
    @Transactional(readOnly = true)
    public Optional<InterfaceRunSnapshotEntity> getSnapshot(UUID interfaceId, UUID workflowRunId,
                                                              String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return snapshotRepository.findByInterfaceIdAndWorkflowRunIdInOrgScope(
                    interfaceId, workflowRunId, organizationId);
        }
        log.warn("[InterfaceSnapshotService] getSnapshot fell back to unscoped lookup "
                + "(no organizationId) - caller may UUID-guess across orgs: interfaceId={}, runId={}",
                interfaceId, workflowRunId);
        return snapshotRepository.findByInterfaceIdAndWorkflowRunId(interfaceId, workflowRunId);
    }

    @Transactional(readOnly = true)
    public List<InterfaceRunSnapshotEntity> getSnapshotsForRun(UUID workflowRunId) {
        return getSnapshotsForRun(workflowRunId, null);
    }

    /**
     * Org-aware snapshot list. Same scoping contract as {@link #getSnapshot(UUID, UUID, String)}.
     */
    @Transactional(readOnly = true)
    public List<InterfaceRunSnapshotEntity> getSnapshotsForRun(UUID workflowRunId, String organizationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return snapshotRepository.findByWorkflowRunIdInOrgScope(workflowRunId, organizationId);
        }
        log.warn("[InterfaceSnapshotService] getSnapshotsForRun fell back to unscoped lookup "
                + "(no organizationId): runId={}", workflowRunId);
        return snapshotRepository.findByWorkflowRunId(workflowRunId);
    }

    public void deleteSnapshotsForRun(UUID workflowRunId) {
        snapshotRepository.deleteByWorkflowRunId(workflowRunId);
        log.debug("Deleted snapshots for run={}", workflowRunId);
    }

    /**
     * Refresh every snapshot of a workflow run with the current live HTML/CSS/JS/name/description
     * of its source {@link InterfaceEntity}.
     *
     * <p>Mirrors the plan-refresh idiom used by
     * {@code WorkflowResumeService.refreshPlanFromWorkflowDefinition} in orchestrator-service:
     * frozen-at-run-creation snapshots are updated to the latest live content so that long-running
     * {@code WAITING_TRIGGER} runs (form/webhook/schedule "apps") pick up agent-driven interface
     * iterations on the NEXT trigger fire without forcing a cancel + new-run cycle.
     *
     * <p><b>What is refreshed</b>: {@code htmlTemplate}, {@code cssTemplate}, {@code jsTemplate},
     * {@code name}, {@code description}. <b>NOT refreshed</b>: {@code variableMappings} and
     * {@code actionMappings} - those are stamped from the workflow plan at snapshot-creation time
     * and the plan itself is independently refreshed by
     * {@code WorkflowResumeService.refreshPlanFromWorkflowDefinition}. Decoupling avoids
     * double-write conflicts when both refresh paths fire on the same trigger.
     *
     * <p><b>Deletion guard</b>: if the source {@link InterfaceEntity} no longer exists (deleted
     * since the run started), the snapshot is left untouched. This preserves the user-visible UI
     * of the run instead of blanking it - an interface deletion mid-run is treated as "user wants
     * to keep what's there for the in-flight run". Logged at INFO so it surfaces in support
     * diagnostics.
     *
     * <p><b>No-op when content unchanged</b>: identity-compares each field with the live entity
     * and skips the DB write when nothing changed. Saves a Hibernate dirty-checking round-trip on
     * the common case (most trigger fires on a stable interface).
     *
     * <p>This method is intentionally tolerant: per-snapshot failures are logged and swallowed so
     * one stale row never blocks the trigger-fire pipeline.
     *
     * @param workflowRunId the run whose snapshots to refresh
     * @return refresh counters (refreshed/unchanged/missing/error) for observability + test
     *     assertions; never null
     */
    public RefreshResult refreshSnapshotsFromLiveInterface(UUID workflowRunId) {
        return refreshSnapshotsFromLiveInterface(workflowRunId, null);
    }

    /**
     * Org-aware variant. When {@code organizationId} is non-blank, only snapshots whose parent
     * {@link InterfaceEntity#getOrganizationId()} matches are refreshed - closing the
     * cross-org-write gap on the internal {@code /snapshots/refresh-from-live/{runId}} endpoint.
     * A UUID-guess from another org's caller now no-ops instead of overwriting HTML/CSS/JS.
     * Null/blank orgId preserves legacy unscoped behaviour with a WARN log so the missing
     * context surfaces in prod logs (post-V263 contract).
     */
    public RefreshResult refreshSnapshotsFromLiveInterface(UUID workflowRunId, String organizationId) {
        List<InterfaceRunSnapshotEntity> snapshots;
        if (organizationId != null && !organizationId.isBlank()) {
            snapshots = snapshotRepository.findByWorkflowRunIdInOrgScope(workflowRunId, organizationId);
        } else {
            log.warn("[InterfaceSnapshotService] refresh fell back to unscoped lookup "
                    + "(no organizationId) - caller can overwrite snapshots cross-org: runId={}",
                    workflowRunId);
            snapshots = snapshotRepository.findByWorkflowRunId(workflowRunId);
        }
        if (snapshots.isEmpty()) {
            log.debug("No snapshots to refresh for run={}", workflowRunId);
            return RefreshResult.empty();
        }

        int refreshed = 0;
        int unchanged = 0;
        int missing = 0;
        int errors = 0;

        for (InterfaceRunSnapshotEntity snap : snapshots) {
            try {
                Optional<InterfaceEntity> liveOpt = interfaceRepository.findById(snap.getInterfaceId());

                // Deletion guard - keep the frozen snapshot when the source interface has been
                // removed since the run started. Without this guard we would either NPE on
                // accessor calls or, worse, blank the snapshot fields and break in-flight UIs.
                if (liveOpt.isEmpty()) {
                    log.info("Interface {} no longer exists; keeping frozen snapshot for run={}",
                            snap.getInterfaceId(), workflowRunId);
                    missing++;
                    continue;
                }
                InterfaceEntity live = liveOpt.get();

                // No-op short-circuit: don't dirty the entity if every refreshable field already
                // matches the live source. Identity-comparable on Strings via Objects.equals
                // (null-safe). Keeps the per-fire cost at one indexed SELECT for stable
                // interfaces.
                if (Objects.equals(snap.getHtmlTemplate(), live.getHtmlTemplate())
                        && Objects.equals(snap.getCssTemplate(), live.getCssTemplate())
                        && Objects.equals(snap.getJsTemplate(), live.getJsTemplate())
                        && Objects.equals(snap.getName(), live.getName())
                        && Objects.equals(snap.getDescription(), live.getDescription())) {
                    unchanged++;
                    continue;
                }

                snap.setHtmlTemplate(live.getHtmlTemplate());
                snap.setCssTemplate(live.getCssTemplate());
                snap.setJsTemplate(live.getJsTemplate());
                snap.setName(live.getName());
                snap.setDescription(live.getDescription());
                snapshotRepository.save(snap);
                refreshed++;
                log.info("Refreshed snapshot for interface={}, run={}",
                        snap.getInterfaceId(), workflowRunId);
            } catch (Exception e) {
                // Defensive: one corrupt row must not block the trigger-fire path.
                errors++;
                log.error("Failed to refresh snapshot for interface={}, run={}: {}",
                        snap.getInterfaceId(), workflowRunId, e.getMessage(), e);
            }
        }

        log.debug("Snapshot refresh for run={}: refreshed={}, unchanged={}, missing={}, errors={}",
                workflowRunId, refreshed, unchanged, missing, errors);
        return new RefreshResult(refreshed, unchanged, missing, errors);
    }

    /**
     * Counters returned by {@link #refreshSnapshotsFromLiveInterface(UUID)}. Exposed so callers
     * can log/observe outcomes and tests can assert exact branch coverage without scraping log
     * lines.
     */
    public record RefreshResult(int refreshed, int unchanged, int missing, int errors) {
        public static RefreshResult empty() { return new RefreshResult(0, 0, 0, 0); }
        public int total() { return refreshed + unchanged + missing + errors; }
    }
}
