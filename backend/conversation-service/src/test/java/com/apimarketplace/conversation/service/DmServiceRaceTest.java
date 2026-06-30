package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.DmThreadDto;
import com.apimarketplace.conversation.entity.DmThread;
import com.apimarketplace.conversation.repository.DmMessageRepository;
import com.apimarketplace.conversation.repository.DmThreadRepository;
import com.apimarketplace.conversation.streaming.DmEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Concurrency coverage for {@link DmService#openOrGetThread}. The thread id is a
 * Hibernate-generated UUID, so the new-thread INSERT only flushes at commit; the fix
 * routes it through a {@code REQUIRES_NEW} proxy ({@code saveThreadInNewTransaction}) and
 * catches the {@code dm_threads_unique_pair} violation, re-fetching the concurrent winner
 * instead of bubbling a 500. These tests construct the service with a mock {@code self} so
 * the proxy seam is observable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DmService - concurrent openOrGetThread (race convergence)")
class DmServiceRaceTest {

    @Mock private DmThreadRepository threadRepository;
    @Mock private DmMessageRepository messageRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private DmEventPublisher eventPublisher;
    @Mock private DmService self;

    private DmService service;

    @BeforeEach
    void setUp() {
        service = new DmService(threadRepository, messageRepository, attachmentService, eventPublisher, self);
    }

    private static DmThread thread(String id, String a, String b) {
        DmThread t = new DmThread(a, b);
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        return t;
    }

    @Test
    @DisplayName("no race: creates through the REQUIRES_NEW proxy, never a direct save")
    void createsThroughProxyWhenNoRace() {
        when(threadRepository.findByParticipantLoAndParticipantHi("7", "8")).thenReturn(Optional.empty());
        when(self.saveThreadInNewTransaction("7", "8")).thenReturn(thread("t-new", "7", "8"));
        when(messageRepository.countUnreadForUser(any(), eq("7"))).thenReturn(0L);

        DmThreadDto dto = service.openOrGetThread("7", "8");

        assertThat(dto.id()).isEqualTo("t-new");
        assertThat(dto.otherUserId()).isEqualTo("8");
        verify(self).saveThreadInNewTransaction("7", "8");
        verify(threadRepository, never()).save(any());
    }

    @Test
    @DisplayName("lost race: the unique-pair violation re-fetches and returns the winner (no 500)")
    void returnsWinnerOnUniqueViolation() {
        DmThread winner = thread("t-win", "7", "8");
        // First lookup: we think we're first. After the violation, the re-fetch finds the winner.
        when(threadRepository.findByParticipantLoAndParticipantHi("7", "8"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(self.saveThreadInNewTransaction("7", "8"))
                .thenThrow(new DataIntegrityViolationException("dm_threads_unique_pair"));
        when(messageRepository.countUnreadForUser(any(), eq("7"))).thenReturn(0L);

        DmThreadDto dto = service.openOrGetThread("7", "8");

        assertThat(dto.id()).isEqualTo("t-win");
        verify(self).saveThreadInNewTransaction("7", "8");
    }

    @Test
    @DisplayName("rethrows the violation when the re-fetch finds nothing (genuine constraint failure)")
    void rethrowsWhenRefetchEmpty() {
        when(threadRepository.findByParticipantLoAndParticipantHi("7", "8"))
                .thenReturn(Optional.empty(), Optional.empty());
        when(self.saveThreadInNewTransaction("7", "8"))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));

        assertThatThrownBy(() -> service.openOrGetThread("7", "8"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
