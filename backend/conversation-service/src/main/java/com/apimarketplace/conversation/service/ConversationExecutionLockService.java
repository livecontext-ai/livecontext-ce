package com.apimarketplace.conversation.service;

import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Serializes synchronous agent execution per conversation.
 *
 * <p>Agent tasks for the same agent reuse the same conversation. Several task rows may be
 * {@code in_progress} at once, but only one sync chat turn for that conversation may run.
 */
@Service
public class ConversationExecutionLockService {

    private static final int SINGLE_CONVERSATION_PERMIT = 1;
    private static final String LOCK_KEY_PREFIX = "conversation-execution:";
    static final long DEFAULT_MAX_WAIT_MS = TimeUnit.MINUTES.toMillis(300);

    private final DistributedSemaphore semaphore;

    @Value("${conversation.execution-lock.max-wait-ms:18000000}")
    private long maxWaitMs = DEFAULT_MAX_WAIT_MS;

    @Value("${conversation.execution-lock.poll-interval-ms:250}")
    private long pollIntervalMs = 250L;

    public ConversationExecutionLockService(DistributedSemaphore semaphore) {
        this.semaphore = semaphore;
    }

    public <T> T withConversationLock(String conversationId, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }

        String lockKey = LOCK_KEY_PREFIX + conversationId;
        String ownerId = UUID.randomUUID().toString();
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxWaitMs);
        boolean acquired = false;
        try {
            acquired = acquire(lockKey, ownerId, deadlineNanos);
            return action.get();
        } finally {
            if (acquired) {
                semaphore.release(lockKey, ownerId);
            }
        }
    }

    private boolean acquire(String lockKey, String ownerId, long deadlineNanos) {
        while (true) {
            if (semaphore.tryAcquire(lockKey, SINGLE_CONVERSATION_PERMIT, ownerId)) {
                return true;
            }

            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new ConversationExecutionLockTimeoutException(lockKey);
            }

            long sleepMs = Math.min(Math.max(1L, pollIntervalMs),
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConversationExecutionLockInterruptedException(lockKey, e);
            }
        }
    }

    public static class ConversationExecutionLockTimeoutException extends RuntimeException {
        public ConversationExecutionLockTimeoutException(String lockKey) {
            super("Timed out waiting for conversation execution lock: " + lockKey);
        }
    }

    public static class ConversationExecutionLockInterruptedException extends RuntimeException {
        public ConversationExecutionLockInterruptedException(String lockKey, InterruptedException cause) {
            super("Interrupted waiting for conversation execution lock: " + lockKey, cause);
        }
    }
}
