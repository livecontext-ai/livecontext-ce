package com.apimarketplace.orchestrator.trigger.queue;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Prevents a prod Redis scaling configuration from silently falling back to the
 * JVM-local execution queue.
 */
@Component
class ExecutionQueueBackendGuard {

    private final String scalingBackend;
    private final String queueBackend;

    ExecutionQueueBackendGuard(
            @Value("${scaling.backend:memory}") String scalingBackend,
            @Value("${workflow.execution-queue.backend:${scaling.backend:memory}}") String queueBackend) {
        this.scalingBackend = scalingBackend;
        this.queueBackend = queueBackend;
    }

    @PostConstruct
    void validate() {
        if ("redis".equalsIgnoreCase(scalingBackend) && !"redis".equalsIgnoreCase(queueBackend)) {
            throw new IllegalStateException(
                    "scaling.backend=redis requires workflow.execution-queue.backend=redis; "
                            + "local memory queue would break horizontal scaling");
        }
        if ("redis".equalsIgnoreCase(queueBackend) && !"redis".equalsIgnoreCase(scalingBackend)) {
            throw new IllegalStateException(
                    "workflow.execution-queue.backend=redis requires scaling.backend=redis; "
                            + "local scaling primitives would break distributed queue semantics");
        }
    }
}
