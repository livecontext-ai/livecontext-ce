package com.apimarketplace.conversation.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.conversation.dto.AttachmentRef;
import com.apimarketplace.conversation.dto.MessageAttachmentDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.entity.MessageAttachment;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.ConversationNotFoundException;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import com.apimarketplace.conversation.mapper.MessageMapper;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageAttachmentRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.ChatCompactionOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import com.apimarketplace.common.event.EventBus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for managing conversation messages.
 *
 * Supports all message roles:
 * - USER: User input
 * - ASSISTANT: AI responses (may have tool_calls)
 * - SYSTEM: System instructions
 * - TOOL: Tool execution results
 */
@Service
public class MessageService {

    private static final Logger logger = LoggerFactory.getLogger(MessageService.class);

    private static final String WS_CHANNEL_PREFIX = "ws:conversation:";

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository messageAttachmentRepository;
    private final MessageMapper messageMapper;
    private final EventBus eventBus;
    private final ObjectMapper objectMapper;
    private final StorageBreakdownService breakdownService;
    private final ChatCompactionOrchestrator compactionOrchestrator;

    public MessageService(ConversationRepository conversationRepository,
                          MessageRepository messageRepository,
                          MessageAttachmentRepository messageAttachmentRepository,
                          MessageMapper messageMapper,
                          EventBus eventBus,
                          ObjectMapper objectMapper,
                          StorageBreakdownService breakdownService,
                          @org.springframework.beans.factory.annotation.Autowired(required = false)
                          ChatCompactionOrchestrator compactionOrchestrator) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.messageAttachmentRepository = messageAttachmentRepository;
        this.messageMapper = messageMapper;
        this.eventBus = eventBus;
        this.objectMapper = objectMapper;
        this.breakdownService = breakdownService;
        this.compactionOrchestrator = compactionOrchestrator;
    }

    /**
     * Persist a user prompt + a typed assistant error message into the conversation
     * as a best-effort pair. Used by short-circuit branches in the sync chat
     * controllers (cloud + CE) when a request can't reach the agent loop (e.g. 402
     * insufficient credits) so the conversation shows the attempt and the reason
     * rather than staying empty.
     *
     * <p><b>Transactional posture</b> (subtle - Spring AOP gotcha): NOT declared
     * {@code @Transactional} on purpose. The two inner {@code addMessage} calls
     * are <i>self-invocations</i> on {@code this}, so the {@code @Transactional}
     * annotation on {@code addMessage} is BYPASSED - Spring's proxy-based AOP
     * cannot intercept calls that don't go through the proxy. Persistence still
     * commits because Spring Data's {@link org.springframework.data.jpa.repository.support.SimpleJpaRepository}
     * declares {@code @Transactional} on {@code save()}, so each individual repo
     * write opens its own micro-tx. Net effect:
     * <ul>
     *   <li>first {@code addMessage} fails → its repo writes roll back; second
     *       still attempts (separate try/catch); both errors logged.</li>
     *   <li>first succeeds, second fails → user message already committed by its
     *       own micro-tx, assistant line lost. Partial state ("user prompt
     *       visible, assistant line missing") is more informative than nothing.</li>
     * </ul>
     *
     * <p><b>Hazard documented above (pre-existing, not introduced here):</b>
     * inside {@code addMessage} itself, the message-row save and the conversation-
     * row updated_at save are two separate micro-tx - a crash between them leaves
     * a message persisted without its parent conversation's {@code updated_at}
     * bumped. Acceptable for this best-effort path.
     *
     * <p>Each call is wrapped in its own try/catch + ERROR log so a persistence
     * failure surfaces in operator dashboards instead of silently disappearing -
     * the caller still gets its original verdict (402 / failure map), this is
     * purely observability.
     */
    public void persistAttemptAndError(String conversationId, String userContent, String errorContent) {
        if (conversationId == null || conversationId.isBlank()) return;

        try {
            MessageDto userMsg = new MessageDto();
            userMsg.setConversationId(conversationId);
            userMsg.setRole("user");
            userMsg.setContent(userContent != null ? userContent : "");
            userMsg.setTimestamp(Instant.now().toString());
            addMessage(conversationId, userMsg);
        } catch (Exception e) {
            logger.error("Failed to persist user attempt message for conversation {}: {}",
                    conversationId, e.getMessage());
        }

        try {
            MessageDto errMsg = new MessageDto();
            errMsg.setConversationId(conversationId);
            errMsg.setRole("assistant");
            errMsg.setContent(errorContent);
            errMsg.setTimestamp(Instant.now().toString());
            addMessage(conversationId, errMsg);
        } catch (Exception e) {
            logger.error("Failed to persist assistant error message for conversation {}: {}",
                    conversationId, e.getMessage());
        }
    }

    /**
     * Add a message to a conversation.
     * Content is required for USER and SYSTEM roles, optional for ASSISTANT and TOOL.
     */
    @Transactional
    public MessageDto addMessage(String conversationId, MessageDto messageDto) {
        logger.info("Adding {} message to conversation: {}", messageDto.getRole(), conversationId);

        Conversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));

        if (Boolean.FALSE.equals(conversation.getActive())) {
            throw new ConversationInactiveException(conversationId);
        }

        if (messageDto.getRoleEnum() == null) {
            throw new InvalidMessageException("Message role is required");
        }

        // Validate content based on role
        validateMessageContent(messageDto);

        // Correct any visualize-marker id the assistant mistyped in its reply text
        // against the authoritative `visualization` metadata of the tools that ran
        // this turn (embedded in tool_calls). One chokepoint for every surface, so
        // both the rendered card (parsed from content) and the agent's next-turn
        // self-reference (history is read from content) get the right id. Non-fatal.
        if (messageDto.getRoleEnum() == Message.MessageRole.ASSISTANT) {
            String reconciled = VisualizeMarkerReconciler.reconcile(
                    messageDto.getContent(), messageDto.getToolCalls());
            if (reconciled != null && !reconciled.equals(messageDto.getContent())) {
                messageDto.setContent(reconciled);
            }
        }

        Message message = messageMapper.toEntity(messageDto);
        if (message.getTimestamp() == null) {
            message.setTimestamp(Instant.now().toString());
        }
        conversation.addMessage(message);

        Message savedMessage = messageRepository.save(message);
        conversation.setUpdatedAt(LocalDateTime.now());
        conversationRepository.save(conversation);

        // Track storage breakdown
        long contentSize = messageDto.getContent() != null ? messageDto.getContent().length() : 0;
        long toolCallsSize = messageDto.getToolCalls() != null ? messageDto.getToolCalls().length() : 0;
        breakdownService.trackSave(conversation.getUserId(), "CONVERSATIONS", contentSize + toolCallsSize);

        MessageDto savedDto = messageMapper.toDto(savedMessage);

        // Publish real-time event to WebSocket channel so conversation panels update live
        publishMessageAdded(conversationId, savedDto);

        // Surface-agnostic compaction chokepoint: every assistant turn - chat, workflow-agent,
        // standalone-agent, sub-agent - lands here via ConversationClient.saveMessage or the
        // direct chat pipeline. Dispatch once, from one place, guarded by role.
        if (compactionOrchestrator != null
                && message.getRole() == Message.MessageRole.ASSISTANT) {
            try {
                compactionOrchestrator.afterTurnAsync(
                        conversationId,
                        conversation.getProvider(),
                        conversation.getModel(),
                        conversation.getUserId(),
                        conversation.getOrganizationId(),
                        null);
            } catch (Exception e) {
                logger.warn("Compaction dispatch failed (non-fatal) for conv {}: {}",
                        conversationId, e.toString());
            }
        }

        return savedDto;
    }

    /**
     * Publish a message_added event to Redis for WebSocket consumers.
     * The gateway bridges ws:conversation:{id} → conversation:{id} on the frontend.
     */
    private void publishMessageAdded(String conversationId, MessageDto messageDto) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "message_added");
            event.put("conversationId", conversationId);

            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("id", messageDto.getId());
            msg.put("role", messageDto.getRole());
            msg.put("content", messageDto.getContent());
            msg.put("toolCalls", messageDto.getToolCalls());
            msg.put("timestamp", messageDto.getTimestamp());
            // Server-monotonic creation time: the frontend's authoritative ordering key
            // (sortMessagesByTime prefers it over the client/server-mixed `timestamp`), so a
            // live-delivered message orders correctly without waiting for a full reload.
            msg.put("createdAt", messageDto.getCreatedAt());
            event.put("message", msg);

            String json = objectMapper.writeValueAsString(event);
            String channel = WS_CHANNEL_PREFIX + conversationId;
            eventBus.publish(channel, json);
            logger.debug("Published message_added to {}", channel);
        } catch (Exception e) {
            logger.warn("Failed to publish message_added event: {}", e.getMessage());
        }
    }

    /**
     * Validate message content based on role.
     * USER and SYSTEM require content.
     * ASSISTANT can have content OR tool_calls (or both).
     * TOOL requires content (the result) and tool_call_id.
     */
    private void validateMessageContent(MessageDto messageDto) {
        Message.MessageRole role = messageDto.getRoleEnum();
        String content = messageDto.getContent();
        boolean hasContent = content != null && !content.isBlank();
        boolean hasToolCalls = messageDto.hasToolCalls();

        switch (role) {
            case USER, SYSTEM -> {
                if (!hasContent) {
                    throw new InvalidMessageException("Content is required for " + role + " messages");
                }
            }
            case ASSISTANT -> {
                // ASSISTANT can have content, tool_calls, or both
                // At least one must be present
                if (!hasContent && !hasToolCalls) {
                    throw new InvalidMessageException("ASSISTANT message must have content or tool_calls");
                }
            }
            case TOOL -> {
                // TOOL must have tool_call_id and content (the result)
                if (messageDto.getToolCallId() == null || messageDto.getToolCallId().isBlank()) {
                    throw new InvalidMessageException("TOOL message must have tool_call_id");
                }
                if (!hasContent) {
                    throw new InvalidMessageException("TOOL message must have content (result)");
                }
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<MessageDto> getMessagesByConversationId(String conversationId, int page, int size) {
        return getMessagesByConversationId(conversationId, page, size, null);
    }

    /**
     * Paginated message fetch, optionally scoped to a single execution.
     * <p>
     * {@code executionId} == "latest" resolves to the most recent executionId on the
     * conversation (or falls back to the un-filtered branch if the conversation has
     * never emitted an executionId). Returns DESC order so page 0 is the newest batch,
     * matching the frontend's reverse-infinite-scroll UX.
     */
    @Transactional(readOnly = true)
    public Page<MessageDto> getMessagesByConversationId(String conversationId, int page, int size, String executionId) {
        Pageable pageable = PageRequest.of(page, size);

        String resolvedExecutionId = executionId;
        if ("latest".equals(executionId)) {
            List<String> ids = messageRepository.findLatestExecutionIds(conversationId, PageRequest.of(0, 1));
            resolvedExecutionId = ids.isEmpty() ? null : ids.get(0);
        }

        Page<MessageDto> messagePage;
        if (resolvedExecutionId != null) {
            logger.info("Getting paged messages for conversation: {} executionId: {} page: {} size: {} (DESC order)",
                    conversationId, resolvedExecutionId, page, size);
            messagePage = messageRepository
                    .findByConversationIdAndExecutionIdOrderByCreatedAtDesc(conversationId, resolvedExecutionId, pageable)
                    .map(messageMapper::toDto);
        } else {
            logger.info("Getting paged messages for conversation: {} page: {} size: {} (DESC order)",
                    conversationId, page, size);
            messagePage = messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable)
                    .map(messageMapper::toDto);
        }

        loadAttachmentsForMessages(messagePage.getContent());
        return messagePage;
    }

    /**
     * Get the last N messages for a conversation in chronological order (oldest first).
     * Useful for LLM context where we want recent messages in correct order.
     */
    @Transactional(readOnly = true)
    public List<MessageDto> getLastNMessagesChronological(String conversationId, int limit) {
        logger.info("Getting last {} messages for conversation: {} in chronological order", limit, conversationId);

        Pageable pageable = PageRequest.of(0, limit);
        Page<Message> recentMessages = messageRepository.findByConversationIdOrderByCreatedAtDesc(conversationId, pageable);

        List<Message> messages = recentMessages.getContent();

        // Reverse to get chronological order (oldest first)
        List<MessageDto> chronological = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            chronological.add(messageMapper.toDto(messages.get(i)));
        }

        logger.info("Returning {} messages in chronological order for conversation: {}", chronological.size(), conversationId);
        return chronological;
    }

    /**
     * Update a message's toolCalls field.
     * Used to update workflow run status and duration after completion.
     */
    @Transactional
    public MessageDto updateMessageToolCalls(String messageId, String toolCallsJson) {
        logger.info("Updating toolCalls for message: {}", messageId);

        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new InvalidMessageException("Message not found: " + messageId));

        message.setToolCalls(toolCallsJson);
        Message savedMessage = messageRepository.save(message);

        return messageMapper.toDto(savedMessage);
    }

    /**
     * Scope-aware overload - Audit 2026-05-17 round-3. Verifies that the
     * caller (userId + optional orgId) is in scope for the parent
     * conversation BEFORE mutating. Throws {@link InvalidMessageException}
     * (mapped to 404 at the controller) when out-of-scope so existence
     * isn't leaked across tenants.
     */
    @Transactional
    public MessageDto updateMessageToolCalls(String messageId, String toolCallsJson,
                                              String callerUserId, String callerOrgId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new InvalidMessageException("Message not found: " + messageId));
        if (!isMessageInScope(message, callerUserId, callerOrgId)) {
            logger.warn("[SCOPE] updateMessageToolCalls cross-tenant blocked: messageId={} caller={} orgId={}",
                    messageId, callerUserId, callerOrgId);
            throw new InvalidMessageException("Message not found: " + messageId);
        }
        message.setToolCalls(toolCallsJson);
        Message savedMessage = messageRepository.save(message);
        return messageMapper.toDto(savedMessage);
    }

    /**
     * Update user feedback on a message.
     * @param feedback 1 (thumbs up), -1 (thumbs down), or null (clear)
     */
    @Transactional
    public MessageDto updateMessageFeedback(String messageId, Integer feedback) {
        logger.info("Updating feedback for message: {} to {}", messageId, feedback);

        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new InvalidMessageException("Message not found: " + messageId));

        message.setFeedback(feedback != null ? feedback.shortValue() : null);
        Message saved = messageRepository.save(message);
        return messageMapper.toDto(saved);
    }

    /**
     * Scope-aware overload - Audit 2026-05-17 round-3. See
     * {@link #updateMessageToolCalls(String, String, String, String)}.
     */
    @Transactional
    public MessageDto updateMessageFeedback(String messageId, Integer feedback,
                                             String callerUserId, String callerOrgId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new InvalidMessageException("Message not found: " + messageId));
        if (!isMessageInScope(message, callerUserId, callerOrgId)) {
            logger.warn("[SCOPE] updateMessageFeedback cross-tenant blocked: messageId={} caller={} orgId={}",
                    messageId, callerUserId, callerOrgId);
            throw new InvalidMessageException("Message not found: " + messageId);
        }
        message.setFeedback(feedback != null ? feedback.shortValue() : null);
        Message saved = messageRepository.save(message);
        return messageMapper.toDto(saved);
    }

    /**
     * Conversation-scoped messages page - Audit 2026-05-18.
     * Strict-isolation scope check before paginating messages. Returns empty
     * page when the conversation does not belong to the caller's active
     * workspace, so a member viewing OrgA cannot read messages from a conv
     * in their personal workspace (and vice versa).
     */
    public Page<MessageDto> getMessagesByConversationIdScoped(String conversationId,
                                                               int page, int size,
                                                               String executionId,
                                                               String callerUserId,
                                                               String callerOrgId) {
        Conversation conv = conversationRepository.findById(conversationId).orElse(null);
        if (conv == null || !isConversationInScope(conv, callerUserId, callerOrgId)) {
            logger.warn("[SCOPE] getMessagesPage cross-tenant blocked: convId={} caller={} orgId={}",
                    conversationId, callerUserId, callerOrgId);
            return Page.empty();
        }
        return getMessagesByConversationId(conversationId, page, size, executionId);
    }

    private boolean isMessageInScope(Message message, String userId, String orgId) {
        Conversation conv = message.getConversation();
        return isConversationInScope(conv, userId, orgId);
    }

    /**
     * Strict-isolation scope predicate for {@link Conversation}, delegating to
     * {@link com.apimarketplace.common.scope.ScopeGuard#isInStrictScope}. Aligned
     * 2026-05-18: the prior owner-OR-org pattern let a caller currently in OrgA
     * workspace read messages of their personal conversation (org=NULL) because
     * userId matched the row's tenantId regardless of caller's active workspace
     * - fixed via the shared helper. See {@code ConversationQueryService
     * .getConversationById} for the canonical strict pattern this aligns to.
     */
    private boolean isConversationInScope(Conversation conv, String userId, String orgId) {
        if (conv == null) return false;
        return com.apimarketplace.common.scope.ScopeGuard.isInStrictScope(
                userId, orgId, conv.getUserId(), conv.getOrganizationId());
    }

    /**
     * Save attachments for a message.
     */
    @Transactional
    public void saveAttachments(String messageId, List<AttachmentRef> attachments) {
        if (attachments == null || attachments.isEmpty()) {
            return;
        }

        logger.info("Saving {} attachments for message: {}", attachments.size(), messageId);

        for (int i = 0; i < attachments.size(); i++) {
            AttachmentRef ref = attachments.get(i);
            MessageAttachment attachment = new MessageAttachment(
                messageId,
                UUID.fromString(ref.getStorageId()),
                ref.getType(),
                ref.getFileName(),
                ref.getMimeType(),
                null, // sizeBytes not in AttachmentRef
                i
            );
            messageAttachmentRepository.save(attachment);
        }

        logger.info("Saved {} attachments for message: {}", attachments.size(), messageId);
    }

    /**
     * Load attachments for a list of messages (batch query for efficiency).
     */
    private void loadAttachmentsForMessages(List<MessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        // Get all message IDs
        List<String> messageIds = messages.stream()
                .map(MessageDto::getId)
                .filter(id -> id != null)
                .collect(Collectors.toList());

        if (messageIds.isEmpty()) {
            return;
        }

        // Batch load all attachments
        List<MessageAttachment> allAttachments = messageAttachmentRepository.findByMessageIdIn(messageIds);

        // Group by message ID
        Map<String, List<MessageAttachment>> attachmentsByMessageId = allAttachments.stream()
                .collect(Collectors.groupingBy(MessageAttachment::getMessageId));

        // Set attachments on each message
        for (MessageDto message : messages) {
            List<MessageAttachment> messageAttachments = attachmentsByMessageId.get(message.getId());
            if (messageAttachments != null && !messageAttachments.isEmpty()) {
                List<MessageAttachmentDto> attachmentDtos = messageAttachments.stream()
                        .map(a -> new MessageAttachmentDto(
                                a.getStorageId().toString(),
                                a.getAttachmentType(),
                                a.getFileName(),
                                a.getMimeType(),
                                a.getSizeBytes()
                        ))
                        .collect(Collectors.toList());
                message.setAttachments(attachmentDtos);
            }
        }
    }

}

