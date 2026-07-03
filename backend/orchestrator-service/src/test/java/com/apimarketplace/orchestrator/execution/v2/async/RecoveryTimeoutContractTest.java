package com.apimarketplace.orchestrator.execution.v2.async;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workflow-lane budget guards vs the 7200s executionTimeout/inactivityTimeout contract.
 *
 * <p>The recovery hard timeout is pure wall-clock from offload (no per-task worker
 * liveness signal exists), so any default below the longest legitimate run makes the
 * scan synthetically FAIL healthy long agent runs mid-flight - the pre-2026-07 30-min
 * default did exactly that. Same causal chain for the pending-record TTL: it also
 * shields a RUNNING run from the zombie scan, so a max-length run must never outlive
 * its own record.
 */
@DisplayName("Recovery guards - defaults cover the 7200s agent timeout contract")
class RecoveryTimeoutContractTest {

    /** Contract maximum for executionTimeout AND inactivityTimeout (agent-service validation). */
    private static final Duration CONTRACT_MAX = Duration.ofSeconds(7200);

    /** Bridge hard cap default (mcp/bridge/server.mjs BRIDGE_MAX_TIMEOUT_MS). */
    private static final Duration BRIDGE_CAP = Duration.ofMinutes(125);

    @Test
    @DisplayName("scaling.agent.recovery.hard-timeout-ms default exceeds the contract max and the bridge cap")
    void hardTimeoutDefaultCoversContract() {
        long defaultMs = resolveHardTimeoutAnnotationDefault();
        assertThat(Duration.ofMillis(defaultMs))
            .as("a hard timeout below the longest legitimate run synthetically fails healthy runs")
            .isGreaterThan(CONTRACT_MAX)
            .isGreaterThan(BRIDGE_CAP);
    }

    @Test
    @DisplayName("RedisPendingAgentStore DEFAULT_TTL outlives the hard timeout (the pending record shields the run)")
    void pendingTtlOutlivesHardTimeout() {
        long hardTimeoutMs = resolveHardTimeoutAnnotationDefault();
        assertThat(RedisPendingAgentStore.DEFAULT_TTL)
            .isGreaterThan(Duration.ofMillis(hardTimeoutMs))
            .isGreaterThan(CONTRACT_MAX);
    }

    /**
     * Reads the {@code @Value("${scaling.agent.recovery.hard-timeout-ms:<default>}")}
     * literal off the autowired constructor so the test trips on the ANNOTATION default,
     * not on a value some context happens to inject.
     */
    private long resolveHardTimeoutAnnotationDefault() {
        for (Constructor<?> ctor : AgentRecoveryService.class.getDeclaredConstructors()) {
            for (Parameter p : ctor.getParameters()) {
                Value value = p.getAnnotation(Value.class);
                if (value != null && value.value().contains("scaling.agent.recovery.hard-timeout-ms")) {
                    String expr = value.value();
                    int colon = expr.indexOf(':');
                    int end = expr.indexOf('}', colon);
                    return Long.parseLong(expr.substring(colon + 1, end));
                }
            }
        }
        throw new AssertionError("hard-timeout @Value annotation not found on AgentRecoveryService");
    }
}
