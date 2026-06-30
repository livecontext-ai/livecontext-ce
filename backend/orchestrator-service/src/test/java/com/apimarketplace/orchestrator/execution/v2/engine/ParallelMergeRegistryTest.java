package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ParallelMergeRegistry} - the scoped defer/claim registry
 * that guarantees a merge node fires exactly once per fork scope-tree.
 */
@DisplayName("ParallelMergeRegistry")
class ParallelMergeRegistryTest {

    private ExecutionNode node(String nodeId) {
        ExecutionNode node = mock(ExecutionNode.class);
        lenient().when(node.getNodeId()).thenReturn(nodeId);
        return node;
    }

    @Nested
    @DisplayName("defer()")
    class DeferTests {

        @Test
        @DisplayName("deduplicates by nodeId - same merge deferred by two branches appears once")
        void deferDeduplicatesByNodeId() {
            ParallelMergeRegistry registry = ParallelMergeRegistry.root();
            ExecutionNode fromBranchA = node("core:merge");
            ExecutionNode fromBranchB = node("core:merge");

            registry.defer(fromBranchA);
            registry.defer(fromBranchB);

            assertThat(registry.unclaimedDeferred()).hasSize(1);
        }

        @Test
        @DisplayName("defer after claim is a no-op - an executed merge cannot be re-registered")
        void deferAfterClaimIsNoOp() {
            ParallelMergeRegistry registry = ParallelMergeRegistry.root();
            assertThat(registry.tryClaim("core:merge")).isTrue();

            registry.defer(node("core:merge"));

            assertThat(registry.unclaimedDeferred()).isEmpty();
        }
    }

    @Nested
    @DisplayName("tryClaim()")
    class ClaimTests {

        @Test
        @DisplayName("exactly one of N concurrent claimers wins (atomic claim)")
        void exactlyOneConcurrentClaimerWins() throws Exception {
            ParallelMergeRegistry registry = ParallelMergeRegistry.root();
            int threads = 16;
            CountDownLatch startGate = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                CountDownLatch done = new CountDownLatch(threads);
                for (int i = 0; i < threads; i++) {
                    pool.execute(() -> {
                        try {
                            startGate.await();
                            if (registry.tryClaim("core:merge")) {
                                winners.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                startGate.countDown();
                assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            assertThat(winners.get())
                .as("atomic claim must admit exactly one executor for the merge")
                .isEqualTo(1);
            assertThat(registry.isClaimed("core:merge")).isTrue();
        }

        @Test
        @DisplayName("second sequential claim returns false")
        void secondClaimReturnsFalse() {
            ParallelMergeRegistry registry = ParallelMergeRegistry.root();
            assertThat(registry.tryClaim("core:merge")).isTrue();
            assertThat(registry.tryClaim("core:merge")).isFalse();
        }
    }

    @Nested
    @DisplayName("scope chain")
    class ScopeChainTests {

        @Test
        @DisplayName("child shares the root claim set - claim in child blocks claim in root and siblings")
        void childSharesRootClaimSet() {
            ParallelMergeRegistry root = ParallelMergeRegistry.root();
            ParallelMergeRegistry childA = root.child();
            ParallelMergeRegistry childB = root.child();

            assertThat(childA.tryClaim("core:merge")).isTrue();

            assertThat(root.tryClaim("core:merge")).isFalse();
            assertThat(childB.tryClaim("core:merge")).isFalse();
            assertThat(root.isClaimed("core:merge")).isTrue();
        }

        @Test
        @DisplayName("promoteUnclaimedToParent moves only unclaimed merges and clears the child")
        void promotionMovesOnlyUnclaimed() {
            ParallelMergeRegistry root = ParallelMergeRegistry.root();
            ParallelMergeRegistry child = root.child();
            child.defer(node("core:merge_blocked"));
            child.defer(node("core:merge_done"));
            assertThat(child.tryClaim("core:merge_done")).isTrue();

            int promoted = child.promoteUnclaimedToParent();

            assertThat(promoted).isEqualTo(1);
            List<ExecutionNode> rootDeferred = root.unclaimedDeferred();
            assertThat(rootDeferred).hasSize(1);
            assertThat(rootDeferred.get(0).getNodeId()).isEqualTo("core:merge_blocked");
            assertThat(child.unclaimedDeferred()).isEmpty();
        }

        @Test
        @DisplayName("promoteUnclaimedToParent on the root scope throws")
        void promotionOnRootThrows() {
            ParallelMergeRegistry root = ParallelMergeRegistry.root();
            assertThat(root.hasParent()).isFalse();
            assertThatThrownBy(root::promoteUnclaimedToParent)
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("unclaimedDeferred excludes merges claimed after deferral")
    void unclaimedExcludesLaterClaims() {
        ParallelMergeRegistry registry = ParallelMergeRegistry.root();
        registry.defer(node("core:merge_1"));
        registry.defer(node("core:merge_2"));

        assertThat(registry.tryClaim("core:merge_1")).isTrue();

        List<ExecutionNode> unclaimed = registry.unclaimedDeferred();
        assertThat(unclaimed).hasSize(1);
        assertThat(unclaimed.get(0).getNodeId()).isEqualTo("core:merge_2");
    }
}
