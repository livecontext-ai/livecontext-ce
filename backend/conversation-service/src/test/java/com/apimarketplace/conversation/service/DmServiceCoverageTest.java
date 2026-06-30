package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.DmMessageDto;
import com.apimarketplace.conversation.dto.DmThreadDto;
import com.apimarketplace.conversation.entity.DmMessage;
import com.apimarketplace.conversation.entity.DmThread;
import com.apimarketplace.conversation.repository.DmMessageRepository;
import com.apimarketplace.conversation.repository.DmThreadRepository;
import com.apimarketplace.conversation.streaming.DmEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for read/format paths of {@link DmService} that the main suite left untested:
 * {@code getThread} success/404/403, {@code listMessages} page mapping, the 280-char preview
 * clamp, and the defensive {@code parseAttachments} malformed-JSON degradation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DmService - read/format coverage")
class DmServiceCoverageTest {

    @Mock private DmThreadRepository threadRepository;
    @Mock private DmMessageRepository messageRepository;
    @Mock private AttachmentService attachmentService;
    @Mock private DmEventPublisher eventPublisher;
    @InjectMocks private DmService service;

    private static DmThread thread(String id, String a, String b) {
        DmThread t = new DmThread(a, b);
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "createdAt", Instant.parse("2026-01-01T00:00:00Z"));
        return t;
    }

    private static DmMessage message(String id, String threadId, String sender, String content) {
        DmMessage m = new DmMessage(threadId, sender, content);
        ReflectionTestUtils.setField(m, "id", id);
        ReflectionTestUtils.setField(m, "createdAt", Instant.parse("2026-01-02T00:00:00Z"));
        return m;
    }

    @Nested
    @DisplayName("getThread")
    class GetThread {

        @Test
        @DisplayName("returns the thread as seen by a participant")
        void returnsForParticipant() {
            when(threadRepository.findById("t1")).thenReturn(Optional.of(thread("t1", "7", "8")));
            when(messageRepository.countUnreadForUser(eq("t1"), eq("7"))).thenReturn(2L);

            DmThreadDto dto = service.getThread("7", "t1");

            assertThat(dto.id()).isEqualTo("t1");
            assertThat(dto.otherUserId()).isEqualTo("8");
            assertThat(dto.unreadCount()).isEqualTo(2L);
        }

        @Test
        @DisplayName("404 when the thread is missing")
        void notFoundWhenMissing() {
            when(threadRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getThread("7", "missing"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("403 when the caller is not a participant")
        void forbiddenForNonParticipant() {
            when(threadRepository.findById("t1")).thenReturn(Optional.of(thread("t1", "7", "8")));

            assertThatThrownBy(() -> service.getThread("9", "t1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("listMessages")
    class ListMessages {

        @Test
        @DisplayName("maps the newest-first page of messages for a participant")
        void mapsPageForParticipant() {
            when(threadRepository.findById("t1")).thenReturn(Optional.of(thread("t1", "7", "8")));
            Pageable pageable = PageRequest.of(0, 20);
            when(messageRepository.findByThreadIdOrderByCreatedAtDesc("t1", pageable))
                    .thenReturn(new PageImpl<>(List.of(message("m1", "t1", "7", "hello"))));

            Page<DmMessageDto> page = service.listMessages("7", "t1", pageable);

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0).content()).isEqualTo("hello");
            assertThat(page.getContent().get(0).senderUserId()).isEqualTo("7");
        }

        @Test
        @DisplayName("a malformed attachments column degrades to an empty list (no throw)")
        void malformedAttachmentsDegradeToEmpty() {
            when(threadRepository.findById("t1")).thenReturn(Optional.of(thread("t1", "7", "8")));
            DmMessage m = message("m1", "t1", "7", "see file");
            m.setAttachments("{ this is not valid json");
            Pageable pageable = PageRequest.of(0, 20);
            when(messageRepository.findByThreadIdOrderByCreatedAtDesc("t1", pageable))
                    .thenReturn(new PageImpl<>(List.of(m)));

            Page<DmMessageDto> page = service.listMessages("7", "t1", pageable);

            assertThat(page.getContent().get(0).attachments()).isEmpty();
        }
    }

    @Nested
    @DisplayName("sendMessage preview")
    class Preview {

        @Test
        @DisplayName("clamps lastMessagePreview to 280 chars")
        void clampsPreviewTo280() {
            when(threadRepository.findById("t1")).thenReturn(Optional.of(thread("t1", "7", "8")));
            when(messageRepository.save(any(DmMessage.class))).thenAnswer(inv -> {
                DmMessage m = inv.getArgument(0);
                ReflectionTestUtils.setField(m, "id", "m1");
                ReflectionTestUtils.setField(m, "createdAt", Instant.parse("2026-01-02T00:00:00Z"));
                return m;
            });

            // 300 chars: under the 4000 message-content cap, over the 280 preview cap.
            service.sendMessage("7", "t1", "x".repeat(300));

            ArgumentCaptor<DmThread> captor = ArgumentCaptor.forClass(DmThread.class);
            verify(threadRepository).save(captor.capture());
            assertThat(captor.getValue().getLastMessagePreview()).hasSize(280);
        }
    }
}
