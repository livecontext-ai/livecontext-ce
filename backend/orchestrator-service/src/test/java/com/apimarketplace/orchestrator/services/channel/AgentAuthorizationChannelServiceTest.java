package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.Decision;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.AnswerOutcome;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.DeliveryRequest;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.DeliveryResult;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.DeliveryStatus;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentAuthorizationChannelService}.
 *
 * <p>Two properties carry the feature. A question must never be sent twice while
 * the first one is still waiting, or a person approving one of four identical
 * messages cannot know what they are approving. And a decision that could not be
 * APPLIED must never be shown as decided, or the run stays blocked behind a
 * message that says it was allowed.
 */
class AgentAuthorizationChannelServiceTest {

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
        // The interface default, not the 0 a bare mock answers: the body is capped to this
        // before it is sent, so an unstubbed 0 would send an empty message everywhere.
        when(connector.maxDecisionTextChars()).thenCallRealMethod();
        when(channelService.resolveFor(ORG, null)).thenReturn(Optional.of(
                new ResolvedTarget(LINK_ID, "telegram", 9L, "-100123", List.of())));
        when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        // Claimed by default: the contested case has its own test.
        when(requestRepository.claim(any(), any())).thenReturn(1);
        org.mockito.Mockito.lenient().when(connector.closeDecisionRequest(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(Outcome.of(null));
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

    private static DeliveryRequest request() {
        return new DeliveryRequest(TENANT, ORG, "conv-1", "call-1", "workflow:execute",
                UUID.randomUUID().toString(), "Night Publisher", "workflow_id=abc", "workflow|workflow_id=abc");
    }

    private void sendSucceeds() {
        when(connector.sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(Outcome.of("555"));
    }

    private static ChatAuthorizationRequestEntity liveRow() {
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
        row.setRule("workflow:execute");
        row.setFingerprint("fp");
        row.setMessageId("555");
        row.setStatus(RequestStatus.SENT);
        row.setCreatedAt(Instant.parse("2026-09-16T22:00:00Z"));
        // Relative, not a fixed date: a request is answerable only while its deadline holds,
        // so a hardcoded one turns every test in this class into an expired-request test on
        // the day it passes. What each case here is about is the decision, not the clock.
        row.setExpiresAt(Instant.now().plus(java.time.Duration.ofHours(24)));
        return row;
    }

    @Nested
    @DisplayName("deliver() to the agent's own destination")
    class AgentDestination {

        private final UUID agentId = UUID.randomUUID();
        private final UUID chosen = UUID.randomUUID();
        private AgentAuthorizationChannelService withAgents;

        @BeforeEach
        @SuppressWarnings("unchecked")
        void agentAware() {
            AgentClient agentClient = mock(AgentClient.class);
            com.apimarketplace.agent.client.dto.AgentDto agent = new com.apimarketplace.agent.client.dto.AgentDto();
            agent.setName("Finance");
            agent.setChatChannelLinkId(chosen);
            when(agentClient.getAgent(agentId, TENANT, ORG)).thenReturn(agent);
            ObjectProvider<AgentClient> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(agentClient);
            withAgents = new AgentAuthorizationChannelService(channelService,
                    new ChatChannelConnectorRegistry(List.of(connector)), requestRepository, linkRepository,
                    applier, provider, 24);
        }

        private DeliveryRequest fromAgent() {
            // No name carried: it comes from the same lookup as the destination.
            return new DeliveryRequest(TENANT, ORG, "conv-1", "call-1", "workflow:execute",
                    agentId.toString(), null, "workflow_id=abc", "workflow|workflow_id=abc");
        }

        @Test
        @DisplayName("a request goes to the destination the agent chose, named after the agent")
        void chosenDestination() {
            sendSucceeds();
            when(channelService.resolveFor(ORG, chosen)).thenReturn(Optional.of(
                    new ResolvedTarget(chosen, "telegram", 11L, "-200999", List.of())));

            var result = withAgents.deliver(fromAgent());

            assertThat(result.status()).isEqualTo(DeliveryStatus.SENT);
            org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(connector).sendDecisionRequest(eq(TENANT), eq(11L), eq("-200999"), text.capture(),
                    anyString(), anyString());
            assertThat(text.getValue()).contains("Finance");
        }

        @Test
        @DisplayName("an agent whose channel is switched off reaches nobody (NO_CHANNEL)")
        void switchedOffReachesNobody() {
            AgentClient client = mock(AgentClient.class);
            com.apimarketplace.agent.client.dto.AgentDto off = new com.apimarketplace.agent.client.dto.AgentDto();
            off.setChatChannelEnabled(false);
            off.setChatChannelLinkId(chosen);
            when(client.getAgent(agentId, TENANT, ORG)).thenReturn(off);
            @SuppressWarnings("unchecked")
            ObjectProvider<AgentClient> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(client);
            AgentAuthorizationChannelService service = new AgentAuthorizationChannelService(channelService,
                    new ChatChannelConnectorRegistry(List.of(connector)), requestRepository, linkRepository,
                    applier, provider, 24);

            assertThat(service.deliver(fromAgent()).status()).isEqualTo(DeliveryStatus.NO_CHANNEL);
            verify(channelService, never()).resolveFor(any(), any());
        }

        @Test
        @DisplayName("a failed agent lookup sends nothing rather than guessing the default")
        void lookupFailureSendsNothing() {
            AgentClient failing = mock(AgentClient.class);
            when(failing.getAgent(agentId, TENANT, ORG)).thenThrow(new RuntimeException("agent-service down"));
            @SuppressWarnings("unchecked")
            ObjectProvider<AgentClient> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(failing);
            AgentAuthorizationChannelService service = new AgentAuthorizationChannelService(channelService,
                    new ChatChannelConnectorRegistry(List.of(connector)), requestRepository, linkRepository,
                    applier, provider, 24);

            var result = service.deliver(fromAgent());

            assertThat(result.status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(result.error()).contains("nothing was sent");
            verify(connector, never()).sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString());
            verify(channelService, never()).resolveFor(any(), any());
        }

        @Test
        @DisplayName("a chosen destination that is gone is not replaced by the workspace default")
        void goneIsNotReplaced() {
            when(channelService.resolveFor(ORG, chosen)).thenReturn(Optional.empty());

            var result = withAgents.deliver(fromAgent());

            // Falling back would put a finance approval in front of whoever reads the default chat.
            assertThat(result.status()).isEqualTo(DeliveryStatus.NO_CHANNEL);
            verify(connector, never()).sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString());
            verify(channelService, never()).resolveFor(ORG, null);
        }
    }

    @Nested
    @DisplayName("deliver()")
    class Deliver {

        @Test
        @DisplayName("sends nothing when the workspace has connected no destination")
        void noChannel() {
            when(channelService.resolveFor(ORG, null)).thenReturn(Optional.empty());

            assertThat(service.deliver(request()).status()).isEqualTo(DeliveryStatus.NO_CHANNEL);
            verify(connector, never()).sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("sends nothing when the same question is already waiting there")
        void alreadyPending() {
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(
                    eq("conv-1"), anyString(), eq(RequestStatus.SENT)))
                    .thenReturn(Optional.of(liveRow()));

            DeliveryResult result = service.deliver(request());

            assertThat(result.status()).isEqualTo(DeliveryStatus.ALREADY_PENDING);
            assertThat(result.requestedAt()).isEqualTo(Instant.parse("2026-09-16T22:00:00Z"));
            verify(connector, never()).sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString());
            verify(requestRepository, never()).save(any());
        }

        @Test
        @DisplayName("keeps no row when the message could not be delivered")
        void sendFailed() {
            when(connector.sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString())).thenReturn(Outcome.failed("chat not found"));

            DeliveryResult result = service.deliver(request());

            assertThat(result.status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(result.error()).isEqualTo("chat not found");
            // A row would claim a live question exists and block the next ask behind it.
            verify(requestRepository, never()).save(any());
        }

        @Test
        @DisplayName("names the agent and the action in the message, and puts two buttons on it")
        void sendsAReadableMessage() {
            sendSucceeds();

            service.deliver(request());

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> approve = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> reject = ArgumentCaptor.forClass(String.class);
            verify(connector).sendDecisionRequest(eq(TENANT), eq(9L), eq("-100123"), text.capture(),
                    approve.capture(), reject.capture());
            // Read on a phone, with none of the context the app would have given.
            assertThat(text.getValue()).contains("Night Publisher")
                    .contains("workflow:execute")
                    .contains("workflow_id=abc")
                    .contains("Nothing has run yet");
            assertThat(approve.getValue()).startsWith("lcaut:").endsWith(":a");
            assertThat(reject.getValue()).startsWith("lcaut:").endsWith(":r");
            assertThat(approve.getValue().length()).isLessThanOrEqualTo(64);
        }

        @Test
        @DisplayName("persists what the answer will need, and an expiry")
        void persistsTheRequest() {
            sendSucceeds();

            service.deliver(request());

            ArgumentCaptor<ChatAuthorizationRequestEntity> saved =
                    ArgumentCaptor.forClass(ChatAuthorizationRequestEntity.class);
            verify(requestRepository).save(saved.capture());
            ChatAuthorizationRequestEntity row = saved.getValue();
            assertThat(row.getConversationId()).isEqualTo("conv-1");
            assertThat(row.getGateKey()).isEqualTo("call-1");
            assertThat(row.getRule()).isEqualTo("workflow:execute");
            assertThat(row.getMessageId()).isEqualTo("555");
            assertThat(row.getStatus()).isEqualTo(RequestStatus.SENT);
            // Without an expiry the duplicate rule would block this agent forever
            // after its first unanswered question.
            assertThat(row.getExpiresAt()).isAfter(Instant.now());
        }

        @Test
        @DisplayName("closes the duplicate it just sent when another replica won the race")
        void closesTheDuplicateOnRace() {
            sendSucceeds();
            // doThrow, not when(save(any())): the latter CALLS the already-stubbed mock
            // with a null argument while registering the matcher.
            // The REAL index name: "already asked" is recognised by the constraint that
            // was violated, so a fixture with a made-up message would test nothing.
            org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                    "ERROR: duplicate key value violates unique constraint "
                            + "\"uq_chat_auth_requests_live\""))
                    .when(requestRepository).save(any());
            when(requestRepository.findByConversationIdAndFingerprintAndStatus(
                    eq("conv-1"), anyString(), eq(RequestStatus.SENT)))
                    .thenReturn(Optional.empty(), Optional.of(liveRow()));

            DeliveryResult result = service.deliver(request());

            assertThat(result.status()).isEqualTo(DeliveryStatus.ALREADY_PENDING);
            // Two identical pairs of buttons is exactly what the index exists to stop;
            // losing the race must not leave one of them live.
            verify(connector).closeDecisionRequest(eq(TENANT), eq(9L), eq("-100123"), eq("555"),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("refuses to address a request with no workspace or no parked call")
        void refusesIncompleteRequests() {
            assertThat(service.deliver(new DeliveryRequest(TENANT, null, "conv-1", "call-1",
                    "workflow:execute", null, null, null, null)).status()).isEqualTo(DeliveryStatus.NO_CHANNEL);
            assertThat(service.deliver(new DeliveryRequest(TENANT, ORG, "conv-1", "  ",
                    "workflow:execute", null, null, null, null)).status()).isEqualTo(DeliveryStatus.NO_CHANNEL);
        }
    }

    @Nested
    @DisplayName("answer()")
    class Answer {

        @Test
        @DisplayName("ignores a token nothing matches")
        void unknownToken() {
            when(requestRepository.findByCallbackToken("nope")).thenReturn(Optional.empty());

            AnswerOutcome outcome = service.answer("nope", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.handled()).isFalse();
            verify(applier, never()).apply(any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("regression: a token pressed from another provider, bot or chat decides nothing")
        void aPressFromElsewhereIsNotOurs() {
            ChatAuthorizationRequestEntity row = liveRow();
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));

            // Sent on Telegram with bot credential 9 to chat -100123. Replayed anywhere else, with an
            // allowed person's id in an unsigned body, it would otherwise authorize the agent.
            for (PressOrigin elsewhere : java.util.List.of(new PressOrigin("whatsapp", 9L, "-100123"),
                    new PressOrigin("telegram", 10L, "-100123"), new PressOrigin("telegram", null, "-100999"))) {
                assertThat(service.answer("tok", true, "5", elsewhere).handled()).isFalse();
            }
            verify(requestRepository, never()).claim(any(), any());
            verify(applier, never()).apply(any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("says it was already decided rather than deciding twice")
        void alreadyDecided() {
            ChatAuthorizationRequestEntity row = liveRow();
            row.setStatus(RequestStatus.RESOLVED);
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));

            AnswerOutcome outcome = service.answer("tok", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.replyToUser()).contains("already decided");
            verify(applier, never()).apply(any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("a question's token pressed as an approval is not ours")
        void aQuestionTokenIsNotAnApproval() {
            ChatAuthorizationRequestEntity question = liveRow();
            question.setKind(ChatAuthorizationRequestEntity.RequestKind.CHOICE);
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(question));

            AnswerOutcome outcome = service.answer("tok", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Nothing in Telegram produces it, since each button carries its own family's prefix.
            // Resolving it would apply a verdict to a row that asked for a value and post an
            // approval against a question's gate key, so it is treated as an unknown token.
            assertThat(outcome.handled()).isFalse();
            verify(requestRepository, never()).claim(any(), any());
            verify(applier, never()).apply(any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("refuses a presser outside the destination's allow-list")
        void refusesDisallowedUser() {
            ChatAuthorizationRequestEntity row = liveRow();
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setAllowedUserIds(List.of("777"));
            when(linkRepository.findById(LINK_ID)).thenReturn(Optional.of(link));

            AnswerOutcome outcome = service.answer("tok", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.asAlert()).isTrue();
            assertThat(outcome.replyToUser()).contains("not allowed");
            verify(applier, never()).apply(any(), anyBoolean(), anyString());
        }

        @Test
        @DisplayName("lets anyone in the chat decide when no allow-list is set")
        void allowsAnyoneWithoutAList() {
            ChatAuthorizationRequestEntity row = liveRow();
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
            when(linkRepository.findById(LINK_ID)).thenReturn(Optional.empty());
            when(applier.apply(any(), anyBoolean(), any())).thenReturn(true);

            assertThat(service.answer("tok", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram")).replyToUser()).contains("Approved");
        }

        @Test
        @DisplayName("does not mark a request decided when the decision could not be applied")
        void unappliedDecisionIsNotRecorded() {
            ChatAuthorizationRequestEntity row = liveRow();
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
            when(linkRepository.findById(LINK_ID)).thenReturn(Optional.empty());
            when(applier.apply(any(), anyBoolean(), any())).thenReturn(false);
            // One row updated: the hand-back landed. Stubbed rather than left at a bare mock's
            // zero, because zero now MEANS something (the conditional UPDATE matched nothing,
            // so the row is still RESOLVED with no decision and has to be retired instead).
            // This test is about the hand-back succeeding; its sibling covers the zero.
            when(requestRepository.releaseClaim(row.getId())).thenReturn(1);

            AnswerOutcome outcome = service.answer("tok", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            // Showing "Approved" here would tell the person the run may proceed while
            // nothing was authorized at all.
            assertThat(outcome.replyToUser()).contains("Could not apply");
            // The claim is handed back, so the question is answerable again and the
            // duplicate rule does not lock the agent out for a day over a failure.
            verify(requestRepository).releaseClaim(row.getId());
            assertThat(row.getStatus()).isEqualTo(RequestStatus.SENT);
            verify(requestRepository, never()).save(any());
            verify(connector, never()).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                    anyString(), anyString());
        }

        @Test
        @DisplayName("a second press changes nothing, because the first one claimed it")
        void secondPressIsRefused() {
            ChatAuthorizationRequestEntity row = liveRow();
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
            when(linkRepository.findById(LINK_ID)).thenReturn(Optional.empty());
            // Same shape as a double tap, two members of a group, or a provider retry:
            // the row still reads SENT here, and only the UPDATE can tell them apart.
            when(requestRepository.claim(any(), any())).thenReturn(0);

            AnswerOutcome outcome = service.answer("tok", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.replyToUser()).contains("already decided");
            // The second press must not answer the conversation again: with the call no
            // longer parked, that write is a standing grant nobody asked for.
            verify(applier, never()).apply(any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("records the verdict and takes the buttons away once applied")
        void appliedDecisionIsRecorded() {
            ChatAuthorizationRequestEntity row = liveRow();
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
            when(linkRepository.findById(LINK_ID)).thenReturn(Optional.empty());
            when(applier.apply(any(), anyBoolean(), any())).thenReturn(true);

            AnswerOutcome outcome = service.answer("tok", false, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

            assertThat(outcome.replyToUser()).contains("Refused");
            assertThat(row.getStatus()).isEqualTo(RequestStatus.RESOLVED);
            assertThat(row.getDecision()).isEqualTo(Decision.REJECTED);
            assertThat(row.getDecidedBy()).isEqualTo("telegram:5");
            verify(connector).closeDecisionRequest(eq(TENANT), eq(9L), eq("-100123"), eq("555"),
                    anyString(), eq("❌ Refused"));
        }
    }

    @Test
    @DisplayName("an approval survives a connector that cannot close its message")
    void closeFailureDoesNotUndoTheDecision() {
        ChatAuthorizationRequestEntity row = liveRow();
        when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
        when(linkRepository.findById(LINK_ID)).thenReturn(Optional.empty());
        when(applier.apply(any(), anyBoolean(), any())).thenReturn(true);
        when(connector.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenThrow(new IllegalStateException("telegram down"));

        AnswerOutcome outcome = service.answer("tok", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

        // The edit runs AFTER the decision was applied. Letting it escape would report
        // a failure for an approval that really happened, on a settled question.
        assertThat(outcome.replyToUser()).contains("Approved");
        assertThat(row.getStatus()).isEqualTo(RequestStatus.RESOLVED);
    }

    @Test
    @DisplayName("stores the same identity it looks a duplicate up by")
    void storesTheFingerprintItSearchesBy() {
        sendSucceeds();
        String expected = AgentAuthorizationChannelService.fingerprintOf(
                "workflow:execute", "workflow|workflow_id=abc");

        service.deliver(request());

        // Store one identity and search by another and the whole anti-pile-up rule is
        // inert, with every test that stubs the lookup loosely still passing.
        verify(requestRepository).findByConversationIdAndFingerprintAndStatus(
                eq("conv-1"), eq(expected), eq(RequestStatus.SENT));
        ArgumentCaptor<ChatAuthorizationRequestEntity> saved =
                ArgumentCaptor.forClass(ChatAuthorizationRequestEntity.class);
        verify(requestRepository).save(saved.capture());
        assertThat(saved.getValue().getFingerprint()).isEqualTo(expected);
    }

    @Test
    @DisplayName("an integrity failure that is NOT the duplicate index is reported as a failure")
    void nonDuplicateIntegrityFailureIsNotCalledSuperseded() {
        sendSucceeds();
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                "null value in column \"rule\" violates not-null constraint"))
                .when(requestRepository).save(any());

        DeliveryResult result = service.deliver(request());

        // Telling the person their real question was "superseded by an identical request"
        // would be a sentence that is simply not true.
        assertThat(result.status()).isEqualTo(DeliveryStatus.FAILED);
        ArgumentCaptor<String> verdict = ArgumentCaptor.forClass(String.class);
        verify(connector).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), verdict.capture());
        assertThat(verdict.getValue()).contains("could not be recorded");
        assertThat(verdict.getValue()).doesNotContain("Superseded");
    }

    @Test
    @DisplayName("retires a request it cannot hand back, instead of stranding it")
    void retiresARequestItCannotHandBack() {
        ChatAuthorizationRequestEntity row = liveRow();
        when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
        when(linkRepository.findById(LINK_ID)).thenReturn(Optional.empty());
        when(applier.apply(any(), anyBoolean(), any())).thenReturn(false);
        // The agent's next run asked the same question again and took the live slot,
        // which the partial unique index allows exactly one of.
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException("uq_chat_auth_requests_live"))
                .when(requestRepository).releaseClaim(row.getId());

        AnswerOutcome outcome = service.answer("tok", true, "5", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

        assertThat(outcome.replyToUser()).contains("Could not apply");
        // Terminal, so it blocks nobody and no longer looks answerable. Left RESOLVED with
        // no decision it would be invisible to the expiry sweep and pending for ever.
        assertThat(row.getStatus()).isEqualTo(RequestStatus.EXPIRED);
    }

    @Nested
    @DisplayName("fingerprint")
    class Fingerprint {

        @Test
        @DisplayName("is the same for the same ask, so a repeat is recognised")
        void stableForTheSameAsk() {
            assertThat(AgentAuthorizationChannelService.fingerprintOf("workflow:execute", "id=abc"))
                    .isEqualTo(AgentAuthorizationChannelService.fingerprintOf("workflow:execute", "id=abc"));
        }

        @Test
        @DisplayName("differs when the arguments differ, so two real asks both get through")
        void differsPerArguments() {
            assertThat(AgentAuthorizationChannelService.fingerprintOf("workflow:execute", "id=abc"))
                    .isNotEqualTo(AgentAuthorizationChannelService.fingerprintOf("workflow:execute", "id=xyz"));
        }

        @Test
        @DisplayName("fits the column whatever the arguments looked like")
        void isBounded() {
            String huge = "x".repeat(50_000);
            assertThat(AgentAuthorizationChannelService.fingerprintOf("catalog:execute", huge))
                    .hasSize(32);
        }
    }

    @Nested
    @DisplayName("analytics: channel_request_delivered / channel_request_answered (agent_permission)")
    class Analytics {

        private com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics;

        @BeforeEach
        void wire() {
            analytics = mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        }

        @Test
        @DisplayName("every delivery outcome is reported under the requester, with its status")
        void deliveryOutcomes() {
            sendSucceeds();
            service.deliver(request());
            when(channelService.resolveFor(ORG, null)).thenReturn(Optional.empty());
            service.deliver(request());

            verify(analytics).channelRequestDelivered(TENANT, ORG,
                    com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.AGENT_PERMISSION,
                    "telegram",
                    com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestStatus.SENT);
            verify(analytics).channelRequestDelivered(TENANT, ORG,
                    com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.AGENT_PERMISSION,
                    null,
                    com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestStatus.NO_CHANNEL);
        }

        @Test
        @DisplayName("an applied button press is reported as approved; a refused press is not reported")
        void answerReported() {
            ChatAuthorizationRequestEntity row = liveRow();
            when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(row));
            when(linkRepository.findById(LINK_ID)).thenReturn(Optional.empty());
            when(applier.apply(any(), anyBoolean(), any())).thenReturn(true);

            service.answer("tok", true, "5", PressOrigin.of("telegram"));
            service.answer("tok", true, "5", PressOrigin.of("telegram")); // already decided now

            verify(analytics, org.mockito.Mockito.times(1)).channelRequestAnswered(TENANT, ORG,
                    com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.AGENT_PERMISSION,
                    "telegram",
                    com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Decision.APPROVED,
                    com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Input.BUTTON);
        }
    }
}
