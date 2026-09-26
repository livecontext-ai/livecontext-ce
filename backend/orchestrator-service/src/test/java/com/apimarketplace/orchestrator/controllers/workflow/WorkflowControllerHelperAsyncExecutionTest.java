package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowExecution;
import com.apimarketplace.orchestrator.lifecycle.LocalRunExecutionTracker;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A run started by {@code POST /execute} or {@code POST /{workflowId}/runs/{runId}/start} runs
 * on the ForkJoin common pool, which nothing awaits at shutdown. It must be counted by the
 * {@link LocalRunExecutionTracker} the drain waits on, for its whole life including the error
 * write, or a rolling restart cuts it mid-node.
 */
@DisplayName("WorkflowControllerHelper.startAsyncExecution - counted for the shutdown drain")
class WorkflowControllerHelperAsyncExecutionTest {

    private final WorkflowExecutionService executionService = mock(WorkflowExecutionService.class);
    private final LocalRunExecutionTracker tracker = new LocalRunExecutionTracker();
    private final WorkflowExecution execution = mock(WorkflowExecution.class);

    private WorkflowControllerHelper helper() {
        WorkflowControllerHelper helper = new WorkflowControllerHelper();
        ReflectionTestUtils.setField(helper, "executionService", executionService);
        ReflectionTestUtils.setField(helper, "runTracker", tracker);
        when(execution.getRunId()).thenReturn("run-1");
        return helper;
    }

    @Test
    @DisplayName("a run executing on the common pool is counted until it ends (regression: deploy cut runs mid-node)")
    void runIsCountedWhileExecuting() throws Exception {
        WorkflowControllerHelper helper = helper();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
            return null;
        }).when(executionService).execute(same(execution));

        helper.startAsyncExecution(execution, "org-1");

        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(tracker.activeCount()).isEqualTo(1);
        release.countDown();
        waitUntil(() -> tracker.activeCount() == 0);
    }

    @Test
    @DisplayName("the failure write happens while the run is still counted")
    void errorHandlingIsCounted() throws Exception {
        WorkflowControllerHelper helper = helper();
        AtomicInteger countDuringErrorWrite = new AtomicInteger(-1);
        doThrow(new IllegalStateException("node failed")).when(executionService).execute(same(execution));
        doAnswer(inv -> {
            countDuringErrorWrite.set(tracker.activeCount());
            return null;
        }).when(executionService).handleExecutionError(same(execution), any());

        helper.startAsyncExecution(execution, "org-1");

        waitUntil(() -> tracker.activeCount() == 0);
        assertThat(countDuringErrorWrite.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("an error escaping the inner handler is handled inside the counted task, then released")
    void criticalErrorHandledInsideCountedTask() throws Exception {
        WorkflowControllerHelper helper = helper();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger countDuringSecondWrite = new AtomicInteger(-1);
        doThrow(new IllegalStateException("node failed")).when(executionService).execute(same(execution));
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("error write failed");
            }
            countDuringSecondWrite.set(tracker.activeCount());
            return null;
        }).when(executionService).handleExecutionError(same(execution), any());

        helper.startAsyncExecution(execution, "org-1");

        waitUntil(() -> tracker.activeCount() == 0);
        verify(executionService, times(2)).handleExecutionError(same(execution), any());
        assertThat(countDuringSecondWrite.get()).isEqualTo(1);
    }

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
