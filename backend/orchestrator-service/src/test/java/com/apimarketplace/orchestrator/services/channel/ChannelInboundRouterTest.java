package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.services.approvalchannel.WorkflowApprovalPressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ChannelInboundRouter")
class ChannelInboundRouterTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUv";
    private static final PressOrigin SLACK = new PressOrigin("slack", null, "C1");

    private AgentAuthorizationChannelService authorizations;
    private ChatQuestionService questions;
    private WorkflowApprovalPressService approvals;
    private ChannelInboundRouter router;

    @BeforeEach
    void setUp() {
        authorizations = mock(AgentAuthorizationChannelService.class);
        questions = mock(ChatQuestionService.class);
        approvals = mock(WorkflowApprovalPressService.class);
        router = new ChannelInboundRouter(authorizations, questions, approvals);
    }

    private static ChatAuthorizationRequestEntity row() {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setTenantId("tenant");
        row.setOrganizationId("org");
        row.setCredentialId(5L);
        row.setChatId("C1");
        return row;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lcaut:" + TOKEN + ":a", "lcask:" + TOKEN + ":o3", "lcask:" + TOKEN + ":done",
            "lcask:" + TOKEN + ":other", "lcapr:" + TOKEN + ":r"})
    @DisplayName("recognises each family's buttons")
    void recognisesOurs(String payload) {
        assertThat(router.isOurPayload(payload)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"lcaut:short:a", "lcaut:" + TOKEN + ":x", "lcask:" + TOKEN + ":o123",
            "lcapr:" + TOKEN + ":a:extra", "their_button", "lcxyz:" + TOKEN + ":a",
            "lcaut:AbCdEfGhIjKlMnOpQrStUvAbCdEfGhIjKlMnOpQrStUvAbCdE:a"})
    @DisplayName("rejects malformed, foreign or oversized payloads without calling any service")
    void rejectsOthers(String payload) {
        assertThat(router.isOurPayload(payload)).isFalse();

        ChannelInboundRouter.Result result = router.press(SLACK, payload, "U1");

        assertThat(result.handled()).isFalse();
        verifyNoInteractions(authorizations, questions, approvals);
    }

    @Test
    @DisplayName("an authorization press reaches its service with the origin, and carries the row's scope back")
    void authorizationPress() {
        when(authorizations.answer(TOKEN, true, "U1", SLACK))
                .thenReturn(new AgentAuthorizationChannelService.AnswerOutcome(true, "Approved", false, row()));

        ChannelInboundRouter.Result result = router.press(SLACK, "lcaut:" + TOKEN + ":a", "U1");

        assertThat(result.handled()).isTrue();
        assertThat(result.replyToUser()).isEqualTo("Approved");
        assertThat(result.credentialId()).isEqualTo(5L);
        assertThat(result.organizationId()).isEqualTo("org");
    }

    @Test
    @DisplayName("a settled question hands its answers over only after the acknowledgement")
    void settledQuestionDefersTheHandOver() {
        ChatAuthorizationRequestEntity row = row();
        when(questions.answer(TOKEN, "o1", "U1", SLACK))
                .thenReturn(new ChatQuestionService.AnswerOutcome(true, null, false, row, true));

        ChannelInboundRouter.Result result = router.press(SLACK, "lcask:" + TOKEN + ":o1", "U1");

        // Handing over starts an LLM turn; running it inside press() would miss the provider's
        // acknowledgement window. It must wait for afterAck.
        verify(questions, never()).applyIfComplete(any());
        result.afterAck().run();
        verify(questions).applyIfComplete(row);
    }

    @Test
    @DisplayName("an unsettled press (a toggle) hands nothing over")
    void toggleHandsNothingOver() {
        when(questions.answer(TOKEN, "o1", "U1", SLACK))
                .thenReturn(new ChatQuestionService.AnswerOutcome(true, "Added.", false, row(), false));

        router.press(SLACK, "lcask:" + TOKEN + ":o1", "U1").afterAck().run();

        verify(questions, never()).applyIfComplete(any());
    }

    @Test
    @DisplayName("a service that does not recognise the token makes the press not ours")
    void unknownTokenIsNotOurs() {
        when(authorizations.answer(anyString(), anyBoolean(), any(), any()))
                .thenReturn(new AgentAuthorizationChannelService.AnswerOutcome(false, null, false, null));

        assertThat(router.press(SLACK, "lcaut:" + TOKEN + ":r", "U1").handled()).isFalse();
    }

    @Test
    @DisplayName("a workflow approval press reaches its service with the origin and carries the delivery's scope back")
    void approvalPress() {
        ApprovalChannelDeliveryEntity delivery = new ApprovalChannelDeliveryEntity();
        delivery.setTenantId("tenant");
        delivery.setCredentialId(7L);
        delivery.setChatId("C1");
        when(approvals.press(SLACK, TOKEN, false, "U1"))
                .thenReturn(new WorkflowApprovalPressService.PressOutcome(true, "Rejected", false, delivery));

        ChannelInboundRouter.Result result = router.press(SLACK, "lcapr:" + TOKEN + ":r", "U1");

        assertThat(result.handled()).isTrue();
        assertThat(result.credentialId()).isEqualTo(7L);
        assertThat(result.chatId()).isEqualTo("C1");
    }

    @Test
    @DisplayName("an approval token the service does not own is not ours")
    void unknownApprovalIsNotOurs() {
        when(approvals.press(any(), anyString(), anyBoolean(), any()))
                .thenReturn(new WorkflowApprovalPressService.PressOutcome(false, null, false, null));

        assertThat(router.press(SLACK, "lcapr:" + TOKEN + ":a", "U1").handled()).isFalse();
    }

    @Test
    @DisplayName("a typed reply is answered with the origin; a recorded one needs no line of its own, a refusal does")
    void typedReplies() {
        PressOrigin whatsapp = new PressOrigin("whatsapp", 5L, "336");
        when(questions.answerWithText("336", "wamid.q", "106", "Tuesday", "336", whatsapp))
                .thenReturn(new ChatQuestionService.AnswerOutcome(true, "Recorded.", false, row(), true));
        when(questions.answerWithText("336", "wamid.q", "106", "Nope", "999", whatsapp))
                .thenReturn(new ChatQuestionService.AnswerOutcome(true, "You are not one of the people who may "
                        + "answer here.", false, row(), false));

        assertThat(router.reply(whatsapp, "336", "wamid.q", "106", "Tuesday", "336").replyToUser()).isNull();
        assertThat(router.reply(whatsapp, "336", "wamid.q", "106", "Nope", "999").replyToUser())
                .contains("not one of the people");
    }
}
