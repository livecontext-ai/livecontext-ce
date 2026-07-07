package com.apimarketplace.orchestrator.services.approvalchannel.telegram;

import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity.DeliveryStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService;
import com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TelegramApprovalCallbackHandler}: the inbound half of the
 * delegated approval. Pins the callback_data recognition (only the namespaced
 * {@code lcapr:} shape is intercepted, ordinary bot buttons keep dispatching to
 * workflows), the token-capability resolution to APPROVED/REJECTED, the terminal
 * and allowlist short-circuits, and the always-swallow contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramApprovalCallbackHandler")
class TelegramApprovalCallbackHandlerTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUv"; // 22 base64url chars
    private static final String RUN_ID = "run_pub_abc";
    private static final String NODE_ID = "core:manager_approval";

    @Mock private ApprovalChannelDeliveryRepository deliveryRepository;
    @Mock private RunSignalResolutionService runSignalResolutionService;
    @Mock private TelegramApprovalNotifier notifier;

    private MeterRegistry meterRegistry;
    private TelegramApprovalCallbackHandler handler;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        handler = new TelegramApprovalCallbackHandler(
                deliveryRepository, runSignalResolutionService, notifier, meterRegistry);
    }

    private Map<String, Object> callbackPayload(String data, Object fromId) {
        Map<String, Object> callbackQuery = new HashMap<>();
        callbackQuery.put("id", "cbq-1");
        callbackQuery.put("data", data);
        if (fromId != null) {
            callbackQuery.put("from", Map.of("id", fromId));
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("update_id", 1001);
        payload.put("callback_query", callbackQuery);
        return payload;
    }

    private ApprovalChannelDeliveryEntity sentDelivery() {
        ApprovalChannelDeliveryEntity delivery = new ApprovalChannelDeliveryEntity();
        delivery.setId(1L);
        delivery.setSignalWaitId(55L);
        delivery.setChannel("telegram");
        delivery.setStatus(DeliveryStatus.SENT);
        delivery.setCallbackToken(TOKEN);
        delivery.setTenantId("tenant-1");
        delivery.setRunId(RUN_ID);
        delivery.setNodeId(NODE_ID);
        delivery.setItemId("0");
        delivery.setEpoch(2);
        delivery.setCredentialId(42L);
        delivery.setChatId("123456");
        return delivery;
    }

    private RunSignalResolutionService.Outcome okOutcome(String resolution) {
        return new RunSignalResolutionService.Outcome(true, null, 55L, 2, resolution);
    }

    private RunSignalResolutionService.Outcome notFoundOutcome() {
        return new RunSignalResolutionService.Outcome(false, "no_pending_approval", null, null, null);
    }

    @Nested
    @DisplayName("isApprovalCallback()")
    class IsApprovalCallback {

        @Test
        @DisplayName("true for a callback_query whose data matches lcapr:<token>:a")
        void trueForApprovalShape() {
            assertTrue(handler.isApprovalCallback(callbackPayload("lcapr:" + TOKEN + ":a", 777)));
            assertTrue(handler.isApprovalCallback(callbackPayload("lcapr:" + TOKEN + ":r", 777)));
        }

        @Test
        @DisplayName("false for a payload without callback_query (plain message update)")
        void falseWithoutCallbackQuery() {
            assertFalse(handler.isApprovalCallback(Map.of(
                    "update_id", 1001,
                    "message", Map.of("text", "hello"))));
        }

        @Test
        @DisplayName("false for an ordinary bot button (no lcapr: prefix): user workflows keep their clicks")
        void falseForOrdinaryCallbackData() {
            assertFalse(handler.isApprovalCallback(callbackPayload("my_workflow_button", 777)));
        }

        @Test
        @DisplayName("false for a null payload")
        void falseForNullPayload() {
            assertFalse(handler.isApprovalCallback(null));
        }

        @Test
        @DisplayName("false when the verdict flag is not a|r or the token is too short")
        void falseForMalformedShape() {
            assertFalse(handler.isApprovalCallback(callbackPayload("lcapr:" + TOKEN + ":x", 777)));
            assertFalse(handler.isApprovalCallback(callbackPayload("lcapr:short:a", 777)));
        }
    }

    @Nested
    @DisplayName("handle()")
    class Handle {

        @Test
        @DisplayName("approve click resolves APPROVED as telegram:<from.id> with the delivery's epoch/itemId, then acks Approved")
        void approveClickResolvesApproved() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(deliveryRepository.findByCallbackToken(TOKEN)).thenReturn(Optional.of(delivery));
            when(runSignalResolutionService.resolveApproval(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(okOutcome("APPROVED"));

            handler.handle(callbackPayload("lcapr:" + TOKEN + ":a", 777));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
            verify(runSignalResolutionService).resolveApproval(
                    eq(RUN_ID), eq(NODE_ID), eq(SignalResolution.APPROVED),
                    dataCaptor.capture(), eq("telegram:777"), eq(2), eq("0"));
            assertThat(dataCaptor.getValue())
                    .containsEntry("source", "telegram")
                    .containsEntry("telegramUserId", "777")
                    .containsEntry("chatId", "123456");
            verify(notifier).answerCallbackQuery(delivery, "cbq-1", "Approved ✅", false);
        }

        @Test
        @DisplayName("reject click resolves REJECTED and acks Rejected")
        void rejectClickResolvesRejected() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(deliveryRepository.findByCallbackToken(TOKEN)).thenReturn(Optional.of(delivery));
            when(runSignalResolutionService.resolveApproval(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(okOutcome("REJECTED"));

            handler.handle(callbackPayload("lcapr:" + TOKEN + ":r", 777));

            verify(runSignalResolutionService).resolveApproval(
                    eq(RUN_ID), eq(NODE_ID), eq(SignalResolution.REJECTED),
                    any(), eq("telegram:777"), eq(2), eq("0"));
            verify(notifier).answerCallbackQuery(delivery, "cbq-1", "Rejected ❌", false);
        }

        @Test
        @DisplayName("unknown token (stale or forged) resolves nothing and answers nothing")
        void unknownTokenResolvesNothing() {
            when(deliveryRepository.findByCallbackToken(TOKEN)).thenReturn(Optional.empty());

            handler.handle(callbackPayload("lcapr:" + TOKEN + ":a", 777));

            verifyNoInteractions(runSignalResolutionService);
            verifyNoInteractions(notifier);
            assertThat(meterRegistry.counter("approval.delegation.errors",
                    "type", "UnknownCallbackToken").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("terminal delivery (already decided) answers the toast without resolving again")
        void terminalDeliveryShortCircuits() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            delivery.setStatus(DeliveryStatus.RESOLVED);
            when(deliveryRepository.findByCallbackToken(TOKEN)).thenReturn(Optional.of(delivery));

            handler.handle(callbackPayload("lcapr:" + TOKEN + ":a", 777));

            verifyNoInteractions(runSignalResolutionService);
            verify(notifier).answerCallbackQuery(
                    delivery, "cbq-1", "This approval was already decided.", false);
        }

        @Test
        @DisplayName("allowlist: a non-allowed user gets a show_alert refusal and resolves nothing")
        void nonAllowedUserRefused() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            delivery.setAllowedUserIds(List.of("111", "222"));
            when(deliveryRepository.findByCallbackToken(TOKEN)).thenReturn(Optional.of(delivery));

            handler.handle(callbackPayload("lcapr:" + TOKEN + ":a", 777));

            verifyNoInteractions(runSignalResolutionService);
            verify(notifier).answerCallbackQuery(
                    delivery, "cbq-1", "You are not allowed to decide this approval.", true);
        }

        @Test
        @DisplayName("allowlist: an allowed user resolves normally")
        void allowedUserResolves() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            delivery.setAllowedUserIds(List.of("777"));
            when(deliveryRepository.findByCallbackToken(TOKEN)).thenReturn(Optional.of(delivery));
            when(runSignalResolutionService.resolveApproval(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(okOutcome("APPROVED"));

            handler.handle(callbackPayload("lcapr:" + TOKEN + ":a", 777));

            verify(runSignalResolutionService).resolveApproval(
                    eq(RUN_ID), eq(NODE_ID), eq(SignalResolution.APPROVED),
                    any(), eq("telegram:777"), eq(2), eq("0"));
        }

        @Test
        @DisplayName("resolution outcome !ok (timeout race / double click) answers already-decided")
        void notOkOutcomeAnswersAlreadyDecided() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(deliveryRepository.findByCallbackToken(TOKEN)).thenReturn(Optional.of(delivery));
            when(runSignalResolutionService.resolveApproval(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(notFoundOutcome());

            handler.handle(callbackPayload("lcapr:" + TOKEN + ":a", 777));

            verify(notifier).answerCallbackQuery(
                    delivery, "cbq-1", "This approval was already decided.", false);
        }

        @Test
        @DisplayName("non-matching callback_data is ignored (defense in depth behind isApprovalCallback)")
        void nonMatchingDataIgnored() {
            handler.handle(callbackPayload("my_workflow_button", 777));

            verifyNoInteractions(deliveryRepository);
            verifyNoInteractions(runSignalResolutionService);
        }

        @Test
        @DisplayName("everything is swallowed: a repository failure never propagates (Telegram would retry non-2xx)")
        void repositoryFailureSwallowed() {
            when(deliveryRepository.findByCallbackToken(anyString()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatCode(() -> handler.handle(callbackPayload("lcapr:" + TOKEN + ":a", 777)))
                    .doesNotThrowAnyException();

            verify(notifier, never()).answerCallbackQuery(any(), anyString(), anyString(), anyBoolean());
        }
    }
}
