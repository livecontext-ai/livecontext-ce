package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.execution.NodeStatus;
import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Branch coverage on {@link UnifiedExecutionEngine#shouldCascadeSkipFromResult}.
 *
 * <p>The predicate gates the SKIPPED-to-successors cascade at two engine call
 * sites (auto-mode and step-by-step). Pin all four states so a future change
 * can't widen the cascade to routing skips (Decision/Switch branch unselection)
 * or narrow it past genuine failures.
 */
@DisplayName("UnifiedExecutionEngine - shouldCascadeSkipFromResult contract")
class UnifiedExecutionEngineCascadeSkipTest {

    @Test
    @DisplayName("FAILED → cascades (original contract)")
    void failureCascades() {
        NodeExecutionResult failed = NodeExecutionResult.failure("core:x", "boom");
        assertThat(UnifiedExecutionEngine.shouldCascadeSkipFromResult(failed)).isTrue();
    }

    @Test
    @DisplayName("SKIPPED + CASCADE_SKIP_TO_SUCCESSORS=true → cascades (new contract)")
    void skippedWithFlagCascades() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS, true);
        NodeExecutionResult skipped = new NodeExecutionResult(
            "core:agg",
            NodeStatus.SKIPPED,
            Map.of(),
            Optional.of("no items routed"),
            meta,
            0
        );
        assertThat(UnifiedExecutionEngine.shouldCascadeSkipFromResult(skipped))
                .as("terminal-skip nodes (e.g. SplitAggregateHandler with 0 routed items) must cascade")
                .isTrue();
    }

    @Test
    @DisplayName("SKIPPED without flag → does NOT cascade (routing skips stay route-only)")
    void skippedWithoutFlagDoesNotCascade() {
        // This is what a Decision/Switch routing-skip looks like - the engine's
        // handleSkippedNode produces a SKIPPED result without the cascade flag.
        // The engine's per-port edge filter (not cascadeFailureToSuccessors) is
        // responsible for marking the unselected branch's edges as skipped.
        NodeExecutionResult routingSkip = NodeExecutionResult.skipped("core:x", "branch not selected");
        assertThat(UnifiedExecutionEngine.shouldCascadeSkipFromResult(routingSkip))
                .as("routing skips must NOT cascade - Decision/Switch port filtering handles them")
                .isFalse();
    }

    @Test
    @DisplayName("COMPLETED → does NOT cascade")
    void completedDoesNotCascade() {
        NodeExecutionResult success = NodeExecutionResult.success("core:x", Map.of("ok", true));
        assertThat(UnifiedExecutionEngine.shouldCascadeSkipFromResult(success)).isFalse();
    }

    @Test
    @DisplayName("null result → does NOT cascade (defensive)")
    void nullResultDoesNotCascade() {
        assertThat(UnifiedExecutionEngine.shouldCascadeSkipFromResult(null)).isFalse();
    }

    @Test
    @DisplayName("SKIPPED + flag=false (explicitly false) → does NOT cascade")
    void skippedWithExplicitFalseDoesNotCascade() {
        Map<String, Object> meta = new HashMap<>();
        meta.put(ExecutionMetadataKeys.CASCADE_SKIP_TO_SUCCESSORS, false);
        NodeExecutionResult skipped = new NodeExecutionResult(
            "core:x",
            NodeStatus.SKIPPED,
            Map.of(),
            Optional.of("not cascading"),
            meta,
            0
        );
        assertThat(UnifiedExecutionEngine.shouldCascadeSkipFromResult(skipped)).isFalse();
    }
}
