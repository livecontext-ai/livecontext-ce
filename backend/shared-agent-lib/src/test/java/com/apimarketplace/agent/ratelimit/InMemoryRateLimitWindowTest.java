package com.apimarketplace.agent.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link InMemoryRateLimitWindow}.
 */
class InMemoryRateLimitWindowTest {

    private InMemoryRateLimitWindow window;

    @BeforeEach
    void setUp() {
        window = new InMemoryRateLimitWindow(60);
    }

    @Test
    void shouldStartEmpty() {
        assertTrue(window.isEmpty());
        assertEquals(0, window.getCount());
        assertEquals(0, window.getSum());
        assertEquals(0, window.getOldestTimestamp());
    }

    @Test
    void shouldTrackAddedEntries() {
        long now = System.currentTimeMillis();
        window.add(now, 100);
        window.add(now + 10, 200);

        assertFalse(window.isEmpty());
        assertEquals(2, window.getCount());
        assertEquals(300, window.getSum());
        assertEquals(now, window.getOldestTimestamp());
    }

    @Test
    void shouldCleanupOldEntries() {
        long now = System.currentTimeMillis();
        window.add(now - 70_000, 100);  // older than 60s window
        window.add(now - 30_000, 200);  // within window
        window.add(now, 300);            // just added

        // Cleanup entries older than (now - 60s)
        window.cleanup(now - 60_000);

        assertEquals(2, window.getCount());
        assertEquals(500, window.getSum());
    }

    @Test
    void shouldReturnOldestTimestamp() {
        long t1 = 1000L;
        long t2 = 2000L;
        window.add(t1, 10);
        window.add(t2, 20);

        assertEquals(t1, window.getOldestTimestamp());
    }

    @Test
    void shouldTrackLastAccessTime() {
        long before = System.currentTimeMillis();
        window.add(before, 10);
        long accessTime = window.getLastAccessTime();
        assertTrue(accessTime >= before);
    }

    @Test
    void shouldHandleCleanupOfAllEntries() {
        window.add(1000L, 50);
        window.add(2000L, 60);

        window.cleanup(3000L); // cutoff after all entries

        assertTrue(window.isEmpty());
        assertEquals(0, window.getSum());
        assertEquals(0, window.getCount());
    }
}
