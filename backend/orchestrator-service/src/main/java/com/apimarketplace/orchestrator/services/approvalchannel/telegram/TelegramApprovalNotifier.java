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
 * never transits the orchestrator, only the credential id does). With an image
 * delegation the message is sent as ONE photo (image + caption + the same buttons)
 * and the verdict edit goes through editMessageCaption. The button
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

    // Default inline-button labels, used when the approval node sets no custom label.
    static final String DEFAULT_APPROVE_LABEL = "✅ Approve";
    static final String DEFAULT_REJECT_LABEL = "❌ Reject";

    // Tool slugs of the catalog Telegram integration (apiSlug/toolSlug format,
    // matching the ids workflow mcp: steps use for the same operations).
    private static final ToolRef TOOL_SEND_MESSAGE = new ToolRef("telegram/telegram-send-message", 1);
    private static final ToolRef TOOL_SEND_PHOTO = new ToolRef("telegram/telegram-send-photo", 1);
    private static final ToolRef TOOL_EDIT_MESSAGE_TEXT = new ToolRef("telegram/telegram-edit-message-text", 1);
    private static final ToolRef TOOL_EDIT_MESSAGE_CAPTION = new ToolRef("telegram/telegram-edit-message-caption", 1);
    static final ToolRef TOOL_ANSWER_CALLBACK_QUERY = new ToolRef("telegram/telegram-answer-callback-query", 1);

    /** Telegram caps photo captions at 1024 chars (plain text messages allow 4096). */
    private static final int TELEGRAM_CAPTION_MAX_CHARS = 1024;
    /**
     * Room reserved at SEND time for the verdict line appended on resolution
     * ("\n\n" + the longest verdict, e.g. "✅ Approved via Telegram"). Without it a
     * caption capped at exactly 1024 leaves the edit nothing to append: the keyboard
     * still strips but the approver never sees the verdict on the photo.
     */
    private static final int CAPTION_VERDICT_RESERVE_CHARS = 64;

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
                    run.getTenantId(), run.getOrgId(), signal.getRunId(), signal.getNodeId(),
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

            // Only chatId is truly required. credentialId is optional: when absent the
            // catalog call carries NO credential markers and the catalog applies its
            // implicit fallback (the tenant's own telegram credential), exactly like an
            // mcp:telegram step with no explicit credential selected. This is the common
            // agent-built shape: the builder LLM rarely knows the numeric credential id.
            if (config.chatId() == null || config.chatId().isBlank()) {
                fail(delivery, "Delegation misconfigured: missing chatId");
                return;
            }
            ToolsGateway gateway = toolsGatewayProvider.getIfAvailable();
            if (gateway == null) {
                fail(delivery, "Catalog tools gateway unavailable in this deployment");
                return;
            }

            String text = buildMessageText(signal, config, workflowName);
            // Button TEXT is customizable per approval node (config.approveLabel()/
            // rejectLabel()); blank/null falls back to the defaults. Only the label
            // changes - the callback_data (":a"/":r") and resolution semantics are fixed.
            String approveLabel = labelOr(config.approveLabel(), DEFAULT_APPROVE_LABEL);
            String rejectLabel = labelOr(config.rejectLabel(), DEFAULT_REJECT_LABEL);
            Map<String, Object> replyMarkup = Map.of("inline_keyboard", List.of(List.of(
                    Map.of("text", approveLabel, "callback_data", CALLBACK_PREFIX + ":" + token + ":a"),
                    Map.of("text", rejectLabel, "callback_data", CALLBACK_PREFIX + ":" + token + ":r"))));

            // Image delegation: ONE photo message carrying the image, the text as
            // caption AND the buttons (a photo plus a separate text message would
            // detach the buttons from the image). The image rides exactly as resolved
            // at yield: a FileRef Map (the catalog's multipart encoder uploads the
            // bytes) or a String HTTP URL / file_id passed through verbatim.
            boolean isPhoto = config.image() != null;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("chat_id", config.chatId());
            if (isPhoto) {
                params.put("photo", config.image());
                // Reserve verdict room: the resolution edit appends "\n\n<verdict>"
                // to this same caption and must still fit under Telegram's cap.
                text = capCaption(text, TELEGRAM_CAPTION_MAX_CHARS - CAPTION_VERDICT_RESERVE_CHARS);
                params.put("caption", text);
            } else {
                params.put("text", text);
            }
            params.put("reply_markup", replyMarkup);

            ExecutionResult result = gateway.executeTool(
                    isPhoto ? TOOL_SEND_PHOTO : TOOL_SEND_MESSAGE, params,
                    run.getTenantId(), userCredential(config.credentialId()));

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
                        ? result.getErrorMessage()
                        : isPhoto ? "Telegram send_photo failed" : "Telegram send_message failed");
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
     * Photo deliveries (image delegation) edit the CAPTION: Telegram rejects
     * editMessageText on a photo message ("there is no text in the message to edit").
     * Best-effort: Telegram rejects edits on messages older than 48h or with
     * identical text; the decision itself already landed, so failures only log.
     */
    private void editMessage(ApprovalChannelDeliveryEntity delivery, String verdict) {
        try {
            if (delivery.getMessageId() == null || delivery.getChatId() == null) {
                return;
            }
            ToolsGateway gateway = toolsGatewayProvider.getIfAvailable();
            if (gateway == null) {
                return;
            }
            boolean isPhoto = wasPhotoDelivery(delivery);
            String base = delivery.getMessageText() != null ? delivery.getMessageText() : "";
            String text = base.isBlank() ? verdict : base + "\n\n" + verdict;
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("chat_id", delivery.getChatId());
            params.put("message_id", delivery.getMessageId());
            if (isPhoto) {
                // Full cap here: the send already reserved verdict room, so the
                // appended verdict fits; the guard only protects the degenerate case.
                params.put("caption", capCaption(text, TELEGRAM_CAPTION_MAX_CHARS));
            } else {
                params.put("text", text);
            }
            params.put("reply_markup", Map.of("inline_keyboard", List.of()));
            ExecutionResult result = gateway.executeTool(
                    isPhoto ? TOOL_EDIT_MESSAGE_CAPTION : TOOL_EDIT_MESSAGE_TEXT, params,
                    delivery.getTenantId(), userCredential(delivery.getCredentialId()));
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

    /**
     * True when the delivery's original message was a photo (image delegation).
     * Re-derived from the signal's persisted delegation block via the existing
     * signal row (no delivery-column migration): the delegation image was resolved
     * and embedded in signal_config at yield, so it is authoritative for what was
     * sent. Best-effort like the edit itself: any lookup failure falls back to the
     * text edit, which then fails cosmetically like any other edit error.
     */
    private boolean wasPhotoDelivery(ApprovalChannelDeliveryEntity delivery) {
        try {
            if (delivery.getSignalWaitId() == null) {
                return false;
            }
            return signalWaitRepository.findById(delivery.getSignalWaitId())
                    .map(SignalWaitEntity::getSignalConfig)
                    .map(ApprovalDelegationConfig::fromSignalConfig)
                    .map(config -> config.image() != null)
                    .orElse(false);
        } catch (Exception ex) {
            logger.debug("[approval-telegram] photo-delivery lookup failed, assuming text: {}", ex.getMessage());
            return false;
        }
    }

    /** Trim a caption to the given cap so a long approval context never fails the send/edit. */
    private static String capCaption(String text, int maxChars) {
        return text != null && text.length() > maxChars
                ? text.substring(0, maxChars)
                : text;
    }

    /** The custom label when set (non-blank), otherwise the channel default. */
    private static String labelOr(String value, String def) {
        return value != null && !value.isBlank() ? value : def;
    }

    /** Ack the button tap (small toast in Telegram). Used by the callback handler. */
    void answerCallbackQuery(ApprovalChannelDeliveryEntity delivery, String callbackQueryId,
                             String text, boolean showAlert) {
        try {
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

    /**
     * Credential markers for the catalog call. Null when no explicit credential is
     * pinned: the catalog then applies its implicit fallback (the tenant's own
     * telegram credential), the same resolution an mcp:telegram step gets when the
     * author selected no credential. An explicit id pins a specific bot (strict:
     * the catalog will NOT fall back past it).
     */
    private Map<String, Object> userCredential(Long credentialId) {
        if (credentialId == null) {
            return null;
        }
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

    /** send_message AND send_photo projected output: { ok, result: { message_id, ... } }. */
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
