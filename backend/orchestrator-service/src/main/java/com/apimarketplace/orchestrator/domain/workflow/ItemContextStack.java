package com.apimarketplace.orchestrator.domain.workflow;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages item context stacks for workflow execution.
 * Provides thread-local stacks for item context.
 */
public class ItemContextStack {

    private final ThreadLocal<Deque<ItemContext>> itemContextStack = ThreadLocal.withInitial(ArrayDeque::new);

    public Optional<ItemContext> getCurrentItemContext() {
        Deque<ItemContext> stack = itemContextStack.get();
        return stack.isEmpty() ? Optional.empty() : Optional.of(stack.peek());
    }

    public void pushItemContext(String stepId, String triggerKey, int index, Map<String, Object> data) {
        itemContextStack.get().push(new ItemContext(stepId, triggerKey, index, data, new ConcurrentHashMap<>()));
    }

    public void pushItemContext(ItemContext context) {
        if (context == null) return;
        itemContextStack.get().push(context);
    }

    public void popItemContext() {
        Deque<ItemContext> stack = itemContextStack.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) itemContextStack.remove();
    }

    public void clear() {
        itemContextStack.remove();
    }

    /**
     * Represents the context for a single item being processed.
     */
    public static final class ItemContext {
        private final String stepId;
        private final String triggerKey;
        private final int index;
        private final Map<String, Object> data;
        private final Map<String, Object> stepOutputs;

        public ItemContext(String stepId, String triggerKey, int index, Map<String, Object> data,
                           Map<String, Object> stepOutputs) {
            this.stepId = stepId;
            this.triggerKey = triggerKey;
            this.index = index;
            this.data = data != null ? Collections.unmodifiableMap(new HashMap<>(data)) : Map.of();
            this.stepOutputs = stepOutputs != null ? stepOutputs : new ConcurrentHashMap<>();
        }

        public String stepId() { return stepId; }
        public String triggerKey() { return triggerKey; }
        public int index() { return index; }
        public Map<String, Object> data() { return data; }
        public Map<String, Object> stepOutputs() { return stepOutputs; }
    }
}
