package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F2.4 - short inline waits (≤3s) must be interruptible by a run cancel
 * signal. Pre-fix, {@code Thread.sleep(durationMs)} held the worker for the
 * full configured duration regardless of STOP. With many parallel branches
 * of short waits, that compounded into seconds of wasted blocking after the
 * user STOP.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitNode - F2.4 inline wait interruptible by cancel signal")
class WaitNodeCancelTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private WorkflowRedisPublisher publisher;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-1", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    private void wirePublisher(WaitNode node) throws Exception {
        Field f = WaitNode.class.getDeclaredField("workflowRedisPublisher");
        f.setAccessible(true);
        f.set(node, publisher);
    }

    @Test
    @DisplayName("Cancel during 3s wait - node bails within ~100ms with cancelled output")
    void cancelDuringInlineWaitBailsFast() throws Exception {
        WaitNode node = new WaitNode("core:wait", 3_000L);
        wirePublisher(node);

        AtomicInteger checks = new AtomicInteger(0);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(publisher.isAgentCancelSignalSet(anyString()))
            .thenAnswer(inv -> {
                if (checks.incrementAndGet() >= 2) {
                    cancelled.set(true);
                }
                return cancelled.get();
            });

        long start = System.currentTimeMillis();
        NodeExecutionResult result = node.execute(context);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorMessage().orElse("")).containsIgnoringCase("cancelled");
        assertThat(result.output().get("cancelled")).isEqualTo(true);
        assertThat(elapsed)
            .as("STOP must release within a few slice cycles, not the full 3s")
            .isLessThan(800);
    }

    @Test
    @DisplayName("No publisher (legacy path) - wait still completes normally; no NPE")
    void noPublisherLegacyPathStillWorks() {
        WaitNode node = new WaitNode("core:wait", 100L);

        long start = System.currentTimeMillis();
        NodeExecutionResult result = node.execute(context);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isSuccess()).isTrue();
        assertThat(elapsed).isGreaterThanOrEqualTo(90).isLessThan(500);
    }

    @Test
    @DisplayName("No cancel during wait - completes normally even with publisher wired")
    void noCancelDuringWaitCompletesNormally() throws Exception {
        WaitNode node = new WaitNode("core:wait", 200L);
        wirePublisher(node);
        lenient().when(publisher.isAgentCancelSignalSet(anyString())).thenReturn(false);

        long start = System.currentTimeMillis();
        NodeExecutionResult result = node.execute(context);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isSuccess()).isTrue();
        assertThat(elapsed).isGreaterThanOrEqualTo(190);
    }

    @Test
    @DisplayName("durationMs=0 short-circuits - no sleep, no cancel poll, success")
    void zeroDurationShortCircuits() throws Exception {
        WaitNode node = new WaitNode("core:wait", 0L);
        wirePublisher(node);

        NodeExecutionResult result = node.execute(context);
        assertThat(result.isSuccess()).isTrue();
    }
}
