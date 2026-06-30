package com.apimarketplace.conversation.service;

import com.apimarketplace.common.scaling.lock.DistributedSemaphore;
import com.apimarketplace.conversation.service.ConversationExecutionLockService.ConversationExecutionLockInterruptedException;
import com.apimarketplace.conversation.service.ConversationExecutionLockService.ConversationExecutionLockTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ConversationExecutionLockService}, the per-conversation
 * serialization guard that prevents two synchronous agent turns from running
 * against the same conversation at once.
 *
 * <p>The whole value of this class is its acquire/finally contract: it must
 * (1) acquire exactly one permit keyed by the conversation, (2) ALWAYS release
 * what it acquired - even when the action throws, (3) release with the SAME
 * owner token it acquired with (so a crash-safe semaphore can attribute the
 * permit), and (4) NOT release a permit it never acquired (timeout / interrupt).
 * These tests assert each of those invariants, not just the happy path.
 */
@ExtendWith(MockitoExtension.class)
class ConversationExecutionLockServiceTest {

    private static final String CONVERSATION_ID = "11111111-2222-3333-4444-555555555555";
    private static final String EXPECTED_LOCK_KEY = "conversation-execution:" + CONVERSATION_ID;

    @Mock
    private DistributedSemaphore semaphore;

    private ConversationExecutionLockService newService() {
        ConversationExecutionLockService service = new ConversationExecutionLockService(semaphore);
        // Keep the poll loop snappy for the contention/timeout/interrupt paths.
        ReflectionTestUtils.setField(service, "pollIntervalMs", 5L);
        return service;
    }

    @Test
    @DisplayName("acquires one permit, runs the action, returns its result, then releases")
    void happyPathAcquiresRunsAndReleases() {
        when(semaphore.tryAcquire(eq(EXPECTED_LOCK_KEY), eq(1), anyString())).thenReturn(true);
        ConversationExecutionLockService service = newService();

        String result = service.withConversationLock(CONVERSATION_ID, () -> "done");

        assertThat(result).isEqualTo("done");
        verify(semaphore).tryAcquire(eq(EXPECTED_LOCK_KEY), eq(1), anyString());
        verify(semaphore).release(eq(EXPECTED_LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("releases with the SAME owner token it acquired with")
    void releasesWithTheSameOwnerToken() {
        when(semaphore.tryAcquire(eq(EXPECTED_LOCK_KEY), eq(1), anyString())).thenReturn(true);
        ConversationExecutionLockService service = newService();

        service.withConversationLock(CONVERSATION_ID, () -> "ok");

        ArgumentCaptor<String> acquireOwner = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> releaseOwner = ArgumentCaptor.forClass(String.class);
        verify(semaphore).tryAcquire(anyString(), anyInt(), acquireOwner.capture());
        verify(semaphore).release(anyString(), releaseOwner.capture());
        assertThat(releaseOwner.getValue())
                .as("release must use the owner token that acquired the permit")
                .isEqualTo(acquireOwner.getValue());
    }

    @Test
    @DisplayName("releases the permit even when the action throws")
    void releasesWhenActionThrows() {
        when(semaphore.tryAcquire(eq(EXPECTED_LOCK_KEY), eq(1), anyString())).thenReturn(true);
        ConversationExecutionLockService service = newService();

        RuntimeException boom = new RuntimeException("boom");
        assertThatThrownBy(() -> service.withConversationLock(CONVERSATION_ID, () -> {
            throw boom;
        })).isSameAs(boom);

        verify(semaphore).release(eq(EXPECTED_LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("retries on contention and acquires once a permit frees up")
    void retriesUntilPermitAvailable() {
        // First two polls see the permit held by another turn, the third frees it.
        when(semaphore.tryAcquire(eq(EXPECTED_LOCK_KEY), eq(1), anyString()))
                .thenReturn(false, false, true);
        ConversationExecutionLockService service = newService();
        AtomicInteger ran = new AtomicInteger();

        String result = service.withConversationLock(CONVERSATION_ID, () -> {
            ran.incrementAndGet();
            return "after-wait";
        });

        assertThat(result).isEqualTo("after-wait");
        assertThat(ran.get()).isEqualTo(1);
        verify(semaphore, times(3)).tryAcquire(eq(EXPECTED_LOCK_KEY), eq(1), anyString());
        verify(semaphore).release(eq(EXPECTED_LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("throws timeout and does NOT release when no permit frees before the deadline")
    void throwsTimeoutWithoutReleasing() {
        when(semaphore.tryAcquire(eq(EXPECTED_LOCK_KEY), eq(1), anyString())).thenReturn(false);
        ConversationExecutionLockService service = newService();
        ReflectionTestUtils.setField(service, "maxWaitMs", 25L);
        AtomicInteger ran = new AtomicInteger();

        assertThatThrownBy(() -> service.withConversationLock(CONVERSATION_ID, () -> {
            ran.incrementAndGet();
            return "should-not-run";
        }))
                .isInstanceOf(ConversationExecutionLockTimeoutException.class)
                .hasMessageContaining(EXPECTED_LOCK_KEY);

        assertThat(ran.get()).as("action must never run when the lock is not acquired").isZero();
        // Never acquired → must never release (releasing a foreign permit would corrupt the semaphore).
        verify(semaphore, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("on interrupt throws the interrupted exception, restores the flag, and does NOT release")
    void interruptRestoresFlagAndDoesNotRelease() {
        // Setting the interrupt flag from inside tryAcquire makes the subsequent
        // Thread.sleep in the poll loop throw InterruptedException deterministically.
        when(semaphore.tryAcquire(eq(EXPECTED_LOCK_KEY), eq(1), anyString())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return false;
        });
        ConversationExecutionLockService service = newService();

        assertThatThrownBy(() -> service.withConversationLock(CONVERSATION_ID, () -> "x"))
                .isInstanceOf(ConversationExecutionLockInterruptedException.class)
                .hasMessageContaining(EXPECTED_LOCK_KEY);

        // The service re-asserts the interrupt flag before throwing; assert it is
        // set, and clear it via Thread.interrupted() so it doesn't leak to siblings.
        assertThat(Thread.interrupted()).as("interrupt flag must be restored").isTrue();
        verify(semaphore, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("rejects a blank conversationId before touching the semaphore")
    void rejectsBlankConversationId() {
        ConversationExecutionLockService service = newService();

        assertThatThrownBy(() -> service.withConversationLock("  ", () -> "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");

        verifyNoInteractions(semaphore);
    }

    @Test
    @DisplayName("rejects a null conversationId before touching the semaphore")
    void rejectsNullConversationId() {
        ConversationExecutionLockService service = newService();

        assertThatThrownBy(() -> service.withConversationLock(null, () -> "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conversationId");

        verifyNoInteractions(semaphore);
    }

    @Test
    @DisplayName("rejects a null action before touching the semaphore")
    void rejectsNullAction() {
        ConversationExecutionLockService service = newService();

        assertThatThrownBy(() -> service.withConversationLock(CONVERSATION_ID, (Supplier<String>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("action");

        verifyNoInteractions(semaphore);
    }
}
