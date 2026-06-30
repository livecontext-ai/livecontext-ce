package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.WorkflowStepDataEntity;
import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.domain.execution.StatusCounts;
import com.apimarketplace.orchestrator.domain.workflow.*;
import com.apimarketplace.orchestrator.services.resume.WorkflowRunState;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Handles building step state objects for state reconstruction.
 * Single Responsibility: Step state building operations.
 */
public class StepStateBuilder {

    private static final Logger logger = LoggerFactory.getLogger(StepStateBuilder.class);

    private final StateReconstructorHelper helper;
    private final StatusCountsBuilder statusCountsBuilder;

    public StepStateBuilder(StateReconstructorHelper helper, StatusCountsBuilder statusCountsBuilder) {
        this.helper = helper;
        this.statusCountsBuilder = statusCountsBuilder;
    }

    /**
     * Builds the state objects for all steps.
     */
    public List<WorkflowRunState.StepState> buildStepStates(
            WorkflowPlan plan,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts) {
        return buildStepStates(plan, graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, null, Map.of());
    }

    /**
     * Builds the state objects for all steps, with awaiting signal awareness.
     */
    public List<WorkflowRunState.StepState> buildStepStates(
            WorkflowPlan plan,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            Set<String> awaitingSignalNodeIds) {
        return buildStepStates(plan, graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, awaitingSignalNodeIds, Map.of());
    }

    /**
     * Builds the state objects for all steps, with awaiting signal awareness and counts.
     */
    public List<WorkflowRunState.StepState> buildStepStates(
            WorkflowPlan plan,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            Set<String> awaitingSignalNodeIds,
            Map<String, Integer> awaitingSignalCounts) {
        return buildStepStates(plan, graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, awaitingSignalNodeIds, awaitingSignalCounts, Map.of(), null);
    }

    /**
     * Builds the state objects for all steps, with awaiting signal awareness, counts, NodeCounts for timing,
     * and running node awareness (for SBS mode where nodes are actively executing).
     *
     * <p>Backwards-compatible overload: defaults to FULL output loading.
     */
    public List<WorkflowRunState.StepState> buildStepStates(
            WorkflowPlan plan,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            Set<String> awaitingSignalNodeIds,
            Map<String, Integer> awaitingSignalCounts,
            Map<String, StateSnapshot.NodeCounts> nodeCounts,
            Set<String> runningNodeIds) {
        return buildStepStates(plan, graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, awaitingSignalNodeIds, awaitingSignalCounts,
            nodeCounts, runningNodeIds, StateReconstructor.OutputLoadMode.FULL);
    }

    /**
     * Overload with explicit OutputLoadMode. AGENT_AND_INTERFACE_ONLY skips
     * storage round-trips for mcp/trigger/core/table aliases - only agent: and
     * interface: aliases dereference their output blob.
     */
    public List<WorkflowRunState.StepState> buildStepStates(
            WorkflowPlan plan,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            Set<String> awaitingSignalNodeIds,
            Map<String, Integer> awaitingSignalCounts,
            Map<String, StateSnapshot.NodeCounts> nodeCounts,
            Set<String> runningNodeIds,
            StateReconstructor.OutputLoadMode mode) {
        return buildStepStates(plan, graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, awaitingSignalNodeIds, awaitingSignalCounts,
            nodeCounts, runningNodeIds, mode, Set.of());
    }

    /**
     * Full overload with current-pass partial-failure awareness.
     *
     * <p>{@code partialFailedNodeIds} is the flat
     * {@link StateSnapshot#getPartialFailedNodeIds()} view: nodes that COMPLETED in the
     * current pass with per-item failures (split continue-anyway). It is the ONLY
     * evidence allowed to demote a current-epoch COMPLETED to PARTIAL_SUCCESS -
     * accumulated NodeCounts keep stale {@code failed} entries after a rerun fixed a
     * previously-FAILED node, and using them resurrected PARTIAL_SUCCESS forever (see
     * {@link #deriveStatusFromCounts}).
     */
    public List<WorkflowRunState.StepState> buildStepStates(
            WorkflowPlan plan,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            Set<String> awaitingSignalNodeIds,
            Map<String, Integer> awaitingSignalCounts,
            Map<String, StateSnapshot.NodeCounts> nodeCounts,
            Set<String> runningNodeIds,
            StateReconstructor.OutputLoadMode mode,
            Set<String> partialFailedNodeIds) {

        List<WorkflowRunState.StepState> states = new ArrayList<>();
        Set<String> processedAliases = new HashSet<>();

        logger.info("[buildStepStates] === Building step states === mode={}", mode);
        logger.info("[buildStepStates] completedStepIds: {}", completedStepIds);
        logger.info("[buildStepStates] failedStepIds: {}", failedStepIds);
        logger.info("[buildStepStates] skippedStepIds: {}", skippedStepIds);
        logger.info("[buildStepStates] readySteps: {}", readySteps);

        // Output blob loading is skipped for non-rendered aliases when mode is API.
        // Agent and interface always load (panel/modal need them at refresh).
        boolean loadNonRenderedOutputs = (mode == StateReconstructor.OutputLoadMode.FULL);

        // Process each node type using helper methods
        processStepNodes(plan.getMcps(), graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, states, processedAliases, loadNonRenderedOutputs);

        processTriggerNodes(plan.getTriggers(), stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, states, processedAliases, loadNonRenderedOutputs);

        processAgentNodes(plan.getAgents(), graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, states, processedAliases);

        processCores(plan.getCores(), graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, states, processedAliases, loadNonRenderedOutputs);

        processTableNodes(plan.getTables(), graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, states, processedAliases, loadNonRenderedOutputs);

        processInterfaceNodes(plan.getInterfaces(), graph, stepsByAlias, completedStepIds, failedStepIds,
            skippedStepIds, readySteps, stepStatusCounts, states, processedAliases);

        // Post-process: fix awaiting signal nodes whose status was incorrectly derived
        // as COMPLETED from accumulated NodeCounts. The node is currently awaiting a signal
        // (user approval, timer, webhook) in the active epoch - that's its current state.
        // Also inject awaitingSignal count into statusCounts for the frontend badge.
        if (awaitingSignalNodeIds != null && !awaitingSignalNodeIds.isEmpty()) {
            for (int i = 0; i < states.size(); i++) {
                WorkflowRunState.StepState s = states.get(i);
                if (awaitingSignalNodeIds.contains(s.stepId()) && s.status() != RunStatus.FAILED) {
                    // Inject awaitingSignal count into statusCounts
                    Map<String, Integer> enrichedCounts = s.statusCounts() != null
                        ? new LinkedHashMap<>(s.statusCounts()) : new LinkedHashMap<>();
                    int awaitCount = awaitingSignalCounts != null
                        ? awaitingSignalCounts.getOrDefault(s.stepId(), 1) : 1;
                    enrichedCounts.put("awaitingSignal", awaitCount);

                    states.set(i, new WorkflowRunState.StepState(
                        s.stepId(), s.stepAlias(), s.toolId(),
                        RunStatus.AWAITING_SIGNAL,
                        s.inputData(), s.output(), s.itemIndex(), s.iteration(),
                        s.httpStatus(), s.errorMessage(), s.startTime(), s.endTime(),
                        s.executionTimeMs(), s.dependencies(), s.canExecute(), enrichedCounts,
                        s.totalExecutionTimeMs()));
                }
            }
        }

        // Post-process: surface CURRENT-pass partial failures (split continue-anyway).
        // deriveStatusFromCounts no longer demotes COMPLETED via accumulated counts
        // (stale failed entries survive reruns), so the per-epoch partialFailed marker
        // is the single source of truth for the PARTIAL_SUCCESS display.
        if (partialFailedNodeIds != null && !partialFailedNodeIds.isEmpty()) {
            for (int i = 0; i < states.size(); i++) {
                WorkflowRunState.StepState s = states.get(i);
                if (partialFailedNodeIds.contains(s.stepId()) && s.status() == RunStatus.COMPLETED) {
                    states.set(i, new WorkflowRunState.StepState(
                        s.stepId(), s.stepAlias(), s.toolId(),
                        RunStatus.PARTIAL_SUCCESS,
                        s.inputData(), s.output(), s.itemIndex(), s.iteration(),
                        s.httpStatus(), s.errorMessage(), s.startTime(), s.endTime(),
                        s.executionTimeMs(), s.dependencies(), s.canExecute(), s.statusCounts(),
                        s.totalExecutionTimeMs()));
                }
            }
        }

        // Post-process: fix running nodes whose status was incorrectly derived
        // as PENDING. In SBS mode, claimNodeForExecution() adds the node to
        // runningNodeIds in StateSnapshot, but the per-node status derivation
        // (determineStepStatus + deriveStatusFromCounts) doesn't check running sets.
        // Only override PENDING → RUNNING (don't override AWAITING_SIGNAL, COMPLETED, etc.).
        if (runningNodeIds != null && !runningNodeIds.isEmpty()) {
            for (int i = 0; i < states.size(); i++) {
                WorkflowRunState.StepState s = states.get(i);
                if (runningNodeIds.contains(s.stepId()) && s.status() == RunStatus.PENDING) {
                    states.set(i, new WorkflowRunState.StepState(
                        s.stepId(), s.stepAlias(), s.toolId(),
                        RunStatus.RUNNING,
                        s.inputData(), s.output(), s.itemIndex(), s.iteration(),
                        s.httpStatus(), s.errorMessage(), s.startTime(), s.endTime(),
                        s.executionTimeMs(), s.dependencies(), s.canExecute(), s.statusCounts(),
                        s.totalExecutionTimeMs()));
                }
            }
        }

        // Post-process: enrich with totalExecutionTimeMs from NodeCounts
        if (nodeCounts != null && !nodeCounts.isEmpty()) {
            for (int i = 0; i < states.size(); i++) {
                WorkflowRunState.StepState s = states.get(i);
                StateSnapshot.NodeCounts nc = nodeCounts.get(s.stepId());
                if (nc != null && nc.totalExecutionTimeMs() > 0) {
                    states.set(i, new WorkflowRunState.StepState(
                        s.stepId(), s.stepAlias(), s.toolId(), s.status(),
                        s.inputData(), s.output(), s.itemIndex(), s.iteration(),
                        s.httpStatus(), s.errorMessage(), s.startTime(), s.endTime(),
                        s.executionTimeMs(), s.dependencies(), s.canExecute(), s.statusCounts(),
                        nc.totalExecutionTimeMs()));
                }
            }
        }

        logger.info("[buildStepStates] === Final step states ({} total) ===", states.size());
        for (WorkflowRunState.StepState state : states) {
            logger.info("[buildStepStates]   {} | status={} | canExecute={}",
                state.stepId(), state.status(), state.canExecute());
        }

        return states;
    }

    /**
     * Derives the RunStatus from statusCounts map.
     *
     * <p>IMPORTANT: The defaultStatus reflects the CURRENT EPOCH state (from completedNodeIds, etc.).
     * Counts are ACCUMULATED across epochs for display purposes.
     *
     * <p>Rules:
     * <ul>
     *   <li>READY (isReady=true): Always respect - node is explicitly ready for execution,
     *       even if accumulated counts show completed/failed from previous epochs or reruns</li>
     *   <li>PENDING: Respect current epoch state, unless parallel epoch counts show progress</li>
     *   <li>RUNNING: Always override - node is actively running</li>
     *   <li>For terminal states (COMPLETED/FAILED/SKIPPED): Use counts to determine final status</li>
     * </ul>
     *
     * @param counts       The statusCounts map (accumulated across epochs, can be null)
     * @param defaultStatus The current epoch status (PENDING, COMPLETED, etc.)
     * @param isReady       Whether the node is in the readySteps set (explicitly ready for execution)
     * @return The derived RunStatus
     */
    private RunStatus deriveStatusFromCounts(Map<String, Integer> counts, RunStatus defaultStatus, boolean isReady) {
        // If node is explicitly in readySteps, respect that regardless of accumulated counts.
        // After a rerun, NodeCounts still has completed=1 from previous execution, but the
        // EpochState was reset and the node is READY. Don't let stale counts override.
        if (isReady) {
            return defaultStatus;
        }

        // AWAITING_SIGNAL means the node is currently waiting for a signal (user approval,
        // timer, webhook) in an active epoch. Don't let accumulated counts from prior
        // epochs override this - the current state takes priority.
        if (defaultStatus == RunStatus.AWAITING_SIGNAL) {
            return defaultStatus;
        }

        // PENDING means the node is NOT in any terminal flat view (completed/failed/skipped)
        // and NOT in readySteps (isReady=false above). It truly has no state in the current
        // active epoch(s). Don't let historical NodeCounts override - the node hasn't been
        // executed in this epoch. Return PENDING so the UI shows the correct state after
        // a new trigger fire (new epoch with fresh EpochState).
        // The historical counts are still available in statusCounts for badge display.
        if (defaultStatus == RunStatus.PENDING) {
            return defaultStatus;
        }

        // SKIPPED in the current epoch means a branching node deliberately routed around
        // this node in the CURRENT pass. NodeCounts accumulate across epochs and are never
        // reset, so after a rerun that switches a decision branch the deactivated branch
        // still carries completed>0 from the pre-rerun pass - that history must not
        // resurrect COMPLETED over the current epoch's SKIPPED (same current-state-wins
        // rule as the READY / AWAITING_SIGNAL / PENDING guards above).
        if (defaultStatus == RunStatus.SKIPPED) {
            return defaultStatus;
        }

        // COMPLETED in the current epoch is also current-state-wins. Accumulated counts
        // keep a stale failed entry after a rerun fixed a previously-FAILED node - letting
        // counts decide demoted the fresh COMPLETED to PARTIAL_SUCCESS forever. Genuine
        // current-pass partial failures (split continue-anyway) are surfaced by the
        // partialFailedNodeIds post-process in buildStepStates, NOT by these counts.
        // Only the RUNNING override survives: a parallel epoch may still be executing
        // this node and the live activity beats the terminal state of the other epoch.
        if (defaultStatus == RunStatus.COMPLETED) {
            int runningNow = counts != null ? counts.getOrDefault("running", 0) : 0;
            return runningNow > 0 ? RunStatus.RUNNING : defaultStatus;
        }

        if (counts == null || counts.isEmpty()) {
            return defaultStatus;
        }

        int running = counts.getOrDefault("running", 0);
        int success = counts.getOrDefault("completed", 0);
        int failure = counts.getOrDefault("failed", 0);
        int skipped = counts.getOrDefault("skipped", 0);
        int total = counts.getOrDefault("total", 0);

        // No items processed yet
        if (total == 0) {
            return defaultStatus;
        }

        // Items are currently being processed
        if (running > 0) {
            return RunStatus.RUNNING;
        }

        // All items skipped (no success, no failure) - node was on a non-taken branch
        if (skipped > 0 && success == 0 && failure == 0) {
            return RunStatus.SKIPPED;
        }

        // Has failures
        if (failure > 0) {
            // Mixed results: some success, some failure
            if (success > 0) {
                return RunStatus.PARTIAL_SUCCESS;
            }
            // All failures (with or without skipped)
            return RunStatus.FAILED;
        }

        // Has success (no failures)
        if (success > 0) {
            return RunStatus.COMPLETED;
        }

        return defaultStatus;
    }

    /**
     * Process step nodes.
     */
    private void processStepNodes(
            List<Step> steps,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            List<WorkflowRunState.StepState> states,
            Set<String> processedAliases,
            boolean loadOutput) {

        for (Step step : steps) {
            String stepId = step.getNormalizedKey();
            String rawLabel = step.label() != null ? step.label() : stepId;
            String alias = LabelNormalizer.normalizeLabel(rawLabel);
            if (alias == null) alias = rawLabel;

            if (processedAliases.contains(alias)) continue;
            processedAliases.add(alias);

            List<WorkflowStepDataEntity> entities = stepsByAlias.getOrDefault(alias, List.of());
            Set<String> dependencies = graph.getDependencies(stepId);
            RunStatus status = helper.determineStepStatus(stepId, completedStepIds, failedStepIds, skippedStepIds, readySteps);
            boolean canExecute = readySteps.contains(stepId) || readySteps.contains(alias);
            Map<String, Integer> counts = statusCountsBuilder.getStatusCountsMap(stepId, alias, stepStatusCounts);

            logger.info("[processStepNodes] stepId={}, alias={}, hasEntities={}, status={}, canExecute={}, inReadySteps={}",
                stepId, alias, !entities.isEmpty(), status, canExecute, readySteps.contains(stepId));

            // Resolve toolId: prefer graphNodeId from plan (React Flow node ID), fallback to step.id()
            String planToolId = step.graphNodeId() != null ? step.graphNodeId() : step.id();

            if (entities.isEmpty()) {
                // Derive status from statusCounts if available (for batch mode accuracy)
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                states.add(new WorkflowRunState.StepState(
                    stepId, alias, planToolId, finalStatus,
                    null, null, null, null, null, null,
                    null, null, 0, dependencies, canExecute, counts
                ));
            } else {
                WorkflowStepDataEntity entity = entities.get(entities.size() - 1);
                // Skip storage round-trip when caller doesn't need the output blob
                // (REST /state path for mcp/trigger/core/table aliases).
                Map<String, Object> output = loadOutput ? helper.loadStepOutput(entity) : null;
                // Use StateSnapshot-derived status (not stale entity.getStatus()) as fallback
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                // Prefer graphNodeId from plan, then entity toolId, then step.id()
                String resolvedToolId = step.graphNodeId() != null ? step.graphNodeId() : entity.getToolId();

                states.add(new WorkflowRunState.StepState(
                    stepId, alias, resolvedToolId,
                    finalStatus,
                    entity.getInputData(), output,
                    entity.getItemIndex(), entity.getIteration(),
                    entity.getHttpStatus(), entity.getErrorMessage(),
                    entity.getStartTime(), entity.getEndTime(),
                    helper.calculateExecutionTime(entity),
                    dependencies, canExecute, counts
                ));
            }
        }
    }

    /**
     * Process trigger nodes.
     */
    private void processTriggerNodes(
            List<Trigger> triggers,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            List<WorkflowRunState.StepState> states,
            Set<String> processedAliases,
            boolean loadOutput) {

        for (Trigger trigger : triggers) {
            String triggerId = trigger.getNormalizedKey();
            String rawLabel = trigger.label();
            String alias = LabelNormalizer.normalizeLabel(rawLabel);
            if (alias == null) alias = rawLabel;

            if (processedAliases.contains(alias)) continue;
            processedAliases.add(alias);

            List<WorkflowStepDataEntity> entities = stepsByAlias.getOrDefault(alias, List.of());
            RunStatus status = helper.determineStepStatus(triggerId, completedStepIds, failedStepIds, skippedStepIds, readySteps);
            boolean canExecute = readySteps.contains(triggerId) || readySteps.contains(alias);
            Map<String, Integer> counts = statusCountsBuilder.getStatusCountsMap(triggerId, alias, stepStatusCounts);

            if (entities.isEmpty()) {
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                states.add(new WorkflowRunState.StepState(
                    triggerId, alias, trigger.id(), finalStatus,
                    null, null, null, null, null, null,
                    null, null, 0, Set.of(), canExecute, counts
                ));
            } else {
                WorkflowStepDataEntity entity = entities.get(entities.size() - 1);
                // Skip storage round-trip when caller doesn't need the output blob
                // (REST /state path for trigger aliases).
                Map<String, Object> output = loadOutput ? helper.loadStepOutput(entity) : null;
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);

                states.add(new WorkflowRunState.StepState(
                    triggerId, alias, entity.getToolId(),
                    finalStatus,
                    entity.getInputData(), output,
                    entity.getItemIndex(), entity.getIteration(),
                    entity.getHttpStatus(), entity.getErrorMessage(),
                    entity.getStartTime(), entity.getEndTime(),
                    helper.calculateExecutionTime(entity),
                    Set.of(), canExecute, counts
                ));
            }
        }
    }

    /**
     * Process agent nodes.
     */
    private void processAgentNodes(
            List<Agent> agents,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            List<WorkflowRunState.StepState> states,
            Set<String> processedAliases) {

        for (Agent agent : agents) {
            String agentId = agent.getNormalizedKey();
            String rawLabel = agent.label() != null ? agent.label() : agentId;
            String alias = LabelNormalizer.normalizeLabel(rawLabel);
            if (alias == null) alias = rawLabel;

            if (processedAliases.contains(alias)) continue;
            processedAliases.add(alias);

            List<WorkflowStepDataEntity> entities = stepsByAlias.getOrDefault(alias, List.of());
            Set<String> dependencies = graph.getDependencies(agentId);
            RunStatus status = helper.determineStepStatus(agentId, completedStepIds, failedStepIds, skippedStepIds, readySteps);
            boolean canExecute = readySteps.contains(agentId) || readySteps.contains(alias);
            Map<String, Integer> counts = statusCountsBuilder.getStatusCountsMap(agentId, alias, stepStatusCounts);

            String agentPlanToolId = agent.graphNodeId() != null ? agent.graphNodeId() : agent.id();

            if (entities.isEmpty()) {
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                states.add(new WorkflowRunState.StepState(
                    agentId, alias, agentPlanToolId, finalStatus,
                    null, null, null, null, null, null,
                    null, null, 0, dependencies, canExecute, counts
                ));
            } else {
                WorkflowStepDataEntity entity = entities.get(entities.size() - 1);
                Map<String, Object> output = helper.loadStepOutput(entity);
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                String resolvedToolId = agent.graphNodeId() != null ? agent.graphNodeId() : entity.getToolId();

                states.add(new WorkflowRunState.StepState(
                    agentId, alias, resolvedToolId,
                    finalStatus,
                    entity.getInputData(), output,
                    entity.getItemIndex(), entity.getIteration(),
                    entity.getHttpStatus(), entity.getErrorMessage(),
                    entity.getStartTime(), entity.getEndTime(),
                    helper.calculateExecutionTime(entity),
                    dependencies, canExecute, counts
                ));
            }
        }
    }

    /**
     * Process core nodes (decision, loop, wait, transform, fork, split, aggregate).
     */
    private void processCores(
            List<Core> coreNodes,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            List<WorkflowRunState.StepState> states,
            Set<String> processedAliases,
            boolean loadOutput) {

        for (Core coreNode : coreNodes) {
            String coreId;
            switch (coreNode.type()) {
                case "loop":
                case "split":
                case "decision":
                case "switch":
                case "wait":
                case "transform":
                case "fork":
                case "aggregate":
                case "download_file":
                case "http_request":
                case "exit":
                case "response":
                case "option":
                case "data_input":
                case "approval":
                case "merge":
                case "filter":
                case "sort":
                case "limit":
                case "remove_duplicates":
                case "summarize":
                case "date_time":
                case "crypto_jwt":
                case "xml":
                case "compression":
                case "rss":
                case "convert_to_file":
                case "extract_from_file":
                case "compare_datasets":
                case "sub_workflow":
                case "respond_to_webhook":
                case "send_email":
                case "email_inbox":
                case "code":
                    coreId = LabelNormalizer.coreKey(coreNode.label(), coreNode.id());
                    break;
                default:
                    // Unknown core type - still include it with computed coreId
                    coreId = LabelNormalizer.coreKey(coreNode.label(), coreNode.id());
                    break;
            }

            if (coreId == null) continue;

            String rawLabel = coreNode.label() != null ? coreNode.label() : coreNode.id();
            String alias = LabelNormalizer.normalizeLabel(rawLabel);
            if (alias == null) alias = rawLabel;

            if (processedAliases.contains(alias)) continue;
            processedAliases.add(alias);

            List<WorkflowStepDataEntity> entities = stepsByAlias.getOrDefault(alias, List.of());
            Set<String> dependencies = graph.getDependencies(coreId);
            RunStatus status = helper.determineStepStatus(coreId, completedStepIds, failedStepIds, skippedStepIds, readySteps);
            boolean canExecute = readySteps.contains(coreId) || readySteps.contains(alias);
            Map<String, Integer> counts = statusCountsBuilder.getStatusCountsMap(coreId, alias, stepStatusCounts);

            String corePlanToolId = coreNode.graphNodeId() != null ? coreNode.graphNodeId() : coreNode.id();

            if (entities.isEmpty()) {
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                states.add(new WorkflowRunState.StepState(
                    coreId, alias, corePlanToolId, finalStatus,
                    null, null, null, null, null, null,
                    null, null, 0, dependencies, canExecute, counts
                ));
            } else {
                WorkflowStepDataEntity entity = entities.get(entities.size() - 1);
                // Skip storage round-trip when caller doesn't need the output blob
                // (REST /state path for core aliases).
                Map<String, Object> output = loadOutput ? helper.loadStepOutput(entity) : null;
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                String resolvedToolId = coreNode.graphNodeId() != null ? coreNode.graphNodeId() : entity.getToolId();

                states.add(new WorkflowRunState.StepState(
                    coreId, alias, resolvedToolId,
                    finalStatus,
                    entity.getInputData(), output,
                    entity.getItemIndex(), entity.getIteration(),
                    entity.getHttpStatus(), entity.getErrorMessage(),
                    entity.getStartTime(), entity.getEndTime(),
                    helper.calculateExecutionTime(entity),
                    dependencies, canExecute, counts
                ));
            }
        }
    }

    /**
     * Process table nodes (CRUD operations).
     * Tables use "table:" prefix instead of "mcp:" (Step.getNormalizedKey() returns "mcp:").
     */
    private void processTableNodes(
            List<Step> tables,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            List<WorkflowRunState.StepState> states,
            Set<String> processedAliases,
            boolean loadOutput) {

        if (tables == null) return;

        for (Step table : tables) {
            String rawLabel = table.label() != null ? table.label() : table.id();
            String alias = LabelNormalizer.normalizeLabel(rawLabel);
            if (alias == null) alias = rawLabel;

            // Tables use "table:" prefix, not "mcp:"
            String stepId = "table:" + alias;

            if (processedAliases.contains(alias)) continue;
            processedAliases.add(alias);

            List<WorkflowStepDataEntity> entities = stepsByAlias.getOrDefault(alias, List.of());
            Set<String> dependencies = graph.getDependencies(stepId);
            RunStatus status = helper.determineStepStatus(stepId, completedStepIds, failedStepIds, skippedStepIds, readySteps);
            boolean canExecute = readySteps.contains(stepId) || readySteps.contains(alias);
            Map<String, Integer> counts = statusCountsBuilder.getStatusCountsMap(stepId, alias, stepStatusCounts);

            String tablePlanToolId = table.graphNodeId() != null ? table.graphNodeId() : table.id();
            // Phase H (archi-refoundation 2026-05-04) - INFO → DEBUG: per-/state-call × N tables
            // = ~17 lines per call for typical workflows (ORCH_OPS #1a). Not an operational
            // state transition; status=PENDING + canExecute=false is the normal idle state.
            logger.debug("[processTableNodes] stepId={}, alias={}, hasEntities={}, status={}, canExecute={}",
                stepId, alias, !entities.isEmpty(), status, canExecute);

            if (entities.isEmpty()) {
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                states.add(new WorkflowRunState.StepState(
                    stepId, alias, tablePlanToolId, finalStatus,
                    null, null, null, null, null, null,
                    null, null, 0, dependencies, canExecute, counts
                ));
            } else {
                WorkflowStepDataEntity entity = entities.get(entities.size() - 1);
                // Skip storage round-trip when caller doesn't need the output blob
                // (REST /state path for table aliases).
                Map<String, Object> output = loadOutput ? helper.loadStepOutput(entity) : null;
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                String resolvedToolId = table.graphNodeId() != null ? table.graphNodeId() : entity.getToolId();

                states.add(new WorkflowRunState.StepState(
                    stepId, alias, resolvedToolId,
                    finalStatus,
                    entity.getInputData(), output,
                    entity.getItemIndex(), entity.getIteration(),
                    entity.getHttpStatus(), entity.getErrorMessage(),
                    entity.getStartTime(), entity.getEndTime(),
                    helper.calculateExecutionTime(entity),
                    dependencies, canExecute, counts
                ));
            }
        }
    }

    /**
     * Process interface nodes.
     * Interfaces use "interface:" prefix.
     */
    private void processInterfaceNodes(
            List<InterfaceDef> interfaces,
            ExecutionGraph graph,
            Map<String, List<WorkflowStepDataEntity>> stepsByAlias,
            Set<String> completedStepIds,
            Set<String> failedStepIds,
            Set<String> skippedStepIds,
            Set<String> readySteps,
            Map<String, StatusCounts> stepStatusCounts,
            List<WorkflowRunState.StepState> states,
            Set<String> processedAliases) {

        if (interfaces == null) return;

        for (InterfaceDef iface : interfaces) {
            String stepId = iface.getNormalizedKey(); // "interface:label"
            String rawLabel = iface.label() != null ? iface.label() : iface.id();
            String alias = LabelNormalizer.normalizeLabel(rawLabel);
            if (alias == null) alias = rawLabel;

            if (processedAliases.contains(alias)) continue;
            processedAliases.add(alias);

            List<WorkflowStepDataEntity> entities = stepsByAlias.getOrDefault(alias, List.of());
            Set<String> dependencies = graph.getDependencies(stepId);
            RunStatus status = helper.determineStepStatus(stepId, completedStepIds, failedStepIds, skippedStepIds, readySteps);
            boolean canExecute = readySteps.contains(stepId) || readySteps.contains(alias);
            Map<String, Integer> counts = statusCountsBuilder.getStatusCountsMap(stepId, alias, stepStatusCounts);

            if (entities.isEmpty()) {
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);
                states.add(new WorkflowRunState.StepState(
                    stepId, alias, iface.id(), finalStatus,
                    null, null, null, null, null, null,
                    null, null, 0, dependencies, canExecute, counts
                ));
            } else {
                WorkflowStepDataEntity entity = entities.get(entities.size() - 1);
                Map<String, Object> output = helper.loadStepOutput(entity);
                RunStatus finalStatus = deriveStatusFromCounts(counts, status, canExecute);

                states.add(new WorkflowRunState.StepState(
                    stepId, alias, entity.getToolId(),
                    finalStatus,
                    entity.getInputData(), output,
                    entity.getItemIndex(), entity.getIteration(),
                    entity.getHttpStatus(), entity.getErrorMessage(),
                    entity.getStartTime(), entity.getEndTime(),
                    helper.calculateExecutionTime(entity),
                    dependencies, canExecute, counts
                ));
            }
        }
    }
}
