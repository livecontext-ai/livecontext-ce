package com.apimarketplace.orchestrator.trigger.queue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionQueueBackendGuardTest {

    @Test
    @DisplayName("Redis scaling requires Redis execution queue")
    void redisScalingRequiresRedisQueue() {
        ExecutionQueueBackendGuard guard = new ExecutionQueueBackendGuard("redis", "memory");

        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    @DisplayName("Redis execution queue requires Redis scaling primitives")
    void redisQueueRequiresRedisScalingPrimitives() {
        ExecutionQueueBackendGuard guard = new ExecutionQueueBackendGuard("memory", "redis");

        assertThrows(IllegalStateException.class, guard::validate);
    }

    @Test
    @DisplayName("Matching queue and scaling backends are accepted")
    void matchingBackendsAreAccepted() {
        assertDoesNotThrow(() -> new ExecutionQueueBackendGuard("redis", "redis").validate());
        assertDoesNotThrow(() -> new ExecutionQueueBackendGuard("memory", "memory").validate());
    }
}
