package com.apimarketplace.common.scaling.queue;

import java.util.Optional;

/**
 * Abstraction for a distributed priority queue.
 * <p>
 * InMemory (single-instance mode): backed by a PriorityBlockingQueue.
 * Redis (multi-instance mode): backed by Redis Streams with weighted fair queuing.
 *
 * @param <T> the payload type
 */
public interface DistributedPriorityQueue<T> {

    /**
     * Push a message onto the queue.
     *
     * @param message the message to enqueue
     */
    void push(QueueMessage<T> message);

    /**
     * Dequeue the highest-priority message available for this consumer.
     *
     * @param consumerId identifier of the consumer (for ownership tracking)
     * @return the next message, or empty if the queue is empty
     */
    Optional<QueueMessage<T>> dequeue(String consumerId);

    /**
     * Acknowledge successful processing of a message.
     *
     * @param messageId the ID of the message to acknowledge
     */
    void acknowledge(String messageId);

    /**
     * Acknowledge successful processing of a message.
     *
     * <p>Implementations that need broker-specific receipt metadata should override
     * this method. The default path preserves the original message-id contract.
     *
     * @param message the message to acknowledge
     */
    default void acknowledge(QueueMessage<T> message) {
        acknowledge(message.getId());
    }

    /**
     * Returns whether the queue backend is reachable for enqueue/dequeue work.
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Get the total number of pending messages in the queue.
     */
    long size();

    /**
     * Get the number of pending messages at a specific priority level.
     *
     * @param priority the priority tier
     */
    long size(int priority);
}
