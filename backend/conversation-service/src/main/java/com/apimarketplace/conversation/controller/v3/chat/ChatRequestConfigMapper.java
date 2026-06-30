package com.apimarketplace.conversation.controller.v3.chat;

import com.apimarketplace.conversation.dto.ChatRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the initial {@code conversation.chat_config} payload for a
 * conversation created by a first chat message.
 *
 * <p>Single source of truth for BOTH first-message create paths - the cloud
 * {@link ChatStreamInitializer} and the CE {@code MonolithChatController}.
 * Extracted 2026-06-11: the CE controller called the create overload WITHOUT
 * chatConfig, so the per-(user, workspace) defaults (V312) the composer sends
 * on the first message (temperature, systemPrompt, maxIterations, …) and the
 * composer's initial skill selection were silently dropped on CE while cloud
 * persisted them ("same as cloud" comment notwithstanding).
 */
public final class ChatRequestConfigMapper {

    private ChatRequestConfigMapper() {
    }

    /**
     * Merge the request's {@code chatConfig} (user defaults + per-conversation
     * draft edits, already merged client-side) with the explicit
     * {@code defaultSkillIds} selection when one was provided. Returns
     * {@code null} when there is nothing to persist so callers can skip the
     * column entirely.
     */
    public static Map<String, Object> initialChatConfig(ChatRequest request) {
        Map<String, Object> chatConfig = new HashMap<>();
        if (request.getChatConfig() != null && !request.getChatConfig().isEmpty()) {
            chatConfig.putAll(request.getChatConfig());
        }
        if (!request.isDefaultSkillIdsProvided()) {
            return chatConfig.isEmpty() ? null : chatConfig;
        }
        List<String> ids = request.getDefaultSkillIds() != null ? request.getDefaultSkillIds() : List.of();
        chatConfig.put("defaultSkillIds", List.copyOf(ids));
        return chatConfig;
    }
}
