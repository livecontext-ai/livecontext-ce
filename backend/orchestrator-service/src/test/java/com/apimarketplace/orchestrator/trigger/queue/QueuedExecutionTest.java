package com.apimarketplace.orchestrator.trigger.queue;

import com.apimarketplace.orchestrator.trigger.TriggerType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueuedExecutionTest {

    @Test
    void higherPriorityComesFirst() {
        QueuedExecution free = createExecution(0);
        QueuedExecution enterprise = createExecution(70);

        assertTrue(enterprise.compareTo(free) < 0, "Enterprise should come before Free");
        assertTrue(free.compareTo(enterprise) > 0, "Free should come after Enterprise");
    }

    @Test
    void samePriorityUseFifo() {
        QueuedExecution first = createExecution(20);
        QueuedExecution second = createExecution(20);

        assertTrue(first.compareTo(second) < 0, "Earlier submission should come first");
        assertTrue(second.compareTo(first) > 0, "Later submission should come after");
    }

    @Test
    void sortingProducesCorrectOrder() {
        QueuedExecution free1 = createExecution(0);
        QueuedExecution pro = createExecution(20);
        QueuedExecution free2 = createExecution(0);
        QueuedExecution enterprise = createExecution(70);

        List<QueuedExecution> list = new ArrayList<>(List.of(free1, pro, free2, enterprise));
        Collections.sort(list);

        assertEquals(70, list.get(0).getPriority(), "Enterprise first");
        assertEquals(20, list.get(1).getPriority(), "Pro second");
        // free1 should come before free2 (FIFO within same priority)
        assertTrue(list.get(2).getSequenceNumber() < list.get(3).getSequenceNumber(),
            "FIFO within same priority");
    }

    @Test
    void isExpiredReturnsTrueWhenTimedOut() {
        QueuedExecution item = createExecution(0);
        // Just created, so should not be expired with a reasonable timeout
        assertFalse(item.isExpired(300));
        // Should be expired with 0 timeout
        assertTrue(item.isExpired(0));
    }

    @Test
    void cancelMarksCancelled() {
        QueuedExecution item = createExecution(0);
        assertFalse(item.isCancelled());
        item.cancel();
        assertTrue(item.isCancelled());
    }

    @Test
    void futureCompletionWorks() {
        QueuedExecution item = createExecution(0);
        assertFalse(item.getFuture().isDone());

        item.complete(null);
        assertTrue(item.getFuture().isDone());
    }

    private QueuedExecution createExecution(int priority) {
        return new QueuedExecution(null, "trigger:test", TriggerType.MANUAL, Map.of(), priority);
    }

    @Test
    void startedItemCannotBeCancelled() {
        QueuedExecution item = new QueuedExecution(null, "trigger:t", TriggerType.MANUAL, Map.of(), 1);

        assertTrue(item.tryStart());
        assertFalse(item.tryCancel(), "a started item must report that it could not be cancelled");
        assertFalse(item.isCancelled());
    }

    @Test
    void cancelledItemCannotBeStarted() {
        QueuedExecution item = new QueuedExecution(null, "trigger:t", TriggerType.MANUAL, Map.of(), 1);

        assertTrue(item.tryCancel());
        assertTrue(item.tryCancel(), "cancelling twice stays cancelled");
        assertFalse(item.tryStart(), "a worker must skip a cancelled item");
        assertTrue(item.isCancelled());
    }

    @Test
    void racingStartAndCancelHaveExactlyOneWinner() throws Exception {
        for (int i = 0; i < 500; i++) {
            QueuedExecution item = new QueuedExecution(null, "trigger:t", TriggerType.MANUAL, Map.of(), 1);
            java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean started = new java.util.concurrent.atomic.AtomicBoolean();
            Thread worker = new Thread(() -> {
                try { go.await(); } catch (InterruptedException ignored) { }
                started.set(item.tryStart());
            });
            worker.start();
            go.countDown();
            boolean cancelled = item.tryCancel();
            worker.join();
            assertNotEquals(started.get(), cancelled, "exactly one of start and cancel must win");
        }
    }
}
