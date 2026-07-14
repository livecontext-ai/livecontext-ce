package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.execution.v2.cache.ExecutionCacheManager;
import com.apimarketplace.orchestrator.execution.v2.nodes.AgentNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.split.SplitAwareNodeExecutor;
import com.apimarketplace.orchestrator.execution.v2.split.SplitExecutionOptions;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Per-item continuation for split-context signals (approval {@code continuationMode=per_item}).
 *
 * <p>Instead of deferring every successor until the LAST sibling signal resolves
 * (all_items barrier), each resolution immediately WALKS the resolved item through its
 * downstream chain of plain per-item nodes. The barrier does not disappear - it moves
 * to the first cross-item consumer (merge / aggregate / loop / fork / nested split),
 * which still waits for all items, as merge semantics require.
 *
 * <h2>Walk</h2>
 * A walk BFS-visits the static WALK REGION - the subgraph reachable from the signal
 * node's successors through {@link #isWalkable walkable} nodes only - and invokes
 * {@code V2StepByStepService.executeNode(.., SplitExecutionOptions.perItemContinuationWalk())}
 * on each region node, in predecessor-before-successor order. All per-item math is
 * delegated to the split fan-out itself: {@code getRoutedItemIndices} derives the
 * routed items from persisted rows (the approval's per-item {@code selected_branch}),
 * and the continuation options exclude items whose terminal row already landed in a
 * prior walk. A walk therefore executes exactly the newly-resolved item's path -
 * wrong-port or already-persisted nodes no-op with a benign deferred result.
 *
 * <p>Per-item persists run with {@code suppressGlobalMark} (Phase 2.E): rows, counts,
 * billing and WS events land per item, but node-level EpochState stays untouched so
 * the DAG readiness walker keeps the region pending until the seal.
 *
 * <h2>Seal (last resolution, under the caller's per-run resume lock)</h2>
 * {@link #sealRegion} first re-runs a walk pass - in a resolve-all burst every signal
 * is DB-resolved before the first resume runs, and the sealing resume may need to
 * execute several items' paths after {@code persistMissingSplitSignalOutputs}
 * backfilled their approval rows - then, for each region node with at least one
 * per-item row, writes the node-level mark ONCE via the idempotent
 * {@code recordSplitAggregateIfMissing} and materializes the per-item SKIPPED rows
 * for unrouted items (statusCounts parity with the all_items fan-out). Region nodes
 * with ZERO rows (a port no item selected) are deliberately left untouched: the
 * caller's post-seal ready-loop executes them through the normal fan-out, which
 * resolves an empty routed set and marks them SKIPPED with cascade - the exact
 * all_items behavior.
 *
 * <p>The caller (SignalResumeService) holds the per-run distributed resume lock for
 * both entry points, so walks and seals for one run are serialized cross-instance.
 */
@Service
public class PerItemContinuationService {

    private static final Logger logger = LoggerFactory.getLogger(PerItemContinuationService.class);

    private final V2StepByStepService v2StepByStepService;
    private final ExecutionCacheManager executionCacheManager;
    private final NodeSearchService nodeSearchService;
    private final StepCompletionOrchestrator stepCompletionOrchestrator;
    private final WorkflowStepDataRepository stepDataRepository;
    private final SplitAwareNodeExecutor splitAwareNodeExecutor;

    @Autowired
    public PerItemContinuationService(
            @Lazy V2StepByStepService v2StepByStepService,
            ExecutionCacheManager executionCacheManager,
            NodeSearchService nodeSearchService,
            StepCompletionOrchestrator stepCompletionOrchestrator,
            WorkflowStepDataRepository stepDataRepository,
            @Lazy SplitAwareNodeExecutor splitAwareNodeExecutor) {
        this.v2StepByStepService = v2StepByStepService;
        this.executionCacheManager = executionCacheManager;
        this.nodeSearchService = nodeSearchService;
        this.stepCompletionOrchestrator = stepCompletionOrchestrator;
        this.stepDataRepository = stepDataRepository;
        this.splitAwareNodeExecutor = splitAwareNodeExecutor;
    }

    /**
     * True when this resolved signal opted into per-item continuation AND the shape is
     * supported: split context (the caller already verified {@code isSplitContextNode}),
     * a plain (non-nested) sub-item id, and a per_item continuationMode persisted in
     * the signal's config at yield time.
     */
    public boolean isPerItemContinuation(SignalWaitEntity resolvedSignal) {
        if (resolvedSignal == null) {
            return false;
        }
        Map<String, Object> config = resolvedSignal.getSignalConfig();
        if (config == null
                || !Core.ApprovalConfig.CONTINUATION_PER_ITEM.equals(
                    Core.ApprovalConfig.normalizeContinuationMode(
                        config.get("continuationMode") instanceof String s ? s : null))) {
            return false;
        }
        // Scoped sub-item ids (any dotted id, e.g. "7.3") keep the all_items barrier.
        // Top-level split sub-items use single-segment ids ("3"); a dot means an outer
        // scope (nested split, or a multi-item outer workflow) where the durable
        // item_index column cannot distinguish outer/inner indices - the row-based
        // per-item exclusion would collide across outer scopes and silently drop
        // sibling items. Fail safe: dotted id -> pre-feature barrier semantics.
        String itemId = resolvedSignal.getItemId();
        if (itemId != null && itemId.indexOf('.') >= 0) {
            return false;
        }
        return true;
    }

    /**
     * Walk the resolved item's downstream chain (see class javadoc). Never throws:
     * a walk failure leaves the region rows partial, which the seal pass (or the
     * all_items-equivalent post-seal ready-loop) re-derives durably.
     *
     * @return the walked region node ids (informational / logging)
     */
    public Set<String> walkResolvedItem(SignalWaitEntity resolvedSignal) {
        String runId = resolvedSignal.getRunId();
        String nodeId = resolvedSignal.getNodeId();
        try {
            RegionContext region = loadRegion(runId, nodeId);
            if (region == null || region.regionNodeIds().isEmpty()) {
                logger.info("[PerItemContinuation] Empty walk region (no walkable successors): runId={}, nodeId={}",
                    runId, nodeId);
                return Set.of();
            }
            String parentItemId = resolveParentItemId(resolvedSignal);
            String triggerId = resolvedSignal.getDagTriggerId();
            int epoch = resolvedSignal.getEpoch();
            logger.info("[PerItemContinuation] Walking region for resolved item: runId={}, signalNode={}, itemId={}, region={}",
                runId, nodeId, resolvedSignal.getItemId(), region.regionNodeIds());
            for (String regionNodeId : region.regionNodeIds()) {
                try {
                    v2StepByStepService.executeNode(runId, regionNodeId, parentItemId, epoch, triggerId,
                        SplitExecutionOptions.perItemContinuationWalk());
                } catch (Exception e) {
                    // Continue the walk: a later region node may still be routable from
                    // rows persisted before the failure; the seal re-derives the rest.
                    logger.warn("[PerItemContinuation] Walk step failed (continuing): runId={}, node={}, error={}",
                        runId, regionNodeId, e.getMessage());
                }
            }
            return region.regionNodeIds();
        } catch (Exception e) {
            logger.error("[PerItemContinuation] Walk failed: runId={}, nodeId={}, error={}",
                runId, nodeId, e.getMessage(), e);
            return Set.of();
        }
    }

    /**
     * Seal the walk region after the LAST sibling signal resolved (see class javadoc):
     * final walk pass, then node-level aggregate marks + per-item SKIPPED rows for
     * every region node that executed at least one item.
     */
    public void sealRegion(SignalWaitEntity resolvedSignal, int splitItemCount) {
        String runId = resolvedSignal.getRunId();
        String nodeId = resolvedSignal.getNodeId();
        try {
            RegionContext region = loadRegion(runId, nodeId);
            if (region == null || region.regionNodeIds().isEmpty()) {
                return;
            }
            // Final walk pass: in a resolve-all burst the sealing resume is often the
            // FIRST one to observe "all outputs persisted" - items resolved by sibling
            // resumes that have not run yet must be executed here, from their durable
            // approval rows, before any node-level mark makes the region look terminal.
            walkResolvedItem(resolvedSignal);

            String triggerId = resolvedSignal.getDagTriggerId();
            int epoch = resolvedSignal.getEpoch();
            for (String regionNodeId : region.regionNodeIds()) {
                try {
                    List<Integer> rowItems = stepDataRepository.findTerminalItemIndicesByEpoch(
                        runId, regionNodeId, epoch);
                    if (rowItems.isEmpty()) {
                        // No item ever routed here (e.g. rejected port, nobody rejected):
                        // leave untouched - the post-seal ready-loop runs the normal
                        // empty-routed fan-out which marks it SKIPPED with cascade.
                        continue;
                    }
                    stepCompletionOrchestrator.recordSplitAggregateIfMissing(
                        runId, triggerId, regionNodeId, epoch);
                    if (splitItemCount > rowItems.size()) {
                        ExecutionNode node = region.nodeMap().get(regionNodeId);
                        if (node != null && region.execution() != null) {
                            splitAwareNodeExecutor.persistSealSkipRecords(
                                region.execution(), node, new HashSet<>(rowItems),
                                splitItemCount, epoch, triggerId);
                        }
                    }
                } catch (Exception e) {
                    logger.warn("[PerItemContinuation] Seal step failed (continuing): runId={}, node={}, error={}",
                        runId, regionNodeId, e.getMessage());
                }
            }
            logger.info("[PerItemContinuation] Region sealed: runId={}, signalNode={}, epoch={}, region={}",
                runId, nodeId, epoch, region.regionNodeIds());
        } catch (Exception e) {
            logger.error("[PerItemContinuation] Seal failed: runId={}, nodeId={}, error={}",
                runId, nodeId, e.getMessage(), e);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Region derivation
    // ────────────────────────────────────────────────────────────────────────

    private record RegionContext(Set<String> regionNodeIds,
                                 Map<String, ExecutionNode> nodeMap,
                                 WorkflowExecution execution) {}

    /**
     * BFS the static walk region: nodes reachable from the signal node's successors
     * through walkable nodes only, in predecessor-before-successor order (each region
     * node has exactly one in-region predecessor - a second incoming edge makes it an
     * implicit merge, which is a frontier, not part of the region).
     */
    private RegionContext loadRegion(String runId, String signalNodeId) {
        ExecutionCacheManager.LoadedExecution loaded = executionCacheManager.loadTreeAndExecution(runId);
        if (loaded == null || loaded.tree() == null) {
            logger.warn("[PerItemContinuation] Execution not available: runId={}", runId);
            return null;
        }
        Map<String, ExecutionNode> nodeMap = nodeSearchService.buildNodeMapFromAllRoots(loaded.tree());
        ExecutionNode signalNode = nodeMap.get(signalNodeId);
        if (signalNode == null) {
            logger.warn("[PerItemContinuation] Signal node not found in tree: runId={}, nodeId={}", runId, signalNodeId);
            return null;
        }
        Set<String> region = new LinkedHashSet<>();
        Deque<ExecutionNode> queue = new ArrayDeque<>(signalNode.getSuccessors());
        while (!queue.isEmpty()) {
            ExecutionNode candidate = queue.poll();
            if (candidate == null || region.contains(candidate.getNodeId())) {
                continue;
            }
            if (!isWalkable(candidate)) {
                continue; // frontier: handled by the normal ready-loop after the seal
            }
            region.add(candidate.getNodeId());
            queue.addAll(candidate.getSuccessors());
        }
        return new RegionContext(region, nodeMap, loaded.execution());
    }

    /**
     * A node is walkable when executing it for ONE item at a time is semantically
     * identical to executing it inside the all-items fan-out:
     * <ul>
     *   <li>cross-item consumers wait for the seal: merge (explicit or implicit),
     *       aggregate, loop, fork, nested split;</li>
     *   <li>signal-yielding nodes (approval, wait, interface) keep the
     *       "all items register their signals at once" invariant that the sibling
     *       accounting ({@code hasRemaining}) relies on;</li>
     *   <li>async-queue agents register a coalesce barrier sized to the full item
     *       batch - a one-item walk would seal it prematurely.</li>
     * </ul>
     * Decision/switch/classify (per-item routing) and plain per-item nodes (mcp,
     * code, transform, http, table CRUD, sync agents) are walkable.
     */
    private boolean isWalkable(ExecutionNode node) {
        if (node.isMergeNode() || node.isImplicitMerge() || node.isAggregateNode()
                || node.isLoopNode() || node.isForkNode() || node.isSplitNode()) {
            return false;
        }
        // END/Exit nodes skip split handling BEFORE the split-context lookup in
        // SplitAwareNodeExecutor, so a walk would execute them unguarded at node level
        // once per sibling resolution (EpochState mark mid-barrier + NodeCounts drift).
        // Frontier instead: the post-seal ready-loop runs them once, all_items-identical.
        if (node.isEndNode()) {
            return false;
        }
        if (node.isApprovalNode()
                || node.getType() == NodeType.WAIT
                || node.getType() == NodeType.INTERFACE) {
            return false;
        }
        if (node instanceof AgentNode agent && agent.isAsyncQueueEnabled()) {
            return false;
        }
        return true;
    }

    /**
     * Parent workflow item id for split-context signal successors - mirrors
     * {@code SignalResumeService.resolveParentItemIdForSplitSignal}: the outer
     * {@code workflowItemIndex} stashed in splitItemData at yield, else the sub-item
     * id with its LAST split-suffix segment stripped ("4.2" -> "4"), else "0".
     * With the dotted-id guard in {@link #isPerItemContinuation} the suffix-strip
     * fallback is defensive only (dotted ids never reach the walk via the gate).
     */
    private String resolveParentItemId(SignalWaitEntity resolvedSignal) {
        Map<String, Object> splitItemData = resolvedSignal.getSplitItemData();
        if (splitItemData != null) {
            Object idx = splitItemData.get("workflowItemIndex");
            if (idx != null) {
                return String.valueOf(idx);
            }
        }
        String itemId = resolvedSignal.getItemId();
        if (itemId != null && itemId.contains(".")) {
            return itemId.substring(0, itemId.lastIndexOf('.'));
        }
        return "0";
    }
}
