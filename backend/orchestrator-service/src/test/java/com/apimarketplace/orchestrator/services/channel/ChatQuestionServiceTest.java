package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.agent.tools.ask.UserQuestion;
import com.apimarketplace.agent.tools.ask.UserQuestionOption;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestKind;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChoiceOption;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget;
import com.apimarketplace.orchestrator.services.channel.ChatQuestionService.DeliveryStatus;
import com.apimarketplace.orchestrator.services.channel.ChatQuestionService.QuestionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Putting a question to somebody who is not in the app.
 *
 * <p>Two properties carry this one. A question must arrive as something a person can actually
 * answer from a phone, which is what the body and the keyboard are about. And the record of it
 * must be able to resolve a press that arrives days later, which is what the row is about: a
 * message whose row cannot be found is a button that does nothing, with no error anywhere.
 */
@DisplayName("ChatQuestionService - asking outside the app")
class ChatQuestionServiceTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-1";
    private static final UUID LINK_ID = UUID.randomUUID();

    private ChatChannelService channelService;
    private ChatChannelConnector connector;
    private ChatAuthorizationRequestRepository requestRepository;
    private ChatChannelLinkRepository linkRepository;
    private ChatQuestionAnswerApplier answerApplier;
    private ChatChannelBotRepository botRepository;
    private ChatQuestionService service;
    private AgentClient agentClient;

    @BeforeEach
    void setUp() {
        channelService = mock(ChatChannelService.class);
        connector = mock(ChatChannelConnector.class);
        requestRepository = mock(ChatAuthorizationRequestRepository.class);
        linkRepository = mock(ChatChannelLinkRepository.class);

        when(connector.channelId()).thenReturn("telegram");
        // The interface default, not the 0 a bare mock answers: the body is capped to this
        // before it is sent, so an unstubbed 0 would send an empty message everywhere.
        when(connector.maxDecisionTextChars()).thenCallRealMethod();
        // Telegram's shape by default: it hears typed replies, so "Other..." is offered.
        when(connector.capabilities()).thenCallRealMethod();
        when(channelService.resolveFor(ORG, null)).thenReturn(Optional.of(
                new ResolvedTarget(LINK_ID, "telegram", 9L, "-100123", List.of())));
        when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(requestRepository.save(any())).thenAnswer(invocation -> {
            ChatAuthorizationRequestEntity row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            return row;
        });
        when(linkRepository.findById(LINK_ID)).thenReturn(Optional.empty());
        when(connector.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(Outcome.of("msg-1"));

        answerApplier = mock(ChatQuestionAnswerApplier.class);
        botRepository = mock(ChatChannelBotRepository.class);
        agentClient = mock(AgentClient.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AgentClient> agentClientProvider = mock(ObjectProvider.class);
        when(agentClientProvider.getIfAvailable()).thenReturn(agentClient);
        service = new ChatQuestionService(channelService, requestRepository,
                new ChatChannelConnectorRegistry(List.of(connector)), linkRepository,
                answerApplier, botRepository, agentClientProvider, 6);
    }

    @Nested
    @DisplayName("what the person is shown")
    class WhatIsShown {

        @Test
        @DisplayName("one message per question, because one keyboard cannot say which question it answers")
        void oneMessagePerQuestion() {
            service.deliverQuestions(request(single("Tone", "Which tone?", "Formal", "Playful"),
                    single("Length", "How long?", "Short", "Long")));

            verify(connector, times(2)).sendChoiceRequest(anyString(), anyLong(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("the option descriptions go in the body, where a button has no room for them")
        void descriptionsTravelInTheBody() {
            UserQuestion question = new UserQuestion("Tone", "Which tone?", List.of(
                    new UserQuestionOption("Formal", "for the board"),
                    new UserQuestionOption("Playful", "for the newsletter")), false);

            service.deliverQuestions(request(question));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(connector).sendChoiceRequest(anyString(), anyLong(), anyString(), body.capture(), any());
            // A Telegram button has no subtitle, so a description that is not here cannot be
            // read anywhere, and half of what an option means is in its description.
            assertThat(body.getValue()).contains("Tone").contains("Which tone?")
                    .contains("Formal: for the board").contains("Playful: for the newsletter");
        }

        @Test
        @DisplayName("every question offers a way out of the options")
        void alwaysOffersOther() {
            service.deliverQuestions(request(single("Tone", "Which tone?", "Formal", "Playful")));

            // The tool help tells the agent never to declare an "Other" option, because the
            // person can always answer in their own words. That promise has to hold here too,
            // or the channel is the poorer place to be asked.
            assertThat(labels()).contains(ChatQuestionService.OTHER_LABEL);
        }

        @Test
        @DisplayName("a provider that cannot bring a typed reply back is not offered Other")
        void noOtherWhereATypedReplyCannotComeBack() {
            when(connector.capabilities()).thenReturn(
                    new ChatChannelConnector.Capabilities(true, false, true, false));

            service.deliverQuestions(request(single("Tone", "Which tone?", "Formal", "Playful")));

            // A Slack or Discord interaction carries no text. Offered there, the button would
            // promise a way to answer that has nowhere to go: pressed, the person would be told to
            // reply, and the reply would never reach us.
            assertThat(labels()).doesNotContain(ChatQuestionService.OTHER_LABEL)
                    .containsExactly("Formal", "Playful");
        }

        @Test
        @DisplayName("a single select has no Done, because the pick IS the answer")
        void singleSelectHasNoDone() {
            service.deliverQuestions(request(single("Tone", "Which tone?", "Formal", "Playful")));

            assertThat(labels()).doesNotContain(ChatQuestionService.DONE_LABEL)
                    .containsSequence("Formal", "Playful");
        }

        @Test
        @DisplayName("a multi select has Done, and says so in words as well")
        void multiSelectHasDone() {
            UserQuestion question = new UserQuestion("Topics", "Which topics?", List.of(
                    new UserQuestionOption("Pricing", null),
                    new UserQuestionOption("Hiring", null)), true);

            service.deliverQuestions(request(question));

            assertThat(labels()).contains(ChatQuestionService.DONE_LABEL);
            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(connector).sendChoiceRequest(anyString(), anyLong(), anyString(), body.capture(), any());
            // A keyboard where several picks are allowed looks exactly like one where they are
            // not, so the only way the person learns it is the text.
            assertThat(body.getValue()).contains("Pick as many as apply");
        }
    }

    @Nested
    @DisplayName("what is recorded")
    class WhatIsRecorded {

        @Test
        @DisplayName("the row knows it is a question, which call it belongs to, and what was asked")
        void theRowCarriesTheQuestion() {
            service.deliverQuestions(request(single("Tone", "Which tone?", "Formal", "Playful")));

            ChatAuthorizationRequestEntity row = savedRows().get(0);
            assertThat(row.getKind()).isEqualTo(RequestKind.CHOICE);
            // Minted per delivery, NOT the provider's tool call id: Gemini numbers its calls
            // call_0, call_1, so that id repeats every turn and in every workspace, and
            // grouping on it would let another tenant's row join this one.
            assertThat(row.getGroupKey()).isNotBlank().isNotEqualTo("call-1");
            assertThat(row.getStatus()).isEqualTo(RequestStatus.SENT);
            assertThat(row.getMessageId()).isEqualTo("msg-1");
            // The question AS SENT. Rebuilt later it would be reworded, and the reworded
            // version is the one the record would keep.
            assertThat(row.getPayload()).containsEntry("header", "Tone");
        }

        @Test
        @DisplayName("the questions of one call get distinct fingerprints, or only the first could be stored")
        void siblingsDoNotCollide() {
            service.deliverQuestions(request(single("Tone", "Which tone?", "Formal", "Playful"),
                    single("Length", "How long?", "Short", "Long")));

            List<ChatAuthorizationRequestEntity> rows = savedRows();
            // The live-request index is unique per (conversation, fingerprint) and the questions
            // of one call share a conversation. Identical fingerprints would let exactly one row
            // through and leave the second question unanswerable.
            assertThat(rows.get(0).getFingerprint()).isNotEqualTo(rows.get(1).getFingerprint());
        }

        @Test
        @DisplayName("the body is stored exactly as it was sent")
        void storesTheBodyAsSent() {
            service.deliverQuestions(request(single("Tone", "Which tone?", "Formal", "Playful")));

            ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
            verify(connector).sendChoiceRequest(anyString(), anyLong(), anyString(), body.capture(), any());
            // The close appends to this. A stored body that differs from the sent one closes a
            // message nobody ever read.
            assertThat(savedRows().get(0).getMessageText()).isEqualTo(body.getValue());
        }
    }

    @Nested
    @DisplayName("where an agent is reached")
    class AgentDestination {

        private final UUID agentId = UUID.randomUUID();
        private final UUID chosen = UUID.randomUUID();

        private QuestionRequest fromAgent() {
            return new QuestionRequest(TENANT, ORG, "conv-1", "call-1", "call-1:ask", agentId.toString(),
                    List.of(single("Tone", "Which tone?", "A", "B")));
        }

        private void agentChose(UUID linkId) {
            AgentDto agent = new AgentDto();
            agent.setName("Finance");
            agent.setChatChannelLinkId(linkId);
            when(agentClient.getAgent(agentId, TENANT, ORG)).thenReturn(agent);
        }

        @Test
        @DisplayName("an agent that chose a destination is asked there, not in the workspace default")
        void chosenDestinationIsUsed() {
            agentChose(chosen);
            when(channelService.resolveFor(ORG, chosen)).thenReturn(Optional.of(
                    new ResolvedTarget(chosen, "telegram", 11L, "-200999", List.of())));

            var result = service.deliverQuestions(fromAgent());

            assertThat(result.status()).isEqualTo(DeliveryStatus.SENT);
            verify(connector).sendChoiceRequest(eq(TENANT), eq(11L), eq("-200999"), anyString(), any());
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), eq("-100123"), anyString(), any());
        }

        @Test
        @DisplayName("a chosen destination that is gone is NOT replaced by the default: nobody is asked")
        void goneDestinationIsNotReplaced() {
            agentChose(chosen);
            when(channelService.resolveFor(ORG, chosen)).thenReturn(Optional.empty());

            var result = service.deliverQuestions(fromAgent());

            assertThat(result.status()).isEqualTo(DeliveryStatus.NO_CHANNEL);
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("an agent with no choice uses the workspace default")
        void noChoiceUsesDefault() {
            agentChose(null);

            var result = service.deliverQuestions(fromAgent());

            assertThat(result.status()).isEqualTo(DeliveryStatus.SENT);
            verify(connector).sendChoiceRequest(eq(TENANT), eq(9L), eq("-100123"), anyString(), any());
        }

        @Test
        @DisplayName("a failed agent lookup sends NOTHING: whether it chose a destination is unknown")
        void lookupFailureSendsNothing() {
            when(agentClient.getAgent(agentId, TENANT, ORG)).thenThrow(new RuntimeException("agent-service down"));

            var result = service.deliverQuestions(fromAgent());

            // Guessing the default could ask people nobody picked for this agent.
            assertThat(result.status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(result.error()).contains("nothing was sent");
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any());
            verify(channelService, never()).resolveFor(any(), any());
        }

        @Test
        @DisplayName("an agent whose channel is switched off reaches nobody: NO_CHANNEL, like no channel at all")
        void switchedOffReachesNobody() {
            AgentDto agent = new AgentDto();
            agent.setName("Finance");
            agent.setChatChannelEnabled(false);
            when(agentClient.getAgent(agentId, TENANT, ORG)).thenReturn(agent);

            var result = service.deliverQuestions(fromAgent());

            assertThat(result.status()).isEqualTo(DeliveryStatus.NO_CHANNEL);
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any());
            verify(channelService, never()).resolveFor(any(), any());
        }

        @Test
        @DisplayName("an agent that cannot be found is unknown too, not the default")
        void missingAgentSendsNothing() {
            when(agentClient.getAgent(agentId, TENANT, ORG)).thenReturn(null);

            assertThat(service.deliverQuestions(fromAgent()).status()).isEqualTo(DeliveryStatus.FAILED);
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("a question with no agent at all goes to the workspace default")
        void noAgentUsesDefault() {
            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B")));

            assertThat(result.status()).isEqualTo(DeliveryStatus.SENT);
            verify(agentClient, never()).getAgent(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("when it cannot be asked")
    class WhenItCannot {

        @Test
        @DisplayName("no destination is NO_CHANNEL, not a failure")
        void noDestination() {
            when(channelService.resolveFor(ORG, null)).thenReturn(Optional.empty());

            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B")));

            // "Nobody could be asked" and "it broke" lead the agent to completely different
            // behaviour, and only one of them happened.
            assertThat(result.status()).isEqualTo(DeliveryStatus.NO_CHANNEL);
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("the same question already waiting is not asked twice")
        void duplicateIsRefused() {
            ChatAuthorizationRequestEntity live = new ChatAuthorizationRequestEntity();
            live.setCreatedAt(Instant.parse("2026-09-22T08:00:00Z"));
            live.setExpiresAt(Instant.now().plusSeconds(3600));
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(),
                    eq(RequestStatus.SENT))).thenReturn(Optional.of(live));

            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B")));

            // A nightly agent asking the identical question every night would otherwise stack
            // messages nobody can tell apart, and an answer to one of them means nothing.
            assertThat(result.status()).isEqualTo(DeliveryStatus.ALREADY_PENDING);
            assertThat(result.requestedAt()).isEqualTo(live.getCreatedAt());
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("an expired twin is retired first, or the fresh send cannot be recorded")
        void anExpiredTwinIsRetiredNotIgnored() {
            ChatAuthorizationRequestEntity stale = staleRow(null);
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(),
                    eq(RequestStatus.SENT))).thenReturn(Optional.of(stale));

            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B")));

            // The sweep runs every few minutes, so a row can be past its deadline and still
            // SENT. Simply ignoring it was not enough: uq_chat_auth_requests_live is unique over
            // (conversation, fingerprint) for LIVE rows, so the fresh copy was delivered and then
            // failed to record, leaving the person a question whose buttons resolve nothing.
            assertThat(stale.getStatus()).isEqualTo(RequestStatus.EXPIRED);
            assertThat(result.status()).isEqualTo(DeliveryStatus.SENT);
            // And its buttons go: a retired question left live invites a press on a question
            // nobody can answer any more.
            verify(connector).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    any(), eq(AgentAuthorizationChannelService.Verdict.EXPIRED.line()));
        }

        @Test
        @DisplayName("a twin that cannot be retired blocks the fresh send instead of colliding with it")
        void anUnretirableTwinBlocksTheSend() {
            ChatAuthorizationRequestEntity stale = staleRow(null);
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(),
                    eq(RequestStatus.SENT))).thenReturn(Optional.of(stale));
            org.mockito.Mockito.doThrow(new IllegalStateException("lost a race with the sweep"))
                    .when(requestRepository).save(stale);

            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B")));

            // Still SENT means it still holds the fingerprint, and a fresh copy would be
            // delivered and then fail to record. Reporting it pending is the safe answer.
            assertThat(result.status()).isEqualTo(DeliveryStatus.ALREADY_PENDING);
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("an overdue earlier set is retired whole, not one question of it")
        void anOverdueSetIsRetiredWhole() {
            ChatAuthorizationRequestEntity first = staleRow("old-group");
            ChatAuthorizationRequestEntity second = staleRow("old-group");
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(),
                    eq(RequestStatus.SENT))).thenReturn(Optional.of(first));
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc("conv-1", "old-group"))
                    .thenReturn(List.of(first, second));

            service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B"),
                    single("Length", "How long?", "S", "L")));

            // Its rows share one deadline, and any left SENT holds its fingerprint: the fresh
            // copy of THAT question would deliver and then fail to record.
            assertThat(first.getStatus()).isEqualTo(RequestStatus.EXPIRED);
            assertThat(second.getStatus()).isEqualTo(RequestStatus.EXPIRED);
        }

        @Test
        @DisplayName("an earlier set half answered and still in time is pending, not asked again")
        void aHalfAnsweredSetIsStillPending() {
            ChatAuthorizationRequestEntity stillOpen = staleRow("old-group");
            stillOpen.setExpiresAt(Instant.now().plusSeconds(3600));
            // Its first question was answered, so only the second's fingerprint is live. Checking
            // the first question alone let the new call through, to collide on the second.
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(),
                    argThat(fp -> fp != null && !fp.endsWith(":1")), eq(RequestStatus.SENT)))
                    .thenReturn(Optional.empty());
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(),
                    argThat(fp -> fp != null && fp.endsWith(":1")), eq(RequestStatus.SENT)))
                    .thenReturn(Optional.of(stillOpen));

            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B"),
                    single("Length", "How long?", "S", "L")));

            assertThat(result.status()).isEqualTo(DeliveryStatus.ALREADY_PENDING);
            verify(connector, never()).sendChoiceRequest(anyString(), anyLong(), anyString(),
                    anyString(), any());
        }

        @Test
        @DisplayName("an incomplete request is a failure, not a missing chat")
        void anIncompleteRequestIsAFailure() {
            var result = service.deliverQuestions(new QuestionRequest(TENANT, ORG, "conv-1", null,
                    null, null, List.of(single("Tone", "Which tone?", "A", "B"))));

            // NO_CHANNEL tells the agent to have somebody connect a chat, which sends a person to
            // fix a setup that is fine, about a caller that sent an incomplete request.
            assertThat(result.status()).isEqualTo(DeliveryStatus.FAILED);
        }

        @Test
        @DisplayName("a first send that fails records nothing")
        void firstSendFailsRecordsNothing() {
            when(connector.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                    .thenReturn(Outcome.failed("chat not found"));

            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B")));

            assertThat(result.status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(result.error()).contains("chat not found");
            // A row for a message that does not exist would sit live under the duplicate index
            // and silently refuse the next run's identical question for the whole TTL.
            verify(requestRepository, never()).save(any());
        }

        @Test
        @DisplayName("a second send that fails abandons the set, including the question already out")
        void aLaterFailureAbandonsTheSet() {
            when(connector.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                    .thenReturn(Outcome.of("msg-1"))
                    .thenReturn(Outcome.failed("rate limited"));

            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B"),
                    single("Length", "How long?", "S", "L")));

            // The group holds only the rows that recorded, so answering the one that went out
            // would complete it and hand the agent an envelope missing a question, which it
            // would read as complete. The set is abandoned whole: the sent question is closed
            // saying so, and the agent is told nothing was asked.
            assertThat(result.status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(savedRows()).allMatch(row -> row.getStatus() == RequestStatus.EXPIRED);
            verify(connector).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    any(), eq(ChatQuestionService.INCOMPLETE_LINE));
        }

        @Test
        @DisplayName("a delivered question that cannot be recorded is not reported as sent")
        void deliveredButUnrecorded() {
            // do-style, because when(mock.save(any())) CALLS the id-assigning answer that setUp
            // installed, with a null argument, and it dies inside the stubbing itself.
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException("dup"))
                    .when(requestRepository).save(any());

            var result = service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B")));

            // The message exists and nothing can resolve a press on it. Calling that SENT would
            // have the agent wait for an answer that has nowhere to arrive.
            assertThat(result.status()).isEqualTo(DeliveryStatus.FAILED);
            // And its buttons are taken away saying why. Left live, a press finds no row and is
            // not even acknowledged: the button spins, then nothing.
            verify(connector).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    any(), eq(AgentAuthorizationChannelService.Verdict.UNRECORDED.line()));
        }

        /** A question left SENT by an earlier call, past its deadline. */
        private ChatAuthorizationRequestEntity staleRow(String groupKey) {
            ChatAuthorizationRequestEntity stale = new ChatAuthorizationRequestEntity();
            stale.setId(UUID.randomUUID());
            stale.setTenantId(TENANT);
            stale.setOrganizationId(ORG);
            stale.setChannel("telegram");
            stale.setCredentialId(9L);
            stale.setChatId("-100123");
            stale.setMessageId("old-msg");
            stale.setMessageText("old body");
            stale.setConversationId("conv-1");
            stale.setGroupKey(groupKey);
            stale.setStatus(RequestStatus.SENT);
            stale.setExpiresAt(Instant.now().minusSeconds(60));
            return stale;
        }
    }

    @Nested
    @DisplayName("answering")
    class Answering {

        @Test
        @DisplayName("an unknown token is not handled, because there is nothing to answer with")
        void unknownToken() {
            when(requestRepository.findByCallbackToken("nope")).thenReturn(Optional.empty());

            var outcome = service.answer("nope", "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // A stale message from a purged workspace, or a guess. With no row there is not even
            // a credential to acknowledge the press with.
            assertThat(outcome.handled()).isFalse();
        }

        @Test
        @DisplayName("regression: a token answered from another provider, bot or chat decides nothing")
        void anAnswerFromElsewhereIsNotOurs() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));

            // The row went out on Telegram, bot credential 9, chat -100123. A copy of its button value
            // replayed through an unsigned endpoint, another bot or another chat must look unknown.
            for (PressOrigin elsewhere : java.util.List.of(new PressOrigin("whatsapp", 9L, "-100123"),
                    new PressOrigin("telegram", 10L, "-100123"), new PressOrigin("telegram", null, "-100999"))) {
                var outcome = service.answer(row.getCallbackToken(), "o0", "user-1", elsewhere);

                assertThat(outcome.handled()).isFalse();
            }
            verify(requestRepository, never()).claim(any(), any());
            assertThat(row.getAnswer()).isNull();
        }

        @Test
        @DisplayName("regression: a typed reply from another provider or chat is not taken as the answer")
        void aReplyFromElsewhereIsNotOurs() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));
            when(requestRepository.findByChatIdAndMessageIdAndStatus("-100123", "msg-1", RequestStatus.SENT))
                    .thenReturn(List.of(row));

            var outcome = service.answerWithText("-100123", "msg-1", "bot-1", "Formal", "user-1",
                    new PressOrigin("whatsapp", null, "-100123"));

            assertThat(outcome.handled()).isFalse();
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("the hand-over happens once, however many answers land together")
        void handsOverExactlyOnce() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));
            when(requestRepository.claim(eq(row.getId()), any())).thenReturn(1);
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc(
                    "conv-1", row.getGroupKey())).thenReturn(List.of(row));
            // The second caller's claim matches nothing: the first already took the group.
            when(requestRepository.claimGroupForApply(anyString(), anyString(), any()))
                    .thenReturn(1).thenReturn(0);

            service.answer(row.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));
            service.applyIfComplete(row);
            service.applyIfComplete(row);

            // Handing over starts the agent's follow-up turn. Twice means two LLM runs on one
            // conversation, two replies, and an agent reading its own answer twice.
            verify(answerApplier, org.mockito.Mockito.times(1)).apply(any());
        }

        @Test
        @DisplayName("a pick on a single select records the answer and closes the message")
        void singlePickSettles() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));
            when(requestRepository.claim(eq(row.getId()), any())).thenReturn(1);

            var outcome = service.answer(row.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.handled()).isTrue();
            assertThat(row.getStatus()).isEqualTo(RequestStatus.RESOLVED);
            assertThat(row.getAnswer()).containsEntry("header", "Tone");
            assertThat(String.valueOf(row.getAnswer().get("selected"))).contains("Formal");
            // Buttons left live invite a second press on a question already settled.
            verify(connector).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), argThat(line -> line.contains("Formal")));
        }

        @Test
        @DisplayName("a second press changes nothing, because the claim already went")
        void doublePressIsHarmless() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));
            when(requestRepository.claim(eq(row.getId()), any())).thenReturn(0);

            var outcome = service.answer(row.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // One statement decides. The second press matches nothing and is told so, which is
            // the same protection the approval buttons already have.
            assertThat(outcome.replyToUser()).contains("already answered");
            assertThat(row.getStatus()).isEqualTo(RequestStatus.SENT);
            verify(answerApplier, never()).apply(any());
        }

        @Test
        @DisplayName("a toggle on a multi select redraws and leaves the question open")
        void toggleDoesNotSettle() {
            ChatAuthorizationRequestEntity row = liveRow(new UserQuestion("Topics", "Which?", List.of(
                    new UserQuestionOption("Pricing", null),
                    new UserQuestionOption("Hiring", null)), true));
            when(connector.updateChoiceMarkup(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), any())).thenReturn(Outcome.of(null));

            var outcome = service.answer(row.getCallbackToken(), "o1", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Claiming here would settle the question at the first tick and leave the person
            // pressing a dead keyboard for the rest of their picks.
            assertThat(row.getStatus()).isEqualTo(RequestStatus.SENT);
            verify(requestRepository, never()).claim(any(), any());
            assertThat(outcome.replyToUser()).isEqualTo("Added.");
            // The draft lives in the row because the next press can land on the other replica.
            assertThat(String.valueOf(row.getAnswer().get("selected"))).contains("Hiring");
        }

        @Test
        @DisplayName("Done submits what was ticked")
        void doneSubmitsTheDraft() {
            ChatAuthorizationRequestEntity row = liveRow(new UserQuestion("Topics", "Which?", List.of(
                    new UserQuestionOption("Pricing", null),
                    new UserQuestionOption("Hiring", null)), true));
            row.setAnswer(new java.util.LinkedHashMap<>(Map.of("selected", List.of("Pricing"))));
            when(requestRepository.claim(eq(row.getId()), any())).thenReturn(1);

            service.answer(row.getCallbackToken(), "done", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(row.getStatus()).isEqualTo(RequestStatus.RESOLVED);
            assertThat(String.valueOf(row.getAnswer().get("selected"))).contains("Pricing");
        }

        @Test
        @DisplayName("Other points at the reply, because a button cannot open a text box")
        void otherAsksForAReply() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));

            var outcome = service.answer(row.getCallbackToken(), "other", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Not an answer yet. Telegram has no way to open a text box from a button, so the
            // only route back is a reply to that message.
            assertThat(outcome.replyToUser()).contains("Reply to that message");
            assertThat(row.getStatus()).isEqualTo(RequestStatus.SENT);
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("a press past the deadline is refused by the row, not by the sweep")
        void pastDeadlineIsRefused() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));
            row.setExpiresAt(Instant.now().minusSeconds(60));

            var outcome = service.answer(row.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // The sweep runs on a timer, so a message can sit in a chat with live buttons past
            // its deadline. A deadline enforced only by a scheduler is one nobody can rely on.
            assertThat(outcome.replyToUser()).contains("expired");
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("somebody outside the chat's allow-list cannot answer")
        void disallowedUserIsRefused() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setAllowedUserIds(List.of("user-9"));
            when(linkRepository.findById(row.getLinkId())).thenReturn(Optional.of(link));

            var outcome = service.answer(row.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.replyToUser()).contains("not one of the people");
            assertThat(outcome.asAlert()).isTrue();
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("a typed reply answers the question it replies to")
        void typedReplySettles() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));
            row.setMessageId("msg-1");
            when(requestRepository.findByChatIdAndMessageIdAndStatus("-100123", "msg-1", RequestStatus.SENT))
                    .thenReturn(List.of(row));
            when(requestRepository.claim(eq(row.getId()), any())).thenReturn(1);

            var outcome = service.answerWithText("-100123", "msg-1", "bot-1", "  something else  ", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.handled()).isTrue();
            assertThat(row.getAnswer()).containsEntry("freeText", "something else")
                    .containsEntry("custom", true);
        }

        @Test
        @DisplayName("a reply to a message that is not ours is ignored in silence")
        void aForeignReplyIsIgnored() {
            when(requestRepository.findByChatIdAndMessageIdAndStatus(anyString(), anyString(), any()))
                    .thenReturn(List.of());

            var outcome = service.answerWithText("-100123", "msg-99", "bot-1", "hello", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // This runs for every reply in a chat that may also carry a workflow trigger.
            assertThat(outcome.handled()).isFalse();
        }

        @Test
        @DisplayName("a typed reply to an APPROVAL message is not an answer to a question")
        void aReplyToAnApprovalIsNotOurs() {
            ChatAuthorizationRequestEntity approval = liveRow(single("Tone", "Which tone?", "A", "B"));
            approval.setKind(RequestKind.APPROVAL);
            when(requestRepository.findByChatIdAndMessageIdAndStatus("-100123", "msg-1", RequestStatus.SENT))
                    .thenReturn(List.of(approval));

            var outcome = service.answerWithText("-100123", "msg-1", "bot-1", "ok go ahead", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Replying to a bot's message is an everyday gesture. Matched as a question, this
            // claimed the permission request, marked it resolved with no verdict at all, and
            // closed it "Answered: ok go ahead": the request was consumed without being decided.
            assertThat(outcome.handled()).isFalse();
            assertThat(service.hasLiveQuestion("-100123", "msg-1", "bot-1")).isFalse();
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("two bots' questions at one message id are told apart by the bot that sent them")
        void twoBotsAreToldApart() {
            ChatAuthorizationRequestEntity mine = liveRow(single("Tone", "Which tone?", "A", "B"));
            ChatAuthorizationRequestEntity theirs = liveRow(single("Tone", "Which tone?", "A", "B"));
            theirs.setLinkId(UUID.randomUUID());
            botFor(mine, "bot-mine");
            botFor(theirs, "bot-theirs");
            when(requestRepository.findByChatIdAndMessageIdAndStatus("-100123", "msg-1", RequestStatus.SENT))
                    .thenReturn(List.of(mine, theirs));
            when(requestRepository.claim(eq(mine.getId()), any())).thenReturn(1);

            service.answerWithText("-100123", "msg-1", "bot-mine", "an answer", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // In a private chat the chat id is the person's own and each bot numbers its messages
            // independently, so two workspaces can hold a live question at the same pair. The
            // reply names the bot whose message it answers.
            verify(requestRepository).claim(eq(mine.getId()), any());
            verify(requestRepository, never()).claim(eq(theirs.getId()), any());
        }

        @Test
        @DisplayName("a single match from another bot is still not ours")
        void aSingleMatchFromAnotherBotIsNotOurs() {
            ChatAuthorizationRequestEntity mine = liveRow(single("Tone", "Which tone?", "A", "B"));
            botFor(mine, "bot-mine");
            when(requestRepository.findByChatIdAndMessageIdAndStatus("-100123", "msg-1", RequestStatus.SENT))
                    .thenReturn(List.of(mine));

            // Another workspace's bot, driving a workflow trigger, sent its own message at the
            // same id in its own private chat with this person. The reply quotes THAT bot.
            var outcome = service.answerWithText("-100123", "msg-1", "bot-theirs", "for the workflow", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Filtering only when several rows matched let this one through: the reply was
            // diverted from their workflow and settled our question with text meant for them.
            assertThat(outcome.handled()).isFalse();
            assertThat(service.hasLiveQuestion("-100123", "msg-1", "bot-theirs")).isFalse();
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("a reply that still matches several questions is ignored rather than guessed")
        void anAmbiguousReplyIsIgnored() {
            ChatAuthorizationRequestEntity first = liveRow(single("Tone", "Which tone?", "A", "B"));
            ChatAuthorizationRequestEntity second = liveRow(single("Tone", "Which tone?", "A", "B"));
            when(requestRepository.findByChatIdAndMessageIdAndStatus("-100123", "msg-1", RequestStatus.SENT))
                    .thenReturn(List.of(first, second));

            var outcome = service.answerWithText("-100123", "msg-1", null, "an answer", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // A guess would put one workspace's answer into another's agent. And this used to be
            // an Optional over a non-unique index, which threw on the webhook thread outside any
            // catch: Telegram answers a 5xx by sending the same update again, forever.
            assertThat(outcome.handled()).isFalse();
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("somebody outside the allow-list cannot answer by typing either")
        void aTypedAnswerRespectsTheAllowList() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "A", "B"));
            when(requestRepository.findByChatIdAndMessageIdAndStatus("-100123", "msg-1", RequestStatus.SENT))
                    .thenReturn(List.of(row));
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setAllowedUserIds(List.of("user-9"));
            when(linkRepository.findById(row.getLinkId())).thenReturn(Optional.of(link));

            var outcome = service.answerWithText("-100123", "msg-1", "bot-1", "an answer", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Pressing is refused for them; replying must be too, or anybody in a group can
            // settle a question by typing what they could not click.
            assertThat(outcome.replyToUser()).contains("not one of the people");
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("a typed answer longer than the answer endpoint accepts is refused while it can be fixed")
        void aTooLongAnswerIsRefused() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "A", "B"));
            when(requestRepository.findByChatIdAndMessageIdAndStatus("-100123", "msg-1", RequestStatus.SENT))
                    .thenReturn(List.of(row));

            var outcome = service.answerWithText("-100123", "msg-1", "bot-1",
                    "x".repeat(com.apimarketplace.agent.tools.ask.UserQuestionValidator.MAX_FREE_TEXT_LENGTH + 1),
                    "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Telegram allows 4096 characters and the endpoint refuses more than 4000. Recorded,
            // it would be shown as answered and then fail the whole hand-over with nobody told.
            assertThat(outcome.replyToUser()).contains("too long");
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("an expired question is not called answered")
        void anExpiredQuestionSaysExpired() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "A", "B"));
            row.setStatus(RequestStatus.EXPIRED);

            var outcome = service.answer(row.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // "Already answered" about a question nobody answered is the one sentence a closed
            // request must never say.
            assertThat(outcome.replyToUser()).contains("expired").doesNotContain("answered");
        }

        @Test
        @DisplayName("an approval's token pressed as a question is not ours")
        void anApprovalTokenIsNotAQuestion() {
            ChatAuthorizationRequestEntity approval = liveRow(single("Tone", "Which tone?", "A", "B"));
            approval.setKind(RequestKind.APPROVAL);

            var outcome = service.answer(approval.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.handled()).isFalse();
            verify(requestRepository, never()).claim(any(), any());
        }

        @Test
        @DisplayName("an answer whose record fails hands the claim back instead of leaving a hole")
        void aFailedRecordReleasesTheClaim() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "A", "B"));
            when(requestRepository.claim(eq(row.getId()), any())).thenReturn(1);
            org.mockito.Mockito.doThrow(new IllegalStateException("db"))
                    .when(requestRepository).save(any());

            var outcome = service.answer(row.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Left claimed, the row reads RESOLVED with no answer, which the group check counted
            // as answered: the agent would have received the set with a question missing.
            verify(requestRepository).releaseClaim(row.getId());
            assertThat(outcome.settled()).isFalse();
            assertThat(outcome.replyToUser()).contains("try again");
        }

        @Test
        @DisplayName("a settled question with no recorded answer keeps the set from being handed over")
        void aHoleInTheSetBlocksTheHandOver() {
            ChatAuthorizationRequestEntity answered = liveRow(single("Tone", "Which tone?", "A", "B"));
            ChatAuthorizationRequestEntity hole = liveRow(single("Length", "How long?", "S", "L"));
            hole.setGroupKey(answered.getGroupKey());
            answered.setStatus(RequestStatus.RESOLVED);
            answered.setAnswer(new java.util.LinkedHashMap<>(Map.of("header", "Tone")));
            hole.setStatus(RequestStatus.RESOLVED);
            hole.setAnswer(null);
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc(
                    "conv-1", answered.getGroupKey())).thenReturn(List.of(answered, hole));

            service.applyIfComplete(answered);

            verify(requestRepository, never()).claimGroupForApply(any(), any(), any());
            verify(answerApplier, never()).apply(any());
        }

        @Test
        @DisplayName("a failed record leaves the row reading unanswered, so the person is told")
        void aFailedRecordLeavesTheRowUnanswered() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "A", "B"));
            when(requestRepository.claim(eq(row.getId()), any())).thenReturn(1);
            org.mockito.Mockito.doThrow(new IllegalStateException("db"))
                    .when(requestRepository).save(any());

            var outcome = service.answer(row.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // The caller decides what to tell the person from this row. Left RESOLVED in memory,
            // it read as an answer that went through, and the typed-reply path swallowed "your
            // answer could not be saved" for exactly the person who had to try again.
            assertThat(row.getStatus()).isEqualTo(RequestStatus.SENT);
            assertThat(row.getAnswer()).isNull();
        }

        @Test
        @DisplayName("a hand-over that does not reach the conversation is handed back for the retry")
        void aFailedHandOverIsHandedBack() {
            ChatAuthorizationRequestEntity row = answeredRow();
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc("conv-1", row.getGroupKey()))
                    .thenReturn(List.of(row));
            when(requestRepository.claimGroupForApply(anyString(), anyString(), any())).thenReturn(1);
            when(answerApplier.apply(any())).thenReturn(false);

            service.applyIfComplete(row);

            // Kept claimed, a short outage of conversation-service at the moment the last answer
            // landed lost the set for good while the person read "Answered".
            verify(requestRepository).releaseGroupApply("conv-1", row.getGroupKey());
        }

        @Test
        @DisplayName("a hand-over that throws is handed back too")
        void aThrowingHandOverIsHandedBack() {
            ChatAuthorizationRequestEntity row = answeredRow();
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc("conv-1", row.getGroupKey()))
                    .thenReturn(List.of(row));
            when(requestRepository.claimGroupForApply(anyString(), anyString(), any())).thenReturn(1);
            when(answerApplier.apply(any())).thenThrow(new IllegalStateException("conversation-service down"));

            service.applyIfComplete(row);

            verify(requestRepository).releaseGroupApply("conv-1", row.getGroupKey());
        }

        @Test
        @DisplayName("a hand-over that went through keeps its claim")
        void aSuccessfulHandOverIsKept() {
            ChatAuthorizationRequestEntity row = answeredRow();
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc("conv-1", row.getGroupKey()))
                    .thenReturn(List.of(row));
            when(requestRepository.claimGroupForApply(anyString(), anyString(), any())).thenReturn(1);
            when(answerApplier.apply(any())).thenReturn(true);

            service.applyIfComplete(row);

            // Released, it would be handed over again by the next retry pass: a second follow-up
            // turn on a conversation that already has its answer.
            verify(requestRepository, never()).releaseGroupApply(anyString(), anyString());
        }

        @Test
        @DisplayName("the retry pass hands each set over once, however many of its rows it reads")
        void theRetryPassTakesEachSetOnce() {
            ChatAuthorizationRequestEntity first = answeredRow();
            ChatAuthorizationRequestEntity second = answeredRow();
            second.setGroupKey(first.getGroupKey());
            when(requestRepository.findUnappliedAnswers(any(), any(), any())).thenReturn(List.of(first, second));
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc("conv-1", first.getGroupKey()))
                    .thenReturn(List.of(first, second));
            when(requestRepository.claimGroupForApply(anyString(), anyString(), any())).thenReturn(2);
            when(answerApplier.apply(any())).thenReturn(true);

            service.retryUnappliedAnswers();

            verify(answerApplier, times(1)).apply(any());
        }

        @Test
        @DisplayName("the retry pass surviving a failed scan changes nothing")
        void theRetryPassSurvivesAFailedScan() {
            when(requestRepository.findUnappliedAnswers(any(), any(), any()))
                    .thenThrow(new IllegalStateException("db down"));

            service.retryUnappliedAnswers();

            verify(answerApplier, never()).apply(any());
        }

        /** A question settled and recorded, not yet handed over. */
        @Test
        @DisplayName("analytics: a delivered set and a completed hand-over are reported once, typed answer as text")
        void reportsDeliveryAndCompletedSet() {
            com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics = mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
            service.deliverQuestions(request(single("Tone", "Which tone?", "A", "B")));

            ChatAuthorizationRequestEntity row = answeredRow();
            row.getAnswer().put("custom", true);
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc("conv-1", row.getGroupKey()))
                    .thenReturn(List.of(row));
            when(requestRepository.claimGroupForApply(anyString(), anyString(), any())).thenReturn(1).thenReturn(0);
            when(answerApplier.apply(any())).thenReturn(true);
            service.applyIfComplete(row);
            service.applyIfComplete(row);

            verify(analytics).channelRequestDelivered(TENANT, ORG, com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.ASK_USER, "telegram",
                    com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestStatus.SENT);
            verify(analytics, times(1)).channelRequestAnswered(TENANT, ORG, com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.ASK_USER,
                    "telegram", com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Decision.ANSWERED, com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Input.TEXT);
        }

        @Test
        @DisplayName("analytics: a hand-over that fails is not reported (its retry will be)")
        void failedHandOverNotReported() {
            com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics = mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
            ChatAuthorizationRequestEntity row = answeredRow();
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc("conv-1", row.getGroupKey()))
                    .thenReturn(List.of(row));
            when(requestRepository.claimGroupForApply(anyString(), anyString(), any())).thenReturn(1);
            when(answerApplier.apply(any())).thenReturn(false);

            service.applyIfComplete(row);

            verify(analytics, never()).channelRequestAnswered(any(), any(), any(), any(), any(), any());
            assertThat(ChatQuestionService.inputOf(row)).isEqualTo(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Input.BUTTON);
        }

        private ChatAuthorizationRequestEntity answeredRow() {
            ChatAuthorizationRequestEntity row = liveRow(single("Tone", "Which tone?", "A", "B"));
            row.setStatus(RequestStatus.RESOLVED);
            row.setAnswer(new java.util.LinkedHashMap<>(Map.of("header", "Tone")));
            return row;
        }

        @Test
        @DisplayName("the closing line is capped, so a long typed answer cannot push the verdict out")
        void theAnsweredLineIsCapped() {
            String line = ChatQuestionService.answeredLine("y".repeat(3000));

            // The connector truncates the closed message from the END, so an uncapped line lost
            // the very words that say the question was answered.
            assertThat(line).startsWith("Answered: ").hasSizeLessThanOrEqualTo(
                    ChatQuestionService.ANSWERED_LINE_MAX_CHARS).endsWith("...");
        }

        @Test
        @DisplayName("the answers are handed over only once every question of the call has one")
        void appliesOnlyWhenTheCallIsComplete() {
            ChatAuthorizationRequestEntity first = liveRow(single("Tone", "Which tone?", "Formal", "Playful"));
            ChatAuthorizationRequestEntity second = liveRow(single("Length", "How long?", "Short", "Long"));
            // Both rows belong to one delivery, so they share its minted group key.
            second.setGroupKey(first.getGroupKey());
            when(requestRepository.claim(any(), any())).thenReturn(1);
            when(requestRepository.claimGroupForApply(eq("conv-1"), eq(first.getGroupKey()), any()))
                    .thenReturn(2);
            when(requestRepository.findByConversationIdAndGroupKeyOrderByCreatedAtAsc(
                    "conv-1", first.getGroupKey())).thenReturn(List.of(first, second));

            var firstOutcome = service.answer(first.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));
            service.applyIfComplete(firstOutcome.request());

            // One call owes the agent ONE envelope. Half an answer to a set of questions is not
            // an answer, so nothing moves until the last one lands.
            verify(answerApplier, never()).apply(any());

            var secondOutcome = service.answer(second.getCallbackToken(), "o0", "user-1", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));
            service.applyIfComplete(secondOutcome.request());
            verify(answerApplier).apply(any());
        }

        /** Makes the row's link point at a bot with this provider identity. */
        private void botFor(ChatAuthorizationRequestEntity row, String botIdentity) {
            UUID botId = UUID.randomUUID();
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setBotId(botId);
            com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity bot =
                    new com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity();
            bot.setBotIdentity(botIdentity);
            when(linkRepository.findById(row.getLinkId())).thenReturn(Optional.of(link));
            when(botRepository.findById(botId)).thenReturn(Optional.of(bot));
        }

        /** A question already sent and waiting, with its token resolvable. */
        private ChatAuthorizationRequestEntity liveRow(UserQuestion question) {
            ChatAuthorizationRequestEntity row = new ChatAuthorizationRequestEntity();
            row.setId(UUID.randomUUID());
            row.setTenantId(TENANT);
            row.setOrganizationId(ORG);
            row.setLinkId(LINK_ID);
            row.setChannel("telegram");
            row.setCredentialId(9L);
            row.setChatId("-100123");
            row.setCallbackToken("tok-" + UUID.randomUUID());
            row.setConversationId("conv-1");
            row.setGateKey("call-1:ask");
            row.setGroupKey("call-1");
            row.setRule("ask_user");
            row.setKind(RequestKind.CHOICE);
            row.setPayload(question.toMap());
            row.setMessageText("body");
            row.setMessageId("msg-1");
            row.setStatus(RequestStatus.SENT);
            row.setExpiresAt(Instant.now().plusSeconds(3600));
            when(requestRepository.findByCallbackToken(row.getCallbackToken()))
                    .thenReturn(Optional.of(row));
            return row;
        }
    }

    // ---- helpers ----

    private QuestionRequest request(UserQuestion... questions) {
        return new QuestionRequest(TENANT, ORG, "conv-1", "call-1", "call-1:ask", null,
                List.of(questions));
    }

    private static UserQuestion single(String header, String text, String a, String b) {
        return new UserQuestion(header, text, List.of(
                new UserQuestionOption(a, null), new UserQuestionOption(b, null)), false);
    }

    /** The labels of the first keyboard sent, in order. */
    private List<String> labels() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChoiceOption>> options = ArgumentCaptor.forClass(List.class);
        verify(connector).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(),
                options.capture());
        return options.getValue().stream().map(ChoiceOption::label).toList();
    }

    private List<ChatAuthorizationRequestEntity> savedRows() {
        ArgumentCaptor<ChatAuthorizationRequestEntity> rows =
                ArgumentCaptor.forClass(ChatAuthorizationRequestEntity.class);
        verify(requestRepository, org.mockito.Mockito.atLeastOnce()).save(rows.capture());
        return rows.getAllValues();
    }
}
