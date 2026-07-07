package com.apimarketplace.orchestrator.services.approvalchannel.telegram;

import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService;
import com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles a Telegram inline-button click ({@code callback_query}) whose
 * {@code callback_data} carries the delegated-approval capability
 * ({@code lcapr:<token>:a|r}). Entry points, both funneling here:
 * <ul>
 *   <li>the generic public webhook path ({@code WebhookController}): bots already
 *       webhooked to a workflow trigger get their approval clicks intercepted
 *       BEFORE workflow dispatch (the click never opens a spurious epoch);</li>
 *   <li>the dedicated public endpoint ({@code ApprovalCallbackController}) for
 *       bots not otherwise webhooked.</li>
 * </ul>
 *
 * The token is the sole capability: 128-bit SecureRandom, DB-unique, scoped to one
 * pending approval. Idempotency is layered: terminal delivery status short-circuits
 * here, and {@code UnifiedSignalService.resolveSignal}'s claim-before-process makes
 * concurrent clicks across replicas resolve exactly once. Everything is swallowed:
 * a callback must never propagate an error back to Telegram (non-2xx triggers
 * aggressive retries).
 *
 * <p><b>allowedUserIds is best-effort, not cryptographic.</b> The {@code from.id} it
 * checks comes from the unauthenticated webhook payload. On the dedicated endpoint a
 * deployment can pin authenticity with Telegram's {@code secret_token}; on the generic
 * {@code /webhook/&#123;token&#125;} path there is no such check, so anyone who learns the
 * lcapr token (e.g. a chat member extracting callback_data with a custom client) could
 * present a forged {@code from.id}. The real trust boundary is chat membership plus the
 * unguessable token; treat the allowlist as a guard against accidental taps, not as
 * authentication.
 */
@Component
public class TelegramApprovalCallbackHandler {

    private static final Logger logger = LoggerFactory.getLogger(TelegramApprovalCallbackHandler.class);

    private static final Pattern CALLBACK_DATA =
            Pattern.compile("^" + TelegramApprovalNotifier.CALLBACK_PREFIX + ":([A-Za-z0-9_-]{16,48}):(a|r)$");

    private final ApprovalChannelDeliveryRepository deliveryRepository;
    private final RunSignalResolutionService runSignalResolutionService;
    private final TelegramApprovalNotifier notifier;
    private final MeterRegistry meterRegistry;

    public TelegramApprovalCallbackHandler(ApprovalChannelDeliveryRepository deliveryRepository,
                                           RunSignalResolutionService runSignalResolutionService,
                                           TelegramApprovalNotifier notifier,
                                           MeterRegistry meterRegistry) {
        this.deliveryRepository = deliveryRepository;
        this.runSignalResolutionService = runSignalResolutionService;
        this.notifier = notifier;
        this.meterRegistry = meterRegistry;
    }

    /**
     * True when the payload is a Telegram update whose {@code callback_query.data}
     * matches the delegated-approval shape. Ordinary callback_query payloads (user
     * workflows with their own inline buttons) do NOT match and keep dispatching
     * to the workflow untouched.
     */
    public boolean isApprovalCallback(Map<String, Object> webhookPayload) {
        Map<String, Object> callbackQuery = callbackQueryOf(webhookPayload);
        if (callbackQuery == null) {
            return false;
        }
        Object data = callbackQuery.get("data");
        return data instanceof String s && CALLBACK_DATA.matcher(s).matches();
    }

    /** Handle the click end-to-end. Always swallows; always returns quickly. */
    public void handle(Map<String, Object> webhookPayload) {
        try {
            Map<String, Object> callbackQuery = callbackQueryOf(webhookPayload);
            if (callbackQuery == null) {
                return;
            }
            String data = String.valueOf(callbackQuery.get("data"));
            Matcher matcher = CALLBACK_DATA.matcher(data);
            if (!matcher.matches()) {
                return;
            }
            String token = matcher.group(1);
            boolean approve = "a".equals(matcher.group(2));
            String callbackQueryId = callbackQuery.get("id") != null
                    ? String.valueOf(callbackQuery.get("id")) : null;
            String fromId = fromIdOf(callbackQuery);

            Optional<ApprovalChannelDeliveryEntity> deliveryOpt = deliveryRepository.findByCallbackToken(token);
            if (deliveryOpt.isEmpty()) {
                // Unknown token: stale message from a purged run, or a forged guess.
                // Without a delivery there is no credential to even ack the tap with.
                meterRegistry.counter("approval.delegation.errors", "type", "UnknownCallbackToken").increment();
                logger.info("[approval-telegram] callback with unknown token (stale or forged), ignoring");
                return;
            }
            ApprovalChannelDeliveryEntity delivery = deliveryOpt.get();

            if (delivery.isTerminal()) {
                answer(delivery, callbackQueryId, "This approval was already decided.", false);
                return;
            }

            if (!delivery.getAllowedUserIds().isEmpty() && (fromId == null
                    || !delivery.getAllowedUserIds().contains(fromId))) {
                meterRegistry.counter("approval.delegation.errors", "type", "CallbackUserNotAllowed").increment();
                logger.info("[approval-telegram] callback from non-allowed user {} on delivery {}",
                        fromId, delivery.getId());
                answer(delivery, callbackQueryId, "You are not allowed to decide this approval.", true);
                return;
            }

            SignalResolution resolution = approve ? SignalResolution.APPROVED : SignalResolution.REJECTED;
            RunSignalResolutionService.Outcome outcome = runSignalResolutionService.resolveApproval(
                    delivery.getRunId(), delivery.getNodeId(), resolution,
                    Map.of("source", "telegram",
                            "telegramUserId", fromId != null ? fromId : "",
                            "chatId", delivery.getChatId() != null ? delivery.getChatId() : ""),
                    "telegram:" + (fromId != null ? fromId : "unknown"),
                    delivery.getEpoch(), delivery.getItemId());

            if (outcome.ok()) {
                logger.info("[approval-telegram] resolved approval via callback: run={}, node={}, resolution={}, by=telegram:{}",
                        delivery.getRunId(), delivery.getNodeId(), resolution, fromId);
                answer(delivery, callbackQueryId, approve ? "Approved ✅" : "Rejected ❌", false);
                // Message edit + delivery status flip ride the SignalResolvedEvent
                // (single edit path shared with in-app / MCP / timeout resolutions).
            } else {
                // Timeout race or double-click across replicas: the signal is gone.
                answer(delivery, callbackQueryId, "This approval was already decided.", false);
            }
        } catch (Exception ex) {
            meterRegistry.counter("approval.delegation.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[approval-telegram] swallowed callback handling: {}", ex.getMessage());
        }
    }

    private void answer(ApprovalChannelDeliveryEntity delivery, String callbackQueryId,
                        String text, boolean showAlert) {
        if (callbackQueryId != null) {
            // Re-bind the delivery's workspace scope: this thread serves a public
            // webhook (no org header), and the catalog call's default-credential
            // fallback must see the org-shared Telegram credential of a workspace
            // run. Null orgId = personal scope (no-op binding).
            com.apimarketplace.common.web.TenantResolver.runWithOrgScope(delivery.getOrgId(),
                    () -> notifier.answerCallbackQuery(delivery, callbackQueryId, text, showAlert));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> callbackQueryOf(Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        Object cq = payload.get("callback_query");
        return cq instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String fromIdOf(Map<String, Object> callbackQuery) {
        Object from = callbackQuery.get("from");
        if (from instanceof Map<?, ?> fromMap && fromMap.get("id") != null) {
            return String.valueOf(fromMap.get("id"));
        }
        return null;
    }
}
