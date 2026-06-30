package com.apimarketplace.orchestrator.execution.v2.async;

import com.apimarketplace.orchestrator.domain.execution.AgentResultMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the ack-before-commit Sites A + B in {@link AgentAsyncCompletionService}.
 *
 * <p>Pre-fix prod incident 2026-05-22 21:01 UTC: {@code registry.consume} GETDEL'd the
 * pending entry from Redis before {@code stepCompletionOrchestrator.complete} finished.
 * JVM crashed in between, recovery found no pending entry, and in-flight items were lost.
 *
 * <p>Post-fix: {@link RedisInFlightStore} is staged immediately after consume succeeds
 * and cleared in a {@code finally} block around the delivery pipeline.
 */
@ExtendWith(MockitoExtension.class)
class AgentAsyncCompletionServiceInFlightAckTest {

    @Mock private PendingAgentRegistry registry;
    @Mock private RedisInFlightStore inFlightStore;

    private AgentAsyncCompletionService service;
    private PendingAgent pending;
    private AgentResultMessage result;

    @BeforeEach
    void setUp() {
        service = new AgentAsyncCompletionService(
            registry, mock(com.apimarketplace.orchestrator.services.completion.StepCompletionOrchestrator.class),
            mock(com.apimarketplace.orchestrator.execution.v2.split.SplitContextManager.class),
            mock(com.apimarketplace.orchestrator.services.streaming.state.RunningNodeTracker.class),
            mock(com.apimarketplace.orchestrator.execution.v2.async.SplitCoalesceTracker.class),
            mock(com.apimarketplace.orchestrator.execution.v2.services.NodeSearchService.class),
            mock(com.apimarketplace.orchestrator.repository.WorkflowRunRepository.class));
        ReflectionTestUtils.setField(service, "inFlightStore", inFlightStore);

        pending = new PendingAgent(
            "cid-test", "run-x", "agent:classify", "Classify", "trigger:cron",
            46, 7, "item-7", "classify", "tenant-1",
            null, null, null, null, null, "deepseek-chat", null, null,
            Instant.now(), null);
        result = new AgentResultMessage(
            "cid-test", "run-x", "agent:classify",
            Map.of("ok", true), true, null, "classify", Instant.now());
    }

    @Test
    @DisplayName("stagesInFlightRecordImmediatelyAfterConsume")
    void stageHappensAfterConsumeSucceeds() {
        when(registry.consume("cid-test")).thenReturn(Optional.of(pending));

        try {
            service.onAgentResult(result);
        } catch (Exception ignored) {
            // Expected: the inner delivery pipeline is intentionally unstubbed.
        }

        InOrder order = inOrder(registry, inFlightStore);
        order.verify(registry).consume("cid-test");
        order.verify(inFlightStore).stage(eq(pending), eq(result));
    }

    @Test
    @DisplayName("e2eDelayKeepsPostStageCrashWindowOpen")
    void e2eDelayKeepsPostStageCrashWindowOpen() throws Exception {
        ReflectionTestUtils.setField(service, "e2eAgentCompletionDelayMs", 200L);
        when(registry.consume("cid-test")).thenReturn(Optional.of(pending));
        CountDownLatch staged = new CountDownLatch(1);
        doAnswer(invocation -> {
            staged.countDown();
            return null;
        }).when(inFlightStore).stage(eq(pending), eq(result));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> completion = executor.submit(() -> service.onAgentResult(result));

            assertThat(staged.await(100, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(completion.isDone()).isFalse();
            completion.get(2, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        InOrder order = inOrder(registry, inFlightStore);
        order.verify(registry).consume("cid-test");
        order.verify(inFlightStore).stage(eq(pending), eq(result));
        order.verify(inFlightStore).clear("cid-test");
    }

    @Test
    @DisplayName("clearsInFlightRecordInFinallyBlock")
    void clearHappensInFinallyEvenWhenDeliverThrows() {
        when(registry.consume("cid-test")).thenReturn(Optional.of(pending));

        try {
            service.onAgentResult(result);
        } catch (Exception ignored) {
            // Expected: deliverUnderLock throws inside the try.
        }

        verify(inFlightStore, times(1)).clear("cid-test");
    }

    @Test
    @DisplayName("doesNotStageOrClearWhenConsumeReturnsEmpty")
    void noStageWhenConsumeReturnsEmpty() {
        when(registry.consume("cid-unknown")).thenReturn(Optional.empty());
        AgentResultMessage unknownResult = new AgentResultMessage(
            "cid-unknown", "run", "node", Map.of(), true, null, "agent", Instant.now());

        boolean accepted = service.onAgentResult(unknownResult);

        assertThat(accepted).isFalse();
        verify(inFlightStore, never()).stage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(inFlightStore, never()).clear(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("replayInFlightResultBypassesRegistryConsume")
    void replayInFlightResultBypassesRegistryConsume() {
        boolean accepted = service.replayInFlightResult(new RedisInFlightStore.InFlightEntry(pending, result));

        assertThat(accepted).isFalse();
        verify(registry, never()).consume("cid-test");
        verify(inFlightStore, never()).stage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(inFlightStore, times(1)).clear("cid-test");
    }
}
