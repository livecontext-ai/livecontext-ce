package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.execution.v2.engine.NodePolicyRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A split fan-out that fails as a whole already applied the policy to each of its items. A
 * node-level retry around it (the STEP_BY_STEP wrapping) would re-run every item, the ones that
 * already succeeded with side effects included. The runner must propagate it unchanged.
 */
@DisplayName("SplitFanOutFailedException - a node-level retry never re-runs a whole fan-out")
class SplitFanOutFailedExceptionTest {

    @Test
    @DisplayName("REGRESSION: with retryCount 3, a fan-out failure is invoked once and propagates as the same instance")
    void fanOutFailureIsNeverRetried() {
        List<Long> sleeps = new ArrayList<>();
        NodePolicyRunner runner = new NodePolicyRunner(sleeps::add);
        SplitAwareNodeExecutor.SplitFanOutFailedException boom =
            new SplitAwareNodeExecutor.SplitFanOutFailedException("Split item execution failed or timed out", null);
        AtomicInteger invocations = new AtomicInteger();

        assertThatThrownBy(() -> runner.run(new NodePolicy(3, 100L, false), "mcp:each_item", () -> {
            invocations.incrementAndGet();
            throw boom;
        }, null)).isSameAs(boom);

        assertThat(invocations.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
        assertThat(boom).isInstanceOf(NodePolicyRunner.NotRetryable.class).isInstanceOf(RuntimeException.class);
    }
}
