package com.apimarketplace.orchestrator.services.approvalchannel.telegram;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity.DeliveryStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity.SignalWaitStatus;
import com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.services.approvalchannel.ApprovalDelegationConfig;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TelegramApprovalNotifier}: pins the EXACT outbound
 * Telegram payloads (send with two inline buttons, post-resolution edit with a
 * stripped keyboard), the {@code lcapr:<token>:a|r} callback_data contract
 * (under Telegram's 64-byte cap), the BYOK billing identifiers, the insert-owns-
 * the-send idempotency, the message-text precedence chain (the workflow name is
 * PASSED IN, never read off the detached run), and the send/resolve race close.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelegramApprovalNotifier")
class TelegramApprovalNotifierTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String RUN_ID = "run_pub_abc";
    private static final String NODE_ID = "core:manager_approval";
    private static final long CREDENTIAL_ID = 42L;
    private static final String CHAT_ID = "123456";
    private static final ToolRef EDIT_TOOL = new ToolRef("telegram/telegram-edit-message-text", 1);

    @Mock private ObjectProvider<ToolsGateway> toolsGatewayProvider;
    @Mock private ToolsGateway toolsGateway;
    @Mock private ApprovalChannelDeliveryRepository deliveryRepository;
    @Mock private SignalWaitRepository signalWaitRepository;
    @Captor private ArgumentCaptor<Map<String, Object>> paramsCaptor;
    @Captor private ArgumentCaptor<Map<String, Object>> billingCaptor;

    private MeterRegistry meterRegistry;
    private TelegramApprovalNotifier notifier;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        notifier = new TelegramApprovalNotifier(
                toolsGatewayProvider, deliveryRepository, signalWaitRepository,
                new ObjectMapper(), meterRegistry);
    }

    private SignalWaitEntity signal() {
        SignalWaitEntity signal = new SignalWaitEntity();
        signal.setId(55L);
        signal.setRunId(RUN_ID);
        signal.setNodeId(NODE_ID);
        signal.setItemId("0");
        signal.setEpoch(0);
        return signal;
    }

    /** Plain run carrying only the tenant: the notifier must never need more of it. */
    private WorkflowRunEntity run() {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(TENANT_ID);
        run.setRunIdPublic(RUN_ID);
        return run;
    }

    private ApprovalDelegationConfig config(String message) {
        return new ApprovalDelegationConfig("telegram", CREDENTIAL_ID, CHAT_ID, message, List.of());
    }

    private ApprovalChannelDeliveryEntity pendingDelivery() {
        ApprovalChannelDeliveryEntity delivery = new ApprovalChannelDeliveryEntity();
        delivery.setId(1L);
        delivery.setSignalWaitId(55L);
        delivery.setChannel("telegram");
        delivery.setStatus(DeliveryStatus.PENDING);
        delivery.setTenantId(TENANT_ID);
        delivery.setRunId(RUN_ID);
        delivery.setNodeId(NODE_ID);
        delivery.setCredentialId(CREDENTIAL_ID);
        delivery.setChatId(CHAT_ID);
        return delivery;
    }

    /** Stubs the fresh-insert happy path: this call owns the send. */
    private ApprovalChannelDeliveryEntity stubOwnedInsert() {
        ApprovalChannelDeliveryEntity delivery = pendingDelivery();
        when(deliveryRepository.insertPendingIfAbsent(any(), anyString(), anyString(), any(),
                any(), any(), any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(1);
        when(deliveryRepository.findByCallbackToken(anyString())).thenReturn(Optional.of(delivery));
        return delivery;
    }

    private ExecutionResult sendSuccess(Object messageId) {
        return new ExecutionResult(true, Map.of("result", Map.of("message_id", messageId)), null, null);
    }

    private ExecutionResult failure(String message) {
        return new ExecutionResult(false, null, List.of(Map.of("message", message)), null);
    }

    @Nested
    @DisplayName("notifyPending() - send payload")
    class NotifyPendingSendPayload {

        @Test
        @DisplayName("sends telegram-send-message with chat_id, text and an inline keyboard of exactly two buttons")
        void sendsExactPayload() {
            stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));

            notifier.notifyPending(signal(), config("Please approve the refund"), run(), null);

            verify(toolsGateway).executeTool(
                    eq(new ToolRef("telegram/telegram-send-message", 1)),
                    paramsCaptor.capture(), eq(TENANT_ID), anyMap());
            Map<String, Object> params = paramsCaptor.getValue();
            assertThat(params).containsEntry("chat_id", CHAT_ID);
            assertThat(params).containsEntry("text", "Please approve the refund");

            @SuppressWarnings("unchecked")
            Map<String, Object> replyMarkup = (Map<String, Object>) params.get("reply_markup");
            @SuppressWarnings("unchecked")
            List<List<Map<String, Object>>> keyboard =
                    (List<List<Map<String, Object>>>) replyMarkup.get("inline_keyboard");
            assertThat(keyboard).hasSize(1);
            assertThat(keyboard.get(0)).hasSize(2);
            assertThat(keyboard.get(0).get(0).get("text")).isEqualTo("✅ Approve");
            assertThat(keyboard.get(0).get(1).get("text")).isEqualTo("❌ Reject");
        }

        @Test
        @DisplayName("callback_data matches lcapr:<22 base64url chars>:a|r and stays under Telegram's 64-byte cap")
        void callbackDataMatchesContractAndByteCap() {
            stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));

            notifier.notifyPending(signal(), config("msg"), run(), null);

            verify(toolsGateway).executeTool(any(ToolRef.class), paramsCaptor.capture(),
                    anyString(), anyMap());
            @SuppressWarnings("unchecked")
            Map<String, Object> replyMarkup = (Map<String, Object>) paramsCaptor.getValue().get("reply_markup");
            @SuppressWarnings("unchecked")
            List<List<Map<String, Object>>> keyboard =
                    (List<List<Map<String, Object>>>) replyMarkup.get("inline_keyboard");
            String approveData = String.valueOf(keyboard.get(0).get(0).get("callback_data"));
            String rejectData = String.valueOf(keyboard.get(0).get(1).get("callback_data"));

            assertThat(approveData).matches("^lcapr:[A-Za-z0-9_-]{22}:a$");
            assertThat(rejectData).matches("^lcapr:[A-Za-z0-9_-]{22}:r$");
            assertThat(approveData.getBytes(StandardCharsets.UTF_8).length).isLessThan(64);
            assertThat(rejectData.getBytes(StandardCharsets.UTF_8).length).isLessThan(64);
        }

        @Test
        @DisplayName("the callback_data token IS the token persisted on the delivery row (click can be resolved back)")
        void callbackTokenMatchesPersistedToken() {
            stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));

            notifier.notifyPending(signal(), config("msg"), run(), null);

            ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
            verify(deliveryRepository).insertPendingIfAbsent(eq(55L), eq("telegram"),
                    tokenCaptor.capture(), eq(TENANT_ID), isNull(), eq(RUN_ID), eq(NODE_ID),
                    eq("0"), eq(0), eq(CREDENTIAL_ID), eq(CHAT_ID), any(), any());
            verify(toolsGateway).executeTool(any(ToolRef.class), paramsCaptor.capture(),
                    anyString(), anyMap());
            @SuppressWarnings("unchecked")
            Map<String, Object> replyMarkup = (Map<String, Object>) paramsCaptor.getValue().get("reply_markup");
            @SuppressWarnings("unchecked")
            List<List<Map<String, Object>>> keyboard =
                    (List<List<Map<String, Object>>>) replyMarkup.get("inline_keyboard");

            assertThat(keyboard.get(0).get(0).get("callback_data"))
                    .isEqualTo("lcapr:" + tokenCaptor.getValue() + ":a");
        }

        @Test
        @DisplayName("passes the BYOK billing identifiers: __credentialSource__=user + __selectedCredentialId__")
        void passesUserCredentialBillingIdentifiers() {
            stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));

            notifier.notifyPending(signal(), config("msg"), run(), null);

            verify(toolsGateway).executeTool(any(ToolRef.class), anyMap(), eq(TENANT_ID),
                    billingCaptor.capture());
            assertThat(billingCaptor.getValue())
                    .containsEntry("__credentialSource__", "user")
                    .containsEntry("__selectedCredentialId__", CREDENTIAL_ID);
        }
    }

    @Nested
    @DisplayName("notifyPending() - lifecycle and guards")
    class NotifyPendingLifecycle {

        @Test
        @DisplayName("success flips the delivery to SENT with the message_id extracted from output.result and a sentAt timestamp")
        void successMarksSentWithMessageId() {
            ApprovalChannelDeliveryEntity delivery = stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));

            notifier.notifyPending(signal(), config("Custom text"), run(), null);

            verify(deliveryRepository).save(delivery);
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
            assertThat(delivery.getMessageId()).isEqualTo("123");
            assertThat(delivery.getMessageText()).isEqualTo("Custom text");
            assertThat(delivery.getSentAt()).isNotNull();
        }

        @Test
        @DisplayName("idempotency: insert returning 0 (replay/replica race) sends NOTHING")
        void insertConflictNeverSends() {
            when(deliveryRepository.insertPendingIfAbsent(any(), anyString(), anyString(), any(),
                    any(), any(), any(), any(), anyInt(), any(), any(), any(), any())).thenReturn(0);

            notifier.notifyPending(signal(), config("msg"), run(), null);

            verify(toolsGateway, never()).executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap());
            verify(deliveryRepository, never()).save(any());
        }

        @Test
        @DisplayName("regression: missing credentialId sends WITHOUT credential markers (catalog falls back to the user's own Telegram credential)")
        void missingCredentialIdSendsWithDefaultCredentialFallback() {
            // Pre-fix this shape (the common agent-built one: the builder LLM rarely
            // knows the numeric credential id) hard-failed the delivery and no
            // Telegram message was ever sent, even though the tenant had a perfectly
            // usable default telegram credential (first live incident, 2026-07-07).
            ApprovalChannelDeliveryEntity delivery = stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), isNull()))
                    .thenReturn(sendSuccess(123));

            notifier.notifyPending(signal(),
                    new ApprovalDelegationConfig("telegram", null, CHAT_ID, "msg", List.of()), run(), null);

            verify(toolsGateway).executeTool(
                    eq(new ToolRef("telegram/telegram-send-message", 1)),
                    anyMap(), eq(TENANT_ID), isNull());
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
            assertThat(delivery.getMessageId()).isEqualTo("123");
        }

        @Test
        @DisplayName("the run's workspace id is persisted on the delivery row (org-scoped edits and fallback later)")
        void orgIdPersistedOnDeliveryRow() {
            stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));
            WorkflowRunEntity orgRun = run();
            orgRun.setOrganizationId("org-7");

            notifier.notifyPending(signal(), config("msg"), orgRun, null);

            verify(deliveryRepository).insertPendingIfAbsent(eq(55L), eq("telegram"),
                    anyString(), eq(TENANT_ID), eq("org-7"), eq(RUN_ID), eq(NODE_ID),
                    eq("0"), eq(0), eq(CREDENTIAL_ID), eq(CHAT_ID), any(), any());
        }

        @Test
        @DisplayName("an explicit credentialId still pins the credential markers on the send")
        void explicitCredentialIdStillPinsMarkers() {
            stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));

            notifier.notifyPending(signal(), config("msg"), run(), null);

            verify(toolsGateway).executeTool(any(ToolRef.class), anyMap(), eq(TENANT_ID), billingCaptor.capture());
            assertThat(billingCaptor.getValue())
                    .containsEntry("__credentialSource__", "user")
                    .containsEntry("__selectedCredentialId__", CREDENTIAL_ID);
        }

        @Test
        @DisplayName("blank chatId marks the delivery FAILED without sending")
        void blankChatIdFailsWithoutSend() {
            ApprovalChannelDeliveryEntity delivery = stubOwnedInsert();

            notifier.notifyPending(signal(),
                    new ApprovalDelegationConfig("telegram", CREDENTIAL_ID, "  ", "msg", List.of()), run(), null);

            verify(toolsGateway, never()).executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap());
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
        }

        @Test
        @DisplayName("unavailable tools gateway (deployment without catalog) marks the delivery FAILED")
        void unavailableGatewayFails() {
            ApprovalChannelDeliveryEntity delivery = stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(null);

            notifier.notifyPending(signal(), config("msg"), run(), null);

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(delivery.getError()).contains("gateway unavailable");
        }

        @Test
        @DisplayName("gateway failure marks the delivery FAILED with the provider error message")
        void gatewayFailureMarksFailedWithError() {
            ApprovalChannelDeliveryEntity delivery = stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(failure("Bad Request: chat not found"));

            notifier.notifyPending(signal(), config("msg"), run(), null);

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(delivery.getError()).isEqualTo("Bad Request: chat not found");
            verify(deliveryRepository).save(delivery);
        }
    }

    @Nested
    @DisplayName("notifyPending() - send/resolve race close (m1)")
    class SendResolveRace {

        private SignalWaitEntity signalWithStatus(SignalWaitStatus status, SignalResolution resolution,
                                                  String resolvedBy) {
            SignalWaitEntity current = signal();
            current.setStatus(status);
            current.setResolution(resolution);
            current.setResolvedBy(resolvedBy);
            return current;
        }

        @Test
        @DisplayName("regression m1: signal resolved DURING the send -> the message is edited right after (no live buttons linger) and the delivery goes terminal")
        void signalResolvedDuringSendGetsImmediateEdit() {
            ApprovalChannelDeliveryEntity delivery = stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));
            when(signalWaitRepository.findById(55L)).thenReturn(Optional.of(
                    signalWithStatus(SignalWaitStatus.RESOLVED, SignalResolution.APPROVED, "user-42")));

            notifier.notifyPending(signal(), config("msg text"), run(), null);

            verify(toolsGateway).executeTool(eq(EDIT_TOOL), paramsCaptor.capture(),
                    eq(TENANT_ID), anyMap());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("message_id", "123")
                    .containsEntry("text", "msg text\n\n✅ Approved")
                    .containsEntry("reply_markup", Map.of("inline_keyboard", List.of()));
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RESOLVED);
            assertThat(delivery.isTerminal()).isTrue();
        }

        @Test
        @DisplayName("regression m1: signal cancelled DURING the send -> onCancelled edit and delivery CANCELLED")
        void signalCancelledDuringSendGetsCancelledEdit() {
            ApprovalChannelDeliveryEntity delivery = stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));
            when(signalWaitRepository.findById(55L)).thenReturn(Optional.of(
                    signalWithStatus(SignalWaitStatus.CANCELLED, null, null)));

            notifier.notifyPending(signal(), config("msg text"), run(), null);

            verify(toolsGateway).executeTool(eq(EDIT_TOOL), paramsCaptor.capture(),
                    eq(TENANT_ID), anyMap());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("text", "msg text\n\n🚫 Approval cancelled");
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        }

        @Test
        @DisplayName("signal still active after the send -> NO edit, the delivery stays SENT")
        void activeSignalAfterSendLeavesMessageUntouched() {
            ApprovalChannelDeliveryEntity delivery = stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));
            when(signalWaitRepository.findById(55L)).thenReturn(Optional.of(
                    signalWithStatus(SignalWaitStatus.PENDING, null, null)));

            notifier.notifyPending(signal(), config("msg text"), run(), null);

            verify(toolsGateway, never()).executeTool(eq(EDIT_TOOL), anyMap(), anyString(), anyMap());
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.SENT);
        }
    }

    @Nested
    @DisplayName("notifyPending() - message text precedence")
    class MessageTextPrecedence {

        private String sentText(SignalWaitEntity signal, ApprovalDelegationConfig config,
                                WorkflowRunEntity run, String workflowName) {
            stubOwnedInsert();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(sendSuccess(123));

            notifier.notifyPending(signal, config, run, workflowName);

            verify(toolsGateway).executeTool(any(ToolRef.class), paramsCaptor.capture(),
                    anyString(), anyMap());
            return String.valueOf(paramsCaptor.getValue().get("text"));
        }

        @Test
        @DisplayName("configured message wins over the approval context")
        void configuredMessageWins() {
            SignalWaitEntity signal = signal();
            signal.setApprovalContext("Context text");

            assertThat(sentText(signal, config("Configured message"), run(), "Refund Flow"))
                    .isEqualTo("Configured message");
        }

        @Test
        @DisplayName("no configured message: falls back to the signal's resolved approvalContext")
        void fallsBackToApprovalContext() {
            SignalWaitEntity signal = signal();
            signal.setApprovalContext("Approve refund of 120 EUR?");

            assertThat(sentText(signal, config(null), run(), "Refund Flow"))
                    .isEqualTo("Approve refund of 120 EUR?");
        }

        @Test
        @DisplayName("regression M2: the generic line uses the PASSED workflowName; the detached run's lazy workflow proxy is NEVER navigated")
        void genericLineUsesPassedNameNeverTheRunProxy() {
            // A detached run on the async thread: initializing the lazy workflow proxy
            // throws. Pre-fix, buildMessageText read run.getWorkflow().getName(), the
            // exception was swallowed and NO Telegram message went out at all - this
            // test then fails on the missing send.
            WorkflowRunEntity detachedRun = mock(WorkflowRunEntity.class);
            when(detachedRun.getTenantId()).thenReturn(TENANT_ID);
            lenient().when(detachedRun.getWorkflow()).thenThrow(
                    new LazyInitializationException("could not initialize proxy - no Session"));

            assertThat(sentText(signal(), config(null), detachedRun, "Refund Flow"))
                    .isEqualTo("Approval requested: Refund Flow / " + NODE_ID);
            verify(detachedRun, never()).getWorkflow();
        }

        @Test
        @DisplayName("no message, no context, null workflowName: generic line falls back to the literal 'workflow'")
        void nullWorkflowNameFallsBackToGenericLabel() {
            assertThat(sentText(signal(), config(null), run(), null))
                    .isEqualTo("Approval requested: workflow / " + NODE_ID);
        }

        @Test
        @DisplayName("split fan-out: a per-item hint (Item #N, 1-based) is appended so N messages stay distinguishable")
        void appendsSplitItemHint() {
            SignalWaitEntity signal = signal();
            signal.setSplitItemData(Map.of("itemIndex", 2, "current_item", "Order C"));

            assertThat(sentText(signal, config("Approve this order"), run(), null))
                    .isEqualTo("Approve this order\n\nItem #3");
        }
    }

    @Nested
    @DisplayName("onResolved() / onCancelled() - message edit")
    class ResolvedAndCancelledEdits {

        private ApprovalChannelDeliveryEntity sentDelivery() {
            ApprovalChannelDeliveryEntity delivery = pendingDelivery();
            delivery.setStatus(DeliveryStatus.SENT);
            delivery.setMessageId("555");
            delivery.setMessageText("Original approval message");
            return delivery;
        }

        @Test
        @DisplayName("onResolved edits the message: original text + verdict line, keyboard stripped, status RESOLVED")
        void onResolvedEditsMessageAndMarksResolved() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(new ExecutionResult(true, Map.of(), null, null));

            notifier.onResolved(delivery, SignalResolution.APPROVED, "telegram:777");

            verify(toolsGateway).executeTool(
                    eq(EDIT_TOOL),
                    paramsCaptor.capture(), eq(TENANT_ID), billingCaptor.capture());
            Map<String, Object> params = paramsCaptor.getValue();
            assertThat(params).containsEntry("chat_id", CHAT_ID);
            assertThat(params).containsEntry("message_id", "555");
            assertThat(params).containsEntry("text",
                    "Original approval message\n\n✅ Approved via Telegram");
            assertThat(params).containsEntry("reply_markup", Map.of("inline_keyboard", List.of()));
            assertThat(billingCaptor.getValue())
                    .containsEntry("__credentialSource__", "user")
                    .containsEntry("__selectedCredentialId__", CREDENTIAL_ID);

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RESOLVED);
            assertThat(delivery.getResolvedAt()).isNotNull();
            verify(deliveryRepository).save(delivery);
        }

        @Test
        @DisplayName("verdict says 'via Telegram' ONLY when resolvedBy carries the telegram: prefix")
        void verdictWithoutTelegramPrefixIsPlain() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(new ExecutionResult(true, Map.of(), null, null));

            notifier.onResolved(delivery, SignalResolution.APPROVED, "user-42");

            verify(toolsGateway).executeTool(any(ToolRef.class), paramsCaptor.capture(),
                    anyString(), anyMap());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("text", "Original approval message\n\n✅ Approved");
        }

        @Test
        @DisplayName("TIMEOUT resolution appends the timed-out verdict line")
        void timeoutVerdictLine() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(new ExecutionResult(true, Map.of(), null, null));

            notifier.onResolved(delivery, SignalResolution.TIMEOUT, "system");

            verify(toolsGateway).executeTool(any(ToolRef.class), paramsCaptor.capture(),
                    anyString(), anyMap());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("text", "Original approval message\n\n⏰ Timed out");
        }

        @Test
        @DisplayName("regression m3: onResolved with a CANCELLED resolution marks the delivery CANCELLED, not RESOLVED (ledger taxonomy)")
        void onResolvedWithCancelledResolutionMarksCancelled() {
            // Single-signal cancellations flow through SignalResolvedEvent (not the bulk
            // SignalsCancelledEvent); the row must still land in the CANCELLED bucket.
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(new ExecutionResult(true, Map.of(), null, null));

            notifier.onResolved(delivery, SignalResolution.CANCELLED, "system");

            verify(toolsGateway).executeTool(any(ToolRef.class), paramsCaptor.capture(),
                    anyString(), anyMap());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("text", "Original approval message\n\n🚫 Approval cancelled");
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
            assertThat(delivery.getResolvedAt()).isNotNull();
            verify(deliveryRepository).save(delivery);
        }

        @Test
        @DisplayName("an edit failure is only logged: the delivery still flips to RESOLVED (decision already landed)")
        void editFailureStillMarksResolved() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(failure("message is not modified"));

            notifier.onResolved(delivery, SignalResolution.REJECTED, "telegram:777");

            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RESOLVED);
            verify(deliveryRepository).save(delivery);
        }

        @Test
        @DisplayName("a delivery that never got a message id (send failed) skips the edit but is still marked RESOLVED")
        void missingMessageIdSkipsEdit() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            delivery.setMessageId(null);

            notifier.onResolved(delivery, SignalResolution.APPROVED, "telegram:777");

            verify(toolsGateway, never()).executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap());
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.RESOLVED);
        }

        @Test
        @DisplayName("onCancelled edits with the cancelled line and marks the delivery CANCELLED")
        void onCancelledEditsAndMarksCancelled() {
            ApprovalChannelDeliveryEntity delivery = sentDelivery();
            when(toolsGatewayProvider.getIfAvailable()).thenReturn(toolsGateway);
            when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                    .thenReturn(new ExecutionResult(true, Map.of(), null, null));

            notifier.onCancelled(delivery);

            verify(toolsGateway).executeTool(any(ToolRef.class), paramsCaptor.capture(),
                    anyString(), anyMap());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("text", "Original approval message\n\n🚫 Approval cancelled");
            assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.CANCELLED);
            verify(deliveryRepository).save(delivery);
        }
    }
}
