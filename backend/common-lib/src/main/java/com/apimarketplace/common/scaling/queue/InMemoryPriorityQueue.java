package com.apimarketplace.common.scaling.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.PriorityBlockingQueue;

/**
 * In-memory implementation of {@link DistributedPriorityQueue}.
 * Uses a PriorityBlockingQueue ordered by priority (ascending) then createdAt (ascending).
 *
 * @param <T> the payload type
 */
public class InMemoryPriorityQueue<T> implements DistributedPriorityQueue<T> {

    private static final Logger log = LoggerFactory.getLogger(InMemoryPriorityQueue.class);

    private static final int INITIAL_CAPACITY = 64;

    private final PriorityBlockingQueue<QueueMessage<T>> queue;

    public InMemoryPriorityQueue() {
        Comparator<QueueMessage<T>> comparator = Comparator
                .<QueueMessage<T>>comparingInt(QueueMessage::getPriority)
                .thenComparing(QueueMessage::getCreatedAt);
        this.queue = new PriorityBlockingQueue<>(INITIAL_CAPACITY, comparator);
    }

    @Override
    public void push(QueueMessage<T> message) {
        queue.add(message);
        log.debug("Pushed message '{}' with priority {}", message.getId(), message.getPriority());
    }

    @Override
    public Optional<QueueMessage<T>> dequeue(String consumerId) {
        QueueMessage<T> message = queue.poll();
        if (message != null) {
            log.debug("Consumer '{}' dequeued message '{}'", consumerId, message.getId());
        }
        return Optional.ofNullable(message);
    }

    @Override
    public void acknowledge(String messageId) {
        // In-memory queue: messages are removed on dequeue, so acknowledge is a no-op.
        // In Redis mode, this ACKs the message in the consumer group's PEL.
        log.debug("Acknowledged message '{}'", messageId);
    }

    @Override
    public long size() {
        return queue.size();
    }

    @Override
    public long size(int priority) {
        return queue.stream()
                .filter(m -> m.getPriority() == priority)
                .count();
    }
}
