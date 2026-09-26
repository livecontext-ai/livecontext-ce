package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity.DeliveryStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChoiceOption;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConnectorApprovalNotifier")
class ConnectorApprovalNotifierTest {

    private static final String ORG = "org-1";

    @BeforeAll
    static void installTokenAtRest() {
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
    }

    private ApprovalChannelDeliveryRepository deliveries;
    private SignalWaitRepository signals;
    private ChatChannelBotRepository bots;
    private ChatChannelLinkRepository links;
    private ChatChannelConnector slack;
    private ChatChannelConnector whatsapp;
    private ConnectorApprovalNotifier notifier;
    private com.apimarketplace.orchestrator.services.channel.ChatChannelService channelService;
    private ApprovalChannelDeliveryEntity delivery;

    @BeforeEach
    void setUp() {
        deliveries = mock(ApprovalChannelDeliveryRepository.class);
        signals = mock(SignalWaitRepository.class);
        bots = mock(ChatChannelBotRepository.class);
        links = mock(ChatChannelLinkRepository.class);
        slack = mock(ChatChannelConnector.class);
        when(slack.channelId()).thenReturn("slack");
        when(slack.maxDecisionTextChars()).thenReturn(2872);
        when(slack.normalizeChatId(any())).thenCallRealMethod();
        whatsapp = mock(com.apimarketplace.orchestrator.services.channel.whatsapp.WhatsAppChannelConnector.class);
        when(whatsapp.channelId()).thenReturn("whatsapp");
        when(whatsapp.normalizeChatId(any())).thenCallRealMethod();
        channelService = mock(com.apimarketplace.orchestrator.services.channel.ChatChannelService.class);
        notifier = new ConnectorApprovalNotifier(deliveries, signals, bots, links,
                new com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry(List.of(slack, whatsapp)),
                new ObjectMapper(), new SimpleMeterRegistry(), channelService);

        delivery = new ApprovalChannelDeliveryEntity();
        delivery.setId(1L);
        delivery.setChannel("slack");
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setTenantId("tenant");
        when(deliveries.insertPendingIfAbsent(any(), anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        when(deliveries.findByCallbackTokenHash(anyString())).thenReturn(Optional.of(delivery));
        when(signals.findById(any())).thenReturn(Optional.empty());
    }

    private static SignalWaitEntity signal() {
        SignalWaitEntity signal = new SignalWaitEntity();
        signal.setId(55L);
        signal.setRunId("run-1");
        signal.setNodeId("core:review");
        signal.setEpoch(0);
        return signal;
    }

    private static WorkflowRunEntity run() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId("tenant");
        run.setOrganizationId(ORG);
        return run;
    }

    private static ApprovalDelegationConfig config(Long credentialId, String chatId) {
        return new ApprovalDelegationConfig("slack", credentialId, chatId, "Ship it?", null, List.of(), null, null);
    }

    private ChatChannelBotEntity bot(String channel, long credentialId) {
        ChatChannelBotEntity bot = new ChatChannelBotEntity();
        bot.setId(UUID.randomUUID());
        bot.setChannel(channel);
        bot.setCredentialId(credentialId);
        return bot;
    }

    private static ChatChannelLinkEntity link(ChatChannelBotEntity bot, String chatId, boolean active) {
        ChatChannelLinkEntity link = new ChatChannelLinkEntity();
        link.setId(UUID.randomUUID());
        link.setBotId(bot.getId());
        link.setChatId(chatId);
        link.setActive(active);
        return link;
    }

    @Test
    @DisplayName("sends Approve and Reject buttons carrying the lcapr payload the shared router decides")
    @SuppressWarnings("unchecked")
    void sendsTheApprovalButtons() {
        when(slack.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(Outcome.of("1700.01"));
        ArgumentCaptor<List<ChoiceOption>> buttons = ArgumentCaptor.forClass(List.class);

        notifier.notifyPending(slack, signal(), config(5L, "C1"), run(), "Deploys");

        verify(slack).sendChoiceRequest(eq("tenant"), eq(5L), eq("C1"), eq("Ship it?"), buttons.capture());
        assertThat(buttons.getValue()).extracting(ChoiceOption::label).containsExactly("Approve", "Reject");
        assertThat(buttons.getValue().get(0).payload()).matches("lcapr:[A-Za-z0-9_-]{22}:a");
        assertThat(buttons.getValue().get(1).payload()).endsWith(":r");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(delivery.getMessageId()).isEqualTo("1700.01");
    }

    @Test
    @DisplayName("a node that names only the service reaches the workspace's default destination on it")
    void usesTheConnectedDefault() {
        ChatChannelBotEntity slackBot = bot("slack", 5L);
        ChatChannelBotEntity telegramBot = bot("telegram", 9L);
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(telegramBot, slackBot));
        // Ordered default first: the Telegram default is skipped, it is not on this service.
        when(links.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG)).thenReturn(List.of(
                link(telegramBot, "-100", true), link(slackBot, "C-old", false), link(slackBot, "C9", true)));

        ConnectorApprovalNotifier.Destination destination = notifier.destinationFor("slack", config(null, null), ORG);

        assertThat(destination).isEqualTo(new ConnectorApprovalNotifier.Destination(5L, "C9", List.of(), null));
    }

    @Test
    @DisplayName("a pinned credential picks its own bot; a given chat keeps the node's chat")
    void honoursWhatTheNodeGives() {
        ChatChannelBotEntity first = bot("slack", 5L);
        ChatChannelBotEntity second = bot("slack", 6L);
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(first, second));
        when(links.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG))
                .thenReturn(List.of(link(first, "C1", true), link(second, "C2", true)));

        assertThat(notifier.destinationFor("slack", config(6L, null), ORG).chatId()).isEqualTo("C2");
        assertThat(notifier.destinationFor("slack", config(null, "C7"), ORG))
                .isEqualTo(new ConnectorApprovalNotifier.Destination(5L, "C7", List.of(), null));
        // Both given: no lookup at all.
        assertThat(notifier.destinationFor("slack", config(8L, "C8"), null))
                .isEqualTo(new ConnectorApprovalNotifier.Destination(8L, "C8", List.of(), null));
    }

    @Test
    @DisplayName("nothing connected on that service is recorded on the delivery, with what to do, and nothing is sent")
    void nothingConnectedFailsTheDelivery() {
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(bot("telegram", 9L)));

        notifier.notifyPending(slack, signal(), config(null, null), run(), null);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getError()).contains("No slack account is connected").contains("assistant");
        verify(slack, never()).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an account with no working destination says so, rather than sending nowhere")
    void noWorkingDestination() {
        ChatChannelBotEntity slackBot = bot("slack", 5L);
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(slackBot));
        when(links.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG))
                .thenReturn(List.of(link(slackBot, "C1", false)));

        assertThat(notifier.destinationFor("slack", config(null, null), ORG).problem()).contains("no working destination");
    }

    private static ApprovalDelegationConfig picked(String linkId, List<String> nodeAllowed) {
        return new ApprovalDelegationConfig("slack", 99L, "C-typed", "Ship it?", null, nodeAllowed, null, null, linkId);
    }

    private static com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget target(
            java.util.UUID id, String channel, List<String> allowed) {
        return new com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget(
                id, channel, 12L, "C-finance", allowed);
    }

    @Test
    @DisplayName("a picked destination decides account and chat; the node's own credential and chat are ignored")
    void pickedDestinationWins() {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(channelService.resolveFor(ORG, id)).thenReturn(java.util.Optional.of(target(id, "slack", List.of())));

        ConnectorApprovalNotifier.Destination destination =
                notifier.destinationFor("slack", picked(id.toString(), List.of()), ORG);

        assertThat(destination).isEqualTo(new ConnectorApprovalNotifier.Destination(12L, "C-finance", List.of(), null));
        // Not the service-wide lookup: the pick is the answer.
        verify(bots, never()).findByOrganizationIdOrderByCreatedAtAsc(any());
    }

    @Test
    @DisplayName("the picked destination's restriction still bounds who may decide")
    void pickedDestinationKeepsItsAllowList() {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(channelService.resolveFor(ORG, id)).thenReturn(java.util.Optional.of(target(id, "slack", List.of("U1", "U2"))));

        assertThat(notifier.destinationFor("slack", picked(id.toString(), List.of("U2", "U9")), ORG).allowedUserIds())
                .containsExactly("U2");
    }

    @Test
    @DisplayName("default means the workspace default, looked up at send time")
    void defaultKeyword() {
        when(channelService.resolveFor(ORG, null)).thenReturn(
                java.util.Optional.of(target(java.util.UUID.randomUUID(), "slack", List.of())));

        assertThat(notifier.destinationFor("slack", picked("default", List.of()), ORG).chatId()).isEqualTo("C-finance");
    }

    @Test
    @DisplayName("a picked destination that is gone fails the delivery with a sentence, and nothing is sent elsewhere")
    void pickedDestinationGone() {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(channelService.resolveFor(ORG, id)).thenReturn(java.util.Optional.empty());

        notifier.notifyPending(slack, signal(), picked(id.toString(), List.of()), run(), null);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getError()).contains("no longer connected");
        verify(slack, never()).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("no default any more says so, distinctly from a picked destination that is gone")
    void defaultGone() {
        when(channelService.resolveFor(ORG, null)).thenReturn(java.util.Optional.empty());

        assertThat(notifier.destinationFor("slack", picked("default", List.of()), ORG).problem())
                .contains("no default destination");
    }

    @Test
    @DisplayName("a linkId that is not an id is refused, not read as the default")
    void malformedLinkId() {
        assertThat(notifier.destinationFor("slack", picked("finance-chat", List.of()), ORG).problem())
                .contains("not a destination id");
        verify(channelService, never()).resolveFor(any(), any());
    }

    @Test
    @DisplayName("a destination on another service than the notifier's is reported, not sent with the wrong bot")
    void serviceMismatch() {
        java.util.UUID id = java.util.UUID.randomUUID();
        when(channelService.resolveFor(ORG, id)).thenReturn(java.util.Optional.of(target(id, "discord", List.of())));

        assertThat(notifier.destinationFor("slack", picked(id.toString(), List.of()), ORG).problem())
                .contains("on discord, not slack");
    }

    @Test
    @DisplayName("analytics: a send that THROWS is reported as a failed delivery, once")
    void throwingSendIsReportedFailed() {
        com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics = mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(notifier, "analytics", analytics);
        when(slack.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("socket closed"));

        notifier.notifyPending(slack, signal(), config(5L, "C1"), run(), null);

        verify(analytics).channelRequestDelivered("tenant", ORG, com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.WORKFLOW_APPROVAL, "slack",
                com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestStatus.FAILED);
        org.mockito.Mockito.verifyNoMoreInteractions(analytics);
    }

    @Test
    @DisplayName("a refused send lands on the delivery row, never on the run")
    void refusedSendFailsTheDelivery() {
        when(slack.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(Outcome.failed("The Slack app is not in that channel."));

        notifier.notifyPending(slack, signal(), config(5L, "C1"), run(), null);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getError()).isEqualTo("The Slack app is not in that channel.");
    }

    @Test
    @DisplayName("a replayed event does not send twice")
    void replayDoesNotResend() {
        when(deliveries.insertPendingIfAbsent(any(), anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(0);

        notifier.notifyPending(slack, signal(), config(5L, "C1"), run(), null);

        verify(slack, never()).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("decided while the send was in flight: closed right after it, so no live buttons stay")
    void closesARaceLostDuringTheSend() {
        when(slack.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(Outcome.of("1700.01"));
        when(slack.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Outcome.of(null));
        delivery.setChatId("C1");
        delivery.setCredentialId(5L);
        SignalWaitEntity decided = signal();
        decided.setResolution(SignalResolution.APPROVED);
        decided.setResolvedBy("user:1");
        decided.setStatus(SignalWaitEntity.SignalWaitStatus.RESOLVED);
        when(signals.findById(55L)).thenReturn(Optional.of(decided));

        notifier.notifyPending(slack, signal(), config(5L, "C1"), run(), null);

        verify(slack).closeDecisionRequest("tenant", 5L, "C1", "1700.01", "Ship it?", "Approved");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RESOLVED);
    }

    @Test
    @DisplayName("the bound notifier closes with a verdict naming the service it was pressed on")
    void boundNotifierClosesWithTheVerdict() {
        when(slack.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(), any(), anyString()))
                .thenReturn(Outcome.failed("message too old"));
        delivery.setChatId("C1");
        delivery.setCredentialId(5L);
        delivery.setMessageId("1700.01");
        delivery.setMessageText("Ship it?");
        ApprovalChannelNotifier bound = notifier.boundTo(slack);

        bound.onResolved(delivery, SignalResolution.REJECTED, "slack:U42");

        // A failed edit is cosmetic: the decision already landed, so the row still closes.
        verify(slack).closeDecisionRequest("tenant", 5L, "C1", "1700.01", "Ship it?", "Rejected via Slack");
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RESOLVED);
        assertThat(bound.channelId()).isEqualTo("slack");

        bound.onCancelled(delivery);
        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
    }

    @Test
    @DisplayName("verdict lines: via the service only when decided there; timeouts and cancellations say so")
    void verdictLines() {
        assertThat(ConnectorApprovalNotifier.verdictLine(slack, SignalResolution.APPROVED, "user:7")).isEqualTo("Approved");
        assertThat(ConnectorApprovalNotifier.verdictLine(slack, SignalResolution.TIMEOUT, null)).isEqualTo("Timed out");
        assertThat(ConnectorApprovalNotifier.verdictLine(slack, SignalResolution.CANCELLED, null))
                .isEqualTo("Approval cancelled");
    }

    @Test
    @DisplayName("regression: falling back to a connected destination keeps who may decide there")
    void fallbackKeepsTheDestinationAllowList() {
        ChatChannelBotEntity slackBot = bot("slack", 5L);
        ChatChannelLinkEntity restricted = link(slackBot, "C9", true);
        restricted.setAllowedUserIds(List.of("U1", "U2"));
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(slackBot));
        when(links.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG)).thenReturn(List.of(restricted));
        when(slack.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(Outcome.of("1700.01"));

        notifier.notifyPending(slack, signal(), config(null, null), run(), null);

        // Before: the node's empty list went on the delivery, and anyone in the ops channel the
        // user had restricted at connect could approve the workflow.
        verify(deliveries).insertPendingIfAbsent(any(), anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), anyInt(), eq(5L), eq("C9"), eq("[\"U1\",\"U2\"]"), any());
    }

    @Test
    @DisplayName("the destination's restriction is the outer bound: a node narrows it and never widens it")
    void nodeListNarrowsNeverWidens() {
        List<String> destination = List.of("U1", "U2");

        assertThat(ConnectorApprovalNotifier.withAllowList(5L, "C9", List.of("U2"), destination).allowedUserIds())
                .containsExactly("U2");
        // U3 is not allowed at the destination: before, the node's list simply replaced it and U3 decided.
        assertThat(ConnectorApprovalNotifier.withAllowList(5L, "C9", List.of("U2", "U3"), destination)
                .allowedUserIds()).containsExactly("U2");
        assertThat(ConnectorApprovalNotifier.withAllowList(5L, "C9", List.of(), destination).allowedUserIds())
                .containsExactly("U1", "U2");
        assertThat(ConnectorApprovalNotifier.withAllowList(5L, "C9", List.of("U3"), List.of()).allowedUserIds())
                .containsExactly("U3");
    }

    @Test
    @DisplayName("regression: a node naming only people the destination refuses is reported, not sent open or to nobody")
    void disjointListsAreAConflict() {
        ChatChannelBotEntity slackBot = bot("slack", 5L);
        ChatChannelLinkEntity restricted = link(slackBot, "C9", true);
        restricted.setAllowedUserIds(List.of("U1"));
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(slackBot));
        when(links.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG)).thenReturn(List.of(restricted));
        ApprovalDelegationConfig outsider = new ApprovalDelegationConfig("slack", null, null, "Ship it?", null,
                List.of("U3"), null, null);

        notifier.notifyPending(slack, signal(), outsider, run(), null);

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(delivery.getError()).contains("U3").contains("restricted to U1");
        verify(slack, never()).sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("regression: a WhatsApp number typed with + and spaces is stored as the digits a press comes back from")
    void destinationIsStoredAsTheProviderWritesIt() {
        ChatChannelBotEntity waBot = bot("whatsapp", 5L);
        ChatChannelLinkEntity connectedNumber = link(waBot, "33612345678", true);
        connectedNumber.setAllowedUserIds(List.of("33612345678"));
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(waBot));
        when(links.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG)).thenReturn(List.of(connectedNumber));
        ApprovalDelegationConfig typed = new ApprovalDelegationConfig("whatsapp", null, "+33 6 12 34 56 78",
                "Ship it?", null, List.of(), null, null);

        ConnectorApprovalNotifier.Destination destination = notifier.destinationFor("whatsapp", typed, ORG);

        // Before: stored as typed, "+33 6 12 34 56 78" never equalled the "33612345678" the press
        // carries, so every Approve was refused in silence, and the connected restriction was missed.
        assertThat(destination.chatId()).isEqualTo("33612345678");
        assertThat(destination.allowedUserIds()).containsExactly("33612345678");
        assertThat(new com.apimarketplace.orchestrator.services.channel.PressOrigin("whatsapp", 5L, "33612345678")
                .admits("whatsapp", destination.credentialId(), destination.chatId())).isTrue();
    }

    @Test
    @DisplayName("a node naming a connected chat keeps that chat's restriction and its own bot, among several")
    void namedChatKeepsItsRestrictionAndBot() {
        ChatChannelBotEntity first = bot("slack", 5L);
        ChatChannelBotEntity second = bot("slack", 6L);
        ChatChannelLinkEntity secondsChat = link(second, "C2", true);
        secondsChat.setAllowedUserIds(List.of("U7"));
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(first, second));
        when(links.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG))
                .thenReturn(List.of(link(first, "C1", true), secondsChat));

        ConnectorApprovalNotifier.Destination destination = notifier.destinationFor("slack", config(null, "C2"), ORG);

        assertThat(destination).isEqualTo(new ConnectorApprovalNotifier.Destination(6L, "C2", List.of("U7"), null));
    }

    @Test
    @DisplayName("analytics: sent, refused and nothing-connected deliveries are reported as workflow_approval; a replay is not")
    void reportsEachDeliveryOutcome() {
        com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics = mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(notifier, "analytics", analytics);
        when(slack.sendChoiceRequest(anyString(), anyLong(), anyString(), anyString(), any()))
                .thenReturn(Outcome.of("1700.01"))
                .thenReturn(Outcome.failed("The Slack app is not in that channel."));
        when(bots.findByOrganizationIdOrderByCreatedAtAsc(ORG)).thenReturn(List.of(bot("telegram", 9L)));

        notifier.notifyPending(slack, signal(), config(5L, "C1"), run(), null);
        notifier.notifyPending(slack, signal(), config(5L, "C1"), run(), null);
        notifier.notifyPending(slack, signal(), config(null, null), run(), null);
        when(deliveries.insertPendingIfAbsent(any(), anyString(), anyString(), anyString(), any(), any(), any(),
                any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(0);
        notifier.notifyPending(slack, signal(), config(5L, "C1"), run(), null);

        verify(analytics).channelRequestDelivered("tenant", ORG, com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.WORKFLOW_APPROVAL, "slack",
                com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestStatus.SENT);
        verify(analytics).channelRequestDelivered("tenant", ORG, com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.WORKFLOW_APPROVAL, "slack",
                com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestStatus.FAILED);
        verify(analytics).channelRequestDelivered("tenant", ORG, com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.WORKFLOW_APPROVAL, "slack",
                com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestStatus.NO_CHANNEL);
        org.mockito.Mockito.verifyNoMoreInteractions(analytics);
    }
}
