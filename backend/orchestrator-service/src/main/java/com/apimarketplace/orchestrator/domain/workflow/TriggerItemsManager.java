package com.apimarketplace.orchestrator.domain.workflow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages trigger items state including paginated batches, chat inputs, and webhook payloads.
 */
public class TriggerItemsManager {

    private static final Logger logger = LoggerFactory.getLogger(TriggerItemsManager.class);

    private final String runId;
    private final Map<String, TriggerItemsState> triggerItemsState = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> chatTriggerInputs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> webhookTriggerPayloads = new ConcurrentHashMap<>();

    public TriggerItemsManager(String runId) {
        this.runId = runId;
    }

    // ===== TRIGGER BATCHES =====

    public void registerBatch(Trigger trigger, TriggerBatchResult batch) {
        if (trigger == null || batch == null) return;
        String key = trigger.getNormalizedKey();
        TriggerItemsState previousState = triggerItemsState.get(key);
        TriggerItemsState state = new TriggerItemsState(
                trigger,
                List.copyOf(batch.items()),
                batch.offset(),
                batch.totalCount(),
                batch.hasMore(),
                batch.nextOffset(),
                batch.realTotalCount()
        );
        triggerItemsState.put(key, state);
        int previousItemCount = previousState != null ? previousState.items().size() : 0;

        if (logger.isInfoEnabled()) {
            logger.info("[TriggerBatch] runId={} triggerKey={} batchSize={} totalDeclared={} offset={} nextOffset={} hasMore={} previousItems={}",
                    runId, key, state.items().size(), batch.realTotalCount(), batch.offset(), batch.nextOffset(), batch.hasMore(), previousItemCount);
        }
        if (!batch.hasMore() && state.items().size() < batch.realTotalCount()) {
            logger.warn("[TriggerBatch] runId={} triggerKey={} final batch smaller than declared total (batchSize={} totalDeclared={})",
                    runId, key, state.items().size(), batch.realTotalCount());
        }
    }

    public boolean hasItems(String triggerKey) {
        TriggerItemsState state = triggerItemsState.get(triggerKey);
        return state != null && !state.items().isEmpty();
    }

    public List<Map<String, Object>> getItems(String triggerKey) {
        TriggerItemsState state = triggerItemsState.get(triggerKey);
        return state != null ? state.items() : List.of();
    }

    public TriggerItemsState getState(String triggerKey) {
        return triggerItemsState.get(triggerKey);
    }

    public int getItemCount(String triggerKey) {
        TriggerItemsState state = triggerItemsState.get(triggerKey);
        return state != null ? state.items().size() : 0;
    }

    public int getAbsoluteItemIndex(String triggerKey, int localIndex) {
        TriggerItemsState state = triggerItemsState.get(triggerKey);
        return state != null ? state.absoluteIndex(localIndex) : localIndex;
    }

    public void clear() {
        triggerItemsState.clear();
    }

    // ===== CHAT TRIGGER INPUTS =====

    public void setChatTriggerInput(String stepId, Map<String, Object> input) {
        if (stepId != null && input != null) {
            chatTriggerInputs.put(stepId, new HashMap<>(input));
            logger.info("[ChatTrigger] Stored input for stepId={}: {}", stepId, input);
        }
    }

    public Map<String, Object> getChatTriggerInput(String stepId) {
        if (stepId == null) return null;
        Map<String, Object> input = chatTriggerInputs.get(stepId);
        return input != null ? new HashMap<>(input) : null;
    }

    public boolean hasChatTriggerInput(String stepId) {
        return stepId != null && chatTriggerInputs.containsKey(stepId);
    }

    public void clearChatTriggerInput(String stepId) {
        if (stepId != null) chatTriggerInputs.remove(stepId);
    }

    // ===== WEBHOOK TRIGGER PAYLOADS =====

    public void setWebhookTriggerPayload(String stepId, Map<String, Object> payload) {
        if (stepId != null && payload != null) {
            webhookTriggerPayloads.put(stepId, new HashMap<>(payload));
            logger.info("[WebhookTrigger] Stored payload for stepId={}: {}", stepId, payload);
        }
    }

    public Map<String, Object> getWebhookTriggerPayload(String stepId) {
        if (stepId == null) return null;
        Map<String, Object> payload = webhookTriggerPayloads.get(stepId);
        return payload != null ? new HashMap<>(payload) : null;
    }

    public boolean hasWebhookTriggerPayload(String stepId) {
        return stepId != null && webhookTriggerPayloads.containsKey(stepId);
    }

    public void clearWebhookTriggerPayload(String stepId) {
        if (stepId != null) webhookTriggerPayloads.remove(stepId);
    }

    /**
     * State for trigger items including pagination info.
     */
    public static final class TriggerItemsState {
        private final Trigger trigger;
        private final List<Map<String, Object>> items;
        private final int offset;
        private final int totalCount;
        private final boolean hasMore;
        private final int nextOffset;
        private final int realTotalCount;

        public TriggerItemsState(Trigger trigger, List<Map<String, Object>> items, int offset, int totalCount, boolean hasMore, int nextOffset, int realTotalCount) {
            this.trigger = trigger;
            this.items = items != null ? List.copyOf(items) : List.of();
            this.offset = offset;
            this.totalCount = totalCount;
            this.hasMore = hasMore;
            this.nextOffset = nextOffset;
            this.realTotalCount = realTotalCount;
        }

        public Trigger trigger() { return trigger; }
        public List<Map<String, Object>> items() { return items; }
        public int offset() { return offset; }
        public int totalCount() { return totalCount; }
        public boolean hasMore() { return hasMore; }
        public int nextOffset() { return nextOffset; }
        public int realTotalCount() { return realTotalCount; }
        public int absoluteIndex(int localIndex) { return offset + localIndex; }
    }
}
