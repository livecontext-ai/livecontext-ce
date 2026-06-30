package com.apimarketplace.conversation.controller.v3.chat;

import com.apimarketplace.conversation.dto.ChatRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single source of truth for the first-message {@code chat_config} payload,
 * shared by the cloud ChatStreamInitializer and the CE MonolithChatController
 * (extracted 2026-06-11 when the CE path was found dropping it entirely).
 */
@DisplayName("ChatRequestConfigMapper - initial chat_config for first-message conversation creates")
class ChatRequestConfigMapperTest {

    @Test
    @DisplayName("returns null when the request carries neither chatConfig nor a skill selection (column skipped)")
    void returnsNullWhenNothingToPersist() {
        assertThat(ChatRequestConfigMapper.initialChatConfig(new ChatRequest())).isNull();
    }

    @Test
    @DisplayName("passes the request's chatConfig through verbatim (V312 user defaults merged client-side)")
    void passesChatConfigThrough() {
        ChatRequest request = new ChatRequest();
        request.setChatConfig(Map.of("temperature", 0.42, "systemPrompt", "Be terse."));

        Map<String, Object> result = ChatRequestConfigMapper.initialChatConfig(request);

        assertThat(result)
                .containsEntry("temperature", 0.42)
                .containsEntry("systemPrompt", "Be terse.")
                .doesNotContainKey("defaultSkillIds");
    }

    @Test
    @DisplayName("records an explicit skill selection - including the EMPTY selection, which must persist as [] (deliberate opt-out), not null")
    void recordsExplicitSkillSelection() {
        ChatRequest withIds = new ChatRequest();
        withIds.setDefaultSkillIds(List.of("skill-1", "skill-2"));
        assertThat(ChatRequestConfigMapper.initialChatConfig(withIds))
                .containsEntry("defaultSkillIds", List.of("skill-1", "skill-2"));

        ChatRequest optOut = new ChatRequest();
        optOut.setDefaultSkillIds(List.of());
        assertThat(ChatRequestConfigMapper.initialChatConfig(optOut))
                .containsEntry("defaultSkillIds", List.of());
    }

    @Test
    @DisplayName("merges chatConfig and skill selection into one map")
    void mergesBoth() {
        ChatRequest request = new ChatRequest();
        request.setChatConfig(Map.of("maxIterations", 12));
        request.setDefaultSkillIds(List.of("skill-1"));

        Map<String, Object> result = ChatRequestConfigMapper.initialChatConfig(request);

        assertThat(result)
                .containsEntry("maxIterations", 12)
                .containsEntry("defaultSkillIds", List.of("skill-1"));
    }
}
