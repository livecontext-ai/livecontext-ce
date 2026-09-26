package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatAuthorizationRequestEntity.RequestStatus;
import com.apimarketplace.orchestrator.repository.ChatAuthorizationRequestRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import com.apimarketplace.orchestrator.services.channel.AgentAuthorizationChannelService.DeliveryRequest;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The message a person is left with after they press the button.
 *
 * <p>A decision card is sent with three parts: who is asking, WHAT they want to do, and the
 * rule behind it. The closing edit used to rebuild that message from the agent name and the
 * rule alone, so the middle part, the only sentence describing the action in plain words,
 * disappeared at the moment the decision became permanent. On a phone that edited message is
 * the entire record of what was approved: "publish the September report to LinkedIn" became
 * "Action: publish_post". These tests pin the body that was really sent as the body that is
 * kept, through a press, through an expiry, and for rows written before it was stored at all.
 */
class AgentAuthorizationStoredBodyTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-1";
    private static final UUID LINK_ID = UUID.randomUUID();
    private static final String SUMMARY = "publish the September report to LinkedIn";

    private ChatChannelService channelService;
    private ChatChannelConnector connector;
    private ChatAuthorizationRequestRepository requestRepository;
    private AgentAuthorizationAnswerApplier applier;
    private AgentAuthorizationChannelService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        channelService = mock(ChatChannelService.class);
        connector = mock(ChatChannelConnector.class);
        requestRepository = mock(ChatAuthorizationRequestRepository.class);
        ChatChannelLinkRepository linkRepository = mock(ChatChannelLinkRepository.class);
        applier = mock(AgentAuthorizationAnswerApplier.class);
        ObjectProvider<AgentClient> agentClientProvider = mock(ObjectProvider.class);
        when(agentClientProvider.getIfAvailable()).thenReturn(null);

        when(connector.channelId()).thenReturn("telegram");
        // The interface default, not a mock zero: a connector that states no limit of its
        // own still has one, and a 0 here would cap every body in this class to nothing.
        when(connector.maxDecisionTextChars()).thenCallRealMethod();
        when(channelService.resolveFor(ORG, null)).thenReturn(Optional.of(
                new ResolvedTarget(LINK_ID, "telegram", 9L, "-100123", List.of())));
        when(requestRepository.findByConversationIdAndFingerprintAndStatus(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        when(requestRepository.claim(any(), any())).thenReturn(1);
        when(connector.sendDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(Outcome.of("555"));
        when(connector.closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                anyString(), anyString())).thenReturn(Outcome.of(null));
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
        return new DeliveryRequest(TENANT, ORG, "conv-1", "call-1", "publish_post",
                UUID.randomUUID().toString(), "Night Publisher", SUMMARY, "publish|post_id=abc");
    }

    /** The text the connector was handed by the delivery. */
    private String textSent() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(connector).sendDecisionRequest(anyString(), anyLong(), anyString(), text.capture(),
                anyString(), anyString());
        return text.getValue();
    }

    /** The row the delivery persisted. */
    private ChatAuthorizationRequestEntity savedRow() {
        ArgumentCaptor<ChatAuthorizationRequestEntity> row =
                ArgumentCaptor.forClass(ChatAuthorizationRequestEntity.class);
        verify(requestRepository).save(row.capture());
        return row.getValue();
    }

    /** The body the connector was handed by a closing edit. */
    private String bodyClosedWith() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(connector).closeDecisionRequest(anyString(), anyLong(), anyString(), anyString(),
                body.capture(), anyString());
        return body.getValue();
    }

    @Test
    @DisplayName("keeps the body it sent, character for character, on the row")
    void persistsTheBodyItSent() {
        service.deliver(request());

        // Not "a body like the one sent": the same one. Anything reconstructed later from
        // the other columns is a second wording of the question, and the person shown it
        // has no way to tell which one they agreed to.
        assertThat(savedRow().getMessageText()).isEqualTo(textSent());
        assertThat(savedRow().getMessageText()).contains(SUMMARY);
    }

    @Test
    @DisplayName("stores what the provider accepted, not what was composed for it")
    void storesTheTextTelegramActuallySent() {
        when(connector.maxDecisionTextChars()).thenReturn(60);

        service.deliver(request());

        // The cap is applied BEFORE the send, so the row cannot hold text the provider
        // dropped. Storing the uncapped version would make the closing edit longer than
        // the message it replaces, and the verdict line, the one part that says what was
        // decided, is what falls off the end.
        assertThat(textSent()).hasSize(60);
        assertThat(savedRow().getMessageText()).isEqualTo(textSent());
    }

    @Test
    @DisplayName("the edit after a press still says what was approved")
    void closingEditKeepsWhatWasApproved() {
        when(applier.apply(any(), eq(true), any())).thenReturn(true);
        service.deliver(request());
        ChatAuthorizationRequestEntity row = savedRow();
        when(requestRepository.findByCallbackToken(anyString())).thenReturn(Optional.of(row));

        service.answer(row.getCallbackToken(), true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

        // The regression this test exists for: the description of the action was rebuilt
        // away here, leaving the rule alone on the record of a decision already applied.
        assertThat(bodyClosedWith()).isEqualTo(row.getMessageText());
        assertThat(bodyClosedWith()).contains(SUMMARY);
    }

    @Test
    @DisplayName("a request sent before bodies were stored still closes with a sentence")
    void legacyRowWithoutBodyStillCloses() {
        when(applier.apply(any(), eq(true), any())).thenReturn(true);
        ChatAuthorizationRequestEntity legacy = new ChatAuthorizationRequestEntity();
        legacy.setId(UUID.randomUUID());
        legacy.setTenantId(TENANT);
        legacy.setOrganizationId(ORG);
        legacy.setChannel("telegram");
        legacy.setCredentialId(9L);
        legacy.setChatId("-100123");
        legacy.setMessageId("555");
        legacy.setCallbackToken("tok");
        legacy.setConversationId("conv-1");
        legacy.setGateKey("call-1");
        legacy.setRule("publish_post");
        legacy.setAgentName("Night Publisher");
        legacy.setFingerprint("fp");
        legacy.setStatus(RequestStatus.SENT);
        legacy.setExpiresAt(Instant.now().plusSeconds(3600));
        legacy.setMessageText(null);
        when(requestRepository.findByCallbackToken("tok")).thenReturn(Optional.of(legacy));

        service.answer("tok", true, "user-7", com.apimarketplace.orchestrator.services.channel.PressOrigin.of("telegram"));

        // Rows delivered before the column existed have no body to keep. An empty edit
        // would blank the question on their screen, so the old rebuild stays as the
        // fallback, and only as the fallback.
        assertThat(bodyClosedWith()).isNotBlank();
        assertThat(bodyClosedWith()).contains("Night Publisher").contains("publish_post");
    }

    @Test
    @DisplayName("an expired request is closed with the question it asked, not a third wording")
    void expiryEditKeepsTheOriginalBody() {
        service.deliver(request());
        ChatAuthorizationRequestEntity row = savedRow();
        ChatAuthorizationRequestRepository schedulerRepository =
                mock(ChatAuthorizationRequestRepository.class);
        when(schedulerRepository.findByStatusAndExpiresAtBeforeOrderByExpiresAtAsc(eq(RequestStatus.SENT), any(), any()))
                .thenReturn(List.of(row));
        new ChatAuthorizationExpiryScheduler(schedulerRepository,
                new ChatChannelConnectorRegistry(List.of(connector))).expireOverdueRequests();

        // The sweep had a wording of its own ("needed your permission"), which made the
        // message a person came back to differ from the one they were shown, in the tense
        // of a sentence they never read twice. One body, one question.
        assertThat(bodyClosedWith()).isEqualTo(row.getMessageText());
        assertThat(bodyClosedWith()).contains(SUMMARY);
    }
}
