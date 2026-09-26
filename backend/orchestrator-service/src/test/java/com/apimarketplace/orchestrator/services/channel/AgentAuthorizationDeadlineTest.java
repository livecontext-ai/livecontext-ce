package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.AnswerOutcome;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.DeliveryRequest;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.DeliveryStatus;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens in the window between a request's deadline and the sweep that retires it.
 *
 * <p>Requests expire on a timer, in batches. So for a while after its TTL runs out a row still
 * reads {@code SENT}, and the message is still in somebody's chat with two live buttons. Two
 * things went wrong in that window, in opposite directions, and both of them looked like nothing
 * was wrong. A press was honoured, authorizing an action whose run had already given up on it.
 * And a new ask was refused as a duplicate of the dead one, so an agent that asked once and was
 * never answered could be silent for far longer than the TTL it was told about: not a day, but
 * a day plus however long the sweep took to reach that row.
 */
class AgentAuthorizationDeadlineTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-1";
    private static final UUID LINK_ID = UUID.randomUUID();

    private ChatChannelService channelService;
    private ChatChannelConnector connector;
    private ChatAuthorizationRequestRepository requestRepository;
    private ChatChannelLinkRepository linkRepository;
    private AgentAuthorizationAnswerApplier applier;
    private AgentAuthorizationChannelService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        channelService = mock(ChatChannelService.class);
        connector = mock(ChatChannelConnector.class);
        requestRepository = mock(ChatAuthorizationRequestRepository.class);
        linkRepository = mock(ChatChannelLinkRepository.class);
        applier = mock(AgentAuthorizationAnswerApplier.class);
        ObjectProvider<AgentClient> agentClientProvider = mock(ObjectProvider.class);
        when(agentClientProvider.getIfAvailable()).thenReturn(null);

        when(connector.channelId()).thenReturn("telegram");
        when(connector.maxDecisionTextChars()).thenCallRealMethod();
        when(channelService.resolveFor(ORG, null)).thenReturn(Optional.of(
                new ResolvedTarget(LINK_ID, "telegram", 9L, "-100123", List.of())));
        when(connector.sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(Outcome.of("556"));
        when(connector.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(Outcome.of(null));
        when(requestRepository.claim(any(), any())).thenReturn(1);
        when(requestRepository.save(any())).thenAnswer(invocation -> {
            ChatAuthorizationRequestEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            return row;
        });

        service = new AgentAuthorizationChannelService(channelService,
                new ChatChannelConnectorRegistry(List.of(connector)), requestRepository, linkRepository,
                applier, agentClientProvider, 24);
    }

    /** An ObjectProvider that has no AgentClient, like a deployment without agent-service. */
    @SuppressWarnings("unchecked")
    private static ObjectProvider<AgentClient> noAgentClient() {
        ObjectProvider<AgentClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static DeliveryRequest request() {
        return new DeliveryRequest(TENANT, ORG, "conv-1", "call-1", "publish_post",
                UUID.randomUUID().toString(), "Night Publisher", "post the report", "publish|id=abc");
    }

    private static ChatAuthorizationRequestEntity row(Instant expiresAt) {
        ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
        row.setId(UUID.randomUUID());
        row.setTenantId(TENANT);
        row.setOrganizationId(ORG);
        row.setLinkId(LINK_ID);
        row.setChannel("telegram");
        row.setCredentialId(9L);
        row.setChatId("-100123");
        row.setCallbackToken("tok");
        row.setConversationId("conv-1");
        row.setGateKey("call-1");
        row.setRule("publish_post");
        row.setAgentName("Night Publisher");
        row.setFingerprint("fp");
        row.setMessageId("555");
        row.setMessageText("Night Publisher needs your permission to continue.");
        row.setStatus(RequestStatus.SENT);
        row.setCreatedAt(Instant.now().minusSeconds(90_000));
        row.setExpiresAt(expiresAt);
        return row;
    }

    @Nested
    @DisplayName("a button pressed after the deadline")
    class PressedLate {

        @Test
        @DisplayName("authorizes nothing")
        void doesNotApplyTheDecision() {
            when(requestRepository.findByCallbackToken("tok"))
                    .thenReturn(Optional.of(row(Instant.now().minusSeconds(60))));

            AnswerOutcome outcome = service.answer("tok", true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // The whole point. The run that asked has ended; releasing a parked call it no
            // longer holds writes a standing grant for nobody, and the action it names is one
            // the agent was already told it could not take.
            verify(applier, never()).apply(any(), anyBoolean(), anyString());
            verify(requestRepository, never()).claim(any(), any());
            assertThat(outcome.handled()).isTrue();
        }

        @Test
        @DisplayName("says it expired rather than that somebody else decided it")
        void tellsThePersonWhatActuallyHappened() {
            when(requestRepository.findByCallbackToken("tok"))
                    .thenReturn(Optional.of(row(Instant.now().minusSeconds(60))));

            AnswerOutcome outcome = service.answer("tok", true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // "Already decided" would send them looking for the colleague who decided it.
            // Nobody did: the deadline did, and the sentence has to say so, with what
            // happens next, because from a chat there is nothing else to read.
            assertThat(outcome.replyToUser()).contains("expired").contains("ask again");
            assertThat(outcome.asAlert()).isTrue();
        }

        @Test
        @DisplayName("is still refused when the press is a rejection")
        void refusalIsAlsoTooLate() {
            when(requestRepository.findByCallbackToken("tok"))
                    .thenReturn(Optional.of(row(Instant.now().minusSeconds(60))));

            service.answer("tok", false, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // A late "no" is as unapplicable as a late "yes": the call it would refuse is
            // not parked any more. Reading it as harmless is how the guard ends up with a
            // branch that skips it.
            verify(applier, never()).apply(any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("is honoured normally while the deadline still holds")
        void aPressInTimeStillWorks() {
            when(requestRepository.findByCallbackToken("tok"))
                    .thenReturn(Optional.of(row(Instant.now().plusSeconds(3600))));
            when(applier.apply(any(), eq(true), anyString())).thenReturn(true);

            AnswerOutcome outcome = service.answer("tok", true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            verify(applier).apply(any(), eq(true), eq("user-7"));
            assertThat(outcome.replyToUser()).contains("Approved");
        }

        @Test
        @DisplayName("is refused when the request carries no deadline at all")
        void aRequestWithoutADeadlineIsRefused() {
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row(null)));

            service.answer("tok", true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // The column is NOT NULL, so this row cannot come from the database. What the
            // test pins is the DIRECTION the guard fails in if one ever does: a deadline
            // nobody can read is not a reason to grant a permission. The cost of being
            // wrong here is one re-ask; the cost the other way is an unauthorized action.
            verify(applier, never()).apply(any(), anyBoolean(), anyString());
        }
    }

    @Nested
    @DisplayName("a decision that could not be applied and could not be handed back")
    class RetiredAfterAFailedApply {

        /** A live request, found by its token, whose apply is about to fail. */
        private ChatAuthorizationRequestEntity liveRequest() {
            ChatAuthorizationRequestEntity row = row(Instant.now().plusSeconds(3600));
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
            when(applier.apply(any(), anyBoolean(), anyString())).thenReturn(false);
            return row;
        }

        @Test
        @DisplayName("takes the buttons off the message it retires")
        void closesTheMessageItRetires() {
            ChatAuthorizationRequestEntity row = liveRequest();
            // The hand-back is refused: the agent's next run asked the same question again and
            // took the live slot, which the partial unique index allows exactly one of.
            doThrow(new org.springframework.dao.DataIntegrityViolationException("live slot taken"))
                    .when(requestRepository).releaseClaim(row.getId());

            service.answer("tok", true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // This is the THIRD path that writes EXPIRED, and the least obvious: it is reached
            // only when two things fail in a row. A row retired here is invisible to the sweep
            // (which selects SENT), so if this close is ever dropped the buttons stay live for
            // good and the next press is answered "already decided" about a decision nobody took.
            assertThat(row.getStatus()).isEqualTo(RequestStatus.EXPIRED);
            verify(connector).closeDecisionRequest(eq(TENANT), eq(9L), eq("-100123"), eq("555"),
                    eq(row.getMessageText()),
                    eq(AgentAuthorizationChannelService.Verdict.EXPIRED.line()));
        }

        @Test
        @DisplayName("treats a hand-back that matched no row as a failed hand-back")
        void aZeroFromReleaseClaimIsNotSuccess() {
            ChatAuthorizationRequestEntity row = liveRequest();
            // No exception, no row updated. The conditional UPDATE simply matched nothing, which
            // leaves the row RESOLVED with no decision: terminal, invisible to the sweep, buttons
            // live. Reading only the exception calls that a successful hand-back and strands it.
            when(requestRepository.releaseClaim(row.getId())).thenReturn(0);

            service.answer("tok", true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(row.getStatus()).isEqualTo(RequestStatus.EXPIRED);
            verify(connector).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), eq(AgentAuthorizationChannelService.Verdict.EXPIRED.line()));
        }

        @Test
        @DisplayName("hands the row back untouched when the release did land")
        void aSuccessfulHandBackRetiresNothing() {
            ChatAuthorizationRequestEntity row = liveRequest();
            when(requestRepository.releaseClaim(row.getId())).thenReturn(1);

            AnswerOutcome outcome = service.answer("tok", true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // The question was never answered, so it must go back to being answerable rather
            // than sit there looking decided. Nothing is retired and nothing is closed.
            assertThat(row.getStatus()).isEqualTo(RequestStatus.SENT);
            verify(connector, never()).closeDecisionRequest(anyString(), anyLong(), anyString(),
                    anyString(), anyString(), anyString());
            assertThat(outcome.replyToUser()).contains("Could not apply");
        }
    }

    @Nested
    @DisplayName("a workspace with more than one channel connected")
    class SeveralConnectors {

        /** A second provider, so "which connector" stops being a question with one answer. */
        private ChatChannelConnector otherConnector;
        private AgentAuthorizationChannelService withBoth;

        @BeforeEach
        void twoConnectors() {
            otherConnector = mock(ChatChannelConnector.class);
            when(otherConnector.channelId()).thenReturn("whatsapp");
            when(otherConnector.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString())).thenReturn(Outcome.of(null));
            when(otherConnector.sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString())).thenReturn(Outcome.of("999"));
            withBoth = new AgentAuthorizationChannelService(channelService,
                    new ChatChannelConnectorRegistry(List.of(connector, otherConnector)),
                    requestRepository, linkRepository, applier, noAgentClient(), 24);
        }

        @Test
        @DisplayName("closes an overdue request through ITS channel, not the current default")
        void retiresThroughTheRowsOwnConnector() {
            // The workspace's default has moved to the other provider since this request went
            // out. The overdue row is found by conversation and fingerprint, neither of which is
            // channel-scoped, so nothing about the lookup says which connector it belongs to.
            when(channelService.resolveFor(ORG, null)).thenReturn(Optional.of(
                    new ResolvedTarget(LINK_ID, "whatsapp", 77L, "+3312345", List.of())));
            ChatAuthorizationRequestEntity overdue = row(Instant.now().minusSeconds(60));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(overdue));

            withBoth.deliver(request());

            // Editing a Telegram message id through the WhatsApp connector, with a Telegram
            // credential and chat id, is a call that can only fail, and it fails quietly: the
            // edit is best-effort, so the buttons would just stay live with nothing said.
            verify(connector).closeDecisionRequest(eq(TENANT), eq(9L), eq("-100123"), eq("555"),
                    anyString(), eq(AgentAuthorizationChannelService.Verdict.EXPIRED.line()));
            verify(otherConnector, never()).closeDecisionRequest(anyString(), anyLong(), anyString(),
                    anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("closes an answered request through ITS channel too")
        void answersThroughTheRowsOwnConnector() {
            when(applier.apply(any(), eq(true), anyString())).thenReturn(true);
            ChatAuthorizationRequestEntity row = row(Instant.now().plusSeconds(3600));
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));

            withBoth.answer("tok", true, "user-7", PressOrigin.of("telegram"));

            // Same rule on the press path: the verdict belongs on the message that asked, which
            // lives wherever that request was delivered.
            verify(connector).closeDecisionRequest(eq(TENANT), eq(9L), eq("-100123"), eq("555"),
                    anyString(), anyString());
            verify(otherConnector, never()).closeDecisionRequest(anyString(), anyLong(), anyString(),
                    anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("asking again while an overdue request has not been swept")
    class AskingAgain {

        @Test
        @DisplayName("sends the new question instead of reporting the dead one as pending")
        void overdueRequestDoesNotBlockTheNextAsk() {
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(row(Instant.now().minusSeconds(60))));

            assertThat(service.deliver(request()).status()).isEqualTo(DeliveryStatus.SENT);

            // ALREADY_PENDING tells the agent to wait for an answer to a question that can
            // no longer be answered, for as long as it takes a scheduler to reach one row.
            verify(connector).sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("retires the overdue request first, so the live-row index lets the new one in")
        void retiresTheOverdueRequest() {
            ChatAuthorizationRequestEntity overdue = row(Instant.now().minusSeconds(60));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(overdue));

            service.deliver(request());

            // One live row per ask is a partial unique index on SENT. Sending without
            // retiring the old row would have the insert refused by the database, which
            // reads as "superseded by an identical request" to somebody who asked once.
            assertThat(overdue.getStatus()).isEqualTo(RequestStatus.EXPIRED);
            verify(requestRepository).save(overdue);
        }

        @Test
        @DisplayName("takes the buttons off the question it retires, the way the sweep would have")
        void closesTheMessageItRetires() {
            ChatAuthorizationRequestEntity overdue = row(Instant.now().minusSeconds(60));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(overdue));

            service.deliver(request());

            // Retiring a row hides it from the sweep forever: that query selects SENT. So a
            // status flip alone leaves live buttons on a dead question, and pressing one is met
            // with "This request was already decided" about a decision nobody took. Whoever
            // retires the row owes it the same closing edit.
            verify(connector).closeDecisionRequest(eq(TENANT), eq(9L), eq("-100123"), eq("555"),
                    eq(overdue.getMessageText()),
                    eq(AgentAuthorizationChannelService.Verdict.EXPIRED.line()));
        }

        @Test
        @DisplayName("closes it with the same sentence the sweep uses, not a second wording")
        void theVerdictIsTheSweepsVerdict() {
            ChatAuthorizationRequestEntity overdue = row(Instant.now().minusSeconds(60));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(overdue));
            ChatAuthorizationRequestRepository sweepRepository =
                    mock(ChatAuthorizationRequestRepository.class);
            ChatAuthorizationRequestEntity swept = row(Instant.now().minusSeconds(60));
            when(sweepRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                    .thenReturn(List.of(swept));

            service.deliver(request());
            new ChatAuthorizationExpiryScheduler(sweepRepository,
                    new ChatChannelConnectorRegistry(List.of(connector))).expireOverdueRequests();

            // Two paths retire a row. A person must not be able to tell which one reached their
            // message first, and the only place that difference would ever show is their chat.
            ArgumentCaptor<String> verdicts = ArgumentCaptor.forClass(String.class);
            verify(connector, times(2)).closeDecisionRequest(anyString(), anyLong(), anyString(),
                    anyString(), anyString(), verdicts.capture());
            assertThat(verdicts.getAllValues()).hasSize(2)
                    .containsOnly(AgentAuthorizationChannelService.Verdict.EXPIRED.line());
        }

        @Test
        @DisplayName("retires nothing when the workspace has no connector to deliver with")
        void doesNotRetireWhenNothingCouldBeSentAnyway() {
            ChatAuthorizationRequestEntity overdue = row(Instant.now().minusSeconds(60));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(overdue));
            AgentAuthorizationChannelService withoutConnector = new AgentAuthorizationChannelService(
                    channelService, new ChatChannelConnectorRegistry(List.of()), requestRepository,
                    linkRepository, applier, noAgentClient(), 24);

            assertThat(withoutConnector.deliver(request()).status()).isEqualTo(DeliveryStatus.NO_CHANNEL);

            // Losing the old request to a delivery that was never going to happen would leave the
            // workspace with no question anywhere and no message to press: the sweep can still
            // reach this row and close it properly.
            assertThat(overdue.getStatus()).isEqualTo(RequestStatus.SENT);
            verify(requestRepository, never()).save(overdue);
        }

        @Test
        @DisplayName("keeps the new request when the closing edit fails, since the row is what counts")
        void aRefusedEditDoesNotStopTheNewAsk() {
            ChatAuthorizationRequestEntity overdue = row(Instant.now().minusSeconds(60));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(overdue));
            when(connector.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString())).thenThrow(new IllegalStateException("telegram down"));

            assertThat(service.deliver(request()).status()).isEqualTo(DeliveryStatus.SENT);

            // The edit is cosmetic; the row is what the duplicate rule reads. A provider outage
            // must not make the agent wait another cycle to ask a question it is entitled to ask.
            assertThat(overdue.getStatus()).isEqualTo(RequestStatus.EXPIRED);
        }

        @Test
        @DisplayName("still refuses a duplicate while the first question is genuinely live")
        void aLiveRequestIsStillADuplicate() {
            ChatAuthorizationRequestEntity live = row(Instant.now().plusSeconds(3600));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(live));

            assertThat(service.deliver(request()).status()).isEqualTo(DeliveryStatus.ALREADY_PENDING);

            // The property the deadline check must not cost: a nightly agent sending the
            // same question every night until somebody answers is what this rule prevents.
            verify(connector, never()).sendDecisionRequest(anyString(), anyLong(), anyString(),
                    anyString(), anyString(), anyString());
            assertThat(live.getStatus()).isEqualTo(RequestStatus.SENT);
        }

        @Test
        @DisplayName("reports the old request as pending when it cannot be retired")
        void aFailedRetirementDoesNotSendAnyway() {
            ChatAuthorizationRequestEntity overdue = row(Instant.now().minusSeconds(60));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                    .thenReturn(Optional.of(overdue));
            when(requestRepository.save(overdue)).thenThrow(new IllegalStateException("row is gone"));

            assertThat(service.deliver(request()).status()).isEqualTo(DeliveryStatus.ALREADY_PENDING);

            // Losing the race with the sweep is normal. Sending anyway would be refused by
            // the live-row index and leave a second pair of buttons in the chat for the
            // moment it took to notice, so the safe fallback is the old answer.
            verify(connector, never()).sendDecisionRequest(anyString(), anyLong(), anyString(),
                    anyString(), anyString(), anyString());
            // And the old message keeps its buttons, because the old question is still live.
            verify(connector, never()).closeDecisionRequest(anyString(), anyLong(), anyString(),
                    anyString(), anyString(), anyString());
        }
    }
}
