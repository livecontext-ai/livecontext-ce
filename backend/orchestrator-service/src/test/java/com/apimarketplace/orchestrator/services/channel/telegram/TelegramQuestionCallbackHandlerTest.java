package com.apimarketplace.orchestrator.services.channel.telegram;

import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry;
import com.apimarketplace.orchestrator.services.channel.ChatQuestionService;
import com.apimarketplace.orchestrator.services.channel.ChatQuestionService.AnswerOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What arrives from Telegram, and what this makes of it.
 *
 * <p>Two properties carry it. What the handler CLAIMS decides what the webhook stops delivering
 * elsewhere, so claiming too much silently breaks whatever else the bot drives. And the order it
 * does things in decides whether the person's press is ever confirmed, because an
 * acknowledgement is only accepted for a short while after the press.
 */
@DisplayName("TelegramQuestionCallbackHandler - presses and typed replies")
class TelegramQuestionCallbackHandlerTest {

    private ChatQuestionService service;
    private ChatChannelConnector connector;
    private TelegramQuestionCallbackHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(ChatQuestionService.class);
        connector = mock(ChatChannelConnector.class);
        when(connector.channelId()).thenReturn("telegram");
        handler = new TelegramQuestionCallbackHandler(service,
                new ChatChannelConnectorRegistry(List.of(connector)), new SimpleMeterRegistry());
    }

    @Nested
    @DisplayName("what it recognises")
    class Recognition {

        @Test
        @DisplayName("an option press, a done and an other are all ours")
        void recognisesEveryAction() {
            // Every action the keyboard can send. A pattern that misses one leaves those
            // buttons inert with nothing logged anywhere, which is how a press does nothing.
            assertThat(handler.isQuestionCallback(press("lcask:" + token() + ":o0"))).isTrue();
            assertThat(handler.isQuestionCallback(press("lcask:" + token() + ":o7"))).isTrue();
            assertThat(handler.isQuestionCallback(press("lcask:" + token() + ":o12"))).isTrue();
            assertThat(handler.isQuestionCallback(press("lcask:" + token() + ":done"))).isTrue();
            assertThat(handler.isQuestionCallback(press("lcask:" + token() + ":other"))).isTrue();
        }

        @Test
        @DisplayName("another family's press is not ours")
        void leavesOtherFamiliesAlone() {
            assertThat(handler.isQuestionCallback(press("lcaut:" + token() + ":a"))).isFalse();
            assertThat(handler.isQuestionCallback(press("lcapr:" + token() + ":a"))).isFalse();
            assertThat(handler.isQuestionCallback(press("lcask:short:o0"))).isFalse();
            assertThat(handler.isQuestionCallback(Map.of())).isFalse();
        }

        @Test
        @DisplayName("a reply is ours only when a live question of ours is waiting on that message")
        void claimsOnlyOurOwnReplies() {
            Map<String, Object> reply = reply("-100123", "42", "something else");

            when(service.hasLiveQuestion("-100123", "42", "bot-7")).thenReturn(false);
            // The bot commonly drives a workflow trigger too, and what this claims the webhook
            // stops delivering. Claiming on shape alone swallowed every "Reply" in the chat.
            assertThat(handler.isPossibleTextReply(reply)).isFalse();

            when(service.hasLiveQuestion("-100123", "42", "bot-7")).thenReturn(true);
            assertThat(handler.isPossibleTextReply(reply)).isTrue();
        }

        @Test
        @DisplayName("the bot that sent the quoted message is what is passed on, never the person")
        void passesTheQuotedBot() {
            handler.isPossibleTextReply(reply("-100123", "42", "an answer"));

            // reply_to_message.from is the bot whose question is being answered; message.from is
            // the person. Mixing them up would make the two-bot disambiguation compare a human's
            // id against bot identities and never match anything.
            verify(service).hasLiveQuestion("-100123", "42", "bot-7");
        }

        @Test
        @DisplayName("a plain message and an empty reply are never ours")
        void ignoresNonReplies() {
            assertThat(handler.isPossibleTextReply(Map.of("message",
                    Map.of("text", "hello", "chat", Map.of("id", "-100123"))))).isFalse();
            assertThat(handler.isPossibleTextReply(reply("-100123", "42", "   "))).isFalse();
        }
    }

    @Nested
    @DisplayName("handling a press")
    class Presses {

        @Test
        @DisplayName("acknowledges before handing the answers over, not after")
        void acknowledgesBeforeApplying() {
            ChatAuthorizationRequestEntity row = row();
            when(service.answer(anyString(), anyString(), anyString(), any()))
                    .thenReturn(new AnswerOutcome(true, "Answer recorded: Formal", false, row, true));

            handler.handle(press("lcask:" + token() + ":o0"));

            // Handing over runs the agent's follow-up turn, a full synchronous LLM run, and
            // Telegram stops accepting an acknowledgement for a press after a short window.
            var order = org.mockito.Mockito.inOrder(connector, service);
            order.verify(connector).ackButton(anyString(), anyLong(), any(), anyString(), anyBoolean());
            order.verify(service).applyIfComplete(row);
        }

        @Test
        @DisplayName("a toggle is acknowledged and hands nothing over, because nothing is settled")
        void aToggleAppliesNothing() {
            ChatAuthorizationRequestEntity row = row();
            when(service.answer(anyString(), anyString(), anyString(), any()))
                    .thenReturn(new AnswerOutcome(true, "Added.", false, row, false));

            handler.handle(press("lcask:" + token() + ":o1"));

            verify(connector).ackButton(anyString(), anyLong(), any(), eq("Added."), anyBoolean());
            verify(service, never()).applyIfComplete(any());
        }

        @Test
        @DisplayName("an unknown token acknowledges nothing, because there is no credential to do it with")
        void anUnknownTokenIsDropped() {
            when(service.answer(anyString(), anyString(), anyString(), any()))
                    .thenReturn(new AnswerOutcome(false, null, false, null, false));

            handler.handle(press("lcask:" + token() + ":o0"));

            verify(connector, never()).ackButton(anyString(), anyLong(), any(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("a service that throws never reaches the webhook, or Telegram resends forever")
        void swallowsFailures() {
            when(service.answer(anyString(), anyString(), anyString(), any()))
                    .thenThrow(new IllegalStateException("db down"));

            handler.handle(press("lcask:" + token() + ":o0"));

            verify(connector, never()).ackButton(anyString(), anyLong(), any(), anyString(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("handling a typed reply")
    class Replies {

        @Test
        @DisplayName("a refusal is sent back, because a reply has no button to answer through")
        void saysWhyAReplyWasRefused() {
            ChatAuthorizationRequestEntity row = row();
            row.setStatus(RequestStatus.SENT);
            when(service.answerWithText(anyString(), anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(new AnswerOutcome(true, "That question expired before your answer "
                            + "arrived.", false, row, false));

            handler.handleReply(reply("-100123", "42", "too late"));

            // These sentences existed and nothing ever sent them: somebody answering an expired
            // question watched their message disappear.
            ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
            verify(connector).sendTest(anyString(), anyLong(), anyString(), sent.capture());
            assertThat(sent.getValue()).contains("expired");
        }

        @Test
        @DisplayName("a reply whose save failed is told so, whatever the row says")
        void aFailedSaveIsSaidBack() {
            ChatAuthorizationRequestEntity row = row();
            row.setStatus(RequestStatus.RESOLVED);
            when(service.answerWithText(anyString(), anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(new AnswerOutcome(true, "Your answer could not be saved. Please try again.",
                            true, row, false));

            handler.handleReply(reply("-100123", "42", "an answer"));

            // Read from the outcome, not the row: the row could say RESOLVED in memory for a save
            // that then failed, and the one person who needed to retry was told nothing.
            verify(connector).sendTest(anyString(), anyLong(), anyString(), eq("Your answer could not be saved. Please try again."));
            verify(service, never()).applyIfComplete(any());
        }

        @Test
        @DisplayName("a recorded answer says nothing extra, because the closing edit already did")
        void doesNotRepeatItselfOnSuccess() {
            ChatAuthorizationRequestEntity row = row();
            row.setStatus(RequestStatus.RESOLVED);
            when(service.answerWithText(anyString(), anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(new AnswerOutcome(true, "Answer recorded: x", false, row, true));

            handler.handleReply(reply("-100123", "42", "x"));

            verify(connector, never()).sendTest(anyString(), anyLong(), anyString(), anyString());
            verify(service).applyIfComplete(row);
        }

        @Test
        @DisplayName("a reply that is not ours says nothing and applies nothing")
        void aForeignReplyIsSilent() {
            when(service.answerWithText(anyString(), anyString(), anyString(), anyString(), any(), any()))
                    .thenReturn(new AnswerOutcome(false, null, false, null, false));

            handler.handleReply(reply("-100123", "42", "hello"));

            verify(connector, never()).sendTest(anyString(), anyLong(), anyString(), anyString());
            verify(service, never()).applyIfComplete(any());
        }
    }

    // ---- fixtures ----

    private static String token() {
        return "AAAAAAAAAAAAAAAAAAAAAA";
    }

    private static Map<String, Object> press(String data) {
        return Map.of("callback_query", Map.of("id", "ev-1", "data", data,
                "from", Map.of("id", "user-1")));
    }

    private static Map<String, Object> reply(String chatId, String replyToId, String text) {
        return Map.of("message", Map.of(
                "text", text,
                "chat", Map.of("id", chatId),
                "from", Map.of("id", "user-1"),
                "reply_to_message", Map.of("message_id", replyToId, "from", Map.of("id", "bot-7"))));
    }

    private static ChatAuthorizationRequestEntity row() {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setId(UUID.randomUUID());
        row.setTenantId("42");
        row.setOrganizationId("org-1");
        row.setChannel("telegram");
        row.setCredentialId(9L);
        row.setChatId("-100123");
        row.setStatus(RequestStatus.SENT);
        return row;
    }
}
