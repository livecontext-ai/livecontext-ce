package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity.DeliveryStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository;
import com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The provider-neutral half of a workflow approval press: what every provider other than Telegram
 * reaches through the inbound router. Telegram's own suite covers the shared rules through its
 * handler; this pins what only matters once there are several providers.
 */
@DisplayName("WorkflowApprovalPressService")
class WorkflowApprovalPressServiceTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUv";

    @BeforeAll
    static void installTokenAtRest() {
        TokenAtRest.install(new CredentialEncryptionService("test-password-123", "0123456789abcdef"));
    }

    private ApprovalChannelDeliveryRepository deliveries;
    private RunSignalResolutionService resolutions;
    private WorkflowApprovalPressService service;
    private ApprovalChannelDeliveryEntity delivery;

    @BeforeEach
    void setUp() {
        deliveries = mock(ApprovalChannelDeliveryRepository.class);
        resolutions = mock(RunSignalResolutionService.class);
        service = new WorkflowApprovalPressService(deliveries, resolutions, new SimpleMeterRegistry());
        delivery = new ApprovalChannelDeliveryEntity();
        delivery.setId(1L);
        delivery.setChannel("slack");
        delivery.setStatus(DeliveryStatus.SENT);
        delivery.setRunId("run-1");
        delivery.setNodeId("core:review");
        delivery.setItemId("0");
        delivery.setEpoch(1);
        delivery.setChatId("C1");
        when(deliveries.findByCallbackTokenHash(anyString())).thenReturn(Optional.of(delivery));
    }

    @Test
    @DisplayName("a press on the service the approval was sent on resolves it, recorded with that service as source")
    @SuppressWarnings("unchecked")
    void resolvesOnItsOwnService() {
        when(resolutions.resolveApproval(anyString(), anyString(), any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(new RunSignalResolutionService.Outcome(true, null, 55L, 1, "APPROVED"));
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);

        WorkflowApprovalPressService.PressOutcome outcome = service.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("slack"), TOKEN, true, "U42");

        assertThat(outcome.handled()).isTrue();
        verify(resolutions).resolveApproval(eq("run-1"), eq("core:review"), eq(SignalResolution.APPROVED),
                metadata.capture(), eq("slack:U42"), eq(1), eq("0"));
        // The Telegram-only key stays Telegram's: other services record the neutral one.
        assertThat(metadata.getValue()).containsEntry("source", "slack").containsEntry("channelUserId", "U42")
                .doesNotContainKey("telegramUserId");
    }

    @Test
    @DisplayName("a token seen on another service than the one it was sent on is not ours, and decides nothing")
    void refusesATokenFromAnotherService() {
        // The allow-list holds ids of the original service: checked against a Discord id it would
        // mean nothing, so the press is refused before it is even compared.
        delivery.setAllowedUserIds(List.of("U42"));

        WorkflowApprovalPressService.PressOutcome outcome = service.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("discord"), TOKEN, true, "U42");

        assertThat(outcome.handled()).isFalse();
        verify(resolutions, never()).resolveApproval(anyString(), anyString(), any(), any(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("an anonymous press (a Teams link) is refused where the approval has an allow-list")
    void anonymousPressAgainstAnAllowList() {
        delivery.setChannel("teams");
        delivery.setAllowedUserIds(List.of("someone"));

        WorkflowApprovalPressService.PressOutcome outcome = service.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("teams"), TOKEN, true, null);

        assertThat(outcome.asAlert()).isTrue();
        assertThat(outcome.replyToUser()).contains("not allowed");
    }

    @Test
    @DisplayName("regression: a press on another bot's endpoint, or from another chat, decides nothing")
    void refusesAnotherBotOrChat() {
        delivery.setCredentialId(5L);

        assertThat(service.press(new com.apimarketplace.orchestrator.services.channel.PressOrigin("slack", 6L, "C1"),
                TOKEN, true, "U42").handled()).isFalse();
        assertThat(service.press(new com.apimarketplace.orchestrator.services.channel.PressOrigin("slack", 5L, "C9"),
                TOKEN, true, "U42").handled()).isFalse();
        verify(resolutions, never()).resolveApproval(anyString(), anyString(), any(), any(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("an already decided approval says so, and decides nothing")
    void alreadyDecided() {
        delivery.setStatus(DeliveryStatus.RESOLVED);

        WorkflowApprovalPressService.PressOutcome outcome = service.press(
                com.apimarketplace.orchestrator.services.channel.PressOrigin.of("slack"), TOKEN, true, "U42");

        assertThat(outcome.replyToUser()).contains("already decided");
        verify(resolutions, never()).resolveApproval(anyString(), anyString(), any(), any(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("a presser outside the allow-list is refused, with an alert")
    void refusesAnOutsider() {
        delivery.setAllowedUserIds(List.of("U1"));

        WorkflowApprovalPressService.PressOutcome outcome = service.press(
                com.apimarketplace.orchestrator.services.channel.PressOrigin.of("slack"), TOKEN, true, "U42");

        assertThat(outcome.asAlert()).isTrue();
        verify(resolutions, never()).resolveApproval(anyString(), anyString(), any(), any(), anyString(), anyInt(),
                anyString());
    }

    @Test
    @DisplayName("a resolution that finds nothing pending (timed out, or decided elsewhere) says already decided")
    void resolutionThatFindsNothing() {
        when(resolutions.resolveApproval(anyString(), anyString(), any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(new RunSignalResolutionService.Outcome(false, "no_pending_approval", null, null, null));

        WorkflowApprovalPressService.PressOutcome outcome = service.press(
                com.apimarketplace.orchestrator.services.channel.PressOrigin.of("slack"), TOKEN, false, "U42");

        assertThat(outcome.handled()).isTrue();
        assertThat(outcome.replyToUser()).contains("already decided");
    }

    @Test
    @DisplayName("a Telegram press keeps its original telegramUserId key, so its history reads as before")
    @SuppressWarnings("unchecked")
    void telegramKeepsItsKey() {
        delivery.setChannel("telegram");
        delivery.setChatId("-100123");
        when(resolutions.resolveApproval(anyString(), anyString(), any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(new RunSignalResolutionService.Outcome(true, null, 55L, 1, "APPROVED"));
        ArgumentCaptor<Map<String, Object>> metadata = ArgumentCaptor.forClass(Map.class);

        service.press(new com.apimarketplace.orchestrator.services.channel.PressOrigin("telegram", null, "-100123"),
                TOKEN, true, "77");

        verify(resolutions).resolveApproval(anyString(), anyString(), any(), metadata.capture(), eq("telegram:77"),
                anyInt(), anyString());
        assertThat(metadata.getValue()).containsEntry("telegramUserId", "77").containsEntry("source", "telegram");
    }

    @Test
    @DisplayName("analytics: a resolved press is reported under the run's owner; a lost race is not")
    void reportsAResolvedPress() {
        com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics = mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        delivery.setTenantId("42");
        delivery.setOrgId("org-1");
        when(resolutions.resolveApproval(anyString(), anyString(), any(), any(), anyString(), anyInt(), anyString()))
                .thenReturn(new RunSignalResolutionService.Outcome(true, null, 55L, 1, "REJECTED"))
                .thenReturn(new RunSignalResolutionService.Outcome(false, null, 55L, 1, null));

        service.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("slack"), TOKEN, false, "U42");
        service.press(com.apimarketplace.orchestrator.services.channel.PressOrigin.of("slack"), TOKEN, false, "U42");

        verify(analytics, org.mockito.Mockito.times(1)).channelRequestAnswered("42", "org-1",
                com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.RequestType.WORKFLOW_APPROVAL, "slack", com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Decision.REJECTED, com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.Input.BUTTON);
    }
}
