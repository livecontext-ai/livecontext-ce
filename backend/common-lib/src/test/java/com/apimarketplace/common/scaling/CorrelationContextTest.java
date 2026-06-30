package com.apimarketplace.common.scaling;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CorrelationContextTest {

    @AfterEach
    void tearDown() {
        CorrelationContext.clear();
    }

    @Test
    void get_returns_empty_when_not_set() {
        assertEquals(Optional.empty(), CorrelationContext.get());
    }

    @Test
    void set_and_get() {
        CorrelationContext.set("corr-123");
        assertEquals(Optional.of("corr-123"), CorrelationContext.get());
    }

    @Test
    void clear_removes_value() {
        CorrelationContext.set("corr-123");
        CorrelationContext.clear();
        assertEquals(Optional.empty(), CorrelationContext.get());
    }

    @Test
    void set_overwrites_previous_value() {
        CorrelationContext.set("corr-1");
        CorrelationContext.set("corr-2");
        assertEquals(Optional.of("corr-2"), CorrelationContext.get());
    }

    @Test
    void thread_isolation() throws InterruptedException {
        CorrelationContext.set("main-thread");

        AtomicReference<Optional<String>> childValue = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread child = new Thread(() -> {
            // Child thread should not see the main thread's value
            childValue.set(CorrelationContext.get());
            latch.countDown();
        });
        child.start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(Optional.empty(), childValue.get());
        assertEquals(Optional.of("main-thread"), CorrelationContext.get());
    }

    @Test
    void child_thread_has_independent_context() throws InterruptedException {
        CorrelationContext.set("main-corr");

        AtomicReference<Optional<String>> childSeen = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread child = new Thread(() -> {
            CorrelationContext.set("child-corr");
            childSeen.set(CorrelationContext.get());
            CorrelationContext.clear();
            latch.countDown();
        });
        child.start();

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(Optional.of("child-corr"), childSeen.get());
        // Main thread value unaffected
        assertEquals(Optional.of("main-corr"), CorrelationContext.get());
    }
}
