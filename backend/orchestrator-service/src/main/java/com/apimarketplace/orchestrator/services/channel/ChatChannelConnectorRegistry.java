package com.apimarketplace.orchestrator.services.channel;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Registry of {@link ChatChannelConnector} beans keyed by channel id, mirroring
 * {@code ApprovalChannelNotifierRegistry} on the delivery side.
 *
 * <p>This is also the validator for the {@code channel} column, which carries no
 * DB CHECK constraint on purpose: what can be connected is what can actually
 * deliver, and only the running application knows that.
 */
@Component
public class ChatChannelConnectorRegistry {

    /**
     * The providers this product ships a connector for, for places that validate a channel id
     * without a running application (the workflow validator). {@code ChatChannelConnectorRegistryTest}
     * pins it to the connector beans, so the two cannot drift.
     */
    public static final List<String> KNOWN_CHANNELS = List.of("telegram", "slack", "discord", "whatsapp", "teams");

    private final Map<String, ChatChannelConnector> byChannel;

    public ChatChannelConnectorRegistry(List<ChatChannelConnector> connectors) {
        this.byChannel = connectors.stream().collect(Collectors.toUnmodifiableMap(
                c -> c.channelId().toLowerCase(Locale.ROOT), Function.identity()));
    }

    public Optional<ChatChannelConnector> forChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byChannel.get(channel.toLowerCase(Locale.ROOT)));
    }

    /** Channel ids this deployment can actually connect, for help text and error messages. */
    public Set<String> supportedChannels() {
        return byChannel.keySet();
    }
}
