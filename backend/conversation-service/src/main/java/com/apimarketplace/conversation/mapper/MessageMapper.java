package com.apimarketplace.conversation.mapper;

import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Message;
import org.springframework.stereotype.Component;

/**
 * Mapper for Message entity/DTO conversions.
 * Supports all message roles including TOOL.
 */
@Component
public class MessageMapper {

    public MessageDto toDto(Message message) {
        if (message == null) {
            return null;
        }

        MessageDto dto = new MessageDto();
        dto.setId(message.getId());
        dto.setConversationId(message.getConversation() != null ? message.getConversation().getId() : null);
        dto.setRoleEnum(message.getRole());
        dto.setContent(message.getContent());
        dto.setToolCalls(message.getToolCalls());
        dto.setToolCallId(message.getToolCallId());
        dto.setToolName(message.getToolName());
        dto.setModel(message.getModel());
        dto.setAgentId(message.getAgentId());
        dto.setExecutionId(message.getExecutionId());
        dto.setFeedback(message.getFeedback() != null ? message.getFeedback().intValue() : null);
        dto.setTimestamp(message.getTimestamp());
        dto.setCreatedAt(message.getCreatedAt());

        return dto;
    }

    public Message toEntity(MessageDto dto) {
        if (dto == null) {
            return null;
        }

        Message message = new Message();
        message.setRole(dto.getRoleEnum());
        message.setContent(dto.getContent());
        message.setToolCalls(dto.getToolCalls());
        message.setToolCallId(dto.getToolCallId());
        message.setToolName(dto.getToolName());
        message.setModel(dto.getModel());
        message.setAgentId(dto.getAgentId());
        message.setExecutionId(dto.getExecutionId());
        message.setTimestamp(dto.getTimestamp());
        return message;
    }
}
