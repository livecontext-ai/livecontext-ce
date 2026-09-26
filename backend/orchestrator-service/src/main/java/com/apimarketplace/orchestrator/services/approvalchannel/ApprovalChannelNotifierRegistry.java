package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry of {@link ApprovalChannelNotifier} implementations keyed by channel id.
 * Spring injects every notifier bean; an unknown channel in a delegation block
 * resolves to empty (the emitter logs + counts it, the approval stays in-app only).
 *
 * <p>A channel with no dedicated notifier bean but a chat-channel connector (Slack, Discord,
 * WhatsApp, Teams) is served by {@link ConnectorApprovalNotifier} bound to that connector, so a
 * provider added as a connector is an approval channel too, with nothing to register twice.
 */
@Component
public class ApprovalChannelNotifierRegistry {

    private final Map<String, ApprovalChannelNotifier> byChannel;

    private final ChatChannelConnectorRegistry connectors;
    private final ConnectorApprovalNotifier connectorNotifier;

    public ApprovalChannelNotifierRegistry(List<ApprovalChannelNotifier> notifiers,
                                           ChatChannelConnectorRegistry connectors,
                                           ConnectorApprovalNotifier connectorNotifier) {
        this.byChannel = notifiers.stream().collect(Collectors.toUnmodifiableMap(
                n -> n.channelId().toLowerCase(Locale.ROOT), Function.identity()));
        this.connectors = connectors;
        this.connectorNotifier = connectorNotifier;
    }

    public Optional<ApprovalChannelNotifier> forChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return Optional.empty();
        }
        ApprovalChannelNotifier dedicated = byChannel.get(channel.toLowerCase(Locale.ROOT));
        if (dedicated != null) {
            return Optional.of(dedicated);
        }
        return connectors.forChannel(channel).map(connectorNotifier::boundTo);
    }
}
