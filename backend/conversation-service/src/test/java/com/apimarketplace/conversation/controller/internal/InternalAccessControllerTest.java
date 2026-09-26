package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.StreamService;
import com.apimarketplace.conversation.streaming.StreamInterruptionService;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.apimarketplace.conversation.streaming.StreamStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalAccessController (Conversation)")
class InternalAccessControllerTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private StreamStateService streamStateService;

    @Mock
    private StreamPubSubService streamPubSubService;

    @Mock
    private StreamService streamService;

    @Mock
    private StreamInterruptionService streamInterruptionService;

    private InternalAccessController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalAccessController(conversationRepository, streamStateService,
                streamPubSubService, streamService, streamInterruptionService);
    }

    @Nested
    @DisplayName("checkAccess()")
    class CheckAccessTests {

        @Test
        @DisplayName("Should return false when conversation not found")
        void shouldReturnFalseWhenNotFound() {
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            ResponseEntity<Boolean> response = controller.checkAccess("conv-1", "user-1", null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isFalse();
        }

        @Test
        @DisplayName("Should return true when user owns the conversation")
        void shouldReturnTrueForOwner() {
            Conversation conv = mock(Conversation.class);
            when(conv.getUserId()).thenReturn("user-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            ResponseEntity<Boolean> response = controller.checkAccess("conv-1", "user-1", null);

            assertThat(response.getBody()).isTrue();
        }

        @Test
        @DisplayName("Should return false when different user and no org match")
        void shouldReturnFalseForDifferentUser() {
            Conversation conv = mock(Conversation.class);
            when(conv.getUserId()).thenReturn("user-1");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            ResponseEntity<Boolean> response = controller.checkAccess("conv-1", "user-2", null);

            assertThat(response.getBody()).isFalse();
        }

        @Test
        @DisplayName("PR-WS-fix: org teammate (different user, same org) gets access")
        void shouldReturnTrueForOrgTeammate() {
            Conversation conv = mock(Conversation.class);
            when(conv.getUserId()).thenReturn("user-1");
            when(conv.getOrganizationId()).thenReturn("org-acme");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            ResponseEntity<Boolean> response = controller.checkAccess("conv-1", "user-2", "org-acme");

            assertThat(response.getBody()).isTrue();
        }

        @Test
        @DisplayName("Cross-org: different user, different org → denied")
        void shouldReturnFalseForCrossOrg() {
            Conversation conv = mock(Conversation.class);
            when(conv.getUserId()).thenReturn("user-1");
            when(conv.getOrganizationId()).thenReturn("org-acme");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            ResponseEntity<Boolean> response = controller.checkAccess("conv-1", "user-2", "org-globex");

            assertThat(response.getBody()).isFalse();
        }
    }

    @Nested
    @DisplayName("registerStream()")
    class RegisterStreamTests {

        @Test
        @DisplayName("Should create the DB stream row with the conversation owner's userId (in addition to Redis)")
        void shouldCreateDbRowWithConversationOwner() {
            when(streamStateService.registerExternalStream("stream-1", "conv-1", "gpt-4", "workflow"))
                    .thenReturn(Mono.empty());
            Conversation conv = mock(Conversation.class);
            when(conv.getUserId()).thenReturn("user-42");
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.of(conv));

            ResponseEntity<Void> response = controller.registerStream(Map.of(
                    "streamId", "stream-1", "conversationId", "conv-1",
                    "model", "gpt-4", "provider", "workflow"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(streamService).createStream("conv-1", "stream-1", "user-42");
        }

        @Test
        @DisplayName("Should fall back to 'system' userId when the conversation row is missing")
        void shouldFallBackToSystemUser() {
            when(streamStateService.registerExternalStream(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());
            when(conversationRepository.findById("conv-1")).thenReturn(Optional.empty());

            controller.registerStream(Map.of("streamId", "stream-1", "conversationId", "conv-1"));

            verify(streamService).createStream("conv-1", "stream-1", "system");
        }

        @Test
        @DisplayName("Should stay 200 (best-effort) when DB row creation fails")
        void shouldTolerateDbFailure() {
            when(streamStateService.registerExternalStream(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Mono.empty());
            when(conversationRepository.findById("conv-1")).thenThrow(new RuntimeException("DB down"));

            ResponseEntity<Void> response = controller.registerStream(
                    Map.of("streamId", "stream-1", "conversationId", "conv-1"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should reject and create nothing when streamId/conversationId is missing")
        void shouldRejectMissingFields() {
            ResponseEntity<Void> response = controller.registerStream(Map.of("streamId", "stream-1"));

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            verifyNoInteractions(streamService);
        }
    }

    @Nested
    @DisplayName("finalizeStream()")
    class FinalizeStreamTests {

        @Test
        @DisplayName("INTERRUPTED state should route through StreamInterruptionService to rescue partial content")
        void shouldInterruptOnInterruptedState() {
            controller.finalizeStream("stream-1", Map.of("state", "INTERRUPTED"));

            verify(streamInterruptionService).interrupt("stream-1", "Agent execution interrupted (drain/shutdown)");
            // The plain state-flip paths must NOT run - interrupt() owns the full lifecycle
            verify(streamStateService, never()).complete(anyString());
            verify(streamStateService, never()).error(anyString(), anyString());
        }

        @Test
        @DisplayName("COMPLETED state should complete Redis AND close the DB row (no more 30-min ERROR drift)")
        void shouldCompleteOnCompletedState() {
            when(streamStateService.complete("stream-1")).thenReturn(Mono.just(true));

            ResponseEntity<Void> response = controller.finalizeStream("stream-1", Map.of("state", "COMPLETED"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(streamStateService).complete("stream-1");
            verify(streamService).markStreamAsCompleted("stream-1");
            verifyNoInteractions(streamInterruptionService);
        }

        @Test
        @DisplayName("ERROR state should mark Redis error AND set the DB row to ERROR")
        void shouldMarkDbRowErrorOnErrorState() {
            when(streamStateService.error("stream-1", "Agent execution error")).thenReturn(Mono.just(true));

            ResponseEntity<Void> response = controller.finalizeStream("stream-1", Map.of("state", "ERROR"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(streamStateService).error("stream-1", "Agent execution error");
            verify(streamService).markStreamAsError("stream-1", "Agent execution error");
            verifyNoInteractions(streamInterruptionService);
        }

        @Test
        @DisplayName("ERROR with the producer's errorMessage records THAT reason in Redis and the DB, not the placeholder")
        void errorFinalizeRecordsProducerReason() {
            when(streamStateService.error("stream-1", "LLM provider timeout")).thenReturn(Mono.just(true));

            ResponseEntity<Void> response = controller.finalizeStream("stream-1",
                    Map.of("state", "ERROR", "errorMessage", "LLM provider timeout"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(streamStateService).error("stream-1", "LLM provider timeout");
            verify(streamService).markStreamAsError("stream-1", "LLM provider timeout");
        }

        @Test
        @DisplayName("ERROR with a blank errorMessage falls back to the placeholder")
        void errorFinalizeBlankReasonFallsBack() {
            when(streamStateService.error("stream-1", "Agent execution error")).thenReturn(Mono.just(true));

            controller.finalizeStream("stream-1", Map.of("state", "ERROR", "errorMessage", "   "));

            verify(streamStateService).error("stream-1", "Agent execution error");
            verify(streamService).markStreamAsError("stream-1", "Agent execution error");
        }

        @Test
        @DisplayName("ERROR with an oversized errorMessage is bounded before it is stored")
        void errorFinalizeBoundsOversizedReason() {
            String huge = "x".repeat(InternalAccessController.MAX_STREAM_ERROR_LENGTH + 500);
            String bounded = "x".repeat(InternalAccessController.MAX_STREAM_ERROR_LENGTH) + "...";
            when(streamStateService.error("stream-1", bounded)).thenReturn(Mono.just(true));

            controller.finalizeStream("stream-1", Map.of("state", "ERROR", "errorMessage", huge));

            verify(streamService).markStreamAsError("stream-1", bounded);
        }

        @Test
        @DisplayName("ERROR finalize logs once at WARN with the real reason, and never at ERROR")
        void errorFinalizeLogsOnceAtWarn() {
            when(streamStateService.error(anyString(), anyString())).thenReturn(Mono.just(true));
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(InternalAccessController.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                controller.finalizeStream("stream-1",
                        Map.of("state", "ERROR", "errorMessage", "LLM provider timeout"));
            } finally {
                logger.detachAppender(appender);
            }

            assertThat(appender.list)
                    .noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage()).contains("LLM provider timeout"));
        }

        @Test
        @DisplayName("the WARN line carries at most ~500 chars of the reason while the stored value keeps 2000")
        void errorFinalizeCapsTheLoggedReason() {
            String huge = "y".repeat(InternalAccessController.MAX_STREAM_ERROR_LENGTH);
            when(streamStateService.error(anyString(), anyString())).thenReturn(Mono.just(true));
            ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger(InternalAccessController.class);
            ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                    new ch.qos.logback.core.read.ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                controller.finalizeStream("stream-1", Map.of("state", "ERROR", "errorMessage", huge));
            } finally {
                logger.detachAppender(appender);
            }

            verify(streamService).markStreamAsError("stream-1", huge);
            assertThat(appender.list)
                    .filteredOn(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .singleElement()
                    .satisfies(e -> assertThat(e.getFormattedMessage().length())
                            .isLessThan(InternalAccessController.MAX_LOGGED_STREAM_ERROR_LENGTH + 100));
        }

        @Test
        @DisplayName("the legacy 'error' key is accepted when 'errorMessage' is absent")
        void errorFinalizeAcceptsLegacyErrorKey() {
            when(streamStateService.error("stream-1", "legacy reason")).thenReturn(Mono.just(true));

            controller.finalizeStream("stream-1", Map.of("state", "ERROR", "error", "legacy reason"));

            verify(streamService).markStreamAsError("stream-1", "legacy reason");
        }

        @Test
        @DisplayName("Wire contract: the JSON errorMessage field reaches the stored reason (MockMvc)")
        void errorFinalizeOverHttpCarriesReason() throws Exception {
            when(streamStateService.error("stream-1", "Tool quota exceeded")).thenReturn(Mono.just(true));
            org.springframework.test.web.servlet.MockMvc mockMvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .post("/api/internal/streams/stream-1/finalize")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content("{\"state\":\"ERROR\",\"errorMessage\":\"Tool quota exceeded\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

            verify(streamService).markStreamAsError("stream-1", "Tool quota exceeded");
        }

        @Test
        @DisplayName("Should stay 200 (best-effort) when the DB row update fails on COMPLETED")
        void shouldTolerateDbMarkFailureOnCompleted() {
            when(streamStateService.complete("stream-1")).thenReturn(Mono.just(true));
            doThrow(new RuntimeException("DB down")).when(streamService).markStreamAsCompleted("stream-1");

            ResponseEntity<Void> response = controller.finalizeStream("stream-1", Map.of("state", "COMPLETED"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should stay 200 (best-effort) when interrupt throws")
        void shouldTolerateInterruptFailure() {
            when(streamInterruptionService.interrupt(anyString(), anyString()))
                    .thenThrow(new RuntimeException("Redis down"));

            ResponseEntity<Void> response = controller.finalizeStream("stream-1", Map.of("state", "INTERRUPTED"));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }
}
