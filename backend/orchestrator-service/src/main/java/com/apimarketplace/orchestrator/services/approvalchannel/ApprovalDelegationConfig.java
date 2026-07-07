package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.domain.execution.SignalConfig;

import java.util.List;
import java.util.Map;

/**
 * Resolved external-channel delegation, read back from a USER_APPROVAL signal's
 * {@code signal_config.delegation} block (written at yield by UserApprovalNode:
 * chatId and message templates are ALREADY resolved against the paused execution
 * context; nothing here is a template anymore).
 *
 * @param channel        channel id (v1: "telegram")
 * @param credentialId   user credential id of the channel bot (BYOK)
 * @param chatId         destination chat id (resolved)
 * @param message        optional message body (resolved); null/blank = the notifier
 *                       falls back to the signal's approvalContext
 * @param allowedUserIds optional allowlist of channel user ids; empty = anyone in chat
 */
public record ApprovalDelegationConfig(
        String channel,
        Long credentialId,
        String chatId,
        String message,
        List<String> allowedUserIds) {

    public ApprovalDelegationConfig {
        channel = channel == null ? "" : channel;
        allowedUserIds = allowedUserIds == null ? List.of() : List.copyOf(allowedUserIds);
    }

    /**
     * Parse the delegation block out of a signal_config map. Returns null when the
     * signal carries no delegation (the common, non-delegated case) or the block
     * has no channel (never written by the node, but a hand-crafted plan could).
     */
    public static ApprovalDelegationConfig fromSignalConfig(Map<String, Object> signalConfig) {
        Map<String, Object> block = SignalConfig.getDelegation(signalConfig);
        if (block == null) {
            return null;
        }
        String channel = asString(block.get("channel"));
        if (channel == null || channel.isBlank()) {
            return null;
        }
        Long credentialId = block.get("credentialId") instanceof Number n ? n.longValue() : null;
        return new ApprovalDelegationConfig(
                channel,
                credentialId,
                asString(block.get("chatId")),
                asString(block.get("message")),
                asStringList(block.get("allowedUserIds")));
    }

    private static String asString(Object value) {
        return value != null ? value.toString() : null;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
