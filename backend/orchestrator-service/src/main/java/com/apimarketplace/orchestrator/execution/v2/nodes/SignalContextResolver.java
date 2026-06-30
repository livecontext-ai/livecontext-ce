package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves dagTriggerId and epoch for signal-aware nodes (WaitNode, UserApprovalNode).
 *
 * <p><b>Simplified in Multi-DAG refactoring:</b> Now that ExecutionContext carries explicit
 * {@code triggerId} and {@code epoch} fields, resolution is straightforward:
 * <ol>
 *   <li>Explicit setter injection (set by engine/injector)</li>
 *   <li>Context explicit fields (triggerId/epoch on ExecutionContext)</li>
 *   <li>Context global data (legacy fallback)</li>
 * </ol>
 *
 * <p>The previous BFS plan-derivation logic is no longer needed because
 * V2StepByStepContextManager and UnifiedExecutionEngine now set triggerId
 * directly on the ExecutionContext.
 */
final class SignalContextResolver {

    private static final Logger logger = LoggerFactory.getLogger(SignalContextResolver.class);

    static final String KEY_DAG_TRIGGER_ID = "dagTriggerId";
    static final String KEY_EPOCH = "epoch";

    private SignalContextResolver() {} // Utility class

    /**
     * Resolve the dagTriggerId for signal registration.
     *
     * @param nodeId     The current node's ID (unused, kept for API compat)
     * @param fieldValue The explicitly set field value (from setter), may be null
     * @param context    The execution context
     * @return The resolved dagTriggerId, or null if indeterminate
     */
    static String resolveDagTriggerId(String nodeId, String fieldValue, ExecutionContext context) {
        // 1. Explicit field value (set via setter)
        if (fieldValue != null) return fieldValue;

        // 2. Context explicit field (set by engine/context manager)
        if (context.triggerId() != null) return context.triggerId();

        // 3. Context global data (legacy fallback)
        Optional<Object> globalVal = context.getGlobalData(KEY_DAG_TRIGGER_ID);
        if (globalVal.isPresent()) return (String) globalVal.get();

        return null;
    }

    /**
     * Resolve the epoch for signal registration.
     *
     * @param fieldValue The explicitly set field value (from setter)
     * @param context    The execution context
     * @return The resolved epoch
     */
    static int resolveEpoch(int fieldValue, ExecutionContext context) {
        // 1. Explicit field value (set via setter, non-zero means explicitly set)
        if (fieldValue != 0) return fieldValue;

        // 2. Context explicit field (set by engine/context manager)
        if (context.epoch() != 0) return context.epoch();

        // 3. Context global data (legacy fallback)
        Optional<Object> globalVal = context.getGlobalData(KEY_EPOCH);
        if (globalVal.isPresent()) return ((Number) globalVal.get()).intValue();

        // 4. Default to 0 (safe for single-trigger workflows)
        return 0;
    }

    /**
     * Hard cap on the serialized size of a single split item persisted as signal
     * context. The signal payload is exposed verbatim by the run inspector and
     * signals listing while pending, and N pending items × an unbounded item
     * would bloat the JSONB column and every payload. Oversized items are replaced
     * by a truncated string preview plus a {@code _truncated} marker.
     */
    static final int MAX_ITEM_CONTEXT_JSON_CHARS = 4096;
    private static final int TRUNCATED_PREVIEW_CHARS = 1024;

    /**
     * Split context: capture the current item alongside the signal so (a) per-item
     * signals expose WHAT they refer to (`itemContext` in the signals payload) and
     * (b) SignalResumeService.restoreSplitContextIfNeeded can rehydrate the split
     * context after a restart. Outside a split this returns null.
     */
    static Map<String, Object> buildSplitItemData(ExecutionContext context) {
        if (context == null || context.state() == null) {
            return null;
        }
        Object currentItem = context.state().getGlobalData("item").orElse(null);
        if (currentItem == null) {
            return null;
        }
        Map<String, Object> splitItemData = new LinkedHashMap<>();
        // Display fields: exposed verbatim as `itemContext` in the signals payload so the
        // approver SEES which item each per-item signal refers to.
        splitItemData.put("current_item", capForDisplay(currentItem));
        context.state().getGlobalData("index").ifPresent(idx -> splitItemData.put("current_index", idx));

        // Restoration fields: let SplitContextManager.restoreContext rehydrate the split
        // SCOPE when this signal is resumed on a DIFFERENT pod (cross-instance resume at
        // replicas>=2 - the SplitContext map is per-pod in memory). Without splitNodeId +
        // items, restoreContext bails ("Missing splitNodeId or items") and the per-item
        // successor (e.g. send_email after split->approval, or a wait) loses its split
        // scope -> runs ONCE instead of N. Mirrors AgentNode's persisted shape so the
        // agent / approval / wait signal paths all restore identically. No-op outside a
        // split (current_split_id absent) and for non-split signals (returns null above).
        context.getGlobalData("current_split_id")
            .map(Object::toString)
            .ifPresent(scopedSplitId -> {
                String splitNodeId = SplitContextManager.extractBaseSplitNodeId(scopedSplitId);
                Object items = context.getGlobalData("items").orElse(null);
                if (splitNodeId != null && items != null) {
                    splitItemData.put("splitNodeId", splitNodeId);
                    splitItemData.put("items", items);
                    int splitItemIndex = context.getGlobalData("index")
                        .map(v -> v instanceof Number n ? n.intValue() : 0).orElse(0);
                    splitItemData.put("itemIndex", splitItemIndex);
                    splitItemData.put("workflowItemIndex", parseWorkflowItemIndexFromScopedKey(scopedSplitId));
                }
            });
        return splitItemData;
    }

    /**
     * Resolve the approval node's configured context template (literal + {{...}}) against the
     * execution context FROZEN at the moment of the yield, returning a display string exposed
     * verbatim to the human approver as {@code approvalContext} in the signals payload (e.g.
     * "Approve refund of 120 EUR for x@y?"). Independent of any split.
     *
     * <p>Returns {@code null} (the approver simply sees no configured context) when the template
     * is blank, the adapter/context is unavailable, the resolved text is blank, or resolution
     * throws. A malformed template / unresolvable expression must NEVER fail the approval node -
     * this is what makes the field "soft-required" (flagged at edit time but never blocking a run).
     * The result is capped at {@link #MAX_ITEM_CONTEXT_JSON_CHARS} chars, matching the item-context bound.
     */
    static String resolveApprovalContext(String template, ExecutionContext context, V2TemplateAdapter adapter) {
        if (template == null || template.isBlank() || adapter == null || context == null) {
            return null;
        }
        try {
            Object resolved = adapter.evaluateTemplate(template, context);
            if (resolved == null) {
                return null;
            }
            String text = String.valueOf(resolved);
            if (text.isBlank()) {
                return null;
            }
            return text.length() > MAX_ITEM_CONTEXT_JSON_CHARS
                ? text.substring(0, MAX_ITEM_CONTEXT_JSON_CHARS)
                : text;
        } catch (Exception e) {
            logger.warn("Approval contextTemplate resolution failed (approver will see no context): {}",
                e.getMessage());
            return null;
        }
    }

    /**
     * Parse the workflow item index from a scoped split context key
     * ({@code "<splitNodeId>:<workflowItemIndex>"} or {@code ".../sN"}). Returns 0 for a
     * null/malformed key, matching {@link SplitContextManager#buildContextKey}'s default.
     * Kept local (mirrors AgentNode's private helper) so this fix touches no other node.
     */
    private static int parseWorkflowItemIndexFromScopedKey(String scopedKey) {
        if (scopedKey == null || scopedKey.isEmpty()) {
            return 0;
        }
        String stripped = scopedKey;
        int slashIdx = stripped.indexOf('/');
        if (slashIdx > 0) {
            stripped = stripped.substring(0, slashIdx);
        }
        int lastColon = stripped.lastIndexOf(':');
        if (lastColon < 0 || lastColon == stripped.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(stripped.substring(lastColon + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Object capForDisplay(Object currentItem) {
        String serialized;
        try {
            serialized = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(currentItem);
        } catch (Exception e) {
            serialized = String.valueOf(currentItem);
        }
        if (serialized.length() <= MAX_ITEM_CONTEXT_JSON_CHARS) {
            return currentItem;
        }
        Map<String, Object> capped = new LinkedHashMap<>();
        capped.put("_truncated", true);
        capped.put("preview", serialized.substring(0, TRUNCATED_PREVIEW_CHARS));
        return capped;
    }
}
