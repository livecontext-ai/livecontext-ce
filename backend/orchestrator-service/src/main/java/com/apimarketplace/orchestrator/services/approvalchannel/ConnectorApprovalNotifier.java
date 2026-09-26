package com.apimarketplace.orchestrator.services.approvalchannel;

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
import com.apimarketplace.orchestrator.services.approvalchannel.telegram.TelegramApprovalNotifier;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChoiceOption;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The approval node's delivery for every provider that has a {@link ChatChannelConnector} but no
 * dedicated notifier: Slack, Discord, WhatsApp and Teams.
 *
 * <p>Telegram keeps {@link TelegramApprovalNotifier}, which predates the connectors and sends
 * photos, something the connector contract does not carry. Everything else goes through the same
 * connector the dialogue channel uses, so an approval looks and behaves exactly like an agent's
 * question on that provider, and a press comes back through the same inbound route with the same
 * {@code lcapr:} payload, decided by {@link WorkflowApprovalPressService}.
 *
 * <p><b>Where it goes when the node does not say.</b> The node's destination and credential are
 * both optional here. Missing, they are taken from what the workspace already connected on that
 * provider: its default destination when that is on this provider, else the first working one.
 * That is what makes "send approvals to Slack" a one-field setting once Slack is connected.
 *
 * <p>Best-effort like every notifier: a failure lands on the delivery row and a counter, never on
 * the run, which stays decidable in the app.
 */
@Component
public class ConnectorApprovalNotifier {

    private static final Logger logger = LoggerFactory.getLogger(ConnectorApprovalNotifier.class);

    static final String DEFAULT_APPROVE_LABEL = "Approve";
    static final String DEFAULT_REJECT_LABEL = "Reject";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApprovalChannelDeliveryRepository deliveryRepository;
    private final SignalWaitRepository signalWaitRepository;
    private final ChatChannelBotRepository botRepository;
    private final ChatChannelLinkRepository linkRepository;
    private final ChatChannelConnectorRegistry connectors;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final com.apimarketplace.orchestrator.services.channel.ChatChannelService channelService;

    /** Product analytics; optional so a unit test can build the notifier without it. */
    @Autowired(required = false)
    private EngagementAnalyticsEmitter analytics;

    public ConnectorApprovalNotifier(ApprovalChannelDeliveryRepository deliveryRepository,
                                     SignalWaitRepository signalWaitRepository,
                                     ChatChannelBotRepository botRepository,
                                     ChatChannelLinkRepository linkRepository,
                                     ChatChannelConnectorRegistry connectors,
                                     ObjectMapper objectMapper,
                                     MeterRegistry meterRegistry,
                                     com.apimarketplace.orchestrator.services.channel.ChatChannelService channelService) {
        this.deliveryRepository = deliveryRepository;
        this.signalWaitRepository = signalWaitRepository;
        this.botRepository = botRepository;
        this.linkRepository = linkRepository;
        this.connectors = connectors;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.channelService = channelService;
    }

    /** This notifier bound to one provider, in the shape the notifier registry hands out. */
    public ApprovalChannelNotifier boundTo(ChatChannelConnector connector) {
        return new ApprovalChannelNotifier() {
            @Override
            public String channelId() {
                return connector.channelId();
            }

            @Override
            public void notifyPending(SignalWaitEntity signal, ApprovalDelegationConfig config,
                                      WorkflowRunEntity run, String workflowName) {
                ConnectorApprovalNotifier.this.notifyPending(connector, signal, config, run, workflowName);
            }

            @Override
            public void onResolved(ApprovalChannelDeliveryEntity delivery, SignalResolution resolution,
                                   String resolvedBy) {
                ConnectorApprovalNotifier.this.close(connector, delivery, verdictLine(connector, resolution, resolvedBy),
                        resolution == SignalResolution.CANCELLED ? DeliveryStatus.CANCELLED : DeliveryStatus.RESOLVED);
            }

            @Override
            public void onCancelled(ApprovalChannelDeliveryEntity delivery) {
                ConnectorApprovalNotifier.this.close(connector, delivery, "Approval cancelled",
                        DeliveryStatus.CANCELLED);
            }
        };
    }

    void notifyPending(ChatChannelConnector connector, SignalWaitEntity signal, ApprovalDelegationConfig config,
                       WorkflowRunEntity run, String workflowName) {
        String channel = connector.channelId();
        boolean reported = false;
        try {
            Destination destination = destinationFor(channel, config, run.getOrgId());
            String token = generateToken();
            int inserted = deliveryRepository.insertPendingIfAbsent(
                    signal.getId(), channel, TokenAtRest.encrypt(token), TokenAtRest.hash(token),
                    run.getTenantId(), run.getOrgId(), signal.getRunId(), signal.getNodeId(),
                    signal.getItemId() != null ? signal.getItemId() : "0", signal.getEpoch(),
                    destination.credentialId(), destination.chatId(),
                    toJsonOrNull(destination.allowedUserIds()), Instant.now());
            if (inserted == 0) {
                return; // replay or another replica: that dispatch owns the delivery
            }
            Optional<ApprovalChannelDeliveryEntity> deliveryOpt =
                    deliveryRepository.findByCallbackTokenHash(TokenAtRest.hash(token));
            if (deliveryOpt.isEmpty()) {
                return;
            }
            ApprovalChannelDeliveryEntity delivery = deliveryOpt.get();
            if (destination.problem() != null) {
                fail(delivery, destination.problem());
                // No account or no chat to write to is "nothing connected"; a destination that
                // exists but refuses (an allow-list conflict) is a failed delivery.
                reported = trackDelivered(run, channel, destination.credentialId() == null || destination.chatId() == null
                        ? EngagementAnalyticsEmitter.RequestStatus.NO_CHANNEL
                        : EngagementAnalyticsEmitter.RequestStatus.FAILED);
                return;
            }

            String text = capped(buildMessageText(signal, config, workflowName), connector.maxDecisionTextChars());
            List<ChoiceOption> buttons = List.of(
                    new ChoiceOption(labelOr(config.approveLabel(), DEFAULT_APPROVE_LABEL),
                            TelegramApprovalNotifier.CALLBACK_PREFIX + ":" + token + ":a"),
                    new ChoiceOption(labelOr(config.rejectLabel(), DEFAULT_REJECT_LABEL),
                            TelegramApprovalNotifier.CALLBACK_PREFIX + ":" + token + ":r"));
            Outcome<String> sent = connector.sendChoiceRequest(run.getTenantId(), destination.credentialId(),
                    destination.chatId(), text, buttons);
            if (!sent.ok()) {
                fail(delivery, sent.error());
                reported = trackDelivered(run, channel, EngagementAnalyticsEmitter.RequestStatus.FAILED);
                return;
            }
            delivery.setStatus(DeliveryStatus.SENT);
            delivery.setMessageText(text);
            delivery.setMessageId(sent.value());
            delivery.setSentAt(Instant.now());
            deliveryRepository.save(delivery);
            reported = trackDelivered(run, channel, EngagementAnalyticsEmitter.RequestStatus.SENT);
            logger.info("[approval-{}] sent approval message: signal={}, chat={}, messageId={}",
                    channel, signal.getId(), destination.chatId(), sent.value());
            // Decided while the send was in flight: the resolution fan-out only closes SENT rows and
            // has already run, so close it here or its buttons stay live.
            signalWaitRepository.findById(signal.getId()).ifPresent(current -> {
                if (current.isResolved()) {
                    close(connector, delivery, verdictLine(connector, current.getResolution(), current.getResolvedBy()),
                            DeliveryStatus.RESOLVED);
                } else if (current.isCancelled()) {
                    close(connector, delivery, "Approval cancelled", DeliveryStatus.CANCELLED);
                }
            });
        } catch (Exception ex) {
            meterRegistry.counter("approval.delegation.errors", "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[approval-{}] swallowed send for signal {}: {}", channel, signal.getId(), ex.getMessage());
            if (!reported) {
                // A send that THROWS instead of returning a failed result is a failed
                // delivery too; a row already reported (e.g. SENT, then a post-send read
                // throwing) is not counted twice.
                trackDelivered(run, channel, EngagementAnalyticsEmitter.RequestStatus.FAILED);
            }
        }
    }

    /** Say what was decided on the message and take its buttons away; cosmetic, never fatal. */
    void close(ChatChannelConnector connector, ApprovalChannelDeliveryEntity delivery, String verdict,
               DeliveryStatus status) {
        try {
            if (delivery.getMessageId() != null && delivery.getChatId() != null) {
                Outcome<Void> closed = connector.closeDecisionRequest(delivery.getTenantId(),
                        delivery.getCredentialId(), delivery.getChatId(), delivery.getMessageId(),
                        delivery.getMessageText(), verdict);
                if (!closed.ok()) {
                    logger.info("[approval-{}] closing edit failed (cosmetic, decision already applied): {}",
                            connector.channelId(), closed.error());
                }
            }
        } catch (Exception ex) {
            meterRegistry.counter("approval.delegation.errors", "type", ex.getClass().getSimpleName()).increment();
            logger.info("[approval-{}] swallowed closing edit: {}", connector.channelId(), ex.getMessage());
        }
        delivery.setStatus(status);
        delivery.setResolvedAt(Instant.now());
        deliveryRepository.save(delivery);
    }

    /**
     * Where the approval goes: what the node said, completed from what the workspace connected.
     *
     * @param allowedUserIds who may decide, EFFECTIVE: the destination's restriction as it was
     *                       connected, narrowed by the node's own list; empty = anyone in the chat
     * @param problem        set, with the others possibly null, when nothing usable is found; the
     *                       delivery row is still written so the failure is recorded where the
     *                       person looks for it
     * @param allowListConflict the node names people, none of whom may decide at this destination:
     *                       sending would either open it up or reach nobody, so it is not sent
     */
    public record Destination(Long credentialId, String chatId, List<String> allowedUserIds, String problem,
                              boolean allowListConflict) {

        public Destination {
            allowedUserIds = allowedUserIds == null ? List.of() : List.copyOf(allowedUserIds);
        }

        public Destination(Long credentialId, String chatId, List<String> allowedUserIds, String problem) {
            this(credentialId, chatId, allowedUserIds, problem, false);
        }
    }

    public Destination destinationFor(String channel, ApprovalDelegationConfig config, String orgId) {
        if (config.usesDestination()) {
            return pickedDestination(channel, config, orgId);
        }
        Long credentialId = config.credentialId();
        // As the provider writes it: a WhatsApp number typed "+33 6 12..." is sent to 33612..., and
        // that is what a press comes back from and what a connected destination stores. Kept as
        // typed, every press on it would be refused as coming from another chat.
        String typed = config.chatId() != null && !config.chatId().isBlank() ? config.chatId().trim() : null;
        String chatId = typed == null ? null
                : connectors.forChannel(channel).map(c -> c.normalizeChatId(typed)).filter(s -> s != null && !s.isBlank())
                        .orElse(typed);
        List<ChatChannelBotEntity> bots = orgId == null ? List.of()
                : botRepository.findByOrganizationIdOrderByCreatedAtAsc(orgId).stream()
                        .filter(bot -> channel.equalsIgnoreCase(bot.getChannel()))
                        .filter(bot -> credentialId == null || credentialId.equals(bot.getCredentialId()))
                        .toList();
        List<ChatChannelLinkEntity> links = bots.isEmpty() ? List.of()
                : linkRepository.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(orgId).stream()
                        .filter(ChatChannelLinkEntity::isActive)
                        .filter(link -> bots.stream().anyMatch(bot -> bot.getId().equals(link.getBotId())))
                        .toList();
        if (chatId != null) {
            // A named chat that is also a connected destination keeps who may decide there: the
            // restriction belongs to the chat, and naming it in a node must not open it up.
            Optional<ChatChannelLinkEntity> connected = links.stream()
                    .filter(link -> chatId.equals(link.getChatId())).findFirst();
            Long resolved = credentialId != null ? credentialId
                    : connected.map(link -> credentialOf(bots, link)).orElse(bots.isEmpty() ? null
                            : bots.get(0).getCredentialId());
            if (resolved == null) {
                return new Destination(null, chatId, config.allowedUserIds(), noAccount(channel));
            }
            return withAllowList(resolved, chatId, config.allowedUserIds(),
                    connected.map(ChatChannelLinkEntity::getAllowedUserIds).orElse(List.of()));
        }
        if (bots.isEmpty()) {
            return new Destination(credentialId, null, List.of(), noAccount(channel));
        }
        if (links.isEmpty()) {
            return new Destination(bots.get(0).getCredentialId(), null, List.of(), "The " + channel + " account "
                    + "is connected but has no working destination. Connect a destination for it, or give "
                    + "the approval node a destination id.");
        }
        ChatChannelLinkEntity link = links.get(0);
        return withAllowList(credentialOf(bots, link), link.getChatId(), config.allowedUserIds(),
                link.getAllowedUserIds());
    }

    /**
     * A destination picked like a credential (an id, or the workspace default): it decides the
     * account and the chat, and the node's own credential and chat id are ignored.
     *
     * <p>Never replaced by another chat. When it is gone, the delivery is recorded as failed with
     * a sentence saying so, and the approval stays decidable in the app: sending it to the default
     * instead would ask people nobody picked for this approval.
     */
    private Destination pickedDestination(String channel, ApprovalDelegationConfig config, String orgId) {
        if (config.destinationIsMalformed()) {
            return new Destination(null, null, List.of(), "The approval node's destination ('" + config.linkId()
                    + "') is not a destination id. Pick one in the node, or use the workspace default.");
        }
        Optional<com.apimarketplace.orchestrator.services.channel.ChatChannelService.ResolvedTarget> target =
                channelService.resolveFor(orgId, config.destinationId());
        if (target.isEmpty()) {
            return new Destination(null, null, List.of(), config.destinationId() == null
                    ? "This workspace has no default destination any more. Connect one in Settings > Channels, "
                        + "or pick another destination in the approval node."
                    : "The destination picked in this approval node is no longer connected. Pick another one "
                        + "in the node, or use the workspace default.");
        }
        var picked = target.get();
        if (!channel.equalsIgnoreCase(picked.channel())) {
            // Only reachable if the default moved to another service between routing and sending.
            return new Destination(null, null, List.of(), "The approval node's destination is on "
                    + picked.channel() + ", not " + channel + ". It will be sent there on the next approval.");
        }
        return withAllowList(picked.credentialId(), picked.chatId(), config.allowedUserIds(),
                picked.allowedUserIds());
    }

    /**
     * Who may decide. The destination's restriction is the outer bound: a node can narrow it, never
     * widen it, and naming no one keeps it as it is. A node naming only people the destination does
     * not allow is a conflict, reported instead of sent, because either answer (open to the node's
     * people, or to nobody) would be a decision nobody made.
     */
    static Destination withAllowList(Long credentialId, String chatId, List<String> node, List<String> destination) {
        if (destination.isEmpty() || node.isEmpty()) {
            return new Destination(credentialId, chatId, node.isEmpty() ? destination : node, null);
        }
        List<String> both = node.stream().filter(destination::contains).toList();
        if (both.isEmpty()) {
            return new Destination(credentialId, chatId, List.of(), "None of the people the approval node "
                    + "allows (" + String.join(", ", node) + ") may decide at this destination, which is "
                    + "restricted to " + String.join(", ", destination) + ". Change the node's allowed people "
                    + "or the destination's.", true);
        }
        return new Destination(credentialId, chatId, both, null);
    }

    private static Long credentialOf(List<ChatChannelBotEntity> bots, ChatChannelLinkEntity link) {
        return bots.stream().filter(bot -> bot.getId().equals(link.getBotId()))
                .map(ChatChannelBotEntity::getCredentialId).findFirst().orElse(null);
    }

    private static String noAccount(String channel) {
        return "No " + channel + " account is connected in this workspace. Connect one in Settings > "
                + "Channels (or ask the assistant to set it up), or give the approval node its credential "
                + "and destination.";
    }

    // ---- helpers ----

    /** distinct_id = the run's owner, the person the approval waits on. */
    private boolean trackDelivered(WorkflowRunEntity run, String channel,
                                EngagementAnalyticsEmitter.RequestStatus status) {
        if (analytics != null) {
            analytics.channelRequestDelivered(run.getTenantId(), run.getOrgId(),
                    EngagementAnalyticsEmitter.RequestType.WORKFLOW_APPROVAL, channel, status);
        }
        return true;
    }

    private void fail(ApprovalChannelDeliveryEntity delivery, String error) {
        meterRegistry.counter("approval.delegation.errors", "type", "SendFailed").increment();
        logger.warn("[approval-{}] delivery {} failed: {}", delivery.getChannel(), delivery.getId(), error);
        delivery.setStatus(DeliveryStatus.FAILED);
        delivery.setError(error);
        deliveryRepository.save(delivery);
    }

    /** Same precedence as Telegram: the node's message, then the approval context, then a generic line. */
    static String buildMessageText(SignalWaitEntity signal, ApprovalDelegationConfig config, String workflowName) {
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

    static String verdictLine(ChatChannelConnector connector, SignalResolution resolution, String resolvedBy) {
        String channel = connector.channelId();
        boolean viaChannel = resolvedBy != null && resolvedBy.startsWith(channel + ":");
        String via = viaChannel ? " via " + Character.toUpperCase(channel.charAt(0)) + channel.substring(1) : "";
        if (resolution == null) {
            return "Resolved";
        }
        return switch (resolution) {
            case APPROVED -> "Approved" + via;
            case REJECTED -> "Rejected" + via;
            case TIMEOUT -> "Timed out";
            case CANCELLED -> "Approval cancelled";
            default -> "Resolved: " + resolution;
        };
    }

    private static String capped(String text, int max) {
        return text != null && max > 0 && text.length() > max ? text.substring(0, max) : text;
    }

    private static String labelOr(String value, String def) {
        return value != null && !value.isBlank() ? value : def;
    }

    private static String generateToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
