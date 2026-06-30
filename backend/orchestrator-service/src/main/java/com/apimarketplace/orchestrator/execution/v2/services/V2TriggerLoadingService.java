package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionTree;
import com.apimarketplace.orchestrator.services.TriggerResolverService;
import com.apimarketplace.orchestrator.trigger.TriggerType;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Handles trigger loading for step-by-step execution.
 * Manages datasource loading, trigger type detection, and reusable trigger handling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class V2TriggerLoadingService {

    private final TriggerResolverService triggerResolverService;
    private final V2StepByStepContextManager contextManager;

    /**
     * Load trigger items from datasource if not already loaded.
     * This is called when executing a trigger node.
     * For chat triggers, passes the chat input data stored in the execution context.
     */
    @SuppressWarnings("unchecked")
    public void loadTriggerItemsIfNeeded(String runId, ExecutionTree tree, int itemIndex,
                                          String nodeId, WorkflowExecution execution) {
        // Check if already loaded
        if (contextManager.hasTriggerItems(runId)) {
            log.debug("[V2TriggerLoading] Trigger items already loaded for runId={}", runId);
            return;
        }

        WorkflowPlan plan = tree.plan();
        if (plan == null || plan.getTriggers() == null || plan.getTriggers().isEmpty()) {
            log.warn("[V2TriggerLoading] No triggers in plan for runId={}", runId);
            contextManager.cacheTriggerItems(runId, new ArrayList<>());
            return;
        }

        // Find trigger by nodeId instead of hardcoded get(0)
        String triggerLabel = LabelNormalizer.extractTriggerLabel(nodeId);
        Trigger trigger = plan.getTriggers().stream()
            .filter(t -> matchesTriggerLabel(t, triggerLabel))
            .findFirst()
            .orElse(plan.getTriggers().get(0));  // Fallback for backward compatibility

        String tenantId = tree.tenantId();

        log.info("[V2TriggerLoading] Loading trigger items: runId={}, triggerId={}, nodeId={}, tenantId={}, triggerType={}",
            runId, trigger.id(), nodeId, tenantId, trigger.type());

        try {
            // Get chat trigger input if available (for chat triggers)
            Map<String, Object> resolvedInputs = Map.of();
            if (execution != null && nodeId != null) {
                Map<String, Object> chatInput = execution.getChatTriggerInput(nodeId);
                if (chatInput != null && !chatInput.isEmpty()) {
                    resolvedInputs = chatInput;
                    log.info("[V2TriggerLoading] Using chat trigger input for {}: {}", nodeId, chatInput);
                }
            }

            // Use TriggerResolverService to load the datasource items (with chat input if available)
            Map<String, Object> triggerResult = triggerResolverService.resolveTrigger(trigger, tenantId, resolvedInputs);

            // Extract items from the result
            List<Map<String, Object>> items = extractTriggerItems(triggerResult);

            contextManager.cacheTriggerItems(runId, items);
            log.info("[V2TriggerLoading] Loaded {} trigger items for runId={}", items.size(), runId);

        } catch (Exception e) {
            log.error("[V2TriggerLoading] Failed to load trigger items: runId={}, error={}",
                runId, e.getMessage(), e);
            contextManager.cacheTriggerItems(runId, new ArrayList<>());
        }
    }

    /**
     * Extract items from trigger result.
     * The result typically has a structure like: { "items": [...] } or { "data": [...] }
     *
     * <p>Audit 2026-05-06 P0 (round 2 #1): the wrap-the-whole-result fallback below
     * defeated the {@code ScheduleTriggerResolver}'s {@code count: 0, data: []} fix -
     * a non-empty {@code triggerResult} (which schedule's metadata always is)
     * silently produced a single phantom item. We now honour an EXPLICIT {@code
     * count: 0} marker as the resolver's "do not fall back" opt-out: any resolver
     * that knows it has zero iterable items should set {@code count: 0} and
     * {@link #extractTriggerItems} returns an empty list deterministically.
     *
     * <p><b>Resolvers affected by the count=0 opt-out</b> (audit 2026-05-06 round 3 #1
     * behavioural side-effect):
     * <ul>
     *   <li>{@code ScheduleTriggerResolver} - schedule fires never iterate</li>
     *   <li>{@code ChatTriggerResolver.buildUnmatchedPayload} - chat received but no
     *       action mapping matched. Pre-fix wrapped to 1 phantom item carrying
     *       no_match metadata (contradicting the resolver's own {@code count: 0});
     *       post-fix correctly produces 0 items so no downstream {@code core:split}
     *       fan-out happens for unmatched chat.</li>
     *   <li>{@code TriggerPayloadBuilder.buildErrorPayload} - datasource resolution
     *       failed. Same shape, same behavioural correction: 0 items downstream
     *       for an error trigger payload.</li>
     * </ul>
     * Wrap-the-whole-result is preserved for resolvers that emit a scalar payload
     * with NO {@code count} key (webhook with raw HTTP body, manual fire with
     * custom params) - those still get 1 item carrying the full payload.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractTriggerItems(Map<String, Object> triggerResult) {
        List<Map<String, Object>> items = new ArrayList<>();

        if (triggerResult.containsKey("items")) {
            Object itemsObj = triggerResult.get("items");
            if (itemsObj instanceof List) {
                for (Object item : (List<?>) itemsObj) {
                    if (item instanceof Map) {
                        items.add((Map<String, Object>) item);
                    }
                }
            }
        } else if (triggerResult.containsKey("data")) {
            Object dataObj = triggerResult.get("data");
            if (dataObj instanceof List) {
                for (Object item : (List<?>) dataObj) {
                    if (item instanceof Map) {
                        items.add((Map<String, Object>) item);
                    }
                }
            }
        }

        // Explicit zero-count opt-out: a resolver that knows the trigger has
        // zero iterable items (schedule fires, no per-item fan-out) sets
        // {@code count: 0}. Honour it deterministically - do NOT fall back to
        // the wrap-whole-result path. Pre-fix this fallback masked
        // ScheduleTriggerResolver's empty-data signal and produced 1 phantom item
        // downstream, defeating the count-0 contract.
        if (items.isEmpty() && triggerResult.get("count") instanceof Number n && n.intValue() == 0) {
            return items;
        }

        // If no items found in standard locations, wrap the whole result as a single item.
        // Used by resolvers that produce scalar fields without an iterable structure
        // (e.g. webhook with raw HTTP body, manual fire with custom params).
        if (items.isEmpty() && !triggerResult.isEmpty()) {
            items.add(new HashMap<>(triggerResult));
        }

        return items;
    }

    /**
     * Check if a trigger node in the plan is a reusable trigger (webhook, manual, chat, etc.).
     * Reusable triggers require waiting for an external event before executing.
     *
     * @param plan The workflow plan
     * @param nodeId The trigger node ID (e.g., "trigger:my_webhook")
     * @return true if the trigger is a reusable trigger
     */
    public boolean isReusableTrigger(WorkflowPlan plan, String nodeId) {
        if (plan == null || plan.getTriggers() == null || !nodeId.startsWith("trigger:")) {
            return false;
        }

        // Extract the label part using LabelNormalizer (e.g., "trigger:my_webhook" -> "my_webhook")
        String triggerLabel = LabelNormalizer.extractTriggerLabel(nodeId);
        if (triggerLabel == null) {
            return false;
        }

        // Find the trigger and check if it's a reusable type
        return plan.getTriggers().stream()
            .filter(t -> matchesTriggerLabel(t, triggerLabel))
            .anyMatch(t -> TriggerType.isReusableTriggerType(t.type()));
    }

    /**
     * Get the trigger type from the plan for a given trigger node ID.
     *
     * @param plan The workflow plan
     * @param nodeId The trigger node ID (e.g., "trigger:my_datasource")
     * @return The trigger type (e.g., "datasource", "webhook", "manual", "chat") or null if not found
     */
    public String getTriggerType(WorkflowPlan plan, String nodeId) {
        if (plan == null || plan.getTriggers() == null || !nodeId.startsWith("trigger:")) {
            return null;
        }

        // Extract the label part using LabelNormalizer (e.g., "trigger:my_datasource" -> "my_datasource")
        String triggerLabel = LabelNormalizer.extractTriggerLabel(nodeId);
        if (triggerLabel == null) {
            return null;
        }

        // Find the trigger and return its type
        return plan.getTriggers().stream()
            .filter(t -> matchesTriggerLabel(t, triggerLabel))
            .map(Trigger::type)
            .findFirst()
            .orElse(null);
    }

    /**
     * Check if a trigger matches the given label.
     */
    private boolean matchesTriggerLabel(Trigger trigger, String triggerLabel) {
        // Use LabelNormalizer for proper normalization (handles accents, special chars, etc.)
        String normalizedLabel = LabelNormalizer.normalizeLabel(
            trigger.label() != null ? trigger.label() : trigger.id()
        );
        return triggerLabel.equals(normalizedLabel) || triggerLabel.equals(trigger.id());
    }
}
