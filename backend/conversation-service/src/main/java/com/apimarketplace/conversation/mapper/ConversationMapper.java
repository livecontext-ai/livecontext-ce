package com.apimarketplace.conversation.mapper;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Mapper utilitaire pour les entites Conversation.
 */
@Component
public class ConversationMapper {

    public ConversationDto toDto(Conversation conversation) {
        if (conversation == null) {
            return null;
        }

        ConversationDto dto = new ConversationDto();
        dto.setId(conversation.getId());
        dto.setUserId(conversation.getUserId());
        // PR21 - surface the workspace tag so frontend can render org badge / filter UI later.
        dto.setOrganizationId(conversation.getOrganizationId());
        dto.setTitle(conversation.getTitle());
        dto.setModel(conversation.getModel());
        dto.setProvider(conversation.getProvider());
        dto.setWorkflowId(conversation.getWorkflowId());
        dto.setAgentId(conversation.getAgentId());
        dto.setParentConversationId(conversation.getParentConversationId());
        dto.setActive(conversation.getActive());
        dto.setCreatedAt(conversation.getCreatedAt());
        dto.setUpdatedAt(conversation.getUpdatedAt());
        dto.setMessageCount(resolveMessageCount(conversation));
        dto.setPendingAction(conversation.getPendingAction());
        dto.setPendingActions(conversation.getPendingActions());
        dto.setApprovedServices(conversation.getApprovedServices());
        dto.setShareToken(conversation.getShareToken());
        dto.setShareMode(conversation.getShareMode());
        dto.setMemoryEnabled(conversation.getMemoryEnabled());
        dto.setChatConfig(conversation.getChatConfig());
        dto.setCompactionMarker(buildCompactionMarker(conversation.getSummaryCold()));

        return dto;
    }

    private Long resolveMessageCount(Conversation conversation) {
        List<Message> messages = conversation.getMessages();
        if (messages == null) {
            return 0L;
        }
        if (!Hibernate.isInitialized(messages)) {
            return null;
        }
        return (long) messages.size();
    }

    /**
     * Project the internal {@code summary_cold} JSONB blob down to the three
     * fields the UI needs. Returns {@code null} when no summary exists yet -
     * the DTO field is nullable so absent compaction doesn't pollute the
     * payload. Defensive against partial envelopes (pre-schema-v1 rows,
     * model-malformed outputs): we read each field independently and skip
     * the marker only if {@code turns_covered} is missing, since the UI
     * can't place a divider without it.
     */
    @SuppressWarnings("unchecked")
    private ConversationDto.CompactionMarker buildCompactionMarker(Map<String, Object> summaryCold) {
        if (summaryCold == null || summaryCold.isEmpty()) {
            return null;
        }
        Object coveredRaw = summaryCold.get("turns_covered");
        if (!(coveredRaw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<Integer> turns = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Number n) {
                turns.add(n.intValue());
            }
        }
        if (turns.isEmpty()) {
            return null;
        }
        String generatedAt = summaryCold.get("generated_at") instanceof String s ? s : null;
        String model = summaryCold.get("model") instanceof String m ? m : null;
        String status = summaryCold.get("status") instanceof String st ? st : null;
        return new ConversationDto.CompactionMarker(turns, generatedAt, model, status);
    }

    public void updateEntity(ConversationDto dto, Conversation conversation) {
        if (dto == null || conversation == null) {
            return;
        }

        // Only update non-null fields (partial update support)
        if (dto.getTitle() != null) {
            conversation.setTitle(dto.getTitle());
        }
        if (dto.getModel() != null) {
            conversation.setModel(dto.getModel());
        }
        if (dto.getProvider() != null) {
            conversation.setProvider(dto.getProvider());
        }
        if (dto.getActive() != null) {
            conversation.setActive(dto.getActive());
        }
        if (dto.getMemoryEnabled() != null) {
            conversation.setMemoryEnabled(dto.getMemoryEnabled());
        }
        if (dto.getChatConfig() != null) {
            conversation.setChatConfig(dto.getChatConfig());
        }
    }
}
