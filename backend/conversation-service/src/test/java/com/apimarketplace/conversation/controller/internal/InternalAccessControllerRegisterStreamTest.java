package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.StreamService;
import com.apimarketplace.conversation.streaming.StreamInterruptionService;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression tests for the owner attribution of externally-registered streams.
 * <p>
 * Bug: {@code registerExternalStream} stamped {@code userId="internal"} and never
 * indexed the stream under {@code stream:user:{ownerId}}, so
 * {@code /v3/streams/active} (which reads that index) missed every
 * workflow/task/sub-workflow-driven run - the main chat page then never
 * auto-attached to an in-flight external stream (only the side panel, which
 * subscribes unconditionally, showed it live). The controller now resolves the
 * conversation owner and threads it through.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAccessController - external stream registration owner attribution")
class InternalAccessControllerRegisterStreamTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private StreamStateService streamStateService;
    @Mock private StreamPubSubService streamPubSubService;
    @Mock private StreamService streamService;
    @Mock private StreamInterruptionService streamInterruptionService;

    private InternalAccessController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAccessController(conversationRepository, streamStateService,
                streamPubSubService, streamService, streamInterruptionService);
        lenient().when(streamStateService.registerExternalStream(any(), any(), any(), any(), any()))
                .thenReturn(Mono.empty());
    }

    @Test
    @DisplayName("register resolves the conversation OWNER and attributes the stream to them")
    void registerAttributesOwner() {
        Conversation conv = new Conversation();
        conv.setId("conv-1");
        conv.setUserId("42");
        when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

        var response = controller.registerStream(Map.of(
                "streamId", "stream-1",
                "conversationId", "conv-1",
                "model", "deepseek-chat",
                "provider", "workflow"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(streamStateService).registerExternalStream(
                eq("stream-1"), eq("conv-1"), eq("deepseek-chat"), eq("workflow"), eq("42"));
        // The DB row reuses the same resolved owner (previously looked up separately).
        verify(streamService).createStream("conv-1", "stream-1", "42");
    }

    @Test
    @DisplayName("unknown conversation falls back to the legacy attribution (null owner, 'system' DB row)")
    void unknownConversationFallsBack() {
        when(conversationRepository.findById("conv-x")).thenReturn(Optional.empty());

        var response = controller.registerStream(Map.of(
                "streamId", "stream-1",
                "conversationId", "conv-x"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(streamStateService).registerExternalStream(
                eq("stream-1"), eq("conv-x"), any(), any(), isNull());
        verify(streamService).createStream("conv-x", "stream-1", "system");
    }

    @Test
    @DisplayName("owner lookup failure is best-effort: registration still succeeds without attribution")
    void ownerLookupFailureIsBestEffort() {
        when(conversationRepository.findById("conv-1"))
                .thenThrow(new IllegalStateException("db down"));

        var response = controller.registerStream(Map.of(
                "streamId", "stream-1",
                "conversationId", "conv-1"));

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(streamStateService).registerExternalStream(
                eq("stream-1"), eq("conv-1"), any(), any(), isNull());
    }

    @Test
    @DisplayName("missing streamId or conversationId is rejected with 400")
    void missingFieldsRejected() {
        assertThat(controller.registerStream(Map.of("conversationId", "conv-1"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.registerStream(Map.of("streamId", "stream-1"))
                .getStatusCode().value()).isEqualTo(400);
    }
}
