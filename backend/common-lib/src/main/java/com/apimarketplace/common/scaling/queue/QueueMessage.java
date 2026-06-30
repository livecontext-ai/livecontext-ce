package com.apimarketplace.common.scaling.queue;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A message wrapper for the distributed priority queue.
 *
 * @param <T> the payload type
 */
public class QueueMessage<T> {

    private final String id;
    private final T payload;
    private final int priority;
    private final Instant createdAt;
    private final Map<String, String> metadata;

    public QueueMessage(String id, T payload, int priority, Instant createdAt, Map<String, String> metadata) {
        this.id = id;
        this.payload = payload;
        this.priority = priority;
        this.createdAt = createdAt;
        this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Convenience constructor that auto-generates an ID and sets createdAt to now.
     */
    public QueueMessage(T payload, int priority, Map<String, String> metadata) {
        this(UUID.randomUUID().toString(), payload, priority, Instant.now(), metadata);
    }

    /**
     * Convenience constructor with no metadata.
     */
    public QueueMessage(T payload, int priority) {
        this(payload, priority, Map.of());
    }

    public String getId() {
        return id;
    }

    public T getPayload() {
        return payload;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    @Override
    public String toString() {
        return "QueueMessage{id='" + id + "', priority=" + priority + ", createdAt=" + createdAt + '}';
    }
}
