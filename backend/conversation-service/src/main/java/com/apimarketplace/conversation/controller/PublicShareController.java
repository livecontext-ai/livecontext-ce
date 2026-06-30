package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.dto.ConversationDto;
import com.apimarketplace.conversation.dto.MessageDto;
import com.apimarketplace.conversation.dto.PublicMessageDto;
import com.apimarketplace.conversation.dto.PublicMessagePageDto;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.exception.ConversationInactiveException;
import com.apimarketplace.conversation.exception.InvalidMessageException;
import com.apimarketplace.conversation.mapper.ConversationMapper;
import com.apimarketplace.conversation.service.ConversationSharingService;
import com.apimarketplace.conversation.service.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Public controller for shared conversations (no auth required, token-based).
 */
@RestController
@RequestMapping("/api/shared")
@CrossOrigin(origins = "*")
public class PublicShareController {

    private static final Logger logger = LoggerFactory.getLogger(PublicShareController.class);

    private final ConversationSharingService sharingService;
    private final ConversationMapper conversationMapper;
    private final MessageService messageService;

    public PublicShareController(ConversationSharingService sharingService,
                                 ConversationMapper conversationMapper,
                                 MessageService messageService) {
        this.sharingService = sharingService;
        this.conversationMapper = conversationMapper;
        this.messageService = messageService;
    }

    @GetMapping("/c/{token}")
    public ResponseEntity<?> getSharedConversation(@PathVariable String token) {
        try {
            Conversation conversation = sharingService.findByShareToken(token)
                    .orElse(null);

            if (conversation == null || "off".equals(conversation.getShareMode())) {
                return ResponseEntity.notFound().build();
            }

            ConversationDto dto = conversationMapper.toDto(conversation);
            // Strip sensitive / owner-private fields for the anonymous public view.
            // Both the legacy single pending_action AND the pending_actions list must
            // be cleared - each can carry the user's requested services, rule, tool
            // args, and application id.
            dto.setUserId(null);
            dto.setPendingAction(null);
            dto.setPendingActions(null);
            dto.setApprovedServices(null);
            // chatConfig holds the owner's PRIVATE system prompt + per-conversation
            // tool/turn configuration - it must never reach an anonymous share viewer.
            // The internal scope/resource identifiers below are owner-only too; a
            // public reader needs none of them to render the shared transcript.
            dto.setChatConfig(null);
            dto.setOrganizationId(null);
            dto.setAgentId(null);
            dto.setWorkflowId(null);
            dto.setParentConversationId(null);

            return ResponseEntity.ok(dto);
        } catch (Exception e) {
            logger.error("Error fetching shared conversation: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /** Maximum page size accepted on the public endpoint - defends against a scraper
     *  asking for a 10k-row page on every request and exhausting DB connections. */
    private static final int MAX_PAGE_SIZE = 50;
    private static final int DEFAULT_PAGE_SIZE = 20;

    @GetMapping("/c/{token}/messages")
    public ResponseEntity<?> getSharedMessages(
            @PathVariable String token,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // page < 0 is an invalid intent (not just unsafe input) - fail loudly so
        // callers see the bug instead of silently falling back to page 0.
        if (page < 0) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "page must be >= 0"));
        }
        // size is clamped instead of rejected: defensive against ad-hoc /c/<token>/messages?size=10000
        // hits without breaking anyone who passes 0 or a small negative value.
        int clampedSize = Math.max(1, Math.min(MAX_PAGE_SIZE, size));

        try {
            Conversation conversation = sharingService.findByShareToken(token)
                    .orElse(null);

            if (conversation == null || "off".equals(conversation.getShareMode())) {
                return ResponseEntity.notFound().build();
            }

            // Memory disabled by the owner: return an empty page (not 404). Same
            // shape as a successful empty-conversation response so the frontend
            // does not need a special case.
            if (!Boolean.TRUE.equals(conversation.getMemoryEnabled())) {
                return ResponseEntity.ok(new PublicMessagePageDto(Collections.emptyList(), false));
            }

            // Service returns DESC-ordered page (page=0 = newest N). We reverse to
            // ASC for display so the frontend can render top-down and the scroll
            // anchor lands on the latest message at the bottom.
            Page<MessageDto> dbPage = messageService.getMessagesByConversationId(
                    conversation.getId(), page, clampedSize);

            List<MessageDto> descContent = dbPage.getContent();
            List<PublicMessageDto> ascItems = new ArrayList<>(descContent.size());
            for (int i = descContent.size() - 1; i >= 0; i--) {
                ascItems.add(PublicMessageDto.from(descContent.get(i)));
            }

            boolean hasMore = page + 1 < dbPage.getTotalPages();
            return ResponseEntity.ok(new PublicMessagePageDto(ascItems, hasMore));
        } catch (Exception e) {
            logger.error("Error fetching shared messages: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping("/c/{token}/messages")
    public ResponseEntity<?> addSharedMessage(
            @PathVariable String token,
            @RequestBody MessageDto messageDto) {
        try {
            Conversation conversation = sharingService.findByShareToken(token)
                    .orElse(null);

            if (conversation == null || "off".equals(conversation.getShareMode())) {
                return ResponseEntity.notFound().build();
            }

            if (!"readwrite".equals(conversation.getShareMode())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("error", "This conversation is read-only"));
            }

            messageDto.setConversationId(conversation.getId());
            MessageDto saved = messageService.addMessage(conversation.getId(), messageDto);
            return ResponseEntity.status(HttpStatus.CREATED).body(saved);
        } catch (ConversationInactiveException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", e.getMessage()));
        } catch (InvalidMessageException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error adding message to shared conversation: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
