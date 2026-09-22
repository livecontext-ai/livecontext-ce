package com.apimarketplace.conversation.integration;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.StreamRepository;
import com.apimarketplace.conversation.service.StreamService;
import com.apimarketplace.common.storage.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;

/**
 * Integration tests for StreamService with real JPA and mocked Redis pub/sub.
 * Tests stream lifecycle management with actual database interactions.
 */
@IntegrationTest
@Import(IntegrationTestConfig.class)
class StreamServiceIntegrationTest {

    @Autowired
    private StreamService streamService;

    @Autowired
    private StreamRepository streamRepository;

    // Spy (not a plain @Autowired) so the Postgres-only FOR KEY SHARE lock that createStream now
    // issues can be stubbed out under H2 (which cannot parse it). All other repository methods
    // (saveAndFlush / deleteAll / finders) delegate to the real bean.
    @MockitoSpyBean
    private ConversationRepository conversationRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private StorageService storageService;

    private static final String USER_ID = "stream-svc-user";

    @BeforeEach
    void setUp() {
        streamRepository.deleteAll();
        conversationRepository.deleteAll();
        // H2 (this @IntegrationTest's DB) cannot parse the Postgres-only FOR KEY SHARE that
        // createStream issues to guard the streams FK; stub it as "present" so these JPA tests
        // exercise the real insert path. The lock's actual blocking semantics are pinned against
        // real Postgres by StreamConversationFkResiliencePostgresIT. doReturn(...) avoids invoking
        // the real (unparseable on H2) query during stubbing.
        doReturn(Optional.of(1)).when(conversationRepository).lockConversationRowIfExists(anyString());
    }

    // ========================== Helper Methods ==========================

    private Conversation persistConversation(String userId, String title) {
        Conversation conv = new Conversation(userId, title, "gpt-4o", "openai");
        conv.setActive(true);
        conv.setUpdatedAt(LocalDateTime.now());
        // V263 fail-loud: OrgScopedEntity requires organizationId on persist.
        // Stamp from userId since this helper persists directly without a request-bound thread.
        // Stream itself is NOT an OrgScopedEntity so no stamp needed.
        conv.setOrganizationId(userId);
        return conversationRepository.saveAndFlush(conv);
    }

    // ========================== Tests ==========================

    @Nested
    @DisplayName("createStream")
    class CreateStream {

        @Test
        @DisplayName("should create a new active stream for a conversation")
        void shouldCreateActiveStream() {
            Conversation conv = persistConversation(USER_ID, "Chat");

            Stream created = streamService.createStream(conv.getId(), "stream-id-001", USER_ID);

            assertThat(created).isNotNull();
            assertThat(created.getStreamId()).isEqualTo("stream-id-001");
            assertThat(created.getStatus()).isEqualTo(Stream.StreamStatus.ACTIVE);
            assertThat(created.getUserId()).isEqualTo(USER_ID);
            assertThat(created.getConversationId()).isEqualTo(conv.getId());
        }

        @Test
        @DisplayName("should stop existing active streams when creating a new one")
        void shouldStopExistingActiveStreamsOnCreate() {
            Conversation conv = persistConversation(USER_ID, "Chat");

            // Create first stream
            Stream first = streamService.createStream(conv.getId(), "stream-001", USER_ID);
            assertThat(first.isActive()).isTrue();

            // Create second stream - should stop the first
            Stream second = streamService.createStream(conv.getId(), "stream-002", USER_ID);
            assertThat(second.isActive()).isTrue();

            // Verify first is stopped
            Optional<Stream> firstAfter = streamRepository.findByStreamId("stream-001");
            assertThat(firstAfter).isPresent();
            assertThat(firstAfter.get().getStatus()).isEqualTo(Stream.StreamStatus.STOPPED);
        }

        @Test
        @DisplayName("re-registering the same streamId reuses the row instead of a duplicate INSERT")
        void reRegisteringSameStreamIdReusesTheExistingRow() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            Stream first = streamService.createStream(conv.getId(), "stream-dup", USER_ID);

            Stream second = streamService.createStream(conv.getId(), "stream-dup", USER_ID);

            assertThat(second).isNotNull();
            assertThat(second.getId())
                .as("the second registration must hand back the SAME row - inserting a second one "
                    + "violates streams_stream_id_key and logged an ERROR per registration in prod")
                .isEqualTo(first.getId());
            assertThat(streamRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("ordering guard: the idempotency check must run BEFORE active streams are stopped")
        void reRegisteringAnActiveStreamLeavesItActive() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            streamService.createStream(conv.getId(), "stream-still-live", USER_ID);

            streamService.createStream(conv.getId(), "stream-still-live", USER_ID);

            // Re-read rather than trusting the returned object, so the assertion does not depend
            // on stopAllActiveStreamsForConversation keeping its clearAutomatically flag.
            assertThat(streamRepository.findByStreamId("stream-still-live"))
                .get()
                .extracting(Stream::getStatus)
                .as("what this really guards is the ORDER. Move the idempotency check after "
                    + "stopAllActiveStreamsForConversation and the row comes back STOPPED - "
                    + "terminating the very stream being re-registered. (It does also fail on "
                    + "pre-fix code, but incidentally: two rows made the Optional finder throw "
                    + "IncorrectResultSizeDataAccessException, which is its siblings' job to "
                    + "report, not this one's.)")
                .isEqualTo(Stream.StreamStatus.ACTIVE);
        }

        @Test
        @DisplayName("refuses to re-register a streamId already bound to another conversation")
        void refusesToRebindAStreamIdToAnotherConversation() {
            Conversation owner = persistConversation(USER_ID, "Owner");
            Conversation other = persistConversation(USER_ID, "Other");
            streamService.createStream(owner.getId(), "stream-bound", USER_ID);

            Stream rebound = streamService.createStream(other.getId(), "stream-bound", USER_ID);

            assertThat(rebound)
                .as("handing back a row from a different conversation would silently mis-attribute "
                    + "the stream; null is the established 'graceful skip' contract here")
                .isNull();
            assertThat(streamRepository.findByStreamId("stream-bound"))
                .get()
                .extracting(Stream::getConversationId)
                .isEqualTo(owner.getId());
        }
    }

    @Nested
    @DisplayName("getActiveStream")
    class GetActiveStream {

        @Test
        @DisplayName("should return active stream for conversation")
        void shouldReturnActiveStream() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            streamService.createStream(conv.getId(), "active-stream", USER_ID);

            Optional<Stream> active = streamService.getActiveStream(conv.getId());

            assertThat(active).isPresent();
            assertThat(active.get().getStreamId()).isEqualTo("active-stream");
        }

        @Test
        @DisplayName("should return empty when no active stream exists")
        void shouldReturnEmptyWhenNoActive() {
            Conversation conv = persistConversation(USER_ID, "Chat");

            Optional<Stream> active = streamService.getActiveStream(conv.getId());

            assertThat(active).isEmpty();
        }
    }

    @Nested
    @DisplayName("markStreamAs*")
    class MarkStreamStatus {

        @Test
        @DisplayName("should mark stream as completed")
        void shouldMarkAsCompleted() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            streamService.createStream(conv.getId(), "to-complete", USER_ID);

            streamService.markStreamAsCompleted("to-complete");

            Stream updated = streamRepository.findByStreamId("to-complete").orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(Stream.StreamStatus.COMPLETED);
            assertThat(updated.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should mark stream as stopped")
        void shouldMarkAsStopped() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            streamService.createStream(conv.getId(), "to-stop", USER_ID);

            streamService.markStreamAsStopped("to-stop");

            Stream updated = streamRepository.findByStreamId("to-stop").orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(Stream.StreamStatus.STOPPED);
            assertThat(updated.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should mark stream as error with message")
        void shouldMarkAsError() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            streamService.createStream(conv.getId(), "to-error", USER_ID);

            streamService.markStreamAsError("to-error", "Connection timeout");

            Stream updated = streamRepository.findByStreamId("to-error").orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(Stream.StreamStatus.ERROR);
            assertThat(updated.getErrorMessage()).isEqualTo("Connection timeout");
        }
    }

    @Nested
    @DisplayName("stopAllActiveStreamsForConversation")
    class StopAllForConversation {

        @Test
        @DisplayName("should stop all active streams for conversation")
        void shouldStopAll() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            // We cannot create 2 active streams via service (it stops old ones)
            // So use repository directly
            Stream s1 = new Stream(java.util.UUID.randomUUID().toString(),
                    conv.getId(), "s1", USER_ID, Stream.StreamStatus.ACTIVE);
            Stream s2 = new Stream(java.util.UUID.randomUUID().toString(),
                    conv.getId(), "s2", USER_ID, Stream.StreamStatus.ACTIVE);
            streamRepository.saveAndFlush(s1);
            streamRepository.saveAndFlush(s2);

            boolean stopped = streamService.stopAllActiveStreamsForConversation(conv.getId());

            assertThat(stopped).isTrue();
            assertThat(streamService.getActiveStream(conv.getId())).isEmpty();
        }

        @Test
        @DisplayName("should return false when no active streams to stop")
        void shouldReturnFalseWhenNone() {
            Conversation conv = persistConversation(USER_ID, "Chat");

            boolean stopped = streamService.stopAllActiveStreamsForConversation(conv.getId());

            assertThat(stopped).isFalse();
        }
    }

    @Nested
    @DisplayName("Query methods")
    class QueryMethods {

        @Test
        @DisplayName("should check if conversation has active stream")
        void shouldCheckHasActiveStream() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            assertThat(streamService.getActiveStream(conv.getId())).isEmpty();

            streamService.createStream(conv.getId(), "qs1", USER_ID);
            assertThat(streamService.getActiveStream(conv.getId())).isPresent();
        }
    }
}
