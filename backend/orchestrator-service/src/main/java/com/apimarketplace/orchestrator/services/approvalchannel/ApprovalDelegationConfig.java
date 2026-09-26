package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.domain.execution.SignalConfig;

import java.util.List;
import java.util.Map;

/**
 * Resolved external-channel delegation, read back from a USER_APPROVAL signal's
 * {@code signal_config.delegation} block (written at yield by UserApprovalNode:
 * chatId, message and image templates are ALREADY resolved against the paused
 * execution context; nothing here is a template anymore).
 *
 * @param channel        channel id (v1: "telegram")
 * @param credentialId   user credential id of the channel bot (BYOK)
 * @param chatId         destination chat id (resolved)
 * @param message        optional message body (resolved); null/blank = the notifier
 *                       falls back to the signal's approvalContext
 * @param image          optional image (resolved); either a FileRef Map
 *                       ({@code {_type:'file', path, name, ...}}) or a String HTTP
 *                       URL / file_id. Non-null = the notifier sends a photo with
 *                       the message text as caption. Null = plain text message.
 * @param allowedUserIds optional allowlist of channel user ids; empty = anyone in chat
 * @param approveLabel   optional custom approve-button label (resolved); null/blank =
 *                       the notifier uses its channel default ("✅ Approve")
 * @param rejectLabel    optional custom reject-button label (resolved); null/blank =
 *                       the notifier uses its channel default ("❌ Reject")
 */
public record ApprovalDelegationConfig(
        String channel,
        Long credentialId,
        String chatId,
        String message,
        Object image,
        List<String> allowedUserIds,
        String approveLabel,
        String rejectLabel,
        String linkId) {

    /** Keyword for "the workspace default destination" in {@code linkId}. */
    public static final String DEFAULT_DESTINATION = "default";

    /** The pre-destination shape: the node named a service, and optionally a chat. */
    public ApprovalDelegationConfig(String channel, Long credentialId, String chatId, String message, Object image,
                                    List<String> allowedUserIds, String approveLabel, String rejectLabel) {
        this(channel, credentialId, chatId, message, image, allowedUserIds, approveLabel, rejectLabel, null);
    }

    public ApprovalDelegationConfig {
        channel = channel == null ? "" : channel;
        linkId = linkId == null || linkId.isBlank() ? null : linkId.trim();
        // A blank-string image is as good as no image: normalise here so every
        // consumer can branch on a simple null check.
        image = image instanceof String s && s.isBlank() ? null : image;
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
        String linkId = asString(block.get("linkId"));
        // A picked destination decides the service itself, so it needs no channel of its own.
        if ((channel == null || channel.isBlank()) && (linkId == null || linkId.isBlank())) {
            return null;
        }
        Long credentialId = block.get("credentialId") instanceof Number n ? n.longValue() : null;
        return new ApprovalDelegationConfig(
                channel,
                credentialId,
                asString(block.get("chatId")),
                asString(block.get("message")),
                block.get("image"),
                asStringList(block.get("allowedUserIds")),
                asString(block.get("approveLabel")),
                asString(block.get("rejectLabel")),
                linkId);
    }

    /** True when the node picked a destination (an id or {@link #DEFAULT_DESTINATION}). */
    public boolean usesDestination() {
        return linkId != null;
    }

    /**
     * The picked destination as an id, or null for the workspace default. Also null for an id that
     * is not one at all, which then resolves to nothing rather than to the default: see
     * {@link #destinationIsMalformed()}.
     */
    public java.util.UUID destinationId() {
        if (linkId == null || DEFAULT_DESTINATION.equalsIgnoreCase(linkId)) {
            return null;
        }
        try {
            return java.util.UUID.fromString(linkId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** A linkId that is neither an id nor "default": a hand-written plan, refused at send. */
    public boolean destinationIsMalformed() {
        return linkId != null && !DEFAULT_DESTINATION.equalsIgnoreCase(linkId) && destinationId() == null;
    }

    /** The same delegation, sent through another service's notifier (the destination's). */
    public ApprovalDelegationConfig withChannel(String newChannel) {
        return new ApprovalDelegationConfig(newChannel, credentialId, chatId, message, image, allowedUserIds,
                approveLabel, rejectLabel, linkId);
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
