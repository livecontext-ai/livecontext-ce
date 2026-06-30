package com.apimarketplace.conversation.integration;

import com.apimarketplace.conversation.controller.v3.StreamControllerV3;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Stream;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.StreamRepository;
import com.apimarketplace.conversation.streaming.StreamMetadata;
import com.apimarketplace.conversation.streaming.StreamState;
import com.apimarketplace.conversation.streaming.StreamStateService;
import com.apimarketplace.common.storage.service.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for StreamControllerV3 with real Spring context.
 *
 * Tests the non-reactive (REST) endpoints of the stream controller.
 * Reactive streaming endpoints (connect) are better tested with WebTestClient,
 * but the status and stop endpoints are testable with MockMvc.
 *
 * Uses in-memory StreamStateService from IntegrationTestConfig.
 */
@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class StreamControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StreamRepository streamRepository;

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private StreamStateService streamStateService;

    @Autowired
    private StreamControllerV3 streamController;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private StorageService storageService;

    private static final String USER_ID = "stream-test-user";
    private static final String X_USER_ID = "X-User-ID";

    @BeforeEach
    void setUp() {
        streamRepository.deleteAll();
        conversationRepository.deleteAll();
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

    private Stream persistStream(String conversationId, String streamId, String userId, Stream.StreamStatus status) {
        Stream stream = new Stream(UUID.randomUUID().toString(), conversationId, streamId, userId, status);
        return streamRepository.saveAndFlush(stream);
    }

    // ========================== Tests ==========================

    @Nested
    @DisplayName("Stream database operations via StreamService")
    class StreamDatabaseOperations {

        @Test
        @DisplayName("should create and retrieve active stream for conversation")
        void shouldCreateAndRetrieveActiveStream() {
            Conversation conv = persistConversation(USER_ID, "Stream Chat");
            Stream stream = persistStream(conv.getId(), "sid-001", USER_ID, Stream.StreamStatus.ACTIVE);

            var found = streamRepository.findActiveStreamByConversationId(conv.getId());
            assertThat(found).isPresent();
            assertThat(found.get().getStreamId()).isEqualTo("sid-001");
            assertThat(found.get().isActive()).isTrue();
        }

        @Test
        @DisplayName("should find stream by streamId")
        void shouldFindByStreamId() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            persistStream(conv.getId(), "sid-002", USER_ID, Stream.StreamStatus.ACTIVE);

            var found = streamRepository.findByStreamId("sid-002");
            assertThat(found).isPresent();
            assertThat(found.get().getConversationId()).isEqualTo(conv.getId());
        }

        @Test
        @DisplayName("should stop all active streams for conversation")
        void shouldStopAllActiveStreamsForConversation() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            persistStream(conv.getId(), "sid-003", USER_ID, Stream.StreamStatus.ACTIVE);
            persistStream(conv.getId(), "sid-004", USER_ID, Stream.StreamStatus.ACTIVE);

            int stopped = streamRepository.stopAllActiveStreamsForConversation(
                    conv.getId(), LocalDateTime.now());

            assertThat(stopped).isEqualTo(2);

            var active = streamRepository.findActiveStreamByConversationId(conv.getId());
            assertThat(active).isEmpty();
        }

        @Test
        @DisplayName("should find streams for conversation by streamId")
        void shouldFindStreamsForConversation() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            persistStream(conv.getId(), "sid-020", USER_ID, Stream.StreamStatus.COMPLETED);
            persistStream(conv.getId(), "sid-021", USER_ID, Stream.StreamStatus.ACTIVE);

            assertThat(streamRepository.findByStreamId("sid-020")).isPresent();
            assertThat(streamRepository.findByStreamId("sid-021")).isPresent();
        }

        @Test
        @DisplayName("should mark stream as completed")
        void shouldMarkStreamAsCompleted() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            Stream stream = persistStream(conv.getId(), "sid-030", USER_ID, Stream.StreamStatus.ACTIVE);

            var loaded = streamRepository.findByStreamId("sid-030").orElseThrow();
            loaded.markAsCompleted();
            streamRepository.saveAndFlush(loaded);

            var updated = streamRepository.findByStreamId("sid-030").orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(Stream.StreamStatus.COMPLETED);
            assertThat(updated.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("should mark stream as error with message")
        void shouldMarkStreamAsError() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            persistStream(conv.getId(), "sid-031", USER_ID, Stream.StreamStatus.ACTIVE);

            var loaded = streamRepository.findByStreamId("sid-031").orElseThrow();
            loaded.markAsError("Rate limit exceeded");
            streamRepository.saveAndFlush(loaded);

            var updated = streamRepository.findByStreamId("sid-031").orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(Stream.StreamStatus.ERROR);
            assertThat(updated.getErrorMessage()).isEqualTo("Rate limit exceeded");
        }

        @Test
        @DisplayName("should find active streams older than threshold (for TTL)")
        void shouldFindOldActiveStreams() {
            Conversation conv = persistConversation(USER_ID, "Chat");
            persistStream(conv.getId(), "sid-040", USER_ID, Stream.StreamStatus.ACTIVE);

            // Threshold in the future should find all active streams
            var oldStreams = streamRepository.findActiveStreamsOlderThan(
                    LocalDateTime.now().plusHours(1));
            assertThat(oldStreams).hasSize(1);

            // Threshold in the past should find none
            var noStreams = streamRepository.findActiveStreamsOlderThan(
                    LocalDateTime.now().minusHours(1));
            assertThat(noStreams).isEmpty();
        }

    }

    @Nested
    @DisplayName("In-memory StreamStateService operations")
    class InMemoryStreamState {

        @Test
        @DisplayName("should create stream and retrieve metadata")
        void shouldCreateAndRetrieveMetadata() {
            var metadata = streamStateService.createStream(USER_ID, "conv-100", "gpt-4o", "openai")
                    .block();

            assertThat(metadata).isNotNull();
            assertThat(metadata.userId()).isEqualTo(USER_ID);
            assertThat(metadata.conversationId()).isEqualTo("conv-100");

            var retrieved = streamStateService.getMetadata(metadata.streamId()).block();
            assertThat(retrieved).isNotNull();
            assertThat(retrieved.streamId()).isEqualTo(metadata.streamId());
        }

        @Test
        @DisplayName("should get stream by conversation ID")
        void shouldGetByConversationId() {
            var metadata = streamStateService.createStream(USER_ID, "conv-101", "gpt-4o", "openai")
                    .block();

            var found = streamStateService.getByConversationId("conv-101").block();
            assertThat(found).isNotNull();
            assertThat(found.streamId()).isEqualTo(metadata.streamId());
        }

        @Test
        @DisplayName("should manage stream lifecycle states")
        void shouldManageLifecycleStates() {
            var metadata = streamStateService.createStream(USER_ID, "conv-102", "gpt-4o", "openai")
                    .block();
            String streamId = metadata.streamId();

            assertThat(streamStateService.isActive(streamId).block()).isTrue();

            streamStateService.complete(streamId).block();
            assertThat(streamStateService.isActive(streamId).block()).isFalse();

            var state = streamStateService.getState(streamId).block();
            assertThat(state).isEqualTo(com.apimarketplace.conversation.streaming.StreamState.COMPLETED);
        }

        @Test
        @DisplayName("should append and retrieve content chunks")
        void shouldAppendAndRetrieveContent() {
            var metadata = streamStateService.createStream(USER_ID, "conv-103", "gpt-4o", "openai")
                    .block();
            String streamId = metadata.streamId();

            streamStateService.appendContent(streamId, "Hello ").block();
            streamStateService.appendContent(streamId, "World!").block();

            String fullContent = streamStateService.getFullContent(streamId).block();
            assertThat(fullContent).isEqualTo("Hello World!");

            var chunks = streamStateService.getContentChunks(streamId).collectList().block();
            assertThat(chunks).containsExactly("Hello ", "World!");
        }

        @Test
        @DisplayName("should append and retrieve tool events")
        void shouldAppendAndRetrieveToolEvents() {
            var metadata = streamStateService.createStream(USER_ID, "conv-104", "gpt-4o", "openai")
                    .block();
            String streamId = metadata.streamId();

            streamStateService.appendToolEvent(streamId, "{\"type\":\"tool_call\"}").block();
            streamStateService.appendToolEvent(streamId, "{\"type\":\"tool_result\"}").block();

            var events = streamStateService.getToolEvents(streamId).collectList().block();
            assertThat(events).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Cross-user authorization on the real StreamControllerV3 gate")
    class CrossUserAuthorization {

        /**
         * Drives the real StreamControllerV3 bean (the cloud controller - excluded
         * from the CE monolith scan, so the CE e2e harness cannot reach it) against
         * the real ConversationRepository. Proves the owner sees their own buffered
         * stream while a user in a different workspace is denied - the cross-user
         * read leak the gate closes. persistConversation stamps organization_id =
         * userId, so the two users land in distinct workspaces.
         */
        @Test
        @DisplayName("owner sees buffered content; a different-workspace user gets nothing")
        void crossUserStreamStateIsScoped() {
            String owner = "owner-" + UUID.randomUUID();
            String intruder = "intruder-" + UUID.randomUUID();
            // Commit the conversation in its own transaction: the gate's findById runs
            // on a boundedElastic worker (separate connection) that cannot see the
            // surrounding @Transactional test's uncommitted rows. In production the
            // conversation is already committed, so this only mirrors reality.
            var txTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            txTemplate.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Conversation conv = txTemplate.execute(s -> persistConversation(owner, "Owned Stream"));

            try {
            StreamMetadata md = streamStateService
                    .createStream(owner, conv.getId(), "gpt-4o", "openai").block();
            streamStateService.updateState(md.streamId(), StreamState.STREAMING).block();
            streamStateService.appendContent(md.streamId(), "secret assistant answer").block();

            // Owner (organization matches the conversation) sees the live stream.
            var ownerState = streamController.getStreamState(conv.getId(), owner, owner).block();
            assertThat(ownerState).isNotNull();
            assertThat(ownerState.getBody()).isNotNull();
            assertThat(ownerState.getBody().get("streamId")).isEqualTo(md.streamId());
            assertThat(ownerState.getBody().get("content")).isEqualTo("secret assistant answer");
            assertThat(ownerState.getBody().get("hasActiveStream")).isEqualTo(true);

            // A user in a different workspace is denied - empty no-stream shape, never
            // the owner's streamId / model / buffered content.
            var intruderState = streamController.getStreamState(conv.getId(), intruder, intruder).block();
            assertThat(intruderState).isNotNull();
            assertThat(intruderState.getBody()).isNotNull();
            assertThat(intruderState.getBody().get("hasActiveStream")).isEqualTo(false);
            assertThat(intruderState.getBody().get("content")).isEqualTo("");
            assertThat(intruderState.getBody()).doesNotContainKeys("streamId", "model");

            // The status endpoint is gated the same way.
            var intruderStatus = streamController.getStreamStatusByConversation(conv.getId(), intruder, intruder).block();
            assertThat(intruderStatus).isNotNull();
            assertThat(intruderStatus.getBody()).isNotNull();
            assertThat(intruderStatus.getBody().hasActiveStream()).isFalse();
            assertThat(intruderStatus.getBody().streamId()).isNull();

            // The owner CAN see the real status (streamId surfaced to the owner only).
            var ownerStatus = streamController.getStreamStatusByConversation(conv.getId(), owner, owner).block();
            assertThat(ownerStatus.getBody().streamId()).isEqualTo(md.streamId());

            // A cross-user stop is refused (404) BEFORE the stop runs, so the owner's
            // stream is never stopped or cascade-cancelled.
            var intruderStop = streamController.stopStreamByConversation(conv.getId(), intruder, intruder).block();
            assertThat(intruderStop.getStatusCode().value()).isEqualTo(404);
            assertThat(streamStateService.getState(md.streamId()).block()).isEqualTo(StreamState.STREAMING);
            } finally {
                txTemplate.execute(s -> {
                    conversationRepository.deleteById(conv.getId());
                    return null;
                });
            }
        }
    }
}
