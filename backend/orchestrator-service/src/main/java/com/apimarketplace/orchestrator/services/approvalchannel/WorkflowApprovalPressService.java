package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.common.security.token.TokenAtRest;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.services.channel.PressOrigin;
import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.execution.v2.services.RunSignalResolutionService;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository;
import com.apimarketplace.orchestrator.security.OrchestratorTokenAtRestBackfill;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A press on a workflow approval node's button, whichever chat it came from.
 *
 * <p>Extracted from the Telegram handler, where it lived when Telegram was the only channel.
 * Every provider's inbound path now ends here, so the rules that decide a workflow approval from
 * a chat are written once: the token is the capability, a settled delivery is answered "already
 * decided", the delivery's allow-list is checked, and the resolution goes through
 * {@link RunSignalResolutionService}, whose claim-before-process makes a double press across
 * replicas harmless.
 *
 * <p>What it does NOT do is acknowledge the press. That is provider-specific (a Telegram toast, a
 * Slack ephemeral, the HTTP response itself on Discord), so it returns what to say and lets the
 * caller say it.
 */
@Service
public class WorkflowApprovalPressService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowApprovalPressService.class);

    private final ApprovalChannelDeliveryRepository deliveryRepository;
    private final RunSignalResolutionService runSignalResolutionService;
    private final MeterRegistry meterRegistry;

    /**
     * Read-only plaintext fallback for a delivery row still stored in clear (pre-2026-09-17) when
     * its hash lookup misses. Optional so a unit test can build the service without a database.
     */
    @Autowired(required = false)
    private OrchestratorTokenAtRestBackfill tokenBackfill;

    /** Product analytics; optional so a unit test can build the service without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    public WorkflowApprovalPressService(ApprovalChannelDeliveryRepository deliveryRepository,
                                        RunSignalResolutionService runSignalResolutionService,
                                        MeterRegistry meterRegistry) {
        this.deliveryRepository = deliveryRepository;
        this.runSignalResolutionService = runSignalResolutionService;
        this.meterRegistry = meterRegistry;
    }

    /**
     * What a press achieved.
     *
     * @param handled  false only for an unknown token: there is no delivery, so not even a
     *                 credential to acknowledge the press with
     * @param delivery the delivery, for the caller's acknowledgement (its workspace and credential)
     */
    public record PressOutcome(boolean handled, String replyToUser, boolean asAlert,
                               ApprovalChannelDeliveryEntity delivery) {

        static PressOutcome unknown() {
            return new PressOutcome(false, null, false, null);
        }
    }

    public PressOutcome press(PressOrigin origin, String token, boolean approve, String fromUserId) {
        String channel = origin.channel();
        Optional<ApprovalChannelDeliveryEntity> deliveryOpt = TokenAtRest.lookup(token,
                deliveryRepository::findByCallbackTokenHash,
                t -> tokenBackfill == null ? Optional.empty()
                        : tokenBackfill.findLegacy(OrchestratorTokenAtRestBackfill.APPROVAL_CALLBACK_TOKENS, t,
                                deliveryRepository::findLegacyPlaintext));
        if (deliveryOpt.isEmpty()) {
            // Unknown token: stale message from a purged run, or a forged guess.
            meterRegistry.counter("approval.delegation.errors", "type", "UnknownCallbackToken").increment();
            logger.info("[approval-{}] callback with unknown token (stale or forged), ignoring", channel);
            return PressOutcome.unknown();
        }
        ApprovalChannelDeliveryEntity delivery = deliveryOpt.get();
        if (!origin.admits(delivery.getChannel(), delivery.getCredentialId(), delivery.getChatId())) {
            // A token only ever goes out on one provider, one bot, one chat. Seen anywhere else it
            // was copied there, and the allow-list, which holds ids of the original chat, would
            // mean nothing against a presser id the copier chose.
            meterRegistry.counter("approval.delegation.errors", "type", "CallbackOriginMismatch").increment();
            logger.info("[approval-{}] callback for a delivery sent elsewhere ({}), ignoring", channel,
                    delivery.getChannel());
            return PressOutcome.unknown();
        }

        if (delivery.isTerminal()) {
            return new PressOutcome(true, "This approval was already decided.", false, delivery);
        }
        if (!delivery.getAllowedUserIds().isEmpty() && (fromUserId == null
                || !delivery.getAllowedUserIds().contains(fromUserId))) {
            meterRegistry.counter("approval.delegation.errors", "type", "CallbackUserNotAllowed").increment();
            logger.info("[approval-{}] callback from non-allowed user {} on delivery {}",
                    channel, fromUserId, delivery.getId());
            return new PressOutcome(true, "You are not allowed to decide this approval.", true, delivery);
        }

        SignalResolution resolution = approve ? SignalResolution.APPROVED : SignalResolution.REJECTED;
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", channel);
        metadata.put("channelUserId", fromUserId != null ? fromUserId : "");
        if ("telegram".equals(channel)) {
            // Kept under its original key for Telegram so the recorded resolution of a Telegram
            // approval reads exactly as it did before other providers existed.
            metadata.put("telegramUserId", fromUserId != null ? fromUserId : "");
        }
        metadata.put("chatId", delivery.getChatId() != null ? delivery.getChatId() : "");
        RunSignalResolutionService.Outcome outcome = runSignalResolutionService.resolveApproval(
                delivery.getRunId(), delivery.getNodeId(), resolution, metadata,
                channel + ":" + (fromUserId != null ? fromUserId : "unknown"),
                delivery.getEpoch(), delivery.getItemId());

        if (outcome.ok()) {
            logger.info("[approval-{}] resolved approval via callback: run={}, node={}, resolution={}, by={}:{}",
                    channel, delivery.getRunId(), delivery.getNodeId(), resolution, channel, fromUserId);
            // Message edit + delivery status flip ride the SignalResolvedEvent (single edit path
            // shared with in-app / MCP / timeout resolutions).
            if (analytics != null) {
                // The run's owner: the presser is a chat user id, not a platform user.
                analytics.channelRequestAnswered(delivery.getTenantId(), delivery.getOrgId(),
                        EngagementAnalyticsEmitter.RequestType.WORKFLOW_APPROVAL, channel,
                        approve ? EngagementAnalyticsEmitter.Decision.APPROVED
                                : EngagementAnalyticsEmitter.Decision.REJECTED,
                        EngagementAnalyticsEmitter.Input.BUTTON);
            }
            return new PressOutcome(true, approve ? "Approved ✅" : "Rejected ❌", false, delivery);
        }
        // Timeout race or double press across replicas: the signal is gone.
        return new PressOutcome(true, "This approval was already decided.", false, delivery);
    }
}
