package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.DmAttachmentDto;
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
import org.springframework.data.domain.PageRequest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DmService")
class DmServiceTest {

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
    @DisplayName("openOrGetThread")
    class OpenOrGet {

        @Test
        @DisplayName("creates a new thread with a normalised pair and returns the other participant")
        void createsNewThreadNormalised() {
            when(threadRepository.findByParticipantLoAndParticipantHi("100", "99")).thenReturn(Optional.empty());
            when(threadRepository.save(any(DmThread.class))).thenAnswer(inv -> inv.getArgument(0));
            when(messageRepository.countUnreadForUser(any(), eq("99"))).thenReturn(0L);

            // "99" vs "100": "100" < "99" by string order, so lo=100, hi=99
            DmThreadDto dto = service.openOrGetThread("99", "100");

            ArgumentCaptor<DmThread> captor = ArgumentCaptor.forClass(DmThread.class);
            verify(threadRepository).save(captor.capture());
            assertThat(captor.getValue().getParticipantLo()).isEqualTo("100");
            assertThat(captor.getValue().getParticipantHi()).isEqualTo("99");
            assertThat(dto.otherUserId()).isEqualTo("100");
        }

        @Test
        @DisplayName("returns the existing thread without creating a duplicate (dedup is order-independent)")
        void returnsExistingThread() {
            DmThread existing = thread("t1", "99", "100");
            when(threadRepository.findByParticipantLoAndParticipantHi("100", "99")).thenReturn(Optional.of(existing));
            when(messageRepository.countUnreadForUser(eq("t1"), eq("99"))).thenReturn(0L);

            // caller is the OTHER user this time; same pair must resolve the same thread
            DmThreadDto dto = service.openOrGetThread("99", "100");

            assertThat(dto.id()).isEqualTo("t1");
            assertThat(dto.otherUserId()).isEqualTo("100");
            verify(threadRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects opening a thread with yourself (400)")
        void rejectsSelf() {
            assertThatThrownBy(() -> service.openOrGetThread("7", "7"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            verify(threadRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a blank recipient (400)")
        void rejectsBlank() {
            assertThatThrownBy(() -> service.openOrGetThread("7", "  "))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("sendMessage")
    class Send {

        @Test
        @DisplayName("persists, bumps the thread preview, and fans out to thread + recipient inbox")
        void persistsAndFansOut() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.save(any(DmMessage.class)))
                    .thenAnswer(inv -> message("m1", "t1", "7", ((DmMessage) inv.getArgument(0)).getContent()));

            DmMessageDto dto = service.sendMessage("7", "t1", "  hello world  ");

            assertThat(dto.content()).isEqualTo("hello world"); // trimmed
            assertThat(t.getLastMessagePreview()).isEqualTo("hello world");
            assertThat(t.getLastMessageAt()).isNotNull();
            verify(threadRepository).save(t);
            verify(eventPublisher).publishMessage(eq("t1"), any(DmMessageDto.class));
            // recipient of 7 in thread (7,8) is 8
            verify(eventPublisher).publishInbox(eq("8"), eq("t1"), any(DmMessageDto.class));
        }

        @Test
        @DisplayName("clamps an over-long message to 4000 chars")
        void clampsLength() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.save(any(DmMessage.class)))
                    .thenAnswer(inv -> message("m1", "t1", "7", ((DmMessage) inv.getArgument(0)).getContent()));

            service.sendMessage("7", "t1", "x".repeat(5000));

            ArgumentCaptor<DmMessage> captor = ArgumentCaptor.forClass(DmMessage.class);
            verify(messageRepository).save(captor.capture());
            assertThat(captor.getValue().getContent()).hasSize(4000);
        }

        @Test
        @DisplayName("rejects a non-participant (403) and never persists")
        void rejectsNonParticipant() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.sendMessage("999", "t1", "hi"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verify(messageRepository, never()).save(any());
            verify(eventPublisher, never()).publishMessage(any(), any());
        }

        @Test
        @DisplayName("rejects an empty message (400)")
        void rejectsEmpty() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.sendMessage("7", "t1", "   "))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("404 when the thread does not exist")
        void notFound() {
            when(threadRepository.findById("nope")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendMessage("7", "nope", "hi"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("CE mode (no WS publisher wired) - send still persists, no NPE")
        void sendWithoutPublisher() {
            // In the CE monolith DmEventPublisher (conversation.streaming) is excluded,
            // so DmService gets a null publisher - the REST path must still work.
            DmService ce = new DmService(threadRepository, messageRepository, attachmentService, null, null);
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.save(any(DmMessage.class)))
                    .thenAnswer(inv -> message("m1", "t1", "7", ((DmMessage) inv.getArgument(0)).getContent()));

            DmMessageDto dto = ce.sendMessage("7", "t1", "hi");

            assertThat(dto.content()).isEqualTo("hi");
            assertThat(t.getLastMessagePreview()).isEqualTo("hi");
        }
    }

    @Nested
    @DisplayName("sendMessage - attachments (1-change DM files)")
    class SendAttachments {

        private DmAttachmentDto att(String id, String name) {
            return new DmAttachmentDto(id, "IMAGE", name, "image/png");
        }

        private static final String SID = "5f0e8c1a-1111-2222-3333-444455556666";

        @Test
        @DisplayName("persists the refs as JSON and echoes them on the returned dto")
        void persistsAttachments() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.save(any(DmMessage.class))).thenAnswer(inv -> {
                DmMessage m = inv.getArgument(0);
                ReflectionTestUtils.setField(m, "id", "m1");
                ReflectionTestUtils.setField(m, "createdAt", Instant.parse("2026-01-02T00:00:00Z"));
                return m;
            });

            DmMessageDto dto = service.sendMessage("7", "t1", "look", List.of(att(SID, "cat.png")));

            assertThat(dto.attachments()).hasSize(1);
            assertThat(dto.attachments().get(0).storageId()).isEqualTo(SID);
            ArgumentCaptor<DmMessage> captor = ArgumentCaptor.forClass(DmMessage.class);
            verify(messageRepository).save(captor.capture());
            assertThat(captor.getValue().getAttachments()).contains(SID).contains("cat.png");
        }

        @Test
        @DisplayName("regression: an attachment-only message (blank content) is accepted with a 📎 preview")
        void attachmentOnlyAllowed() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.save(any(DmMessage.class))).thenAnswer(inv -> inv.getArgument(0));

            DmMessageDto dto = service.sendMessage("7", "t1", "   ", List.of(att(SID, "report.pdf")));

            assertThat(dto.content()).isEmpty();
            assertThat(t.getLastMessagePreview()).isEqualTo("📎 report.pdf");
        }

        @Test
        @DisplayName("text-only send keeps working unchanged (dto carries an empty attachment list)")
        void textOnlyUnchanged() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.save(any(DmMessage.class))).thenAnswer(inv -> inv.getArgument(0));

            DmMessageDto dto = service.sendMessage("7", "t1", "hi", List.of());

            assertThat(dto.attachments()).isEmpty();
            ArgumentCaptor<DmMessage> captor = ArgumentCaptor.forClass(DmMessage.class);
            verify(messageRepository).save(captor.capture());
            assertThat(captor.getValue().getAttachments()).isNull();
        }

        @Test
        @DisplayName("more than 5 attachments → 400, nothing persisted")
        void tooManyRejected() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            List<DmAttachmentDto> six = List.of(
                    att("5f0e8c1a-1111-2222-3333-444455556661", "a"),
                    att("5f0e8c1a-1111-2222-3333-444455556662", "b"),
                    att("5f0e8c1a-1111-2222-3333-444455556663", "c"),
                    att("5f0e8c1a-1111-2222-3333-444455556664", "d"),
                    att("5f0e8c1a-1111-2222-3333-444455556665", "e"),
                    att("5f0e8c1a-1111-2222-3333-444455556666", "f"));

            assertThatThrownBy(() -> service.sendMessage("7", "t1", "x", six))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("non-UUID storageId → 400 (the ref could never resolve at download time)")
        void invalidStorageIdRejected() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.sendMessage("7", "t1", "x",
                    List.of(att("../../etc/passwd", "evil"))))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("an unknown type is coerced to OTHER (never stored verbatim)")
        void unknownTypeCoerced() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.save(any(DmMessage.class))).thenAnswer(inv -> inv.getArgument(0));

            DmMessageDto dto = service.sendMessage("7", "t1", "x",
                    List.of(new DmAttachmentDto(SID, "EXECUTABLE", "f.bin", "application/x-bin")));

            assertThat(dto.attachments().get(0).type()).isEqualTo("OTHER");
        }
    }

    @Nested
    @DisplayName("getAttachment - DM-scoped download access")
    class GetAttachment {

        private static final String SID = "5f0e8c1a-1111-2222-3333-444455556666";

        @Test
        @DisplayName("participant + referenced-in-thread → bytes loaded under the SENDER's tenant")
        void loadsUnderSenderTenant() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.findAttachmentSenderInThread("t1", SID)).thenReturn(Optional.of("8"));
            AttachmentService.AttachmentData data =
                    new AttachmentService.AttachmentData(new byte[]{1}, "image/png", "cat.png");
            when(attachmentService.getAttachmentWithMetadata(java.util.UUID.fromString(SID), "8"))
                    .thenReturn(Optional.of(data));

            // The RECIPIENT (7) downloads a file the SENDER (8) attached.
            assertThat(service.getAttachment("7", "t1", SID)).isSameAs(data);
        }

        @Test
        @DisplayName("regression: a storageId no message of the thread references → 404 (no IDOR by guessing ids)")
        void unreferencedIdRejected() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.findAttachmentSenderInThread("t1", SID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAttachment("7", "t1", SID))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            verifyNoInteractions(attachmentService);
        }

        @Test
        @DisplayName("a non-participant is rejected (403) before any lookup")
        void nonParticipantRejected() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.getAttachment("999", "t1", SID))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(attachmentService);
        }

        @Test
        @DisplayName("malformed storageId → 400")
        void malformedIdRejected() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.getAttachment("7", "t1", "not-a-uuid"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("regression: getAttachment must NOT run readOnly - the storage read path "
                + "UPDATEs storage.accessed_at in the same transaction (readOnly 500'd every "
                + "DM download in prod: 'cannot execute UPDATE in a read-only transaction')")
        void getAttachmentTransactionIsWritable() throws NoSuchMethodException {
            org.springframework.transaction.annotation.Transactional tx = DmService.class
                    .getMethod("getAttachment", String.class, String.class, String.class)
                    .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

            assertThat(tx).as("@Transactional must stay on getAttachment (it spans the "
                    + "participant check + JSONB sender lookup + storage read)").isNotNull();
            assertThat(tx.readOnly()).as("readOnly would make StorageService.getEntityById's "
                    + "accessed_at UPDATE fail on Postgres - every DM attachment download 500s")
                    .isFalse();
        }

        @Test
        @DisplayName("referenced but gone from storage → 404")
        void missingInStorageRejected() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.findAttachmentSenderInThread("t1", SID)).thenReturn(Optional.of("8"));
            when(attachmentService.getAttachmentWithMetadata(java.util.UUID.fromString(SID), "8"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getAttachment("7", "t1", SID))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteThreadForUser - one-sided soft hide")
    class DeleteThread {

        @Test
        @DisplayName("hides the thread for the CALLER only; the other participant keeps it listed")
        void hidesForCallerOnly() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            service.deleteThreadForUser("7", "t1");

            verify(threadRepository).save(t);
            assertThat(t.isHiddenFor("7")).isTrue();
            assertThat(t.isHiddenFor("8")).isFalse();
        }

        @Test
        @DisplayName("regression: a hidden thread disappears from the hider's inbox but not the other's")
        void hiddenThreadFilteredFromList() {
            DmThread t = thread("t1", "7", "8");
            t.hideFor("7");
            when(threadRepository.findThreadsForUser("7")).thenReturn(List.of(t));
            when(threadRepository.findThreadsForUser("8")).thenReturn(List.of(t));

            assertThat(service.listThreads("7")).isEmpty();
            assertThat(service.listThreads("8")).hasSize(1);
        }

        @Test
        @DisplayName("a new message resurfaces the thread for BOTH participants")
        void newMessageUnhidesBoth() {
            DmThread t = thread("t1", "7", "8");
            t.hideFor("7");
            t.hideFor("8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.save(any(DmMessage.class))).thenAnswer(inv -> inv.getArgument(0));

            service.sendMessage("7", "t1", "hello again");

            assertThat(t.isHiddenFor("7")).isFalse();
            assertThat(t.isHiddenFor("8")).isFalse();
        }

        @Test
        @DisplayName("re-opening a deleted conversation resurfaces it for the caller only")
        void reopenUnhidesCaller() {
            DmThread t = thread("t1", "7", "8");
            t.hideFor("7");
            t.hideFor("8");
            when(threadRepository.findByParticipantLoAndParticipantHi("7", "8")).thenReturn(Optional.of(t));
            when(messageRepository.countUnreadForUser(any(), eq("7"))).thenReturn(0L);

            service.openOrGetThread("7", "8");

            assertThat(t.isHiddenFor("7")).isFalse();
            assertThat(t.isHiddenFor("8")).isTrue();
            verify(threadRepository).save(t);
        }

        @Test
        @DisplayName("a non-participant cannot delete (403), nothing saved")
        void nonParticipantRejected() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.deleteThreadForUser("999", "t1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verify(threadRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("markRead")
    class MarkRead {

        @Test
        @DisplayName("publishes a read receipt only when rows were actually updated")
        void publishesWhenUpdated() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.markThreadReadFor(eq("t1"), eq("7"), any(Instant.class))).thenReturn(2);

            int n = service.markRead("7", "t1");

            assertThat(n).isEqualTo(2);
            verify(eventPublisher).publishRead("t1", "7");
        }

        @Test
        @DisplayName("does not publish when nothing was unread")
        void noPublishWhenZero() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));
            when(messageRepository.markThreadReadFor(eq("t1"), eq("7"), any(Instant.class))).thenReturn(0);

            service.markRead("7", "t1");

            verify(eventPublisher, never()).publishRead(any(), any());
        }

        @Test
        @DisplayName("rejects a non-participant (403)")
        void rejectsNonParticipant() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.markRead("999", "t1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("listThreads / listMessages / isParticipant")
    class Reads {

        @Test
        @DisplayName("listThreads resolves the other participant + unread count relative to the caller")
        void listThreadsMapsRelativeToCaller() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findThreadsForUser("7")).thenReturn(List.of(t));
            when(messageRepository.countUnreadForUser("t1", "7")).thenReturn(3L);

            List<DmThreadDto> threads = service.listThreads("7");

            assertThat(threads).hasSize(1);
            assertThat(threads.get(0).otherUserId()).isEqualTo("8");
            assertThat(threads.get(0).unreadCount()).isEqualTo(3L);
        }

        @Test
        @DisplayName("listMessages 403s a non-participant before reading any message")
        void listMessagesDeniesNonParticipant() {
            DmThread t = thread("t1", "7", "8");
            when(threadRepository.findById("t1")).thenReturn(Optional.of(t));

            assertThatThrownBy(() -> service.listMessages("999", "t1", PageRequest.of(0, 30)))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            verify(messageRepository, never()).findByThreadIdOrderByCreatedAtDesc(any(), any());
        }

        @Test
        @DisplayName("isParticipant is true only for the two members, false for others / missing thread")
        void isParticipant() {
            when(threadRepository.findById("t1")).thenReturn(Optional.of(thread("t1", "7", "8")));
            assertThat(service.isParticipant("t1", "7")).isTrue();
            assertThat(service.isParticipant("t1", "8")).isTrue();
            assertThat(service.isParticipant("t1", "999")).isFalse();

            when(threadRepository.findById("missing")).thenReturn(Optional.empty());
            assertThat(service.isParticipant("missing", "7")).isFalse();
        }
    }
}
