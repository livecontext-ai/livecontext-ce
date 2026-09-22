package com.apimarketplace.orchestrator.execution.v2.adhoc;

import com.apimarketplace.orchestrator.execution.v2.engine.CoreNodeBuilder;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionNodeFactory;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionServiceInjector;
import com.apimarketplace.orchestrator.execution.v2.nodes.ExecutionNode;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import com.apimarketplace.orchestrator.services.persistence.OutputSchemaMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;

/**
 * Batched {@code run_node}: N inputs, one call.
 *
 * <p>The properties pinned here are the ones a reader cannot check by looking: that items really
 * run concurrently rather than one after another, that a bad item is contained instead of
 * poisoning the batch, that the whole-call deadline degrades per item instead of collapsing the
 * result, and that each item is an independent execution - which is what makes "batching buys
 * fewer turns, not a new capability" true rather than merely intended.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdHocNodeExecutionServiceBatchTest {

    @Mock private CoreNodeBuilder coreNodeBuilder;
    // Generate is built by the FACTORY, from the plan's agents, so a probe of
    // an AI node never reaches the core builder at all.
    @Mock private ExecutionNodeFactory executionNodeFactory;
    @Mock private ExecutionServiceInjector serviceInjector;
    @Mock private OutputSchemaMapper outputSchemaMapper;
    @Mock private com.apimarketplace.orchestrator.services.credit.NodeCreditGate nodeCreditGate;
    @Mock private ExecutionNode node;

    private AdHocNodeExecutionService service;

    @BeforeEach
    void setUp() {
        service = new AdHocNodeExecutionService(coreNodeBuilder, executionNodeFactory, serviceInjector, outputSchemaMapper, nodeCreditGate);
        lenient().when(nodeCreditGate.denyOrNull(any(), any())).thenReturn(null);
        lenient().when(outputSchemaMapper.transformToDbSchema(any(), any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A template whose config is irrelevant: what varies between items is the run input. */
    private AdHocNodeRequest template() {
        return new AdHocNodeRequest("http_request", "http_request", new LinkedHashMap<>(Map.of("method", "GET")),
                Map.of(), "tenant-1", "org-1", "OWNER", "Probe");
    }

    private List<Map<String, Object>> inputs(int count) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(Map.of("index", i));
        }
        return items;
    }

    /**
     * Install a node whose execute() body is supplied by the test.
     *
     * <p>Stubbed with an answer rather than a fixed return so the body can observe concurrency:
     * "how many items were in flight at once" is the property these tests exist to pin, and it is
     * invisible to a stub that only returns a value.
     */
    private void nodeExecutes(java.util.function.Function<ExecutionContext, NodeExecutionResult> body) {
        lenient().when(node.execute(any())).thenAnswer(inv -> body.apply(inv.getArgument(0)));
        doAnswer(inv -> {
            Map<String, ExecutionNode> map = inv.getArgument(0);
            map.put("core:probe", node);
            return null;
        }).when(coreNodeBuilder).createCoreNodes(any(), any(), any());
    }

    private static NodeExecutionResult success(Map<String, Object> output) {
        return NodeExecutionResult.success("core:probe", output);
    }

    @Test
    @Timeout(90)
    @DisplayName("items run concurrently: 8 items that each sleep 300ms finish far faster than serially")
    void itemsRunConcurrently() {
        nodeExecutes(ctx -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("ok", true));
        });

        long startedAt = System.currentTimeMillis();
        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(8), 5);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(results).hasSize(8);
        assertThat(results).allMatch(AdHocNodeResult::completed);
        // Serial would be 8 x 300ms = 2400ms. Five at a time is two waves, ~600ms; the bound is
        // deliberately loose so a slow CI box does not fail it, but it still cannot pass serially.
        assertThat(elapsed).isLessThan(1800L);
    }

    @Test
    @Timeout(90)
    @DisplayName("parallelism is honoured: never more than the permitted number of items in flight")
    void parallelismIsBounded() {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        nodeExecutes(ctx -> {
            int now = inFlight.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return success(Map.of("ok", true));
        });

        service.executeBatch(template(), inputs(12), 3);

        // Both bounds: a Semaphore(1) would satisfy "never more than 3" while silently
        // serialising the batch, so the floor matters as much as the ceiling.
        assertThat(peak.get()).isLessThanOrEqualTo(3);
        assertThat(peak.get()).isGreaterThan(1);
    }

    @Test
    @Timeout(90)
    @DisplayName("each item is its own execution: distinct ad-hoc run ids, one credit gate call per item")
    void eachItemIsAnIndependentExecution() {
        Set<String> runIds = ConcurrentHashMap.newKeySet();
        nodeExecutes(ctx -> {
            runIds.add(ctx.runId());
            return success(Map.of("ok", true));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(6), 5);

        assertThat(results).hasSize(6);
        assertThat(runIds).hasSize(6);
        assertThat(runIds).allMatch(id -> id.startsWith("adhoc-"));
        // The gate the engine applies per node is applied per ITEM, not once for the batch:
        // batching must not be a way to pay for one execution and get six.
        org.mockito.Mockito.verify(nodeCreditGate, org.mockito.Mockito.times(6))
                .denyOrNull(any(), any());
    }

    @Test
    @Timeout(90)
    @DisplayName("the credit gate and the node build run under the CALLER's workspace, not an unscoped pool thread")
    void gatesRunUnderTheCallersWorkspaceScope() {
        // Counting six gate calls proves nothing about WHICH balance was checked. The gate reads
        // the organization from a plain thread-local that does not cross a pool hop, so without an
        // explicit re-bind the batch checks a different workspace than a single run_node does -
        // and "credit for credit" would be false while the call count stayed right.
        Set<String> scopeAtGate = ConcurrentHashMap.newKeySet();
        Set<String> roleAtGate = ConcurrentHashMap.newKeySet();
        Set<String> scopeAtBuild = ConcurrentHashMap.newKeySet();
        lenient().when(nodeCreditGate.denyOrNull(any(), any())).thenAnswer(inv -> {
            scopeAtGate.add(String.valueOf(
                    com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId()));
            roleAtGate.add(String.valueOf(
                    com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationRole()));
            return null;
        });
        doAnswer(inv -> {
            scopeAtBuild.add(String.valueOf(
                    com.apimarketplace.common.web.TenantResolver.currentRequestOrganizationId()));
            Map<String, ExecutionNode> map = inv.getArgument(0);
            map.put("core:probe", node);
            return null;
        }).when(coreNodeBuilder).createCoreNodes(any(), any(), any());
        lenient().when(node.execute(any())).thenReturn(success(Map.of("ok", true)));

        service.executeBatch(template(), inputs(4), 2);

        assertThat(scopeAtGate).containsExactly("org-1");
        assertThat(scopeAtBuild).containsExactly("org-1");
        // The ROLE travels with the id. Binding the id alone leaves every role gate on its
        // null-role branch, which is how a restricted-agent incident looks from the inside.
        assertThat(roleAtGate).containsExactly("OWNER");
    }

    @Test
    @Timeout(90)
    @DisplayName("each item sees its own run input, so a per-item template resolves per item")
    void eachItemSeesItsOwnInput() {
        Set<Object> seen = ConcurrentHashMap.newKeySet();
        nodeExecutes(ctx -> {
            Object stepData = ctx.stepOutputs() != null ? ctx.stepOutputs().get("index") : null;
            if (stepData != null) {
                seen.add(stepData);
            }
            return success(Map.of("ok", true));
        });

        service.executeBatch(template(), inputs(5), 5);

        assertThat(seen).containsExactlyInAnyOrder(0, 1, 2, 3, 4);
    }

    @Test
    @Timeout(90)
    @DisplayName("one item throwing does not stop the others: it fails alone and the rest complete")
    void aThrowingItemIsContained() {
        nodeExecutes(ctx -> {
            Object index = ctx.stepOutputs() != null ? ctx.stepOutputs().get("index") : null;
            if (Integer.valueOf(3).equals(index)) {
                throw new IllegalStateException("host unreachable");
            }
            return success(Map.of("ok", true));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(6), 5);

        assertThat(results).hasSize(6);
        assertThat(results.get(3).completed()).isFalse();
        assertThat(results.get(3).error()).contains("host unreachable");
        for (int i = 0; i < 6; i++) {
            if (i != 3) {
                assertThat(results.get(i).completed())
                        .as("item %s should be unaffected by item 3 failing", i).isTrue();
            }
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("results keep input order even when items finish out of order")
    void resultsKeepInputOrder() {
        nodeExecutes(ctx -> {
            Object index = ctx.stepOutputs() != null ? ctx.stepOutputs().get("index") : null;
            int i = index instanceof Integer n ? n : 0;
            try {
                // Reverse the finishing order: the last item completes first.
                Thread.sleep((5 - i) * 40L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("seen", i));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(5), 5);

        for (int i = 0; i < 5; i++) {
            assertThat(results.get(i).output()).containsEntry("seen", i);
        }
    }

    @Test
    @Timeout(90)
    @DisplayName("a credit denial on one item denies only that item")
    void creditDenialIsPerItem() {
        AtomicInteger calls = new AtomicInteger();
        lenient().when(nodeCreditGate.denyOrNull(any(), any())).thenAnswer(inv ->
                calls.incrementAndGet() == 2
                        ? NodeExecutionResult.failure("core:probe", "Out of credits")
                        : null);
        nodeExecutes(ctx -> success(Map.of("ok", true)));

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(4), 1);

        assertThat(results).hasSize(4);
        assertThat(results.stream().filter(AdHocNodeResult::completed)).hasSize(3);
        assertThat(results.stream().filter(r -> !r.completed())).hasSize(1);
    }

    @Test
    @Timeout(90)
    @DisplayName("the shared pool survives a batch: a second batch runs normally after the first")
    void sharedPoolIsNotShutDown() {
        nodeExecutes(ctx -> success(Map.of("ok", true)));

        service.executeBatch(template(), inputs(3), 2);
        List<AdHocNodeResult> second = service.executeBatch(template(), inputs(3), 2);

        assertThat(second).hasSize(3);
        assertThat(second).allMatch(AdHocNodeResult::completed);
    }

    @Test
    @Timeout(90)
    @DisplayName("a single input behaves exactly like the one-item path")
    void singleItemBatch() {
        nodeExecutes(ctx -> success(Map.of("value", 42)));

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(1), 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).completed()).isTrue();
        assertThat(results.get(0).output()).containsEntry("value", 42);
    }

    @Test
    @Timeout(90)
    @DisplayName("an entry the batch never started gets its own status and no elapsed time, not a timeout")
    void neverStartedItemsSayTheyNeverRan() {
        // The distinction matters: an item that never ran had no effect and is safe to resend,
        // while one that was stopped mid-flight may already have had one.
        service.batchDeadlineSeconds = 1;
        CountDownLatch release = new CountDownLatch(1);
        nodeExecutes(ctx -> {
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("slow", true));
        });

        // Parallelism 1 with 3 items: the first occupies the only permit past the deadline, so
        // items 2 and 3 are never submitted at all.
        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(3), 1);
        release.countDown();

        assertThat(results).hasSize(3);
        // Its own status, not TIMED_OUT: a timed-out entry was running and may already have had its
        // effect, a never-started one had none and is safe to resend. And no elapsed time, because
        // it consumed none and the batch total is the sum of the entries.
        assertThat(results.get(1).status()).isEqualTo(AdHocNodeResult.NOT_STARTED);
        assertThat(results.get(1).durationMs()).isZero();
        assertThat(results.get(1).error()).contains("never ran").contains("ran out of time");
        assertThat(results.get(1).note())
                .as("a spent budget IS the case where a smaller batch helps")
                .contains("smaller batch");
    }

    @Test
    @Timeout(90)
    @DisplayName("at the whole-call deadline, unfinished items come back TIMED_OUT and finished ones keep their real result")
    void deadlineDegradesPerItemInsteadOfFailingTheBatch() {
        service.batchDeadlineSeconds = 1;
        CountDownLatch release = new CountDownLatch(1);
        nodeExecutes(ctx -> {
            Object index = ctx.stepOutputs() != null ? ctx.stepOutputs().get("index") : null;
            if (Integer.valueOf(0).equals(index)) {
                return success(Map.of("fast", true));
            }
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("slow", true));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(3), 3);
        release.countDown();

        assertThat(results).hasSize(3);
        // The item that finished keeps its real output - reporting it as timed out would send the
        // agent to redo work that already had its effect.
        assertThat(results.get(0).completed()).isTrue();
        assertThat(results.get(0).output()).containsEntry("fast", true);
        // The rest are individually TIMED_OUT, and the batch is NOT a blanket failure.
        assertThat(results.get(1).status()).isEqualTo(AdHocNodeResult.TIMED_OUT);
        assertThat(results.get(2).status()).isEqualTo(AdHocNodeResult.TIMED_OUT);
        // Its OWN elapsed time, not the batch's: these are summed into the total the agent uses
        // to size the next batch, and a whole-call figure per entry inflates it by the fan-out.
        assertThat(results.get(1).durationMs()).isLessThanOrEqualTo(1500L);
        assertThat(results.get(2).durationMs()).isLessThanOrEqualTo(1500L);
        // The message must quote the SAME figure it is printed beside. Asserting only that it
        // avoids the batch budget is not enough: with a 1s budget both readings print "1", so the
        // assertion has to tie the text to this entry's own duration_ms.
        assertThat(results.get(1).error())
                .as("the timeout message and duration_ms must not disagree")
                .contains(results.get(1).durationMs() + "ms");
    }

    @Test
    @Timeout(90)
    @DisplayName("an entry already finished when its turn to collect comes round keeps its real result")
    void anEntryAlreadyDoneAtCollectionKeepsItsResult() {
        // The deadline is spent by the FIRST entry, so the second is collected with no budget left.
        // Cancelling it there would report TIMED_OUT for work that finished long ago and may have
        // had a side effect, which is the one thing the past-deadline branch exists to prevent.
        service.batchDeadlineSeconds = 1;
        nodeExecutes(ctx -> {
            Object index = ctx.stepOutputs() != null ? ctx.stepOutputs().get("index") : null;
            boolean slow = Integer.valueOf(0).equals(index);
            try {
                Thread.sleep(slow ? 4000 : 100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("fast", !slow));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(2), 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).status()).isEqualTo(AdHocNodeResult.TIMED_OUT);
        assertThat(results.get(1).completed())
                .as("the entry that had already finished must keep its result")
                .isTrue();
        assertThat(results.get(1).output()).containsEntry("fast", true);
    }

    @Test
    @Timeout(90)
    @DisplayName("an entry the pool refuses is reported NOT_STARTED, saying so, without disturbing the others")
    void aRejectedEntryIsContainedAndNamedHonestly() throws Exception {
        // The pool can refuse (shutting down, out of threads). Letting that throw would destroy
        // every result already collected, and blaming the clock would send the agent to make the
        // batch smaller when the size was never the problem.
        // One worker plus one queue slot: the first two entries are accepted, the third refused.
        // A SynchronousQueue would race the worker's start and could refuse the first entry too,
        // which would prove nothing about containment.
        java.util.concurrent.ExecutorService rejecting = new java.util.concurrent.ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(1),
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        service.batchDeadlineSeconds = 2;
        java.lang.reflect.Field f = AdHocNodeExecutionService.class.getDeclaredField("executor");
        f.setAccessible(true);
        java.util.concurrent.ExecutorService original =
                (java.util.concurrent.ExecutorService) f.get(service);
        f.set(service, rejecting);
        nodeExecutes(ctx -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("ok", true));
        });

        List<AdHocNodeResult> results;
        try {
            results = service.executeBatch(template(), inputs(3), 3);
        } finally {
            f.set(service, original);
            rejecting.shutdownNow();
        }

        assertThat(results).hasSize(3);
        assertThat(results.stream().filter(r -> AdHocNodeResult.NOT_STARTED.equals(r.status())))
                .as("a refused entry is never started, not failed").isNotEmpty();
        // Deliberately NOT asserting that another entry completed: every entry needs a second
        // thread for the inner task execute() submits, so a pool small enough to refuse cannot let
        // any entry finish. What this test can prove is that a refusal is contained as a value
        // instead of thrown, which is the invariant the class states.
        assertThat(results).allMatch(r -> r.status() != null);
        assertThat(results.stream()
                .filter(r -> AdHocNodeResult.NOT_STARTED.equals(r.status()))
                .allMatch(r -> r.error().contains("could not start") && r.durationMs() == 0L))
                .as("it names the real reason and charges no time").isTrue();
    }

    @Test
    @Timeout(90)
    @DisplayName("a refused entry gets the remedy for a refusal, not the one for an oversized batch")
    void aRefusedEntryCarriesItsOwnRemedy() {
        // A fixed "send a smaller batch" suffix was wrong for two of the three reasons: neither a
        // refusal nor an interrupt has anything to do with how many entries were sent, and acting
        // on it wastes a turn. The remedy has to travel with the reason.
        service.batchDeadlineSeconds = 5;
        java.util.concurrent.ExecutorService real = java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.atomic.AtomicInteger submits = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ExecutorService refusing = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { real.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return real.shutdownNow(); }
            @Override public boolean isShutdown() { return real.isShutdown(); }
            @Override public boolean isTerminated() { return real.isTerminated(); }
            @Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
                return real.awaitTermination(t, u);
            }
            @Override public void execute(Runnable command) {
                // At parallelism 1 the order is deterministic: entry 0's task, entry 0's inner
                // task, then entry 1's task. Refusing the third refuses the middle entry.
                if (submits.incrementAndGet() == 3) {
                    throw new java.util.concurrent.RejectedExecutionException("refused on purpose");
                }
                real.execute(command);
            }
        };
        java.lang.reflect.Field f;
        java.util.concurrent.ExecutorService original;
        try {
            f = AdHocNodeExecutionService.class.getDeclaredField("executor");
            f.setAccessible(true);
            original = (java.util.concurrent.ExecutorService) f.get(service);
            f.set(service, refusing);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        nodeExecutes(ctx -> success(Map.of("ok", true)));

        List<AdHocNodeResult> results;
        try {
            results = service.executeBatch(template(), inputs(3), 1);
        } finally {
            try {
                f.set(service, original);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            real.shutdownNow();
        }

        assertThat(results).hasSize(3);
        assertThat(results.get(1).status()).isEqualTo(AdHocNodeResult.NOT_STARTED);
        assertThat(results.get(1).error()).contains("could not start");
        assertThat(results.get(1).note())
                .as("the remedy is in note, where both help surfaces say to look")
                .isNotBlank()
                .doesNotContain("smaller batch");
        assertThat(results.get(2).completed())
                .as("the entry after the refusal still runs").isTrue();
    }

    @Test
    @Timeout(90)
    @DisplayName("an entry not started because the call was interrupted says so, not that time ran out")
    void anInterruptedBatchNamesTheInterrupt() {
        // Three reasons can leave an entry unstarted and they call for different things. Blaming
        // the clock for an interrupt tells the agent to send a smaller batch, which would not help.
        service.batchDeadlineSeconds = 30;
        Thread caller = Thread.currentThread();
        nodeExecutes(ctx -> {
            caller.interrupt();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("ok", true));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(3), 1);
        Thread.interrupted(); // clear the flag so it cannot leak into the next test

        assertThat(results).hasSize(3);
        assertThat(results.stream().filter(r -> AdHocNodeResult.NOT_STARTED.equals(r.status())))
                .as("the interrupt stops later entries from starting").isNotEmpty();
        assertThat(results.stream()
                .filter(r -> AdHocNodeResult.NOT_STARTED.equals(r.status()))
                .allMatch(r -> r.error().contains("interrupted")
                        && (r.note() == null || !r.note().contains("smaller batch"))))
                .as("they name the interrupt, and do not advise a smaller batch").isTrue();
        // The entry that was RUNNING when the interrupt landed may already have had its effect, so
        // it must not read as a failure the agent should fix and resend. Whichever of the two
        // handlers caught the interrupt, the answer is the same one.
        assertThat(results.get(0).status())
                .as("an interrupted in-flight entry is stopped or finished, never failed")
                .isIn(AdHocNodeResult.TIMED_OUT, AdHocNodeResult.COMPLETED);
    }

    @Test
    @Timeout(90)
    @DisplayName("an entry that starts late is charged its OWN clock, not the batch's")
    void aLateStartingEntryIsChargedItsOwnClock() {
        // The record removed the misaligned lists, but not the possibility of stamping every entry
        // with the batch's start. A test whose entries all begin at once cannot tell the two
        // apart, so this forces a serial batch where the last entry starts seconds in and then
        // times out: charged the batch clock it would report roughly the whole call.
        service.batchDeadlineSeconds = 3;
        nodeExecutes(ctx -> {
            Object index = ctx.stepOutputs() != null ? ctx.stepOutputs().get("index") : null;
            try {
                // Entry 0 burns most of the budget; entry 1 then starts late and never finishes.
                Thread.sleep(Integer.valueOf(0).equals(index) ? 2000 : 20_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("ok", true));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(2), 1);

        assertThat(results).hasSize(2);
        assertThat(results.get(1).status()).isEqualTo(AdHocNodeResult.TIMED_OUT);
        assertThat(results.get(1).durationMs())
                .as("it ran for the remainder of the budget, not the whole call")
                .isLessThan(1800L);
    }

    @Test
    @Timeout(90)
    @DisplayName("more entries than the cap are refused here, not run because a caller forgot to check")
    void theEntryCapIsEnforcedByTheMethodItProtects() {
        // The class comment justifies holding two threads per entry BY this ceiling, so leaving it
        // to the surface makes the design's own precondition someone else's responsibility. The
        // empty case is already defended in-method; defending the harmless one and delegating the
        // harmful one was the asymmetry.
        List<Map<String, Object>> tooMany = inputs(AdHocNodeExecutionService.MAX_BATCH_ITEMS + 1);

        assertThatThrownBy(() -> service.executeBatch(template(), tooMany, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(AdHocNodeExecutionService.MAX_BATCH_ITEMS));
    }

    @Test
    @Timeout(90)
    @DisplayName("a parallelism of zero runs the batch one at a time instead of deadlocking on no permits")
    void aNonPositiveParallelismStillRuns() {
        // A Semaphore(0) hands out nothing, so every entry would wait out the whole deadline and
        // come back never-started: a silent, total failure from a number a caller can pass by
        // accident. One at a time is the honest floor.
        service.batchDeadlineSeconds = 10;
        nodeExecutes(ctx -> success(Map.of("ok", true)));

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(2), 0);

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(AdHocNodeResult::completed);
    }

    @Test
    @Timeout(90)
    @DisplayName("a parallelism above the cap is clamped to it, not honoured")
    void parallelismIsClampedToTheCap() {
        // Same reason as the entry cap: the thread cost is bounded by parallelism, and a caller
        // asking for 200 would get 400 threads for one call.
        service.batchDeadlineSeconds = 10;
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        nodeExecutes(ctx -> {
            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            inFlight.decrementAndGet();
            return success(Map.of("ok", true));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(
                AdHocNodeExecutionService.MAX_BATCH_PARALLELISM + 3), 200);

        assertThat(results).allMatch(AdHocNodeResult::completed);
        assertThat(peak.get())
                .as("never more at once than the cap allows")
                .isLessThanOrEqualTo(AdHocNodeExecutionService.MAX_BATCH_PARALLELISM);
    }

    @Test
    @Timeout(90)
    @DisplayName("entries waiting their turn hold no pool thread, which is what makes the thread cost bounded")
    void waitingEntriesDoNotOccupyPoolThreads() {
        // The permit is taken on the CALLER's thread, before submit. Acquiring it inside the task
        // instead would park every entry in a pool thread waiting its turn, so one call would hold
        // parallelism + N threads rather than 2 x parallelism - and no test that counts concurrent
        // node executions can see the difference, because both shapes run one node at a time.
        // Counting SUBMISSIONS can: with the permit taken first, entry N+1 is not submitted until
        // an earlier entry has finished.
        service.batchDeadlineSeconds = 15;
        java.util.concurrent.ExecutorService real = java.util.concurrent.Executors.newCachedThreadPool();
        AtomicInteger outstanding = new AtomicInteger();
        AtomicInteger peakOutstanding = new AtomicInteger();
        java.util.concurrent.ExecutorService counting = new java.util.concurrent.AbstractExecutorService() {
            @Override public void shutdown() { real.shutdown(); }
            @Override public List<Runnable> shutdownNow() { return real.shutdownNow(); }
            @Override public boolean isShutdown() { return real.isShutdown(); }
            @Override public boolean isTerminated() { return real.isTerminated(); }
            @Override public boolean awaitTermination(long t, TimeUnit u) throws InterruptedException {
                return real.awaitTermination(t, u);
            }
            @Override public void execute(Runnable command) {
                peakOutstanding.accumulateAndGet(outstanding.incrementAndGet(), Math::max);
                real.execute(() -> {
                    try {
                        command.run();
                    } finally {
                        outstanding.decrementAndGet();
                    }
                });
            }
        };
        java.lang.reflect.Field f;
        java.util.concurrent.ExecutorService original;
        try {
            f = AdHocNodeExecutionService.class.getDeclaredField("executor");
            f.setAccessible(true);
            original = (java.util.concurrent.ExecutorService) f.get(service);
            f.set(service, counting);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        nodeExecutes(ctx -> {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("ok", true));
        });

        List<AdHocNodeResult> results;
        try {
            results = service.executeBatch(template(), inputs(8), 2);
        } finally {
            try {
                f.set(service, original);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
            real.shutdownNow();
        }

        assertThat(results).allMatch(AdHocNodeResult::completed);
        // Two threads per RUNNING entry (the entry's task plus the inner one execute() submits),
        // and nothing for the six still queued behind them: 2 x parallelism at rest.
        //
        // The bound allows one more per running entry, and that is a property of the MEASUREMENT,
        // not slack in the contract. `permits.release()` runs in a finally INSIDE the submitted
        // task, while this wrapper decrements in its own finally, which only runs once that task
        // has returned. So an entry that has just released its permit is still counted when the
        // caller thread, now unblocked, submits the next one. Up to `parallelism` entries can sit
        // in that window at once. Only the outer task can: execute() blocks on future.get(), so
        // the inner one has already finished and decremented by then.
        //
        // 3 x parallelism still fails loudly on the regression this exists to catch. Acquiring the
        // permit inside the task instead would park all eight entries in pool threads at once, for
        // a peak near 16, not 6.
        int parallelism = 2;
        assertThat(peakOutstanding.get())
                .as("bounded by parallelism, not one thread per entry")
                .isLessThanOrEqualTo(3 * parallelism);
    }

    @Test
    @Timeout(90)
    @DisplayName("an interrupted batch hands the caller its interrupt back instead of swallowing it")
    void theCallersInterruptSurvivesTheBatch() {
        // Catching InterruptedException clears the flag. A batch that does not restore it has
        // eaten the only signal telling its caller to stop: the tool call returns as if nothing
        // happened, and every later blocking wait on that thread runs its full budget. Restoring
        // it is also what makes the REST of this batch fail fast rather than wait out the deadline
        // once per remaining entry.
        service.batchDeadlineSeconds = 30;
        Thread caller = Thread.currentThread();
        AtomicInteger call = new AtomicInteger();
        nodeExecutes(ctx -> {
            // ONCE. A body that interrupts on every entry would keep setting the flag again, so a
            // batch that swallowed it would still look correct at the end.
            if (call.getAndIncrement() == 0) {
                caller.interrupt();
            }
            return success(Map.of("ok", true));
        });

        service.executeBatch(template(), inputs(3), 1);

        boolean stillInterrupted = Thread.interrupted(); // reads AND clears, so it cannot leak
        assertThat(stillInterrupted)
                .as("the caller is told it was interrupted, rather than the batch keeping it")
                .isTrue();
    }

    @Test
    @Timeout(90)
    @DisplayName("a node cut off by an interrupt is reported stopped, the same as one cut off by the clock")
    void anInterruptedNodeIsReportedStoppedNotFailed() {
        // Two handlers answer the same question - the node was running and was cut off - and they
        // used to answer it differently: FAILED here, TIMED_OUT one layer up. FAILED reads as
        // "this configuration is wrong, fix it and resend", so an all-interrupted batch reached
        // the all-failed verdict and printed the one message the batch layer exists to prevent.
        Thread caller = Thread.currentThread();
        nodeExecutes(ctx -> {
            caller.interrupt();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("ok", true));
        });

        AdHocNodeResult result = service.execute(template());
        Thread.interrupted(); // clear the flag so it cannot leak into the next test

        assertThat(result.status())
                .as("it was running and was stopped, which is not the same as failing")
                .isEqualTo(AdHocNodeResult.TIMED_OUT);
        assertThat(result.error())
                .as("and it warns that the effect may already have happened")
                .contains("NOT undone");
    }

    @Test
    @Timeout(90)
    @DisplayName("an entry refused for want of time never reaches the node at all")
    void aRefusedEntryNeverReachesTheNode() {
        // NOT_STARTED is a promise about the outside world, not a label: it tells the agent the
        // entry had no effect and is safe to resend, so what has to be pinned is that the node was
        // never entered - not merely that the status says so. A batch that submitted the entry and
        // cancelled it would report TIMED_OUT instead, and the two mean opposite things.
        //
        // Note on the deadline check that guards the acquire: it cannot be pinned by a test here,
        // because this loop is past its budget only when it was blocked on the semaphore, which
        // means every permit was held at that moment. It is deliberate belt-and-braces for the
        // window where the loop itself is delayed by something other than permits.
        service.batchDeadlineSeconds = 1;
        AtomicInteger started = new AtomicInteger();
        nodeExecutes(ctx -> {
            started.incrementAndGet();
            try {
                // The first entry alone outlives the budget, then releases its permit, so the
                // entries behind it meet a spent deadline AND an idle semaphore.
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return success(Map.of("ok", true));
        });

        List<AdHocNodeResult> results = service.executeBatch(template(), inputs(3), 1);

        assertThat(results).hasSize(3);
        assertThat(results.get(1).status())
                .as("the budget is spent, so this entry is refused rather than started and cancelled")
                .isEqualTo(AdHocNodeResult.NOT_STARTED);
        assertThat(results.get(2).status()).isEqualTo(AdHocNodeResult.NOT_STARTED);
        assertThat(started.get())
                .as("and it really never ran: exactly one entry ever reached the node")
                .isEqualTo(1);
    }

    @Test
    @Timeout(90)
    @DisplayName("a null list returns an empty list, because the contract says never null")
    void aNullListIsNotANullReturn() {
        // The contract on this method is "one result per input, never null and never short", and
        // a caller written against it will iterate what it gets back. Returning null for null
        // would honour neither half and would fail at the caller, one frame away from the cause.
        assertThat(service.executeBatch(template(), null, 1)).isEmpty();
    }

    @Test
    @Timeout(90)
    @DisplayName("an empty list returns nothing rather than a report that calls itself both complete and all-failed")
    void anEmptyListIsNotAReport() {
        assertThat(service.executeBatch(template(), List.of(), 5)).isEmpty();
    }
}
