package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.orchestrator.config.ShedLockConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two properties that keep delivery off the thread that just committed a run: the listener
 * runs AFTER_COMMIT on the dedicated pool, and that pool DROPS on saturation instead of running
 * the send on the caller. Deleting either annotation, or the handler, fails here.
 */
@DisplayName("Notification delivery wiring")
class NotificationDeliveryWiringTest {

    @Test
    @DisplayName("onCreated is AFTER_COMMIT (with fallback) and runs on notificationDeliveryExecutor")
    void onCreatedIsAsyncAfterCommit() throws Exception {
        Method m = NotificationDeliveryService.class.getMethod("onCreated", NotificationCreatedEvent.class);

        assertThat(m.getAnnotation(Async.class).value()).isEqualTo("notificationDeliveryExecutor");
        TransactionalEventListener l = m.getAnnotation(TransactionalEventListener.class);
        assertThat(l.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(l.fallbackExecution()).isTrue();
    }

    @Test
    @DisplayName("onProductionSuccess runs on notificationDeliveryExecutor")
    void onProductionSuccessIsAsync() throws Exception {
        Method m = NotificationDeliveryService.class.getMethod("onProductionSuccess", UUID.class);

        assertThat(m.getAnnotation(Async.class).value()).isEqualTo("notificationDeliveryExecutor");
    }

    @Test
    @DisplayName("The emitter's success listener (recovery of schedule/webhook/chat/form workflows) is AFTER_COMMIT")
    void epochSucceededListenerIsAfterCommit() throws Exception {
        Method m = com.apimarketplace.orchestrator.services.notification.NotificationEmitter.class
                .getMethod("onEpochSucceeded", com.apimarketplace.orchestrator.services.events.WorkflowEpochSucceededEvent.class);

        assertThat(m.getAnnotation(TransactionalEventListener.class).phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("A saturated pool drops the delivery; it never runs it on the caller's thread")
    void saturatedPoolDropsInsteadOfCallerRuns() throws Exception {
        ThreadPoolTaskExecutor pool = (ThreadPoolTaskExecutor)
                new ShedLockConfig().notificationDeliveryExecutor(new SimpleMeterRegistry());
        CountDownLatch release = new CountDownLatch(1);
        try {
            Runnable block = () -> {
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
            };
            pool.execute(block);
            pool.execute(block);
            for (int i = 0; i < pool.getQueueCapacity(); i++) pool.execute(block);
            Thread caller = Thread.currentThread();
            AtomicBoolean ranOnCaller = new AtomicBoolean(false);

            pool.execute(() -> ranOnCaller.set(Thread.currentThread() == caller));

            assertThat(ranOnCaller).isFalse();
        } finally {
            release.countDown();
            pool.shutdown();
        }
    }
}
