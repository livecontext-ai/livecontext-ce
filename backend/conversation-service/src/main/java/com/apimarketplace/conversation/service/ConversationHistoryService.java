package com.apimarketplace.conversation.service;

import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for managing conversation history
 * Refactored to use direct service calls instead of RestTemplate
 */
@Service
public class ConversationHistoryService {
    
    private static final Logger logger = LoggerFactory.getLogger(ConversationHistoryService.class);

    private final ConversationCommandService conversationCommandService;
    private final ConversationQueryService conversationQueryService;
    private final MessageService messageService;

    public ConversationHistoryService(ConversationCommandService conversationCommandService,
                                      ConversationQueryService conversationQueryService,
                                      MessageService messageService) {
        this.conversationCommandService = conversationCommandService;
        this.conversationQueryService = conversationQueryService;
        this.messageService = messageService;
    }
    
    /**
     * Create a new conversation (back-compat - defaults to personal scope).
     */
    public String createConversation(String userId, String title, String model, String provider) {
        return createConversation(userId, title, model, provider, null);
    }

    /**
     * Create a new conversation, optionally linked to an agent (back-compat - defaults to personal scope).
     */
    public String createConversation(String userId, String title, String model, String provider, String agentId) {
        return createConversation(userId, null, title, model, provider, agentId);
    }

    /**
     * PR21 R2 - create a new conversation in the given workspace scope.
     * <p>
     * organizationId NULL = personal scope, non-null = team workspace. The
     * write path stamps the row at creation time so subsequent sidebar reads
     * (which route to strict-isolation finders) surface the chat in the
     * workspace it was created in. Without this overload the v3 chat
     * streaming path created every team-workspace chat with organization_id
     * = NULL, leaving them invisible to the org sidebar - exactly the bug
     * class PR21 closes. Reviewer B+C round-1 caught this.
     */
    public String createConversation(String userId, String organizationId,
                                      String title, String model, String provider, String agentId) {
        return createConversation(userId, organizationId, title, model, provider, agentId, null);
    }

    public String createConversation(String userId, String organizationId,
                                      String title, String model, String provider, String agentId,
                                      Map<String, Object> chatConfig) {
        try {
            logger.info("Creating conversation for user: {} (org: {}), agentId: {}", userId, organizationId, agentId);

            ConversationDto conversationDto = new ConversationDto();
            conversationDto.setUserId(userId);
            conversationDto.setOrganizationId(organizationId);
            conversationDto.setTitle(title != null ? title : "Generating title...");
            conversationDto.setModel(model);
            conversationDto.setProvider(provider);
            conversationDto.setActive(true);
            if (agentId != null && !agentId.isBlank()) {
                conversationDto.setAgentId(agentId);
            }
            conversationDto.setChatConfig(chatConfig);

            ConversationDto createdConversation = conversationCommandService.createConversation(conversationDto);

            if (createdConversation != null && createdConversation.getId() != null) {
                logger.info("Created conversation with ID: {}", createdConversation.getId());
                return createdConversation.getId();
            } else {
                logger.error("Failed to create conversation: conversation service returned null");
                return null;
            }

        } catch (Exception e) {
            logger.error("Error creating conversation: {}", e.getMessage(), e);
            return null;
        }
    }

    public void persistDefaultSkillIds(String conversationId, String userId, String organizationId, List<String> defaultSkillIds) {
        if (conversationId == null || conversationId.isBlank() || defaultSkillIds == null) {
            return;
        }
        Optional<ConversationDto> existing = conversationQueryService.getConversationById(conversationId, userId, organizationId);
        if (existing.isEmpty()) {
            logger.warn("Cannot persist defaultSkillIds for out-of-scope conversation {} (user={}, org={})",
                    conversationId, userId, organizationId);
            return;
        }
        Map<String, Object> chatConfig = new HashMap<>();
        if (existing.get().getChatConfig() != null) {
            chatConfig.putAll(existing.get().getChatConfig());
        }
        chatConfig.put("defaultSkillIds", List.copyOf(defaultSkillIds));

        ConversationDto patch = new ConversationDto();
        patch.setChatConfig(chatConfig);
        conversationCommandService.updateConversation(conversationId, patch);
    }
    
    /**
     * Add a message to a conversation and return the created message payload
     */
    public Map<String, Object> addMessage(String conversationId, String role, String content, String model, String timestamp, String toolCalls, String userId) {
        try {
            logger.info("Adding message to conversation: {}", conversationId);
            
            MessageDto messageDto = new MessageDto();
            messageDto.setConversationId(conversationId);
            messageDto.setRole(role);
            messageDto.setContent(content);
            messageDto.setModel(model);
            messageDto.setTimestamp(timestamp != null ? timestamp : java.time.Instant.now().toString());
            messageDto.setToolCalls(toolCalls != null ? toolCalls : "");
            
            MessageDto createdMessage = messageService.addMessage(conversationId, messageDto);
            
            if (createdMessage != null) {
                logger.info("Added message to conversation: {}", conversationId);
                
                // Convert MessageDto to Map for compatibility
                Map<String, Object> messageMap = new HashMap<>();
                messageMap.put("id", createdMessage.getId());
                messageMap.put("conversationId", createdMessage.getConversationId());
                messageMap.put("role", createdMessage.getRole());
                messageMap.put("content", createdMessage.getContent());
                messageMap.put("model", createdMessage.getModel());
                messageMap.put("timestamp", createdMessage.getTimestamp());
                messageMap.put("toolCalls", createdMessage.getToolCalls());
                messageMap.put("createdAt", createdMessage.getCreatedAt());
                
                return messageMap;
            } else {
                logger.error("Failed to add message to conversation: conversation service returned null");
                return null;
            }
            
        } catch (ConversationInactiveException | InvalidMessageException e) {
            logger.warn("Validation error while adding message to conversation {}: {}", conversationId, e.getMessage());
            return null;
        } catch (Exception e) {
            logger.error("Error adding message to conversation: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Get conversation history (capped at {@value #HISTORY_HARD_CAP} most recent messages,
     * returned chronologically). The cap protects callers that consume the full history into
     * memory - tool-call payloads can run MB-scale per row, and unbounded fetch OOM'd the JVM
     * on long agent loops. Callers that need full pagination must use the paginated endpoint
     * directly rather than this convenience method.
     */
    private static final int HISTORY_HARD_CAP = 200;

    public List<Map<String, Object>> getConversationHistory(String conversationId, String userId) {
        try {
            logger.info("Getting conversation history for: {}", conversationId);

            // Fetch most-recent page DESC, then reverse to chronological ASC for callers.
            org.springframework.data.domain.Page<MessageDto> page =
                messageService.getMessagesByConversationId(conversationId, 0, HISTORY_HARD_CAP);

            if (page != null) {
                List<MessageDto> messages = new java.util.ArrayList<>(page.getContent());
                java.util.Collections.reverse(messages);
                logger.info("Retrieved {} messages for conversation: {} (of {} total)",
                        messages.size(), conversationId, page.getTotalElements());

                return messages.stream()
                    .map(this::convertMessageDtoToMap)
                    .toList();
            } else {
                logger.error("Failed to get conversation history: conversation service returned null");
                return List.of();
            }

        } catch (Exception e) {
            logger.error("Error getting conversation history: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get the last N messages of conversation history in chronological order.
     * Used for LLM context to limit history while maintaining correct message order.
     *
     * @param conversationId The conversation ID
     * @param userId The user ID
     * @param limit Maximum number of messages (e.g., 20 for 10 exchanges)
     * @return List of messages in chronological order (oldest first)
     */
    public List<Map<String, Object>> getConversationHistoryLimited(String conversationId, String userId, int limit) {
        try {
            logger.info("Getting last {} messages of conversation history for: {}", limit, conversationId);

            List<MessageDto> messages = messageService.getLastNMessagesChronological(conversationId, limit);

            if (messages != null) {
                logger.info("Retrieved {} messages (limited) for conversation: {}", messages.size(), conversationId);

                // Convert MessageDto list to Map list for compatibility
                return messages.stream()
                    .map(this::convertMessageDtoToMap)
                    .toList();
            } else {
                logger.error("Failed to get limited conversation history: service returned null");
                return List.of();
            }

        } catch (Exception e) {
            logger.error("Error getting limited conversation history: {}", e.getMessage(), e);
            return List.of();
        }
    }
    
    /**
     * Convert conversation history to ChatRequest format
     */
    public List<ChatRequest.ChatMessage> convertToChatMessages(List<Map<String, Object>> messages) {
        return messages.stream()
                .map(msg -> {
                    ChatRequest.ChatMessage chatMessage = new ChatRequest.ChatMessage();
                    chatMessage.setRole((String) msg.get("role"));
                    chatMessage.setContent((String) msg.get("content"));
                    chatMessage.setTimestamp((String) msg.get("timestamp"));
                    chatMessage.setToolCalls((String) msg.get("toolCalls"));
                    return chatMessage;
                })
                .toList();
    }
    
    /**
     * Update conversation title - scope-aware (PR21).
     * Returns false (no-op) if the conversation does not belong to the caller's
     * workspace; the org-aware {@code getConversationById} lookup enforces that
     * cross-scope rows are invisible.
     */
    public boolean updateConversationTitle(String conversationId, String userId, String organizationId, String newTitle) {
        try {
            logger.info("Updating conversation title for: {} to: {}", conversationId, newTitle);

            // First, get the existing conversation to preserve other fields
            Optional<ConversationDto> existingConversationOpt =
                    conversationQueryService.getConversationById(conversationId, userId, organizationId);

            if (existingConversationOpt.isEmpty()) {
                logger.error("Failed to get conversation for update: conversation not found");
                return false;
            }
            
            ConversationDto existingConversation = existingConversationOpt.get();
            
            // Update only the title field
            existingConversation.setTitle(newTitle);
            
            ConversationDto updatedConversation = conversationCommandService.updateConversation(conversationId, existingConversation);
            
            if (updatedConversation != null) {
                logger.info("Successfully updated conversation title for: {}", conversationId);
                return true;
            } else {
                logger.error("Failed to update conversation title: conversation service returned null");
                return false;
            }
            
        } catch (Exception e) {
            logger.error("Error updating conversation title: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Convert MessageDto to Map for compatibility
     */
    private Map<String, Object> convertMessageDtoToMap(MessageDto messageDto) {
        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put("id", messageDto.getId());
        messageMap.put("conversationId", messageDto.getConversationId());
        messageMap.put("role", messageDto.getRole());
        messageMap.put("content", messageDto.getContent());
        messageMap.put("model", messageDto.getModel());
        messageMap.put("timestamp", messageDto.getTimestamp());
        messageMap.put("toolCalls", messageDto.getToolCalls());
        messageMap.put("createdAt", messageDto.getCreatedAt());
        return messageMap;
    }
    
}
