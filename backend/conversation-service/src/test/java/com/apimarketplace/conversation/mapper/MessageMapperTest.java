package com.apimarketplace.conversation.mapper;

import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MessageMapperTest {

    private final MessageMapper mapper = new MessageMapper();

    @Test
    void toDtoCopiesConversationId() {
        Conversation conversation = new Conversation();
        conversation.setId("conv-id");
        Message message = new Message(Message.MessageRole.USER, "content");
        message.setConversation(conversation);

        MessageDto dto = mapper.toDto(message);

        assertThat(dto.getConversationId()).isEqualTo("conv-id");
        assertThat(dto.getRole()).isEqualTo("user");
    }

    @Test
    void toEntityConvertsRole() {
        MessageDto dto = new MessageDto();
        dto.setRole("assistant");
        dto.setContent("hi");
        dto.setModel("model");

        Message entity = mapper.toEntity(dto);

        assertThat(entity.getRole()).isEqualTo(Message.MessageRole.ASSISTANT);
        assertThat(entity.getContent()).isEqualTo("hi");
    }
}
