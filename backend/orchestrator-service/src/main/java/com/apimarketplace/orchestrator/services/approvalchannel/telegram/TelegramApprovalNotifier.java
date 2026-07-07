package com.apimarketplace.orchestrator.services.approvalchannel.telegram;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity.DeliveryStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.services.approvalchannel.ApprovalChannelNotifier;
import com.apimarketplace.orchestrator.services.approvalchannel.ApprovalDelegationConfig;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Telegram implementation of {@link ApprovalChannelNotifier}.
 *
 * Sends the pending-approval message with inline approve/reject buttons through the
 * catalog's Telegram integration (the user's OWN bot credential, BYOK: the bot token
 * never transits the orchestrator, only the credential id does). The button
 * callback_data carries {@code lcapr:<token>:a|r} (30 bytes, under Telegram's 64-byte
 * cap); the click comes back through the public webhook path and resolves via
 * {@code TelegramApprovalCallbackHandler}.
 *
 * All operations are best-effort per the interface contract: failures land on the
 * delivery row ({@code FAILED} + error) and the {@code approval.delegation.errors}
 * counter, never on the workflow. There is deliberately NO transient-send retry: a
 * failed send stays FAILED (replay-protected by the unique constraint) and the
 * approval remains fully resolvable in-app - the bell notification is the always-on
 * fallback surface.
 */
@Component
public class TelegramApprovalNotifier implements ApprovalChannelNotifier {

    private static final Logger logger = LoggerFactory.getLogger(TelegramApprovalNotifier.class);

    public static final String CHANNEL_ID = "telegram";
    /** Namespaced callback prefix; the webhook interceptor matches on it. */
    public static final String CALLBACK_PREFIX = "lcapr";

    // Tool slugs of the catalog Telegram integration (apiSlug/toolSlug format,
    // matching the ids workflow mcp: steps use for the same operations).
    private static final ToolRef TOOL_SEND_MESSAGE = new ToolRef("telegram/telegram-send-message", 1);
    private static final ToolRef TOOL_EDIT_MESSAGE_TEXT = new ToolRef("telegram/telegram-edit-message-text", 1);
    static final ToolRef TOOL_ANSWER_CALLBACK_QUERY = new ToolRef("telegram/telegram-answer-callback-query", 1);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ObjectProvider<ToolsGateway> toolsGatewayProvider;
    private final com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository deliveryRepository;
    private final com.apimarketplace.orchestrator.repository.SignalWaitRepository signalWaitRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public TelegramApprovalNotifier(
            ObjectProvider<ToolsGateway> toolsGatewayProvider,
            com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository deliveryRepository,
            com.apimarketplace.orchestrator.repository.SignalWaitRepository signalWaitRepository,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry) {
        this.toolsGatewayProvider = toolsGatewayProvider;
        this.deliveryRepository = deliveryRepository;
        this.signalWaitRepository = signalWaitRepository;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String channelId() {
        return CHANNEL_ID;
    }

    @Override
    public void notifyPending(SignalWaitEntity signal, ApprovalDelegationConfig config,
                              WorkflowRunEntity run, String workflowName) {
        try {
            String token = generateToken();
            int inserted = deliveryRepository.insertPendingIfAbsent(
                    signal.getId(), CHANNEL_ID, token,
                    run.getTenantId(), signal.getRunId(), signal.getNodeId(),
                    signal.getItemId() != null ? signal.getItemId() : "0", signal.getEpoch(),
                    config.credentialId(), config.chatId(),
                    toJsonOrNull(config.allowedUserIds()), Instant.now());
            if (inserted == 0) {
                // Replay/replica race: another dispatch already owns this delivery.
                return;
            }
            Optional<ApprovalChannelDeliveryEntity> deliveryOpt = deliveryRepository.findByCallbackToken(token);
            if (deliveryOpt.isEmpty()) {
                return;
            }
            ApprovalChannelDeliveryEntity delivery = deliveryOpt.get();

            if (config.credentialId() == null || config.chatId() == null || config.chatId().isBlank()) {
                fail(delivery, "Delegation misconfigured: missing credentialId or chatId");
                return;
            }
            ToolsGateway gateway = toolsGatewayProvider.getIfAvailable();
            if (gateway == null) {
                fail(delivery, "Catalog tools gateway unavailable in this deployment");
                return;
            }

            String text = buildMessageText(signal, config, workflowName);
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("chat_id", config.chatId());
            params.put("text", text);
            params.put("reply_markup", Map.of("inline_keyboard", List.of(List.of(
                    Map.of("text", "✅ Approve", "callback_data", CALLBACK_PREFIX + ":" + token + ":a"),
                    Map.of("text", "❌ Reject", "callback_data", CALLBACK_PREFIX + ":" + token + ":r")))));

            ExecutionResult result = gateway.executeTool(
                    TOOL_SEND_MESSAGE, params, run.getTenantId(), userCredential(config.credentialId()));

            if (result.isSuccess()) {
                delivery.setStatus(DeliveryStatus.SENT);
                delivery.setMessageText(text);
                delivery.setMessageId(extractMessageId(result));
                delivery.setSentAt(Instant.now());
                deliveryRepository.save(delivery);
                logger.info("[approval-telegram] sent approval message: signal={}, chat={}, messageId={}",
                        signal.getId(), config.chatId(), delivery.getMessageId());
                // Close the send/resolve race: if the signal was decided (or cancelled)
                // while the send was in flight, the SignalResolvedEvent fan-out has
                // already run and missed this delivery (it only edits SENT rows). Re-read
                // the signal and apply the terminal edit now, so no live buttons linger.
                signalWaitRepository.findById(signal.getId()).ifPresent(current -> {
                    if (current.isResolved()) {
                        onResolved(delivery, current.getResolution(), current.getResolvedBy());
                    } else if (current.isCancelled()) {
                        onCancelled(delivery);
                    }
                });
            } else {
                fail(delivery, result.getErrorMessage() != null
                        ? result.getErrorMessage() : "Telegram send_message failed");
            }
        } catch (Exception ex) {
            meterRegistry.counter("approval.delegation.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[approval-telegram] swallowed send for signal {}: {}",
                    signal.getId(), ex.getMessage());
        }
    }

    @Override
    public void onResolved(ApprovalChannelDeliveryEntity delivery, SignalResolution resolution, String resolvedBy) {
        String verdict = verdictLine(resolution, resolvedBy);
        editMessage(delivery, verdict);
        // Single-signal cancellations flow through SignalResolvedEvent (not the bulk
        // SignalsCancelledEvent), so keep the ledger taxonomy honest here too.
        delivery.setStatus(resolution == SignalResolution.CANCELLED
                ? DeliveryStatus.CANCELLED : DeliveryStatus.RESOLVED);
        delivery.setResolvedAt(Instant.now());
        deliveryRepository.save(delivery);
    }

    @Override
    public void onCancelled(ApprovalChannelDeliveryEntity delivery) {
        editMessage(delivery, "🚫 Approval cancelled");
        delivery.setStatus(DeliveryStatus.CANCELLED);
        delivery.setResolvedAt(Instant.now());
        deliveryRepository.save(delivery);
    }

    /**
     * Append the verdict line to the original message and strip the inline keyboard.
     * Best-effort: Telegram rejects edits on messages older than 48h or with
     * identical text; the decision itself already landed, so failures only log.
     */
    private void editMessage(ApprovalChannelDeliveryEntity delivery, String verdict) {
        try {
            if (delivery.getMessageId() == null || delivery.getChatId() == null
                    || delivery.getCredentialId() == null) {
                return;
            }
            ToolsGateway gateway = toolsGatewayProvider.getIfAvailable();
            if (gateway == null) {
                return;
            }
            String base = delivery.getMessageText() != null ? delivery.getMessageText() : "";
            String text = base.isBlank() ? verdict : base + "\n\n" + verdict;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("chat_id", delivery.getChatId());
            params.put("message_id", delivery.getMessageId());
            params.put("text", text);
            params.put("reply_markup", Map.of("inline_keyboard", List.of()));
            ExecutionResult result = gateway.executeTool(
                    TOOL_EDIT_MESSAGE_TEXT, params, delivery.getTenantId(),
                    userCredential(delivery.getCredentialId()));
            if (!result.isSuccess()) {
                logger.info("[approval-telegram] message edit failed (cosmetic, decision already applied): {}",
                        result.getErrorMessage());
            }
        } catch (Exception ex) {
            meterRegistry.counter("approval.delegation.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.info("[approval-telegram] swallowed message edit: {}", ex.getMessage());
        }
    }

    /** Ack the button tap (small toast in Telegram). Used by the callback handler. */
    void answerCallbackQuery(ApprovalChannelDeliveryEntity delivery, String callbackQueryId,
                             String text, boolean showAlert) {
        try {
            if (delivery.getCredentialId() == null) {
                return;
            }
            ToolsGateway gateway = toolsGatewayProvider.getIfAvailable();
            if (gateway == null) {
                return;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("callback_query_id", callbackQueryId);
            params.put("text", text);
            if (showAlert) {
                params.put("show_alert", true);
            }
            gateway.executeTool(TOOL_ANSWER_CALLBACK_QUERY, params, delivery.getTenantId(),
                    userCredential(delivery.getCredentialId()));
        } catch (Exception ex) {
            logger.info("[approval-telegram] swallowed answerCallbackQuery: {}", ex.getMessage());
        }
    }

    // ========================================================================
    // HELPERS
    // ========================================================================

    private void fail(ApprovalChannelDeliveryEntity delivery, String error) {
        meterRegistry.counter("approval.delegation.errors", "type", "SendFailed").increment();
        logger.warn("[approval-telegram] delivery {} failed: {}", delivery.getId(), error);
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setError(error);
        deliveryRepository.save(delivery);
    }

    /**
     * Message body precedence: node's resolved messageTemplate, then the resolved
     * approvalContext (contextTemplate), then a generic line naming the workflow
     * (name pre-resolved by the emitter: the run entity is detached here and its
     * lazy workflow association must never be navigated on this async thread).
     * A split fan-out yields one signal (and one message) per item; the per-item
     * hint keeps N messages in the same chat distinguishable.
     */
    private String buildMessageText(SignalWaitEntity signal, ApprovalDelegationConfig config,
                                    String workflowName) {
        String body;
        if (config.message() != null && !config.message().isBlank()) {
            body = config.message();
        } else if (signal.getApprovalContext() != null && !signal.getApprovalContext().isBlank()) {
            body = signal.getApprovalContext();
        } else {
            String name = workflowName != null && !workflowName.isBlank() ? workflowName : "workflow";
            body = "Approval requested: " + name + " / " + signal.getNodeId();
        }
        Map<String, Object> splitItemData = signal.getSplitItemData();
        if (splitItemData != null && splitItemData.get("itemIndex") instanceof Number index) {
            body = body + "\n\nItem #" + (index.intValue() + 1);
        }
        return body;
    }

    private String verdictLine(SignalResolution resolution, String resolvedBy) {
        boolean viaTelegram = resolvedBy != null && resolvedBy.startsWith("telegram:");
        return switch (resolution) {
            case APPROVED -> viaTelegram ? "✅ Approved via Telegram" : "✅ Approved";
            case REJECTED -> viaTelegram ? "❌ Rejected via Telegram" : "❌ Rejected";
            case TIMEOUT -> "⏰ Timed out";
            case CANCELLED -> "🚫 Approval cancelled";
            default -> "Resolved: " + resolution;
        };
    }

    private Map<String, Object> userCredential(Long credentialId) {
        return Map.of(
                "__credentialSource__", "user",
                "__selectedCredentialId__", credentialId);
    }

    /** 128-bit SecureRandom, base64url without padding: 22 chars, DB-unique. */
    private static String generateToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** send_message projected output: { ok, result: { message_id, ... } }. */
    private static String extractMessageId(ExecutionResult result) {
        Object resultObj = result.output() != null ? result.output().get("result") : null;
        if (resultObj instanceof Map<?, ?> map && map.get("message_id") != null) {
            return String.valueOf(map.get("message_id"));
        }
        return null;
    }

    private String toJsonOrNull(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
