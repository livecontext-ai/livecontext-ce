package com.apimarketplace.orchestrator.services.publication;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.DagState;
import com.apimarketplace.orchestrator.domain.execution.EpochState;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.StepAggregationService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.services.state.StateSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Builds the {@code showcase_snapshot} JSONB stored on
 * {@code publication.workflow_publications}. The snapshot captures every
 * piece of run state the marketplace preview needs (run header, steps,
 * edges, aggregated steps, per-epoch signals, epoch timestamps, and per-
 * interface pre-rendered templates + items) so anonymous reads against
 * a published workflow never have to round-trip back to the orchestrator.
 *
 * <p>Read-only, side-effect free. Failures in optional sections (e.g. an
 * interface that has no snapshot yet) degrade gracefully - the missing
 * subtree is left empty rather than aborting the whole capture.
 */
@Service
public class ShowcaseSnapshotBuilder {

    public static final int SCHEMA_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(ShowcaseSnapshotBuilder.class);

    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowResumeService workflowResumeService;
    private final StateSnapshotService stateSnapshotService;
    private final WorkflowEpochService workflowEpochService;
    private final StepAggregationService stepAggregationService;
    private final SignalWaitRepository signalWaitRepository;
    private final InterfaceRenderService interfaceRenderService;
    private final InterfaceClient interfaceClient;
    private static final int RENUMBERED_EPOCH = 1;

    public ShowcaseSnapshotBuilder(
            WorkflowRunRepository workflowRunRepository,
            WorkflowResumeService workflowResumeService,
            StateSnapshotService stateSnapshotService,
            WorkflowEpochService workflowEpochService,
            StepAggregationService stepAggregationService,
            SignalWaitRepository signalWaitRepository,
            InterfaceRenderService interfaceRenderService,
            InterfaceClient interfaceClient) {
        this.workflowRunRepository = workflowRunRepository;
        this.workflowResumeService = workflowResumeService;
        this.stateSnapshotService = stateSnapshotService;
        this.workflowEpochService = workflowEpochService;
        this.stepAggregationService = stepAggregationService;
        this.signalWaitRepository = signalWaitRepository;
        this.interfaceRenderService = interfaceRenderService;
        this.interfaceClient = interfaceClient;
    }

    /**
     * Capture the complete frozen view of a run.
     *
     * @param runIdPublic the public run id ({@code run_*} for source runs,
     *                    {@code showcase_*} for legacy clones during the
     *                    backfill)
     * @param tenantId    the tenant owning the run; cross-tenant calls
     *                    return an empty Optional
     * @return the snapshot, or empty if the run does not exist or belongs
     *         to a different tenant
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> capture(String runIdPublic, String tenantId) {
        return capture(runIdPublic, tenantId, null);
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> capture(String runIdPublic, String tenantId, String organizationId) {
        return capture(runIdPublic, tenantId, organizationId, null);
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> capture(String runIdPublic, String tenantId, String organizationId, Integer epochFilter) {
        Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runIdPublic);
        if (runOpt.isEmpty()) {
            log.warn("[ShowcaseSnapshot] run not found: {}", runIdPublic);
            return Optional.empty();
        }
        WorkflowRunEntity run = runOpt.get();
        if (tenantId != null && !ScopeGuard.isInStrictScope(
                tenantId, organizationId, run.getTenantId(), run.getOrganizationId())) {
            log.warn("[ShowcaseSnapshot] scope mismatch for run {} (caller tenant={} org={}, run tenant={} org={})",
                    runIdPublic, tenantId, organizationId, run.getTenantId(), run.getOrganizationId());
            return Optional.empty();
        }

        StateSnapshot dbSnapshot = loadStateSnapshot(runIdPublic);
        if (epochFilter != null && !epochExists(runIdPublic, dbSnapshot, epochFilter)) {
            log.warn("[ShowcaseSnapshot] requested epoch {} does not exist for run {}", epochFilter, runIdPublic);
            return Optional.empty();
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", SCHEMA_VERSION);
        snapshot.put("capturedAt", Instant.now().toString());
        snapshot.put("sourceRunId", runIdPublic);
        if (epochFilter != null) {
            snapshot.put("sourceEpoch", epochFilter);
        }

        // When epochFilter is set, all sub-methods capture data for that epoch
        // but store it under key "1" so the marketplace always shows clean
        // numbering regardless of which epoch was originally selected.
        String snapshotEpochKey = epochFilter != null ? String.valueOf(RENUMBERED_EPOCH) : null;

        snapshot.put("runState", buildRunState(run, runIdPublic, dbSnapshot, epochFilter));
        snapshot.put("aggregatedSteps", buildAggregatedSteps(runIdPublic, snapshot, epochFilter, snapshotEpochKey));
        snapshot.put("epochSignals", buildEpochSignals(runIdPublic, epochFilter, snapshotEpochKey));
        snapshot.put("epochStates", buildEpochStates(runIdPublic, epochFilter, snapshotEpochKey));
        snapshot.put("interfaceRenders", buildInterfaceRenders(run, runIdPublic, tenantId, organizationId, epochFilter, snapshotEpochKey));

        return Optional.of(snapshot);
    }

    private StateSnapshot loadStateSnapshot(String runIdPublic) {
        try {
            StateSnapshot snapshot = stateSnapshotService.getSnapshot(runIdPublic);
            return snapshot != null ? snapshot : StateSnapshot.empty();
        } catch (Exception e) {
            log.warn("[ShowcaseSnapshot] state snapshot capture failed for {}: {}", runIdPublic, e.getMessage());
            return StateSnapshot.empty();
        }
    }

    private boolean epochExists(String runIdPublic, StateSnapshot snapshot, int epoch) {
        if (epoch < 0) {
            return false;
        }
        if (snapshot != null) {
            for (DagState dag : snapshot.getDags().values()) {
                if (dag.getEpochState(epoch) != null) {
                    return true;
                }
            }
        }
        try {
            boolean timestampExists = workflowEpochService.listEpochTimestamps(runIdPublic).stream()
                    .anyMatch(row -> row.epoch() == epoch);
            if (timestampExists) {
                return true;
            }
        } catch (Exception e) {
            log.debug("[ShowcaseSnapshot] epoch timestamp probe failed for {} epoch {}: {}",
                    runIdPublic, epoch, e.getMessage());
        }
        try {
            if (workflowEpochService.getEpochHeader(runIdPublic, epoch) != null) {
                return true;
            }
        } catch (Exception e) {
            log.debug("[ShowcaseSnapshot] epoch header probe failed for {} epoch {}: {}",
                    runIdPublic, epoch, e.getMessage());
        }
        try {
            Map<String, Object> epochState = workflowEpochService.getEpochState(runIdPublic, epoch);
            if (epochStateHasExecutionData(epochState)) {
                return true;
            }
        } catch (Exception e) {
            log.debug("[ShowcaseSnapshot] epoch state probe failed for {} epoch {}: {}",
                    runIdPublic, epoch, e.getMessage());
        }
        try {
            var perEpoch = stepAggregationService.getAggregatedSteps(runIdPublic, epoch);
            if (perEpoch.isPresent() && !perEpoch.get().isEmpty()) {
                return true;
            }
        } catch (Exception e) {
            log.debug("[ShowcaseSnapshot] epoch aggregation probe failed for {} epoch {}: {}",
                    runIdPublic, epoch, e.getMessage());
        }
        return epoch == 0 && snapshot != null && snapshot.getDags().isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static boolean epochStateHasExecutionData(Map<String, Object> epochState) {
        if (epochState == null || epochState.isEmpty()) {
            return false;
        }
        Object nodes = epochState.get("nodes");
        if (nodes instanceof Map<?, ?> nodeMap && !nodeMap.isEmpty()) {
            return true;
        }
        Object edges = epochState.get("edges");
        if (edges instanceof Map<?, ?> edgeMap && !edgeMap.isEmpty()) {
            return true;
        }
        for (String key : List.of("readyNodeIds", "awaitingSignalNodeIds", "completedNodeIds",
                "failedNodeIds", "skippedNodeIds", "runningNodeIds")) {
            Object value = ((Map<String, Object>) epochState).get(key);
            if (value instanceof Collection<?> collection && !collection.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> buildRunState(
            WorkflowRunEntity run,
            String runIdPublic,
            StateSnapshot dbSnapshot,
            Integer epochFilter) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            WorkflowRunState state = workflowResumeService.reconstructStateForApi(runIdPublic);
            long seq = dbSnapshot.getSeq();

            int currentEpoch = 0;
            for (DagState dag : dbSnapshot.getDags().values()) {
                currentEpoch = Math.max(currentEpoch, dag.getCurrentEpoch());
            }
            EpochNodeView epochNodeView = epochFilter != null
                    ? epochNodeView(dbSnapshot, epochFilter)
                    : null;
            var epochTimestamps = workflowEpochService.listEpochTimestamps(runIdPublic);

            response.put("runId", state.runId());
            response.put("workflowId", state.workflowId());
            response.put("status", state.status());
            response.put("executionMode", state.executionMode());
            response.put("startedAt", state.startedAt());
            response.put("pausedAt", state.pausedAt());
            response.put("plan", state.plan());
            // Steps are scrubbed: inputData (often resolved credentials/tokens)
            // is dropped, errorMessage (often leaks paths/queries) is replaced
            // with a generic sentinel, and output is recursively redacted on
            // credential-shaped keys. The marketplace preview only needs the
            // visible fields (status, statusCounts, output for interface
            // template rendering) - not the raw execution payloads.
            response.put("steps", buildRunStateSteps(runIdPublic, state.steps(), epochNodeView, epochFilter));
            response.put("edges", buildRunStateEdges(runIdPublic, state.edges(), epochNodeView, epochFilter));
            response.put("readySteps", epochNodeView != null ? epochNodeView.ready() : state.readySteps());
            response.put("completedStepIds", epochNodeView != null ? epochNodeView.completed() : state.completedStepIds());
            response.put("failedStepIds", epochNodeView != null ? epochNodeView.failed() : state.failedStepIds());
            response.put("skippedStepIds", epochNodeView != null ? epochNodeView.skipped() : state.skippedStepIds());
            response.put("runningStepIds", epochNodeView != null ? epochNodeView.running() : state.runningStepIds());
            response.put("loops", epochNodeView != null ? epochNodeView.loops() : state.loops());
            response.put("interfaces", state.interfaces());
            response.put("seq", seq);
            if (epochFilter != null) {
                currentEpoch = RENUMBERED_EPOCH;
                if (epochTimestamps != null && !epochTimestamps.isEmpty()) {
                    List<Map<String, Object>> renumbered = new ArrayList<>();
                    for (var t : epochTimestamps) {
                        try {
                            Object ep = t.getClass().getMethod("epoch").invoke(t);
                            if (ep instanceof Number n && n.intValue() == epochFilter) {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("epoch", RENUMBERED_EPOCH);
                                m.put("startedAt", t.getClass().getMethod("startedAt").invoke(t));
                                m.put("endedAt", t.getClass().getMethod("endedAt").invoke(t));
                                renumbered.add(m);
                            }
                        } catch (Exception ignored) {}
                    }
                    response.put("epochTimestamps", renumbered);
                    response.put("currentEpoch", currentEpoch);
                } else {
                    response.put("currentEpoch", currentEpoch);
                }
            } else {
                response.put("currentEpoch", currentEpoch);
                if (epochTimestamps != null && !epochTimestamps.isEmpty()) {
                    response.put("epochTimestamps", epochTimestamps);
                }
            }
            response.put("totalDurationMs", dbSnapshot.getTotalDurationMs());
            response.put("planVersion", run.getPlanVersion());
        } catch (Exception e) {
            log.warn("[ShowcaseSnapshot] runState capture failed for {}: {}", runIdPublic, e.getMessage());
        }
        return response;
    }

    /**
     * Credential-shaped key tokens (whole-word match after splitting the
     * key on case boundaries / non-alphanumeric separators).
     *
     * <p><b>SYNC</b>: this set + {@link #CREDENTIAL_PHRASES} +
     * {@link #looksSensitive(String)} are an inlined copy of the canonical
     * {@code CredentialKeyDetector} in publication-service
     * ({@code backend/publication-service/.../utils/CredentialKeyDetector.java}).
     * Orchestrator-service can't depend on publication-service so the copy
     * lives here. Any change on either side MUST be mirrored - there is no
     * automated drift guard yet.
     */
    private static final Set<String> CREDENTIAL_KEY_TOKENS = Set.of(
            "password", "passphrase", "apikey", "authorization",
            "bearer", "credential", "credentials", "credentialid",
            "privatekey", "clientsecret", "appsecret",
            "accesstoken", "refreshtoken", "idtoken", "authtoken", "sessiontoken",
            "xapikey");

    private static final Set<String> CREDENTIAL_PHRASES = Set.of(
            "apikey", "privatekey", "clientsecret", "accesstoken",
            "refreshtoken", "authtoken", "idtoken");

    private static final String REDACTED = "[redacted]";

    private static List<Map<String, Object>> scrubSteps(List<WorkflowRunState.StepState> steps) {
        return scrubSteps(steps, null);
    }

    private List<Map<String, Object>> buildRunStateSteps(
            String runIdPublic,
            List<WorkflowRunState.StepState> steps,
            EpochNodeView epochNodeView,
            Integer epochFilter) {
        if (epochFilter == null) {
            return scrubSteps(steps);
        }

        List<Map<String, Object>> aggregatedSteps = aggregatedStepsForRunState(runIdPublic, epochFilter);
        if (!aggregatedSteps.isEmpty()) {
            return aggregatedSteps;
        }
        return statusOnlyEpochSteps(epochNodeView);
    }

    private List<Map<String, Object>> aggregatedStepsForRunState(String runIdPublic, int epoch) {
        try {
            var perEpoch = stepAggregationService.getAggregatedSteps(runIdPublic, epoch);
            if (perEpoch.isEmpty() || perEpoch.get().isEmpty()) {
                return List.of();
            }

            List<Map<String, Object>> result = new ArrayList<>(perEpoch.get().size());
            for (StepAggregationService.AggregatedStep step : perEpoch.get()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("stepId", step.alias());
                map.put("stepAlias", step.alias());
                map.put("toolId", step.toolId());
                map.put("status", runStatusFromAggregatedStatus(step.status()));
                map.put("output", null);
                map.put("itemIndex", null);
                map.put("iteration", null);
                map.put("httpStatus", null);
                map.put("errorMessage", null);
                map.put("startTime", step.startTime());
                map.put("endTime", step.endTime());
                long executionTimeMs = Math.max(0, step.totalExecutionTimeMs());
                map.put("executionTimeMs", executionTimeMs);
                map.put("dependencies", Set.of());
                map.put("canExecute", false);
                map.put("statusCounts", step.statusCounts() != null ? step.statusCounts() : Map.of());
                map.put("totalExecutionTimeMs", executionTimeMs);
                result.add(map);
            }
            return result;
        } catch (Exception e) {
            log.warn("[ShowcaseSnapshot] epoch runState step aggregation failed for {} epoch {}: {}",
                    runIdPublic, epoch, e.getMessage());
            return List.of();
        }
    }

    private static RunStatus runStatusFromAggregatedStatus(String status) {
        if (status == null || status.isBlank()) {
            return RunStatus.PENDING;
        }
        return switch (status.toLowerCase(Locale.ROOT).trim()) {
            case "error", "failure" -> RunStatus.FAILED;
            default -> RunStatus.fromString(status);
        };
    }

    private static List<Map<String, Object>> statusOnlyEpochSteps(EpochNodeView epochNodeView) {
        if (epochNodeView == null || epochNodeView.all().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(epochNodeView.all().size());
        for (String nodeId : epochNodeView.all()) {
            RunStatus status = epochNodeView.statusFor(nodeId, nodeId, RunStatus.PENDING);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("stepId", nodeId);
            map.put("stepAlias", nodeId);
            map.put("toolId", null);
            map.put("status", status);
            map.put("output", null);
            map.put("itemIndex", null);
            map.put("iteration", null);
            map.put("httpStatus", null);
            map.put("errorMessage", null);
            map.put("startTime", null);
            map.put("endTime", null);
            map.put("executionTimeMs", null);
            map.put("dependencies", Set.of());
            map.put("canExecute", false);
            map.put("statusCounts", epochNodeView.statusCountsFor(nodeId, nodeId));
            map.put("totalExecutionTimeMs", null);
            result.add(map);
        }
        return result;
    }

    private static List<Map<String, Object>> scrubSteps(
            List<WorkflowRunState.StepState> steps,
            EpochNodeView epochNodeView) {
        if (steps == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>(steps.size());
        for (WorkflowRunState.StepState s : steps) {
            if (epochNodeView != null && !epochNodeView.contains(s.stepId(), s.stepAlias())) {
                continue;
            }
            RunStatus status = epochNodeView != null
                    ? epochNodeView.statusFor(s.stepId(), s.stepAlias(), s.status())
                    : s.status();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepId", s.stepId());
            m.put("stepAlias", s.stepAlias());
            m.put("toolId", s.toolId());
            m.put("status", status);
            // inputData removed entirely - execution payloads are not the
            // marketplace's concern and frequently embed resolved secrets.
            m.put("output", scrubMap(s.output()));
            m.put("itemIndex", s.itemIndex());
            m.put("iteration", s.iteration());
            m.put("httpStatus", s.httpStatus());
            m.put("errorMessage", s.errorMessage() != null ? "Error" : null);
            m.put("startTime", s.startTime());
            m.put("endTime", s.endTime());
            m.put("executionTimeMs", s.executionTimeMs());
            m.put("dependencies", s.dependencies());
            m.put("canExecute", s.canExecute());
            m.put("statusCounts", epochNodeView != null
                    ? epochNodeView.statusCountsFor(s.stepId(), s.stepAlias())
                    : s.statusCounts());
            m.put("totalExecutionTimeMs", s.totalExecutionTimeMs());
            result.add(m);
        }
        return result;
    }

    /**
     * Edge counts for the {@code runState} section.
     *
     * <p>For an epoch-pinned capture, the durable per-epoch edge rows
     * ({@code WorkflowEpochService} - the same source {@link #buildEpochStates}
     * freezes for the working per-epoch view) are the PRIMARY source. The JSONB
     * StateSnapshot prunes a closed epoch's node sets ({@code closeAndPruneEpoch}),
     * so {@link #filterEdgesForEpoch} sees an empty node view on a finished run
     * and drops EVERY edge - the marketplace "All epochs" canvas then renders no
     * edge statusCounts while nodes keep theirs (steps already fall back to the
     * aggregation table in {@link #buildRunStateSteps}). Falls back to the
     * node-view filter when no per-epoch edge rows exist (legacy runs).
     */
    private List<WorkflowRunState.EdgeState> buildRunStateEdges(
            String runIdPublic,
            List<WorkflowRunState.EdgeState> edges,
            EpochNodeView epochNodeView,
            Integer epochFilter) {
        if (epochFilter != null) {
            List<WorkflowRunState.EdgeState> epochEdges = epochEdgeStates(runIdPublic, epochFilter);
            if (!epochEdges.isEmpty()) {
                return epochEdges;
            }
        }
        return filterEdgesForEpoch(edges, epochNodeView);
    }

    /**
     * Convert the per-epoch edge rows ({@code edges: {"from->to": {STATUS: count}}})
     * into {@code EdgeState}s. Returns an empty list when the epoch has no edge
     * rows so the caller can fall back to the JSONB-derived filter.
     *
     * <p><b>SYNC</b>: the parse/derive logic (arrow split, case-insensitive status
     * summing, status precedence RUNNING &gt; FAILED &gt; COMPLETED &gt; SKIPPED) is
     * mirrored by {@code ShowcaseSnapshotReader.synthesizeEdgesFromEpochStates} in
     * publication-service (the read-time self-heal for legacy snapshots frozen with
     * empty edges). Orchestrator and publication-service share no module, so the
     * copy lives there - keep both sides aligned.
     */
    private List<WorkflowRunState.EdgeState> epochEdgeStates(String runIdPublic, int epoch) {
        try {
            Map<String, Object> epochState = workflowEpochService.getEpochState(runIdPublic, epoch);
            Object edgesObj = epochState != null ? epochState.get("edges") : null;
            if (!(edgesObj instanceof Map<?, ?> edgeMap) || edgeMap.isEmpty()) {
                return List.of();
            }
            List<WorkflowRunState.EdgeState> result = new ArrayList<>(edgeMap.size());
            for (Map.Entry<?, ?> entry : edgeMap.entrySet()) {
                String key = String.valueOf(entry.getKey());
                int arrow = key.indexOf("->");
                if (arrow <= 0 || arrow + 2 >= key.length()
                        || !(entry.getValue() instanceof Map<?, ?> counts)) continue;
                int completed = 0;
                int skipped = 0;
                int failed = 0;
                int running = 0;
                for (Map.Entry<?, ?> count : counts.entrySet()) {
                    int value = count.getValue() instanceof Number n ? n.intValue() : 0;
                    switch (String.valueOf(count.getKey()).toUpperCase(Locale.ROOT)) {
                        case "COMPLETED" -> completed += value;
                        case "SKIPPED" -> skipped += value;
                        case "FAILED" -> failed += value;
                        case "RUNNING" -> running += value;
                        default -> { }
                    }
                }
                if (completed == 0 && skipped == 0 && failed == 0 && running == 0) continue;
                RunStatus status = running > 0 ? RunStatus.RUNNING
                        : failed > 0 ? RunStatus.FAILED
                        : completed > 0 ? RunStatus.COMPLETED
                        : RunStatus.SKIPPED;
                result.add(new WorkflowRunState.EdgeState(
                        key.substring(0, arrow),
                        key.substring(arrow + 2),
                        status,
                        completed,
                        skipped,
                        completed + skipped));
            }
            return result;
        } catch (Exception e) {
            log.debug("[ShowcaseSnapshot] per-epoch edge counts unavailable for {} epoch {}: {}",
                    runIdPublic, epoch, e.getMessage());
            return List.of();
        }
    }

    private static List<WorkflowRunState.EdgeState> filterEdgesForEpoch(
            List<WorkflowRunState.EdgeState> edges,
            EpochNodeView epochNodeView) {
        if (edges == null) return List.of();
        if (epochNodeView == null) return edges;

        List<WorkflowRunState.EdgeState> filtered = new ArrayList<>();
        for (WorkflowRunState.EdgeState edge : edges) {
            if (!epochNodeView.contains(edge.from(), null) && !epochNodeView.contains(edge.to(), null)) {
                continue;
            }
            RunStatus status = epochNodeView.edgeStatus(edge);
            int completed = status == RunStatus.COMPLETED ? 1 : 0;
            int skipped = status == RunStatus.SKIPPED ? 1 : 0;
            filtered.add(new WorkflowRunState.EdgeState(
                    edge.from(),
                    edge.to(),
                    status,
                    completed,
                    skipped,
                    completed + skipped));
        }
        return filtered;
    }

    private static EpochNodeView epochNodeView(StateSnapshot snapshot, int epoch) {
        Set<String> completed = new LinkedHashSet<>();
        Set<String> failed = new LinkedHashSet<>();
        Set<String> skipped = new LinkedHashSet<>();
        Set<String> running = new LinkedHashSet<>();
        Set<String> ready = new LinkedHashSet<>();
        Map<String, Object> loops = new LinkedHashMap<>();

        for (DagState dag : snapshot.getDags().values()) {
            EpochState state = dag.getEpochState(epoch);
            if (state == null) continue;
            completed.addAll(state.getCompletedNodeIds());
            failed.addAll(state.getFailedNodeIds());
            skipped.addAll(state.getSkippedNodeIds());
            running.addAll(state.getRunningNodeIds());
            ready.addAll(state.getReadyNodeIds());
            loops.putAll(state.getLoops());
        }

        Set<String> all = new LinkedHashSet<>();
        all.addAll(completed);
        all.addAll(failed);
        all.addAll(skipped);
        all.addAll(running);
        all.addAll(ready);
        return new EpochNodeView(completed, failed, skipped, running, ready, all, loops);
    }

    private record EpochNodeView(
            Set<String> completed,
            Set<String> failed,
            Set<String> skipped,
            Set<String> running,
            Set<String> ready,
            Set<String> all,
            Map<String, Object> loops) {

        boolean contains(String stepId, String stepAlias) {
            return (stepId != null && all.contains(stepId))
                    || (stepAlias != null && all.contains(stepAlias));
        }

        RunStatus statusFor(String stepId, String stepAlias, RunStatus fallback) {
            if (matches(completed, stepId, stepAlias)) return RunStatus.COMPLETED;
            if (matches(failed, stepId, stepAlias)) return RunStatus.FAILED;
            if (matches(skipped, stepId, stepAlias)) return RunStatus.SKIPPED;
            if (matches(running, stepId, stepAlias)) return RunStatus.RUNNING;
            if (matches(ready, stepId, stepAlias)) return RunStatus.PENDING;
            return fallback;
        }

        Map<String, Integer> statusCountsFor(String stepId, String stepAlias) {
            return Map.of(
                    "COMPLETED", matches(completed, stepId, stepAlias) ? 1 : 0,
                    "FAILED", matches(failed, stepId, stepAlias) ? 1 : 0,
                    "SKIPPED", matches(skipped, stepId, stepAlias) ? 1 : 0,
                    "RUNNING", matches(running, stepId, stepAlias) ? 1 : 0,
                    "PENDING", matches(ready, stepId, stepAlias) ? 1 : 0);
        }

        RunStatus edgeStatus(WorkflowRunState.EdgeState edge) {
            if (skipped.contains(edge.to())) return RunStatus.SKIPPED;
            if (failed.contains(edge.to())) return RunStatus.FAILED;
            if (completed.contains(edge.from())) return RunStatus.COMPLETED;
            if (running.contains(edge.from()) || running.contains(edge.to())) return RunStatus.RUNNING;
            return RunStatus.PENDING;
        }

        private static boolean matches(Set<String> values, String stepId, String stepAlias) {
            return (stepId != null && values.contains(stepId))
                    || (stepAlias != null && values.contains(stepAlias));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object scrubMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (looksSensitive(key)) {
                    result.put(key, REDACTED);
                } else {
                    result.put(key, scrubMap(e.getValue()));
                }
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(scrubMap(item));
            return out;
        }
        return value;
    }

    static boolean looksSensitive(String key) {
        if (key == null || key.isEmpty()) return false;
        String[] parts = key.split("(?<=[a-z])(?=[A-Z])|[^A-Za-z0-9]+");
        for (String part : parts) {
            if (CREDENTIAL_KEY_TOKENS.contains(part.toLowerCase(Locale.ROOT))) return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        for (String phrase : CREDENTIAL_PHRASES) {
            if (normalized.contains(phrase)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAggregatedSteps(String runIdPublic, Map<String, Object> snapshotSoFar, Integer epochFilter, String snapshotEpochKey) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (epochFilter != null) {
            List<Map<String, Object>> all = List.of();
            try {
                var perEpoch = stepAggregationService.getAggregatedSteps(runIdPublic, epochFilter);
                List<Map<String, Object>> converted = perEpoch.isPresent()
                        ? stepAggregationService.toResponseList(perEpoch.get()) : List.of();
                all = converted != null ? converted : List.of();
                result.put("all", all);
            } catch (Exception e) {
                log.warn("[ShowcaseSnapshot] aggregatedSteps(epoch={}) failed for {}: {}", epochFilter, runIdPublic, e.getMessage());
                result.put("all", List.of());
            }
            Map<String, Object> byEpoch = new LinkedHashMap<>();
            result.put("byEpoch", byEpoch);
            byEpoch.put(snapshotEpochKey, result.get("all"));
            return result;
        }

        try {
            var allOpt = stepAggregationService.getAggregatedSteps(runIdPublic);
            if (allOpt.isPresent()) {
                result.put("all", stepAggregationService.toResponseList(allOpt.get()));
            } else {
                result.put("all", List.of());
            }
        } catch (Exception e) {
            log.warn("[ShowcaseSnapshot] aggregatedSteps(all) failed for {}: {}", runIdPublic, e.getMessage());
            result.put("all", List.of());
        }

        Map<String, Object> byEpoch = new LinkedHashMap<>();
        Map<String, Object> runState = (Map<String, Object>) snapshotSoFar.getOrDefault("runState", Map.of());
        Object timestamps = runState.get("epochTimestamps");
        Set<Integer> epochs = new TreeSet<>();
        if (timestamps instanceof List<?> list) {
            for (Object row : list) {
                if (row instanceof Map<?, ?> m) {
                    Object ep = m.get("epoch");
                    if (ep instanceof Number n) epochs.add(n.intValue());
                } else {
                    try {
                        var method = row.getClass().getMethod("epoch");
                        Object ep = method.invoke(row);
                        if (ep instanceof Number n) epochs.add(n.intValue());
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (epochs.isEmpty()) epochs.add(0);

        for (int epoch : epochs) {
            try {
                var perEpoch = stepAggregationService.getAggregatedSteps(runIdPublic, epoch);
                if (perEpoch.isPresent()) {
                    byEpoch.put(String.valueOf(epoch), stepAggregationService.toResponseList(perEpoch.get()));
                }
            } catch (Exception e) {
                log.debug("[ShowcaseSnapshot] aggregatedSteps(epoch={}) failed for {}: {}",
                        epoch, runIdPublic, e.getMessage());
            }
        }
        result.put("byEpoch", byEpoch);
        return result;
    }

    /**
     * Per-epoch nodeCounts + edgeCounts via {@code WorkflowEpochService}.
     * Mirrors the auth'd {@code /epochs/{epoch}/state} response so the
     * marketplace reader can surface real edge counts (the previous reader
     * synthesised counts from steps and returned an empty edgeCounts map).
     */
    private Map<String, Object> buildEpochStates(String runIdPublic, Integer epochFilter, String snapshotEpochKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<Integer> epochs;
        if (epochFilter != null) {
            epochs = Set.of(epochFilter);
        } else {
            epochs = discoverEpochsForInterface(runIdPublic);
            if (epochs.isEmpty()) epochs.add(0);
        }
        for (int epoch : epochs) {
            try {
                Map<String, Object> es = workflowEpochService.getEpochState(runIdPublic, epoch);
                if (es != null && !es.isEmpty()) {
                    String key = (snapshotEpochKey != null) ? snapshotEpochKey : String.valueOf(epoch);
                    result.put(key, es);
                }
            } catch (Exception e) {
                log.debug("[ShowcaseSnapshot] epochState({}) failed: {}", epoch, e.getMessage());
            }
        }
        return result;
    }

    private Map<String, Object> buildEpochSignals(String runIdPublic, Integer epochFilter, String snapshotEpochKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            // Probe epochs 0..maxKnownEpoch - cheap because the per-epoch query
            // is indexed on (run_id, epoch) and returns 0 rows for unused epochs.
            int maxEpoch = workflowEpochService.listEpochTimestamps(runIdPublic).stream()
                    .map(t -> {
                        try {
                            return (int) t.getClass().getMethod("epoch").invoke(t);
                        } catch (Exception e) {
                            return 0;
                        }
                    })
                    .max(Integer::compareTo)
                    .orElse(0);
            int startEpoch = epochFilter != null ? epochFilter : 0;
            int endEpoch = epochFilter != null ? epochFilter : maxEpoch;
            for (int epoch = startEpoch; epoch <= endEpoch; epoch++) {
                List<SignalWaitEntity> signals = signalWaitRepository.findActiveByRunIdAndEpoch(runIdPublic, epoch);
                if (signals.isEmpty()) continue;
                List<Map<String, Object>> entries = new ArrayList<>();
                for (SignalWaitEntity s : signals) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("nodeId", s.getNodeId());
                    map.put("signalType", s.getSignalType().name());
                    map.put("status", s.getStatus().name());
                    map.put("itemId", s.getItemId());
                    if (s.getCreatedAt() != null) map.put("createdAt", s.getCreatedAt().toString());
                    if (s.getExpiresAt() != null) map.put("expiresAt", s.getExpiresAt().toString());
                    entries.add(map);
                }
                String key = (snapshotEpochKey != null) ? snapshotEpochKey : String.valueOf(epoch);
                result.put(key, entries);
            }
        } catch (Exception e) {
            log.warn("[ShowcaseSnapshot] epochSignals capture failed for {}: {}", runIdPublic, e.getMessage());
        }
        return result;
    }

    /**
     * Per-interface page-size cap for render snapshots. Big enough to cover
     * normal showcase runs (split nodes typically yield &lt; 100 items at the
     * preview surface) without blowing up the JSONB. The reader paginates
     * over this fixed buffer.
     */
    private static final int RENDER_CAP = 200;

    private Map<String, Object> buildInterfaceRenders(WorkflowRunEntity run, String runIdPublic, String tenantId,
                                                       String organizationId, Integer epochFilter, String snapshotEpochKey) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<InterfaceSnapshotDto> snapshots;
        try {
            snapshots = interfaceClient.getSnapshotsForRun(run.getId(), tenantId, organizationId);
        } catch (Exception e) {
            log.warn("[ShowcaseSnapshot] failed to list interface snapshots for run {}: {}",
                    runIdPublic, e.getMessage());
            return result;
        }
        if (snapshots == null || snapshots.isEmpty()) return result;

        for (InterfaceSnapshotDto snap : snapshots) {
            if (snap.getInterfaceId() == null) continue;
            UUID interfaceId = snap.getInterfaceId();
            Map<String, Object> entry = new LinkedHashMap<>();

            Map<String, Object> templates = new LinkedHashMap<>();
            templates.put("html", snap.getHtmlTemplate());
            templates.put("css", snap.getCssTemplate());
            templates.put("js", snap.getJsTemplate());
            templates.put("variableMappings", snap.getVariableMappings());
            templates.put("actionMappings", snap.getActionMappings());
            entry.put("templates", templates);

            Set<Integer> epochs;
            if (epochFilter != null) {
                epochs = Set.of(epochFilter);
                try {
                    InterfaceRenderService.InterfaceRenderResult filtered =
                            interfaceRenderService.render(interfaceId, runIdPublic, tenantId, 0, RENDER_CAP, epochFilter);
                    Map<String, Object> defaultRender = renderResultToMap(filtered);
                    renumberEpochInItems(defaultRender, RENUMBERED_EPOCH);
                    entry.put("defaultRender", defaultRender);
                } catch (Exception e) {
                    log.debug("[ShowcaseSnapshot] epoch-filtered default-render failed for iface={} epoch={}: {}",
                            interfaceId, epochFilter, e.getMessage());
                }
            } else {
                epochs = discoverEpochsForInterface(runIdPublic);
                try {
                    InterfaceRenderService.InterfaceRenderResult union =
                            interfaceRenderService.render(interfaceId, runIdPublic, tenantId, 0, RENDER_CAP, null);
                    entry.put("defaultRender", renderResultToMap(union));
                    Object itemsObj = renderResultToMap(union).get("items");
                    if (itemsObj instanceof List<?> items) {
                        for (Object item : items) {
                            if (item instanceof Map<?, ?> m) {
                                Object ep = m.get("epoch");
                                if (ep instanceof Number n) epochs.add(n.intValue());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("[ShowcaseSnapshot] default-render failed for iface={} run={}: {}",
                            interfaceId, runIdPublic, e.getMessage());
                }
            }

            Map<String, Object> byEpoch = new LinkedHashMap<>();
            for (int epoch : epochs) {
                try {
                    InterfaceRenderService.InterfaceRenderResult perEpoch =
                            interfaceRenderService.render(interfaceId, runIdPublic, tenantId, 0, RENDER_CAP, epoch);
                    String key = (snapshotEpochKey != null) ? snapshotEpochKey : String.valueOf(epoch);
                    Map<String, Object> rendered = renderResultToMap(perEpoch);
                    if (snapshotEpochKey != null) {
                        renumberEpochInItems(rendered, RENUMBERED_EPOCH);
                    }
                    byEpoch.put(key, rendered);
                } catch (Exception e) {
                    log.debug("[ShowcaseSnapshot] per-epoch render failed iface={} epoch={}: {}",
                            interfaceId, epoch, e.getMessage());
                }
            }
            entry.put("byEpoch", byEpoch);

            result.put(interfaceId.toString(), entry);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void renumberEpochInItems(Map<String, Object> renderMap, int targetEpoch) {
        Object itemsObj = renderMap.get("items");
        if (!(itemsObj instanceof List<?> items)) return;
        List<Map<String, Object>> renumbered = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> copy = new LinkedHashMap<>((Map<String, Object>) m);
                copy.put("epoch", targetEpoch);
                renumbered.add(copy);
            }
        }
        renderMap.put("items", renumbered);
    }

    private Set<Integer> discoverEpochsForInterface(String runIdPublic) {
        Set<Integer> epochs = new TreeSet<>();
        try {
            workflowEpochService.listEpochTimestamps(runIdPublic).forEach(t -> {
                try {
                    Object ep = t.getClass().getMethod("epoch").invoke(t);
                    if (ep instanceof Number n) epochs.add(n.intValue());
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        return epochs;
    }

    private static Map<String, Object> renderResultToMap(InterfaceRenderService.InterfaceRenderResult r) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("htmlTemplate", r.htmlTemplate());
        body.put("cssTemplate", r.cssTemplate());
        body.put("jsTemplate", r.jsTemplate());
        // The format travels with the templates: the showcase preview must be shaped like the
        // interface, not letterboxed into a default 1280x800 box.
        body.put("format", r.format());
        body.put("actionMappings", r.actionMappings());
        List<Map<String, Object>> items = new ArrayList<>();
        for (InterfaceRenderService.ItemRenderData item : r.items()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("epoch", item.epoch());
            m.put("itemIndex", item.itemIndex());
            m.put("spawn", item.spawn());
            m.put("data", item.data());
            m.put("triggerData", item.triggerData());
            items.add(m);
        }
        body.put("items", items);
        InterfaceRenderService.PaginationInfo p = r.pagination();
        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", p.page());
        pagination.put("size", p.size());
        pagination.put("totalItems", p.totalItems());
        pagination.put("totalPages", p.totalPages());
        body.put("pagination", pagination);
        // Surface OrchestratorLimitsConfig truncation so showcase snapshots carry the same
        // "Showing N of M" signal the runtime render path exposes. Otherwise an admin lowering
        // maxItemsPerRender below RENDER_CAP would silently shrink published snapshots with no
        // indication on the marketplace preview.
        InterfaceRenderService.RenderTruncation t = r.truncation();
        if (t != null) {
            Map<String, Object> truncation = new LinkedHashMap<>();
            truncation.put("total", t.total());
            truncation.put("rendered", t.rendered());
            truncation.put("reason", t.reason());
            truncation.put("payloadBytes", t.payloadBytes());
            body.put("truncation", truncation);
        }
        return body;
    }
}
