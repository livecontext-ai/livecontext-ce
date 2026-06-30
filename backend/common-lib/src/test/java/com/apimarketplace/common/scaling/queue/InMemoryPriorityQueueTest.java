package com.apimarketplace.common.scaling.queue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryPriorityQueueTest {

    private InMemoryPriorityQueue<String> queue;

    @BeforeEach
    void setUp() {
        queue = new InMemoryPriorityQueue<>();
    }

    @Test
    void push_and_dequeue_returns_message() {
        QueueMessage<String> msg = new QueueMessage<>("payload", 1);
        queue.push(msg);

        Optional<QueueMessage<String>> result = queue.dequeue("consumer-1");
        assertTrue(result.isPresent());
        assertEquals("payload", result.get().getPayload());
        assertEquals(1, result.get().getPriority());
    }

    @Test
    void dequeue_empty_queue_returns_empty() {
        Optional<QueueMessage<String>> result = queue.dequeue("consumer-1");
        assertTrue(result.isEmpty());
    }

    @Test
    void dequeue_returns_highest_priority_first() {
        Instant base = Instant.now();
        queue.push(new QueueMessage<>("id-low", "low-priority", 5, base, Map.of()));
        queue.push(new QueueMessage<>("id-high", "high-priority", 1, base, Map.of()));
        queue.push(new QueueMessage<>("id-mid", "mid-priority", 3, base, Map.of()));

        assertEquals("high-priority", queue.dequeue("c").get().getPayload());
        assertEquals("mid-priority", queue.dequeue("c").get().getPayload());
        assertEquals("low-priority", queue.dequeue("c").get().getPayload());
    }

    @Test
    void dequeue_same_priority_returns_oldest_first() {
        Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = Instant.parse("2026-01-01T00:01:00Z");

        queue.push(new QueueMessage<>("id-later", "later", 1, later, Map.of()));
        queue.push(new QueueMessage<>("id-earlier", "earlier", 1, earlier, Map.of()));

        assertEquals("earlier", queue.dequeue("c").get().getPayload());
        assertEquals("later", queue.dequeue("c").get().getPayload());
    }

    @Test
    void size_returns_total_count() {
        assertEquals(0, queue.size());

        queue.push(new QueueMessage<>("a", 1));
        queue.push(new QueueMessage<>("b", 2));
        queue.push(new QueueMessage<>("c", 1));

        assertEquals(3, queue.size());
    }

    @Test
    void size_by_priority_returns_filtered_count() {
        queue.push(new QueueMessage<>("a", 1));
        queue.push(new QueueMessage<>("b", 2));
        queue.push(new QueueMessage<>("c", 1));

        assertEquals(2, queue.size(1));
        assertEquals(1, queue.size(2));
        assertEquals(0, queue.size(5));
    }

    @Test
    void acknowledge_records_message_id() {
        QueueMessage<String> msg = new QueueMessage<>("payload", 1);
        queue.push(msg);

        Optional<QueueMessage<String>> dequeued = queue.dequeue("c");
        assertTrue(dequeued.isPresent());

        // acknowledge should not throw
        assertDoesNotThrow(() -> queue.acknowledge(dequeued.get().getId()));
    }

    @Test
    void size_decreases_after_dequeue() {
        queue.push(new QueueMessage<>("a", 1));
        queue.push(new QueueMessage<>("b", 1));
        assertEquals(2, queue.size());

        queue.dequeue("c");
        assertEquals(1, queue.size());
    }
}
