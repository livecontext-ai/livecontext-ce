package com.apimarketplace.orchestrator.execution.v2.split;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.nodes.BaseNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeType;
import com.apimarketplace.orchestrator.execution.v2.services.NodeCompletionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * LC-064, the half of it that is not a ceiling: WHERE the per-item fan-out runs.
 *
 * <p>It used to be submitted to {@link ForkJoinPool#commonPool()}, which is JVM-global and shared
 * with every parallel stream and every other subsystem in the process, so one tenant's oversized
 * split starved work that had nothing to do with workflows. The pool swap had no test at either
 * changed call site, which is how a later "simplification" back to the common pool, or to a plain
 * fixed pool, would land unnoticed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Split fan-out pool (LC-064)")
class SplitFanOutPoolTest {

    private static final String RUN_ID = "run1";
    private static final String NODE_ID = "mcp:step1";
    private static final String SPLIT_KEY = "core:split1";

    @Mock private SplitContextManager contextManager;
    @Mock private NodeCompletionService nodeCompletionService;
    @Mock private ExecutionContext context;

    private SplitAwareNodeExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("every item runs on the INJECTED executor, never on the JVM common pool")
    void fanOutRunsOnTheInjectedExecutor() {
        AtomicInteger submissions = new AtomicInteger();
        ThreadPoolExecutor injected = new ThreadPoolExecutor(
                2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread t = new Thread(runnable, "split-fan-out-test-worker");
                    t.setDaemon(true);
                    return t;
                }) {
            @Override
            public void execute(Runnable command) {
                submissions.incrementAndGet();
                super.execute(command);
            }
        };
        executor = new SplitAwareNodeExecutor(
                contextManager, nodeCompletionService, null, null, null, null, injected);

        SplitContext splitContext = SplitContext.create(SPLIT_KEY + ":0", List.of("a", "b", "c"));
        when(contextManager.findActiveContext(eq(RUN_ID), eq(NODE_ID), eq(0), any()))
                .thenReturn(Optional.of(splitContext));
        when(context.withGlobalData(any(), any())).thenReturn(context);
        when(context.withItemIndex(anyInt())).thenReturn(context);
        lenient().when(context.state())
                .thenReturn(com.apimarketplace.orchestrator.execution.v2.state.ExecutionState.create());
        lenient().when(context.plan()).thenReturn(null);

        CopyOnWriteArraySet<String> threads = new CopyOnWriteArraySet<>();
        RecordingNode node = new RecordingNode(NODE_ID, threads);
        node.setPredecessors(List.of(SPLIT_KEY));
        Map<String, ExecutionNode> nodeMap = new HashMap<>();
        nodeMap.put(SPLIT_KEY, new RecordingNode(SPLIT_KEY, NodeType.SPLIT, threads));
        nodeMap.put(NODE_ID, node);

        executor.execute(node, context, RUN_ID, nodeMap);

        assertThat(submissions.get())
                .as("one submission per item, all to the injected executor")
                .isGreaterThanOrEqualTo(3);
        assertThat(threads)
                .as("items must not run on the shared JVM pool")
                .isNotEmpty()
                .allSatisfy(name -> assertThat(name).startsWith("split-fan-out-test-worker"));
    }

    @Test
    @DisplayName("the PRODUCTION constructor builds a dedicated, bounded ForkJoinPool - not the common pool")
    void productionPoolIsDedicatedBoundedAndStillForkJoin() {
        executor = new SplitAwareNodeExecutor(
                contextManager, nodeCompletionService, null, null, null, null);

        Object pool = ReflectionTestUtils.getField(executor, "executorService");

        assertThat(pool)
                .as("the common pool is JVM-global: one tenant's fan-out starved everything else")
                .isNotSameAs(ForkJoinPool.commonPool());
        // Still a ForkJoinPool on purpose: a per-item task can block on a NESTED fan-out, and
        // CompletableFuture.get inside an FJ worker registers as a ManagedBlocker, so the pool
        // spawns a compensation thread instead of deadlocking. A plain fixed pool would deadlock
        // exactly where the previous code worked, which is why "just use a fixed pool" is wrong.
        assertThat(pool).isInstanceOf(ForkJoinPool.class);
        assertThat(((ForkJoinPool) pool).getParallelism()).isGreaterThanOrEqualTo(2);
    }

    /** Records the thread each execution ran on. */
    private static class RecordingNode extends BaseNode {
        private final CopyOnWriteArraySet<String> threads;

        RecordingNode(String nodeId, CopyOnWriteArraySet<String> threads) {
            this(nodeId, NodeType.MCP, threads);
        }

        RecordingNode(String nodeId, NodeType type, CopyOnWriteArraySet<String> threads) {
            super(nodeId, type);
            this.threads = threads;
        }

        @Override
        public boolean skipsSplitHandling() {
            return type == NodeType.SPLIT;
        }

        @Override
        public boolean isSplitNode() {
            return type == NodeType.SPLIT;
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            threads.add(Thread.currentThread().getName());
            return NodeExecutionResult.success(nodeId, Map.of());
        }
    }
}
