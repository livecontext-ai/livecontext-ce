package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.trigger.TriggerExecutionResult;

import java.util.function.Supplier;

/**
 * Internal context for a caller-supplied execution queue request id.
 *
 * <p>This is deliberately out-of-band from trigger payloads: webhook, form,
 * chat, and public-app payloads are user-controlled and must not be able to
 * choose the queue idempotency key.
 */
public final class ExecutionQueueRequestContext {

    private static final ThreadLocal<String> CURRENT_REQUEST_ID = new ThreadLocal<>();

    private ExecutionQueueRequestContext() {
    }

    public static String currentRequestId() {
        return CURRENT_REQUEST_ID.get();
    }

    public static TriggerExecutionResult runWith(String requestId, Supplier<TriggerExecutionResult> supplier) {
        String previous = CURRENT_REQUEST_ID.get();
        if (requestId != null && !requestId.isBlank()) {
            CURRENT_REQUEST_ID.set(requestId);
        }
        try {
            return supplier.get();
        } finally {
            if (previous != null) {
                CURRENT_REQUEST_ID.set(previous);
            } else {
                CURRENT_REQUEST_ID.remove();
            }
        }
    }
}
