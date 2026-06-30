package com.apimarketplace.conversation.mapper;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for parentConversationId mapping in ConversationMapper.
 */
@DisplayName("ConversationMapper - parentConversationId")
class ConversationMapperParentTest {

    private final ConversationMapper mapper = new ConversationMapper();

    @Test
    @DisplayName("toDto should map parentConversationId")
    void toDtoShouldMapParentConversationId() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setId("conv-id");
        conversation.setParentConversationId("parent-conv-abc");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getParentConversationId()).isEqualTo("parent-conv-abc");
    }

    @Test
    @DisplayName("toDto should map null parentConversationId")
    void toDtoShouldMapNullParentConversationId() {
        Conversation conversation = new Conversation("user", "title", "model", "provider");
        conversation.setId("conv-id");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getParentConversationId()).isNull();
    }

    @Test
    @DisplayName("toDto should map agentId and parentConversationId together")
    void toDtoShouldMapBothAgentAndParent() {
        Conversation conversation = new Conversation("user", "Sub-Agent Chat", "gpt-4", "openai");
        conversation.setId("conv-sub");
        conversation.setAgentId("agent-123");
        conversation.setParentConversationId("parent-conv-456");
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());

        ConversationDto dto = mapper.toDto(conversation);

        assertThat(dto.getAgentId()).isEqualTo("agent-123");
        assertThat(dto.getParentConversationId()).isEqualTo("parent-conv-456");
        assertThat(dto.getTitle()).isEqualTo("Sub-Agent Chat");
    }
}
