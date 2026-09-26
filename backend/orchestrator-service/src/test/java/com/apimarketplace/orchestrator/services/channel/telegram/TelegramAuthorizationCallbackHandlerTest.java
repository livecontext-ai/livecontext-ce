package com.apimarketplace.orchestrator.services.channel.telegram;

import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.services.approvalchannel.ApprovalCallbackInterceptor;
import com.apimarketplace.orchestrator.services.approvalchannel.telegram.TelegramApprovalCallbackHandler;
import com.apimarketplace.orchestrator.services.approvalchannel.telegram.TelegramApprovalNotifier;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.AnswerOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A Telegram bot has ONE webhook, so a single endpoint receives three kinds of
 * traffic: workflow approvals ({@code lcapr:}), agent permission requests
 * ({@code lcaut:}) and the user's own workflow events. These tests pin which of
 * the three each payload reaches, because a mis-route is silent in both
 * directions: a press that resolves nothing, or a workflow epoch opened by a
 * button that was never meant for it.
 */
class TelegramAuthorizationCallbackHandlerTest {

    private AgentAuthorizationChannelService service;
    private TelegramAuthorizationCallbackHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(AgentAuthorizationChannelService.class);
        handler = new TelegramAuthorizationCallbackHandler(service, new SimpleMeterRegistry());
    }

    private static Map<String, Object> press(String data) {
        return Map.of("callback_query", Map.of(
                "id", "cbq-1", "data", data, "from", Map.of("id", 5)));
    }

    private static ChatAuthorizationRequestEntity row() {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setId(UUID.randomUUID());
        row.setChannel("telegram");
        row.setOrganizationId("org-1");
        return row;
    }

    @Nested
    @DisplayName("recognition")
    class Recognition {

        @Test
        @DisplayName("claims its own prefix")
        void claimsOwnPrefix() {
            assertThat(handler.isAuthorizationCallback(press("lcaut:AAAAAAAAAAAAAAAAAAAAAA:a"))).isTrue();
            assertThat(handler.isAuthorizationCallback(press("lcaut:AAAAAAAAAAAAAAAAAAAAAA:r"))).isTrue();
        }

        @Test
        @DisplayName("leaves the workflow-approval prefix alone")
        void ignoresApprovalPrefix() {
            // Both families arrive on the same webhook. Claiming this one would resolve
            // nothing and swallow a workflow approval press.
            assertThat(handler.isAuthorizationCallback(press("lcapr:AAAAAAAAAAAAAAAAAAAAAA:a"))).isFalse();
        }

        @Test
        @DisplayName("leaves an ordinary user button alone")
        void ignoresForeignData() {
            assertThat(handler.isAuthorizationCallback(press("order_42_confirm"))).isFalse();
            assertThat(handler.isAuthorizationCallback(Map.of("message", Map.of("text", "hi")))).isFalse();
            assertThat(handler.isAuthorizationCallback(null)).isFalse();
        }

        @Test
        @DisplayName("refuses a malformed token rather than parsing part of it")
        void refusesMalformedToken() {
            assertThat(handler.isAuthorizationCallback(press("lcaut:short:a"))).isFalse();
            assertThat(handler.isAuthorizationCallback(press("lcaut:AAAAAAAAAAAAAAAAAAAAAA:x"))).isFalse();
            assertThat(handler.isAuthorizationCallback(press("lcaut:AAAA/AAAA+AAAAAAAAAAAA:a"))).isFalse();
        }
    }

    @Nested
    @DisplayName("handling")
    class Handling {

        @Test
        @DisplayName("passes the verdict and the presser to the service")
        void passesTheVerdict() {
            when(service.answer(anyString(), anyBoolean(), anyString(), any()))
                    .thenReturn(new AnswerOutcome(true, "Approved", false, row()));

            handler.handle(press("lcaut:BBBBBBBBBBBBBBBBBBBBBB:r"));

            verify(service).answer(eq("BBBBBBBBBBBBBBBBBBBBBB"), eq(false), eq("5"), any());
        }

        @Test
        @DisplayName("acknowledges the press so the provider's spinner stops")
        void acknowledges() {
            ChatAuthorizationRequestEntity row = row();
            when(service.answer(anyString(), anyBoolean(), any(), any()))
                    .thenReturn(new AnswerOutcome(true, "Approved ✅", false, row));

            handler.handle(press("lcaut:BBBBBBBBBBBBBBBBBBBBBB:a"));

            verify(service).acknowledge(eq(row), eq("cbq-1"), eq("Approved ✅"), eq(false));
        }

        @Test
        @DisplayName("says nothing back when the token matches no request")
        void unknownTokenIsSilent() {
            when(service.answer(anyString(), anyBoolean(), any(), any()))
                    .thenReturn(new AnswerOutcome(false, null, false, null));

            handler.handle(press("lcaut:BBBBBBBBBBBBBBBBBBBBBB:a"));

            // With no row there is no credential to answer with, so attempting an ack
            // would be a call with a null everything.
            verify(service, never()).acknowledge(any(), anyString(), anyString(), anyBoolean());
        }

        @Test
        @DisplayName("swallows a failure instead of letting the provider retry in a loop")
        void swallowsFailures() {
            when(service.answer(anyString(), anyBoolean(), any(), any()))
                    .thenThrow(new IllegalStateException("redis down"));

            // A non-2xx makes Telegram retry aggressively: one failed decision would
            // become a flood.
            assertThatCode(() -> handler.handle(press("lcaut:BBBBBBBBBBBBBBBBBBBBBB:a")))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("handles a press with no identifiable presser")
        void missingFromId() {
            when(service.answer(anyString(), anyBoolean(), any(), any()))
                    .thenReturn(new AnswerOutcome(true, "Approved", false, row()));

            handler.handle(Map.of("callback_query", Map.of(
                    "id", "cbq-2", "data", "lcaut:BBBBBBBBBBBBBBBBBBBBBB:a")));

            verify(service).answer(eq("BBBBBBBBBBBBBBBBBBBBBB"), eq(true), eq(null), any());
        }
    }

    @Nested
    @DisplayName("interceptor routing")
    class Routing {

        @Test
        @DisplayName("sends each family to its own handler and nothing to the other")
        void routesByFamily() {
            TelegramApprovalCallbackHandler approvals = mock(TelegramApprovalCallbackHandler.class);
            TelegramAuthorizationCallbackHandler authorizations =
                    mock(TelegramAuthorizationCallbackHandler.class);
            TelegramQuestionCallbackHandler questions = mock(TelegramQuestionCallbackHandler.class);
            ApprovalCallbackInterceptor interceptor =
                    new ApprovalCallbackInterceptor(approvals, authorizations, questions);

            Map<String, Object> agentPress = press("lcaut:BBBBBBBBBBBBBBBBBBBBBB:a");
            when(authorizations.isAuthorizationCallback(agentPress)).thenReturn(true);
            interceptor.handleAsync(agentPress);
            verify(authorizations).handle(agentPress);
            verify(approvals, never()).handle(any());
            verify(questions, never()).handle(any());

            Map<String, Object> questionPress = press("lcask:DDDDDDDDDDDDDDDDDDDDDD:o1");
            when(questions.isQuestionCallback(questionPress)).thenReturn(true);
            interceptor.handleAsync(questionPress);
            verify(questions).handle(questionPress);
            verify(approvals, never()).handle(any());

            Map<String, Object> workflowPress = press("lcapr:CCCCCCCCCCCCCCCCCCCCCC:a");
            when(authorizations.isAuthorizationCallback(workflowPress)).thenReturn(false);
            interceptor.handleAsync(workflowPress);
            verify(approvals).handle(workflowPress);
        }

        @Test
        @DisplayName("a typed reply goes to the reply handler, not to the workflow")
        void routesATypedReply() {
            TelegramApprovalCallbackHandler approvals = mock(TelegramApprovalCallbackHandler.class);
            TelegramAuthorizationCallbackHandler authorizations =
                    mock(TelegramAuthorizationCallbackHandler.class);
            TelegramQuestionCallbackHandler questions = mock(TelegramQuestionCallbackHandler.class);
            ApprovalCallbackInterceptor interceptor =
                    new ApprovalCallbackInterceptor(approvals, authorizations, questions);
            Map<String, Object> reply = press("irrelevant");
            when(questions.isPossibleTextReply(reply)).thenReturn(true);

            interceptor.handleAsync(reply);

            // A person replying to OUR question was answering us. A bot shared with a workflow
            // trigger therefore stops seeing replies to our own messages, which is the narrow
            // and deliberate cost of the free-text answer.
            verify(questions).handleReply(reply);
            verify(approvals, never()).handle(any());
        }

        @Test
        @DisplayName("diverts a payload either family claims, and only those")
        void divertsEitherFamily() {
            TelegramApprovalCallbackHandler approvals = mock(TelegramApprovalCallbackHandler.class);
            TelegramAuthorizationCallbackHandler authorizations =
                    mock(TelegramAuthorizationCallbackHandler.class);
            TelegramQuestionCallbackHandler questions = mock(TelegramQuestionCallbackHandler.class);
            ApprovalCallbackInterceptor interceptor =
                    new ApprovalCallbackInterceptor(approvals, authorizations, questions);
            Map<String, Object> payload = press("whatever");

            when(approvals.isApprovalCallback(payload)).thenReturn(false);
            when(authorizations.isAuthorizationCallback(payload)).thenReturn(false);
            when(questions.isQuestionCallback(payload)).thenReturn(false);
            when(questions.isPossibleTextReply(payload)).thenReturn(false);
            // Not ours: it must keep flowing to the user's workflow untouched.
            assertThat(interceptor.isApprovalCallback(payload)).isFalse();

            when(authorizations.isAuthorizationCallback(payload)).thenReturn(true);
            assertThat(interceptor.isApprovalCallback(payload)).isTrue();

            when(authorizations.isAuthorizationCallback(payload)).thenReturn(false);
            when(questions.isQuestionCallback(payload)).thenReturn(true);
            assertThat(interceptor.isApprovalCallback(payload)).isTrue();
        }
    }

    @Test
    @DisplayName("the two prefixes are different, which is the whole routing rule")
    void prefixesDiffer() {
        assertThat(AgentAuthorizationChannelService.CALLBACK_PREFIX)
                .isNotEqualTo(TelegramApprovalNotifier.CALLBACK_PREFIX);
    }

    @Test
    @DisplayName("an unused interceptor touches neither handler")
    void idleInterceptorIsInert() {
        TelegramApprovalCallbackHandler approvals = mock(TelegramApprovalCallbackHandler.class);
        TelegramAuthorizationCallbackHandler authorizations = mock(TelegramAuthorizationCallbackHandler.class);
        TelegramQuestionCallbackHandler questions = mock(TelegramQuestionCallbackHandler.class);
        new ApprovalCallbackInterceptor(approvals, authorizations, questions);
        verifyNoInteractions(approvals, authorizations, questions);
    }
}
