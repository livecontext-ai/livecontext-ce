package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.CreateConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.dto.PagedResponseDto;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import com.apimarketplace.conversation.service.ConversationCommandService;
import com.apimarketplace.conversation.service.ConversationQueryService;
import com.apimarketplace.conversation.service.MessageService;
import com.apimarketplace.conversation.service.PendingActionService;
import com.apimarketplace.conversation.service.PendingActionResumeService;
import com.apimarketplace.conversation.service.ConversationSharingService;
import com.apimarketplace.conversation.service.approval.ServiceApprovalService;
import com.apimarketplace.conversation.service.approval.ToolAuthorizationApprovalService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * REST Controller for conversation management
 */
@RestController
@RequestMapping("/api/conversations")
@CrossOrigin(origins = "*")
public class ConversationController {
    
    private static final Logger logger = LoggerFactory.getLogger(ConversationController.class);
    
    private final ConversationCommandService conversationCommandService;
    private final ConversationQueryService conversationQueryService;
    private final MessageService messageService;
    private final PendingActionService pendingActionService;
    private final PendingActionResumeService pendingActionResumeService;
    private final ServiceApprovalService serviceApprovalService;
    private final ToolAuthorizationApprovalService toolAuthorizationApprovalService;
    private final ConversationSharingService conversationSharingService;

    public ConversationController(ConversationCommandService conversationCommandService,
                                  ConversationQueryService conversationQueryService,
                                  MessageService messageService,
                                  PendingActionService pendingActionService,
                                  PendingActionResumeService pendingActionResumeService,
                                  ServiceApprovalService serviceApprovalService,
                                  ToolAuthorizationApprovalService toolAuthorizationApprovalService,
                                  ConversationSharingService conversationSharingService) {
        this.conversationCommandService = conversationCommandService;
        this.conversationQueryService = conversationQueryService;
        this.messageService = messageService;
        this.pendingActionService = pendingActionService;
        this.pendingActionResumeService = pendingActionResumeService;
        this.serviceApprovalService = serviceApprovalService;
        this.toolAuthorizationApprovalService = toolAuthorizationApprovalService;
        this.conversationSharingService = conversationSharingService;
    }
    
    /**
     * Create a new conversation
     */
    @PostMapping
    public ResponseEntity<?> createConversation(
            @Valid @RequestBody CreateConversationDto createConversationDto,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            // PR21 - workspace identity at create time. NULL = personal scope, non-null
            // = team workspace. Strict-isolation contract starts at the write boundary
            // so subsequent reads (which route on this column) see the row in the
            // workspace it was created in.
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        try {
            logger.info("Creating conversation for user: {} (org: {}), data: {}",
                    userId, organizationId, createConversationDto);
            // Convert to ConversationDto and set the userId from the header
            ConversationDto conversationDto = new ConversationDto();
            conversationDto.setUserId(userId);
            conversationDto.setOrganizationId(organizationId);
            conversationDto.setTitle(createConversationDto.getTitle());
            conversationDto.setModel(createConversationDto.getModel());
            conversationDto.setProvider(createConversationDto.getProvider());
            conversationDto.setWorkflowId(createConversationDto.getWorkflowId());
            conversationDto.setAgentId(createConversationDto.getAgentId());
            conversationDto.setParentConversationId(createConversationDto.getParentConversationId());
            conversationDto.setActive(createConversationDto.getActive());
            if (createConversationDto.getMemoryEnabled() != null) {
                conversationDto.setMemoryEnabled(createConversationDto.getMemoryEnabled());
            }
            conversationDto.setChatConfig(createConversationDto.getChatConfig());

            ConversationDto createdConversation = conversationCommandService.createConversation(conversationDto);
            return ResponseEntity.status(HttpStatus.CREATED).body(createdConversation);
        } catch (IllegalArgumentException e) {
            // GuardOverrides range / key / type failure on the optional chatConfig.turnLimits block.
            logger.warn("Rejected conversation creation for user {}: {}", userId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error creating conversation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Find an existing conversation for a specific workflow (does NOT create)
     * Returns 404 if no conversation exists
     */
    @GetMapping("/workflow/{workflowId}")
    public ResponseEntity<ConversationDto> findWorkflowConversation(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            logger.info("Finding workflow conversation for user: {} (org: {}) and workflow: {}",
                    userId, organizationId, workflowId);
            // Post-V261 (2026-05-19) - strict-isolation: every conversation row
            // carries a non-null organization_id (personal workspace resolves to
            // the user's default personal org). The legacy IS NULL personal-scope
            // branch was removed; orgId is required.
            Optional<ConversationDto> conversation = conversationQueryService.findByUserIdAndWorkflowId(
                    userId, organizationId, workflowId);
            return conversation.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error finding workflow conversation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a conversation for a specific workflow
     */
    @PostMapping("/workflow/{workflowId}")
    public ResponseEntity<ConversationDto> createWorkflowConversation(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        try {
            String title = body != null ? body.get("title") : null;
            logger.info("Creating workflow conversation for user: {} (org: {}) and workflow: {} with title: {}",
                    userId, organizationId, workflowId, title);
            ConversationDto conversation = conversationCommandService.createWorkflowConversation(
                    userId, organizationId, workflowId, model, provider, title);
            return ResponseEntity.ok(conversation);
        } catch (Exception e) {
            logger.error("Error creating workflow conversation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==================== AGENT CONVERSATIONS ====================

    /**
     * Find an existing conversation for a specific agent (does NOT create)
     * Returns 404 if no conversation exists
     */
    @GetMapping("/agent/{agentId}")
    public ResponseEntity<ConversationDto> findAgentConversation(
            @PathVariable("agentId") String agentId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            logger.info("Finding agent conversation for user: {} (org: {}) and agent: {}",
                    userId, organizationId, agentId);
            Optional<ConversationDto> conversation = conversationQueryService.findByUserIdAndAgentId(
                    userId, organizationId, agentId);
            return conversation.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error finding agent conversation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create a conversation for a specific agent
     */
    @PostMapping("/agent/{agentId}")
    public ResponseEntity<ConversationDto> createAgentConversation(
            @PathVariable("agentId") String agentId,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        try {
            String title = body != null ? body.get("title") : null;
            logger.info("Creating agent conversation for user: {} (org: {}) and agent: {} with title: {}",
                    userId, organizationId, agentId, title);
            ConversationDto conversation = conversationCommandService.createAgentConversation(
                    userId, organizationId, agentId, model, provider, title);
            return ResponseEntity.ok(conversation);
        } catch (Exception e) {
            logger.error("Error creating agent conversation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete all conversations associated with an agent (soft delete).
     * Called by orchestrator-service when an agent is deleted.
     *
     * <p>2026-05-18 - workspace-scoped: passes the caller's active orgId to
     * the service, which strict-isolates each row before cascade-deleting.
     * A caller in OrgA cannot wipe their personal-scope agent conversations
     * via this endpoint (and vice versa).
     */
    @DeleteMapping("/by-agent/{agentId}")
    public ResponseEntity<Map<String, Object>> deleteConversationsByAgent(
            @PathVariable("agentId") String agentId,
            @RequestHeader(value = "X-User-ID", required = true) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            logger.info("Deleting conversations for agent: {} by user: {} (org: {})",
                    agentId, userId, organizationId);

            int deletedCount = conversationCommandService.deleteConversationsByAgentId(
                    agentId, userId, organizationId);

            logger.info("Deleted {} conversations for agent: {}", deletedCount, agentId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "deletedCount", deletedCount,
                "agentId", agentId
            ));
        } catch (Exception e) {
            logger.error("Error deleting conversations for agent {}: {}", agentId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Get conversation by ID
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationDto> getConversation(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            logger.info("Getting conversation: {} (user: {}, org: {})", conversationId, userId, organizationId);
            // PR21 - strict-isolation scope check. With X-User-ID + optional
            // X-Organization-ID the lookup is fully scope-aware: cross-scope
            // rows return Optional.empty → 404. When X-User-ID is missing
            // (no JWT context - direct internal probes), the personal-scope
            // branch matches no row → 404 (safe default).
            Optional<ConversationDto> conversation =
                    conversationQueryService.getConversationById(conversationId, userId, organizationId);
            return conversation.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error getting conversation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get conversations for a user with pagination
     * User ID is provided by the gateway via X-User-ID header
     */
    @GetMapping
    public ResponseEntity<PagedResponseDto<ConversationDto>> getConversationsByUser(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "includeInactive", defaultValue = "false") boolean includeInactive) {
        try {
            logger.info("📋 [GET] Getting conversations for user: {} (org: {}), page: {}, size: {}",
                    userId, organizationId, page, size);
            // PR21 - strict-isolation: org workspace lists all team chats; personal
            // workspace lists only owner's chats that are NOT tagged with any org.
            // This closes the sidebar-leak bug class on the conversation surface.
            Page<ConversationDto> conversations = conversationQueryService.getConversationsByUserId(
                    userId, organizationId, page, size, includeInactive);
            PagedResponseDto<ConversationDto> response = new PagedResponseDto<>(conversations);
            logger.info("✅ [GET] Found {} conversations for user: {} (org: {})",
                    response.getNumberOfElements(), userId, organizationId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("❌ [GET] Error getting conversations for user: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Search conversations by title
     * User ID is provided by the gateway via X-User-ID header
     */
    @GetMapping("/search/title")
    public ResponseEntity<PagedResponseDto<ConversationDto>> searchConversationsByTitle(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam("searchTerm") String searchTerm,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        try {
            logger.info("🔍 [SEARCH TITLE] Searching conversations by title for user: {} (org: {}), searchTerm: {}",
                    userId, organizationId, searchTerm);
            Page<ConversationDto> conversations = conversationQueryService.searchConversationsByTitle(
                    userId, organizationId, searchTerm, page, size);
            PagedResponseDto<ConversationDto> response = new PagedResponseDto<>(conversations);
            logger.info("✅ [SEARCH TITLE] Found {} conversations matching title: {}", response.getNumberOfElements(), searchTerm);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("❌ [SEARCH TITLE] Error searching conversations by title: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Search conversations by message content
     * User ID is provided by the gateway via X-User-ID header
     */
    @GetMapping("/search/content")
    public ResponseEntity<PagedResponseDto<ConversationDto>> searchConversationsByContent(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam("searchTerm") String searchTerm,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        try {
            logger.info("🔍 [SEARCH CONTENT] Searching conversations by content for user: {} (org: {}), searchTerm: {}",
                    userId, organizationId, searchTerm);
            Page<ConversationDto> conversations = conversationQueryService.searchConversationsByContent(
                    userId, organizationId, searchTerm, page, size);
            PagedResponseDto<ConversationDto> response = new PagedResponseDto<>(conversations);
            logger.info("✅ [SEARCH CONTENT] Found {} conversations matching content: {}", response.getNumberOfElements(), searchTerm);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("❌ [SEARCH CONTENT] Error searching conversations by content: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get recent conversations for a user
     * User ID is provided by the gateway via X-User-ID header
     */
    @GetMapping("/recent")
    public ResponseEntity<List<ConversationDto>> getRecentConversations(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(value = "limit", defaultValue = "5") int limit) {
        try {
            logger.info("Getting recent conversations for user: {} (org: {}), limit: {}",
                    userId, organizationId, limit);
            List<ConversationDto> conversations = conversationQueryService.getRecentConversations(
                    userId, organizationId, limit);
            return ResponseEntity.ok(conversations);
        } catch (Exception e) {
            logger.error("Error getting recent conversations: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Get conversation count for a user
     * User ID is provided by the gateway via X-User-ID header
     */
    @GetMapping("/count")
    public ResponseEntity<Long> getConversationCount(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            logger.info("Getting conversation count for user: {} (org: {})", userId, organizationId);
            long count = conversationQueryService.getConversationCount(userId, organizationId);
            return ResponseEntity.ok(count);
        } catch (Exception e) {
            logger.error("Error getting conversation count: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Update conversation
     */
    @PutMapping("/{conversationId}")
    public ResponseEntity<?> updateConversation(
            @PathVariable("conversationId") String conversationId,
            @RequestBody ConversationDto conversationDto,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // Audit 2026-05-17 round-4 - owner-or-org scope check before mutation.
        // Prior: PUT had NO scope check at all (round-3 missed this endpoint).
        // No @Valid on the DTO: userId is injected by the gateway via X-User-ID; the mapper
        // skips it on update. chatConfig range validation lives in the service layer.
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
            logger.warn("[SCOPE] updateConversation cross-tenant blocked: convId={} caller={} orgId={}",
                    conversationId, userId, organizationId);
            return ResponseEntity.notFound().build();
        }
        try {
            logger.info("[ChatConfig] Updating conversation: {}, chatConfig={}", conversationId, conversationDto.getChatConfig());
            ConversationDto updatedConversation = conversationCommandService.updateConversation(conversationId, conversationDto);
            return ResponseEntity.ok(updatedConversation);
        } catch (IllegalArgumentException e) {
            logger.warn("Rejected conversation update {}: {}", conversationId, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating conversation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
    
    /**
     * Delete conversation (soft delete). Scoped: caller must own the conversation
     * OR be in the org the conversation is tagged to. Audit 2026-05-16: prior
     * implementation had NO scope check - any authenticated user could hard-delete
     * any conversation by knowing the UUID.
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            // Scope-aware existence check: if conversation isn't visible to the
            // caller, return 404 - don't leak existence to other tenants.
            if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
                logger.warn("[DELETE] Conversation not found or out of scope for user {} (org {}): {}",
                        userId, organizationId, conversationId);
                return ResponseEntity.notFound().build();
            }
            conversationCommandService.deleteConversation(conversationId);
            logger.info("[DELETE] Successfully soft deleted conversation: {}", conversationId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("[DELETE] Error deleting conversation {}: {}", conversationId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Permanently delete conversation. Same scope-check as soft delete.
     */
    @DeleteMapping("/{conversationId}/permanent")
    public ResponseEntity<Void> permanentlyDeleteConversation(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
                logger.warn("[DELETE PERMANENT] Conversation not found or out of scope for user {} (org {}): {}",
                        userId, organizationId, conversationId);
                return ResponseEntity.notFound().build();
            }
            conversationCommandService.permanentlyDeleteConversation(conversationId);
            logger.info("[DELETE PERMANENT] Successfully permanently deleted conversation: {}", conversationId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("[DELETE PERMANENT] Error permanently deleting conversation {}: {}", conversationId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Clear all messages from a conversation without deleting the conversation itself.
     * Scope-checked (same as delete).
     */
    @DeleteMapping("/{conversationId}/messages")
    public ResponseEntity<Void> clearConversationMessages(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
                logger.warn("[CLEAR] Conversation not found or out of scope for user {} (org {}): {}",
                        userId, organizationId, conversationId);
                return ResponseEntity.notFound().build();
            }
            conversationCommandService.clearConversationMessages(conversationId);
            logger.info("[CLEAR] Successfully cleared messages for conversation: {}", conversationId);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("[CLEAR] Error clearing messages for conversation {}: {}", conversationId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete all conversations associated with a workflow (soft delete).
     * Called by orchestrator-service when a workflow is deleted.
     *
     * <p>2026-05-18 - workspace-scoped (see {@link #deleteConversationsByAgent}).
     */
    @DeleteMapping("/by-workflow/{workflowId}")
    public ResponseEntity<Map<String, Object>> deleteConversationsByWorkflow(
            @PathVariable("workflowId") String workflowId,
            @RequestHeader(value = "X-User-ID", required = true) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            logger.info("[DELETE BY WORKFLOW] Deleting conversations for workflow: {} by user: {} (org: {})",
                    workflowId, userId, organizationId);

            int deletedCount = conversationCommandService.deleteConversationsByWorkflowId(
                    workflowId, userId, organizationId);

            logger.info("✅ [DELETE BY WORKFLOW] Deleted {} conversations for workflow: {}", deletedCount, workflowId);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "deletedCount", deletedCount,
                "workflowId", workflowId
            ));
        } catch (Exception e) {
            logger.error("❌ [DELETE BY WORKFLOW] Error deleting conversations for workflow {}: {}", workflowId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Add message to conversation
     */
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<?> addMessage(
            @PathVariable("conversationId") String conversationId,
            @Valid @RequestBody MessageDto messageDto,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // Audit 2026-05-17 round-3 - scope-check before mutating.
        if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
            logger.warn("[SCOPE] addMessage cross-tenant blocked: convId={} caller={} orgId={}",
                    conversationId, userId, organizationId);
            return ResponseEntity.notFound().build();
        }
        logger.info("Adding message to conversation: {} for user: {}", conversationId, userId);
        try {
            MessageDto addedMessage = messageService.addMessage(conversationId, messageDto);
            return ResponseEntity.status(HttpStatus.CREATED).body(addedMessage);
        } catch (ConversationInactiveException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("code", "CONVERSATION_INACTIVE", "message", e.getMessage()));
        } catch (InvalidMessageException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "INVALID_MESSAGE", "message", e.getMessage()));
        }
    }

    /**
     * Update user feedback on a message (thumbs up/down).
     * Body: { "feedback": 1 } for thumbs up, { "feedback": -1 } for thumbs down, { "feedback": null } to clear.
     */
    @PutMapping("/messages/{messageId}/feedback")
    public ResponseEntity<MessageDto> updateMessageFeedback(
            @PathVariable("messageId") String messageId,
            @RequestBody Map<String, Integer> body,
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            logger.info("Updating feedback for message: {} by user: {}", messageId, userId);
            Integer feedback = body.get("feedback");
            MessageDto updatedMessage = messageService.updateMessageFeedback(messageId, feedback, userId, organizationId);
            return ResponseEntity.ok(updatedMessage);
        } catch (com.apimarketplace.conversation.exception.InvalidMessageException nfe) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error updating message feedback: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update a message's toolCalls field.
     * Used to update workflow run status and duration after completion.
     */
    @PutMapping("/messages/{messageId}/toolCalls")
    public ResponseEntity<MessageDto> updateMessageToolCalls(
            @PathVariable("messageId") String messageId,
            @RequestBody String toolCallsJson,
            @RequestHeader(value = "X-User-ID", required = true) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            logger.info("Updating toolCalls for message: {} by user: {}", messageId, userId);
            MessageDto updatedMessage = messageService.updateMessageToolCalls(messageId, toolCallsJson, userId, organizationId);
            return ResponseEntity.ok(updatedMessage);
        } catch (com.apimarketplace.conversation.exception.InvalidMessageException nfe) {
            return ResponseEntity.notFound().build();
        } catch (Exception e) {
            logger.error("Error updating message toolCalls: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get messages for a conversation with pagination.
     * <p>
     * Optional {@code executionId} scopes the page to a single execution (or pass "latest"
     * to resolve to the most recent one). Page 0 is the newest batch (DESC order); the
     * frontend reverses for chronological display and triggers loadOlder on scroll-up.
     */
    @GetMapping("/{conversationId}/messages/page")
    public ResponseEntity<PagedResponseDto<MessageDto>> getMessagesPage(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "executionId", required = false) String executionId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            logger.info("📄 [GET MESSAGES PAGE] conversation: {} page: {} size: {} executionId: {}",
                    conversationId, page, size, executionId);
            Page<MessageDto> messages = messageService.getMessagesByConversationIdScoped(
                    conversationId, page, size, executionId, userId, organizationId);
            PagedResponseDto<MessageDto> response = new PagedResponseDto<>(messages);
            logger.info("✅ [GET MESSAGES PAGE] Found {} messages for conversation: {}", response.getNumberOfElements(), conversationId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("❌ [GET MESSAGES PAGE] Error getting paged messages for conversation: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==================== PENDING ACTIONS ====================

    /**
     * Get pending action for a conversation.
     */
    @GetMapping("/{conversationId}/pending-action")
    public ResponseEntity<Map<String, Object>> getPendingAction(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            // Audit 2026-05-17 round-3 - scope-check before reveal.
            if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            logger.info("Getting pending action for conversation: {}", conversationId);
            Optional<Map<String, Object>> pendingAction = pendingActionService.getPendingAction(conversationId);
            return pendingAction.map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            logger.error("Error getting pending action: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Clear pending action(s) for a conversation.
     *
     * <p>With no {@code key}, clears ALL pending actions (a fresh user message dismisses the
     * cards). With a {@code key} (the dedup/clear key of one card - {@code "auth:<rule>"} for
     * a tool authorization or {@code "svc:<sorted serviceTypes>"} for a service approval),
     * clears ONLY that card so the other still-pending cards survive. Always returns 204
     * (clearing a missing/already-cleared action is idempotent).
     */
    @DeleteMapping("/{conversationId}/pending-action")
    public ResponseEntity<Void> clearPendingAction(
            @PathVariable("conversationId") String conversationId,
            @RequestParam(value = "key", required = false) String key,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            // Scope-check: pending-action is per-conversation; refuse cross-scope.
            if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            if (key != null && !key.isBlank()) {
                logger.info("Clearing one pending action (key={}) for conversation: {}", key, conversationId);
                pendingActionService.clearOnePendingAction(conversationId, key);
            } else {
                logger.info("Clearing all pending actions for conversation: {}", conversationId);
                pendingActionService.clearPendingAction(conversationId);
            }
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            logger.error("Error clearing pending action: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Resume a conversation with pending action manually.
     */
    @PostMapping("/{conversationId}/pending-action/resume")
    public ResponseEntity<Map<String, Object>> resumePendingAction(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            // Audit 2026-05-17 round-3 - scope-check before resuming pending action.
            if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
                logger.warn("[SCOPE] resumePendingAction cross-tenant blocked: convId={} caller={} orgId={}",
                        conversationId, userId, organizationId);
                return ResponseEntity.notFound().build();
            }
            logger.info("Manually resuming pending action for conversation: {}", conversationId);
            Map<String, Object> pendingAction = pendingActionResumeService.manualResume(conversationId);
            if (pendingAction == null) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok(pendingAction);
        } catch (Exception e) {
            logger.error("Error resuming pending action: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Notify that a credential has been configured.
     * This will resume all conversations waiting for this credential.
     */
    @PostMapping("/events/credential-configured")
    public ResponseEntity<Map<String, Object>> onCredentialConfigured(
            @RequestHeader(value = "X-User-ID") String userId,
            @RequestParam("credentialType") String credentialType) {
        try {
            logger.info("Credential configured event: {} for user {}", credentialType, userId);
            int resumed = pendingActionResumeService.onCredentialConfigured(credentialType, userId);
            return ResponseEntity.ok(Map.of(
                "credentialType", credentialType,
                "conversationsResumed", resumed
            ));
        } catch (Exception e) {
            logger.error("Error handling credential configured event: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==================== SERVICE APPROVALS ====================

    /**
     * Approve multiple external services for a conversation.
     * The frontend calls this after user approves access to services like Gmail, Slack, etc.
     * Request body: { "services": ["gmail", "slack"] }
     */
    @PostMapping("/{conversationId}/services/approve")
    public ResponseEntity<Map<String, Object>> approveServices(
            @PathVariable("conversationId") String conversationId,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // Audit 2026-05-17 round-4 - scope-check before responding.
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        @SuppressWarnings("unchecked")
        List<String> services = (List<String>) request.getOrDefault("services", List.of());
        logger.info("🔓 [SERVICE_APPROVAL] Approve endpoint called for conversation: {} (no-op)", conversationId);
        return ResponseEntity.ok(Map.of(
            "conversationId", conversationId,
            "approvedServices", services,
            "newlyApproved", services
        ));
    }

    // ==================== TOOL AUTHORIZATION ====================

    /**
     * Authorize a sensitive tool action the agent requested in this conversation.
     * Called by the chat authorization card. Body:
     * {@code { "rule": "application:acquire", "toolCallId": "...", "remember": false }}.
     * {@code remember=true} persists the rule ("Toujours autoriser dans cette conversation");
     * otherwise it is a single-shot grant consumed by the resume turn. Clears the pending
     * action either way; the frontend then resumes the run.
     */
    @PostMapping("/{conversationId}/tool-authorization/approve")
    public ResponseEntity<Map<String, Object>> approveToolAuthorization(
            @PathVariable("conversationId") String conversationId,
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String rule = request != null ? (String) request.get("rule") : null;
        if (rule == null || rule.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "rule is required"));
        }
        boolean remember = request != null && Boolean.TRUE.equals(request.get("remember"));
        toolAuthorizationApprovalService.approve(conversationId, rule, remember);
        // Clear ONLY this rule's card so other parallel cards stay pending.
        pendingActionService.clearOnePendingAction(conversationId, "auth:" + rule);
        logger.info("🔓 [TOOL_AUTH] Approved rule {} for conversation {} (remember={})",
                rule, conversationId, remember);
        return ResponseEntity.ok(Map.of(
            "conversationId", conversationId,
            "rule", rule,
            "remembered", remember
        ));
    }

    /**
     * Decline a requested tool action. Clears the pending action and does NOT resume -
     * the agent stops and the user takes over.
     */
    @PostMapping("/{conversationId}/tool-authorization/deny")
    public ResponseEntity<Map<String, Object>> denyToolAuthorization(
            @PathVariable("conversationId") String conversationId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        if (userId == null || userId.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String rule = request != null ? (String) request.get("rule") : null;
        // Clear ONLY this rule's card so other parallel cards stay pending; fall back to a
        // full clear when no rule is supplied (legacy single-card deny).
        if (rule != null && !rule.isBlank()) {
            pendingActionService.clearOnePendingAction(conversationId, "auth:" + rule);
        } else {
            pendingActionService.clearPendingAction(conversationId);
        }
        logger.info("🚫 [TOOL_AUTH] Declined rule {} for conversation {}", rule, conversationId);
        return ResponseEntity.ok(Map.of(
            "conversationId", conversationId,
            "denied", true
        ));
    }

    /**
     * Get list of approved services for a conversation.
     */
    @GetMapping("/{conversationId}/services/approved")
    public ResponseEntity<Map<String, Object>> getApprovedServices(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            // Audit 2026-05-17 round-4 - scope-check before leak.
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            logger.info("Getting approved services for conversation: {}", conversationId);
            Set<String> approvedServices = serviceApprovalService.getApprovedServices(conversationId);
            return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "approvedServices", approvedServices
            ));
        } catch (Exception e) {
            logger.error("Error getting approved services: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Revoke a service approval for a conversation.
     */
    @DeleteMapping("/{conversationId}/services/{serviceType}")
    public ResponseEntity<Map<String, Object>> revokeServiceApproval(
            @PathVariable("conversationId") String conversationId,
            @PathVariable("serviceType") String serviceType,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            if (userId == null || userId.isBlank()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
            if (conversationQueryService.getConversationById(conversationId, userId, organizationId).isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            logger.info("[SERVICE_APPROVAL] Revoking service {} for conversation: {}", serviceType, conversationId);
            serviceApprovalService.revokeServiceApproval(conversationId, serviceType);

            Set<String> approvedServices = serviceApprovalService.getApprovedServices(conversationId);
            return ResponseEntity.ok(Map.of(
                "conversationId", conversationId,
                "revokedService", serviceType,
                "approvedServices", approvedServices
            ));
        } catch (Exception e) {
            logger.error("Error revoking service approval: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ==================== Sharing ====================

    @PostMapping("/{conversationId}/share")
    public ResponseEntity<?> enableSharing(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody Map<String, Object> body) {
        try {
            String shareMode = (String) body.getOrDefault("shareMode", "read");
            Boolean memoryEnabled = (Boolean) body.get("memoryEnabled");
            ConversationDto dto = conversationSharingService.enableSharing(
                    conversationId, userId, organizationId, shareMode, memoryEnabled);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error enabling sharing: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PatchMapping("/{conversationId}/share")
    public ResponseEntity<?> updateShareSettings(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody Map<String, Object> body) {
        try {
            String shareMode = (String) body.get("shareMode");
            Boolean memoryEnabled = (Boolean) body.get("memoryEnabled");
            ConversationDto dto = conversationSharingService.updateShareSettings(
                    conversationId, userId, organizationId, shareMode, memoryEnabled);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error updating share settings: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{conversationId}/share")
    public ResponseEntity<?> disableSharing(
            @PathVariable("conversationId") String conversationId,
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        try {
            conversationSharingService.disableSharing(conversationId, userId, organizationId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error disabling sharing: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
