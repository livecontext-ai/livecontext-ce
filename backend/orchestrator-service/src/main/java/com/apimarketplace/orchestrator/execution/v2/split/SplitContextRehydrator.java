package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.services.StepOutputService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Makes sure this pod holds the CURRENT epoch's context of the split a node sits in, rebuilding
 * it from the split node's persisted output when it is absent or left behind by an older epoch.
 *
 * <p>Why: a split's context lives in the memory of the pod that ran the split, and the
 * orchestrator runs several replicas. A signal resume on another branch (a wait timer), an async
 * delivery or a step-by-step request can execute a split-scope node on the OTHER pod. Every
 * reader there (split aggregate, split merge, per-item fan-out) then found no context and
 * silently ran the node once instead of N times, with a green status. Prod 2026-09-26, run
 * {@code run_<id>} epoch 51: an aggregate over 5 X upload segments returned 1,
 * so the video was never finalized or posted.
 *
 * <p>Source of truth: the split node's own persisted output ({@code items}, stored at the
 * workflow item index) for the context's epoch, the same durable store the aggregate and merge
 * already read per-item results from. Only top-level splits are rebuilt: a split inside another
 * split's scope is keyed by a parent scope its persisted output does not carry.
 *
 * <p>No-op (no DB read) when the node has no split upstream, or when this pod already holds a
 * context for the split from this epoch or a newer one.
 */
@Service
public class SplitContextRehydrator {

    private static final Logger logger = LoggerFactory.getLogger(SplitContextRehydrator.class);

    private final SplitContextManager contextManager;
    private final StepOutputService stepOutputService;

    public SplitContextRehydrator(SplitContextManager contextManager,
                                  @Lazy StepOutputService stepOutputService) {
        this.contextManager = contextManager;
        this.stepOutputService = stepOutputService;
    }

    /**
     * @return {@code true} when a context for {@code nodeId}'s upstream split is in memory for
     *         this epoch after the call (already there, or rebuilt)
     */
    @SuppressWarnings("unchecked")
    public boolean ensureContext(String runId,
                                 String nodeId,
                                 int workflowItemIndex,
                                 Map<String, ExecutionNode> nodeMap,
                                 ExecutionContext context) {
        if (context == null || runId == null) {
            return false;
        }
        String splitNodeId = SplitContextManager.findUpstreamSplitNodeId(nodeId, nodeMap);
        if (splitNodeId == null) {
            return false;
        }
        if (SplitContextManager.findUpstreamSplitNodeId(splitNodeId, nodeMap) != null) {
            logger.debug("[SplitRehydrate] Split {} is nested in another split scope; not rebuilt: node={}, run={}",
                splitNodeId, nodeId, runId);
            return false;
        }
        int epoch = context.epoch();
        Optional<SplitContext> existing = contextManager.getContext(runId, splitNodeId, workflowItemIndex);
        if (existing.isPresent() && !isOlderEpoch(existing.get(), epoch)) {
            return true;
        }
        if (stepOutputService == null) {
            return existing.isPresent() && !isOlderEpoch(existing.get(), epoch);
        }

        Map<Integer, Object> splitOutputs;
        try {
            splitOutputs = stepOutputService.loadPerItemNodeOutputs(runId, splitNodeId, epoch, context.tenantId());
        } catch (Exception ex) {
            logger.warn("[SplitRehydrate] Could not load split {} output to rebuild its context (node={}, run={}, epoch={}): {}",
                splitNodeId, nodeId, runId, epoch, ex.getMessage());
            return existing.isPresent() && !isOlderEpoch(existing.get(), epoch);
        }
        Object splitOutput = splitOutputs == null ? null : splitOutputs.get(workflowItemIndex);
        Object items = splitOutput instanceof Map<?, ?> m ? m.get("items") : null;
        if (!(items instanceof List<?> itemList)) {
            // The split has not produced items in this epoch (not run yet, or run elsewhere in a
            // shape we cannot read): leave memory as it is rather than invent a scope.
            return existing.isPresent() && !isOlderEpoch(existing.get(), epoch);
        }

        Map<String, Object> splitItemData = new HashMap<>();
        splitItemData.put("splitNodeId", splitNodeId);
        splitItemData.put("items", new ArrayList<>((List<Object>) itemList));
        splitItemData.put("workflowItemIndex", workflowItemIndex);
        // restoreContext keeps a same-or-newer-epoch context and rebuilds an older one.
        contextManager.restoreContext(runId, nodeId, splitItemData, epoch);

        Optional<SplitContext> now = contextManager.getContext(runId, splitNodeId, workflowItemIndex);
        boolean rebuilt = now.isPresent() && !isOlderEpoch(now.get(), epoch);
        if (rebuilt) {
            logger.info("[SplitRehydrate] Split context {} from the durable store: node={}, split={}, run={}, epoch={}, itemCount={}",
                existing.isPresent() ? "replaced (older epoch on this pod)" : "rebuilt (absent on this pod)",
                nodeId, splitNodeId, runId, epoch, itemList.size());
        }
        return rebuilt;
    }

    private static boolean isOlderEpoch(SplitContext ctx, int epoch) {
        return epoch != SplitContext.UNKNOWN_EPOCH
            && ctx.epoch() != SplitContext.UNKNOWN_EPOCH
            && ctx.epoch() < epoch;
    }
}
