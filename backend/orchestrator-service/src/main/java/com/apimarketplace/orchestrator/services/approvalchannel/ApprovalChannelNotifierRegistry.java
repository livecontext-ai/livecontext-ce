package com.apimarketplace.orchestrator.services.approvalchannel;

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
 */
@Component
public class ApprovalChannelNotifierRegistry {

    private final Map<String, ApprovalChannelNotifier> byChannel;

    public ApprovalChannelNotifierRegistry(List<ApprovalChannelNotifier> notifiers) {
        this.byChannel = notifiers.stream().collect(Collectors.toUnmodifiableMap(
                n -> n.channelId().toLowerCase(Locale.ROOT), Function.identity()));
    }

    public Optional<ApprovalChannelNotifier> forChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byChannel.get(channel.toLowerCase(Locale.ROOT)));
    }
}
