package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.MessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PendingActionResumeService")
@ExtendWith(MockitoExtension.class)
class PendingActionResumeServiceTest {

    @Mock
    private PendingActionService pendingActionService;

    @Mock
    private MessageService messageService;

    private PendingActionResumeService resumeService;

    @BeforeEach
    void setUp() {
        resumeService = new PendingActionResumeService(pendingActionService, messageService);
    }

    @Nested
    @DisplayName("onCredentialConfigured")
    class OnCredentialConfigured {

        @Test
        @DisplayName("should resume all conversations waiting for the credential")
        void shouldResumeWaitingConversations() {
            List<String> waitingConversations = List.of("conv-1", "conv-2");
            when(pendingActionService.findConversationsWaitingForCredential("gmail"))
                    .thenReturn(waitingConversations);

            Map<String, Object> pendingAction = Map.of(
                    "waiting_for", "credential:gmail",
                    "original_request", "Check email",
                    "tool_call", Map.of("name", "catalog")
            );
            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.of(pendingAction));
            when(pendingActionService.getPendingAction("conv-2")).thenReturn(Optional.of(pendingAction));
            when(messageService.addMessage(anyString(), any(MessageDto.class))).thenReturn(new MessageDto());

            int resumed = resumeService.onCredentialConfigured("gmail", "user-1");

            assertThat(resumed).isEqualTo(2);
            verify(pendingActionService).clearPendingAction("conv-1");
            verify(pendingActionService).clearPendingAction("conv-2");
        }

        @Test
        @DisplayName("should return 0 when no conversations waiting")
        void shouldReturnZeroWhenNoWaiting() {
            when(pendingActionService.findConversationsWaitingForCredential("gmail"))
                    .thenReturn(List.of());

            int resumed = resumeService.onCredentialConfigured("gmail", "user-1");
            assertThat(resumed).isZero();
        }

        @Test
        @DisplayName("should continue if one conversation fails to resume")
        void shouldContinueOnFailure() {
            when(pendingActionService.findConversationsWaitingForCredential("gmail"))
                    .thenReturn(List.of("conv-1", "conv-2"));

            when(pendingActionService.getPendingAction("conv-1")).thenThrow(new RuntimeException("error"));
            Map<String, Object> action = Map.of(
                    "waiting_for", "credential:gmail",
                    "original_request", "Check email"
            );
            when(pendingActionService.getPendingAction("conv-2")).thenReturn(Optional.of(action));
            when(messageService.addMessage(anyString(), any(MessageDto.class))).thenReturn(new MessageDto());

            int resumed = resumeService.onCredentialConfigured("gmail", "user-1");
            assertThat(resumed).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("resumeConversation")
    class ResumeConversation {

        @Test
        @DisplayName("should inject context and clear pending action")
        void shouldInjectContextAndClear() {
            Map<String, Object> pendingAction = Map.of(
                    "waiting_for", "credential:gmail",
                    "original_request", "Check my Gmail",
                    "context_summary", "Found gmail tool",
                    "tool_call", Map.of("name", "catalog")
            );
            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.of(pendingAction));
            when(messageService.addMessage(anyString(), any(MessageDto.class))).thenReturn(new MessageDto());

            boolean result = resumeService.resumeConversation("conv-1", "credential:gmail");

            assertThat(result).isTrue();

            ArgumentCaptor<MessageDto> captor = ArgumentCaptor.forClass(MessageDto.class);
            verify(messageService).addMessage(eq("conv-1"), captor.capture());

            MessageDto injectedMessage = captor.getValue();
            assertThat(injectedMessage.getRole()).isEqualTo("system");
            assertThat(injectedMessage.getContent()).contains("CONTEXT RESTORED");
            assertThat(injectedMessage.getContent()).contains("Check my Gmail");
            assertThat(injectedMessage.getContent()).contains("credential:gmail");

            verify(pendingActionService).clearPendingAction("conv-1");
        }

        @Test
        @DisplayName("should return false when no pending action found")
        void shouldReturnFalseWhenNoPendingAction() {
            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.empty());

            boolean result = resumeService.resumeConversation("conv-1", "credential:gmail");
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("manualResume")
    class ManualResume {

        @Test
        @DisplayName("should resume and return pending action")
        void shouldResumeAndReturn() {
            Map<String, Object> pendingAction = Map.of(
                    "waiting_for", "credential:gmail",
                    "original_request", "Check email"
            );
            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.of(pendingAction));
            when(messageService.addMessage(anyString(), any(MessageDto.class))).thenReturn(new MessageDto());

            Map<String, Object> result = resumeService.manualResume("conv-1");

            assertThat(result).isNotNull();
            assertThat(result.get("waiting_for")).isEqualTo("credential:gmail");
        }

        @Test
        @DisplayName("should return null when no pending action")
        void shouldReturnNullWhenNoPending() {
            when(pendingActionService.getPendingAction("conv-1")).thenReturn(Optional.empty());

            Map<String, Object> result = resumeService.manualResume("conv-1");
            assertThat(result).isNull();
        }
    }
}
